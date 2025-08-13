package com.example.myapplication

import androidx.exifinterface.media.ExifInterface
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream
import java.util.regex.Pattern

object MetadataExtractor {

    // プロンプト情報が含まれている可能性のあるメタデータのキー
    private val PROMPT_KEYS = setOf("parameters", "Description", "Comment", "prompt")
    private val GSON = Gson()

    /**
     * URLから画像/動画データを取得し、プロンプト情報を抽出する
     */
    suspend fun extract(url: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val fileBytes = downloadFile(url) ?: return@withContext null

                if (url.endsWith(".mp4", ignoreCase = true)) {
                    val mp4Prompt = extractFromMp4(fileBytes)
                    if (!mp4Prompt.isNullOrBlank()) {
                        return@withContext mp4Prompt
                    }
                } else {
                    // まずはExifから試す (JPEG, WEBP)
                    val exifPrompt = extractFromExif(fileBytes)
                    if (!exifPrompt.isNullOrBlank()) {
                        return@withContext exifPrompt
                    }

                    // PNGチャンクから試す
                    if (isPng(fileBytes)) {
                        val pngPrompt = extractFromPngChunks(fileBytes)
                        if (!pngPrompt.isNullOrBlank()) {
                            return@withContext pngPrompt
                        }
                    }
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * ファイルをダウンロードしてByteArrayとして返す
     */
    private fun downloadFile(fileUrl: String): ByteArray? {
        return try {
            val url = URL(fileUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { it.readBytes() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * EXIF情報からUserCommentを抽出する
     */
    private fun extractFromExif(fileBytes: ByteArray): String? {
        return try {
            val exifInterface = ExifInterface(ByteArrayInputStream(fileBytes))
            exifInterface.getAttribute(ExifInterface.TAG_USER_COMMENT)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * PNGのヘッダーシグネチャを持っているかチェック
     */
    private fun isPng(fileBytes: ByteArray): Boolean {
        if (fileBytes.size < 8) return false
        // PNG signature: 137 80 78 71 13 10 26 10
        // KotlinではByteとIntの直接比較ができないため、.toByte()で型を合わせる
        return fileBytes[0] == 137.toByte() &&
                fileBytes[1] == 80.toByte() &&
                fileBytes[2] == 78.toByte() &&
                fileBytes[3] == 71.toByte() &&
                fileBytes[4] == 13.toByte() &&
                fileBytes[5] == 10.toByte() &&
                fileBytes[6] == 26.toByte() &&
                fileBytes[7] == 10.toByte()
    }

    /**
     * PNGのチャンクを解析してプロンプト情報を抽出する
     */
    private fun extractFromPngChunks(bytes: ByteArray): String? {
        val prompts = mutableListOf<String>()
        var offset = 8 // PNGシグネチャの後のオフセット

        while (offset < bytes.size - 12) {
            val length = ByteBuffer.wrap(bytes, offset, 4).int
            val type = String(bytes, offset + 4, 4, StandardCharsets.US_ASCII)

            val dataStart = offset + 8
            val dataEnd = dataStart + length

            if (dataEnd > bytes.size) break

            when (type) {
                "tEXt", "iTXt", "zTXt" -> {
                    val dataBytes = bytes.sliceArray(dataStart until dataEnd)
                    val nullSeparatorIndex = dataBytes.indexOf(0.toByte())
                    if (nullSeparatorIndex > 0) {
                        val key = String(dataBytes, 0, nullSeparatorIndex, StandardCharsets.UTF_8)
                        if (PROMPT_KEYS.contains(key)) {
                            val valueBytes: ByteArray = if (type == "zTXt") {
                                // zTXtは圧縮されているため解凍処理を行う
                                // 圧縮方式のバイト(1byte)をスキップ
                                decompress(dataBytes.sliceArray(nullSeparatorIndex + 2 until dataBytes.size))
                            } else {
                                dataBytes.sliceArray(nullSeparatorIndex + 1 until dataBytes.size)
                            }
                            prompts.add(valueBytes.toString(StandardCharsets.UTF_8))
                        }
                    }
                }
                "IEND" -> {
                    // 終了チャンクなのでループを抜ける
                    return prompts.joinToString("\n\n").ifEmpty { null }
                }
            }
            offset += 12 + length // 次のチャンクへ (Length + Type + Data + CRC)
        }
        return prompts.joinToString("\n\n").ifEmpty { null }
    }

    /**
     * zlib (DEFLATE) で圧縮されたデータを解凍する
     */
    private fun decompress(compressedData: ByteArray): ByteArray {
        val inflater = InflaterInputStream(ByteArrayInputStream(compressedData))
        val outputStream = ByteArrayOutputStream()
        inflater.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
        return outputStream.toByteArray()
    }

    // --- MP4 Specific Extraction Logic ---

    private fun extractFromMp4(fileBytes: ByteArray): String? {
        val content = String(fileBytes, StandardCharsets.UTF_8)
        var result: String? = null

        // 1. Try to find "prompt": "{"nodes": ...}" or "prompt": "{...}"
        val promptPattern = Pattern.compile("""prompt"\s*:\s*("([^"\\]*(\.[^"\\]*)*)"|\{.*?\})""", Pattern.DOTALL)
        var matcher = promptPattern.matcher(content)
        if (matcher.find()) {
            val promptJsonCandidate = matcher.group(1)
            result = parsePromptJson(promptJsonCandidate)
            if (!result.isNullOrBlank()) return result
        }

        // 2. Try to find "workflow": {...}
        val workflowPattern = Pattern.compile("""workflow"\s*:\s*(\{.*?\})""", Pattern.DOTALL)
        matcher = workflowPattern.matcher(content)
        if (matcher.find()) {
            val workflowJsonCandidate = matcher.group(1)
            result = parseWorkflowJson(workflowJsonCandidate)
            if (!result.isNullOrBlank()) return result
        }
        
        // 3. Fallback: Try to find "CLIPTextEncode" ... "text": "..."
        val clipTextEncodePattern = Pattern.compile("""CLIPTextEncode"[\s\S]{0,2000}?"title"\s*:\s*"[^"]*Positive[^"]*"[\s\S]{0,1000}?"(?:text|string)"\s*:\s*"((?:\\.|[^"])*)""", Pattern.CASE_INSENSITIVE)
        matcher = clipTextEncodePattern.matcher(content)
        if (matcher.find()) {
            result = matcher.group(1)?.replace("\"", "")
            if (!result.isNullOrBlank()) return result
        }

        return null
    }

    private fun parsePromptJson(jsonCandidate: String): String? {
        try {
            // Check if it's a stringified JSON (e.g., "prompt": ""{\"nodes\": ...}"")
            if (jsonCandidate.startsWith("\"") && jsonCandidate.endsWith("\"")) {
                val unescapedJson = GSON.fromJson(jsonCandidate, String::class.java)
                val dataMap = GSON.fromJson<Map<String, Any>>(unescapedJson, object : TypeToken<Map<String, Any>>() {}.type)
                return extractDataFromMap(dataMap)
            } else { // Direct JSON object (e.g., "prompt": "{"key": "value"}")
                 val dataMap = GSON.fromJson<Map<String, Any>>(jsonCandidate, object : TypeToken<Map<String, Any>>() {}.type)
                 return extractDataFromMap(dataMap)
            }
        } catch (e: JsonSyntaxException) {
            // Not a valid JSON or not the expected structure
            e.printStackTrace()
        }
        return null
    }
    
    private fun parseWorkflowJson(jsonCandidate: String): String? {
        try {
            val dataMap = GSON.fromJson<Map<String, Any>>(jsonCandidate, object : TypeToken<Map<String, Any>>() {}.type)
            return extractDataFromMap(dataMap)
        } catch (e: JsonSyntaxException) {
             e.printStackTrace()
        }
        return null
    }

    private fun extractDataFromMap(dataMap: Map<String, Any>): String? {
        val nodes = dataMap["nodes"] as? List<Map<String, Any>>
        if (nodes != null) {
            return pickFromNodes(nodes) ?: scanHeuristically(dataMap)
        }
        return scanHeuristically(dataMap)
    }

    private fun isLabely(text: String?): Boolean {
        val t = text?.trim() ?: return false
        return t.matches(Regex("^(TxtEmb|TextEmb)", RegexOption.IGNORE_CASE)) ||
                (!t.contains(Regex("""\s""")) && t.length < 24)
    }

    private fun bestStrFromInputs(inputs: Any?): String? {
        if (inputs !is Map<*, *>) return null
        val priorityKeys = listOf("populated_text", "wildcard_text", "prompt", "positive_prompt", "result", "text", "string", "value")
        for (key in priorityKeys) {
            val value = inputs[key]
            if (value is String && value.trim().isNotEmpty()) {
                return value.trim()
            }
        }
        var best: String? = null
        for ((_, value) in inputs) {
            if (value is String && value.trim().isNotEmpty()) {
                if (best == null || value.length > best.length) {
                    best = value
                }
            }
        }
        return best?.trim()
    }

    private fun pickFromNodes(nodes: List<Map<String, Any>>): String? {
        val nodeMap = nodes.filterNotNull().associateBy { (it["id"]?.toString()) }

        fun resolveNode(node: Map<String, Any>?, depth: Int = 0): String? {
            if (node == null || depth > 4) return null

            val inputs = node["inputs"]
            var s = bestStrFromInputs(inputs)
            if (s != null && s.isNotEmpty() && !isLabely(s)) return s
            
            if (inputs is Map<*, *>) {
                for ((_, value) in inputs) {
                    if (value is List<*> && value.isNotEmpty()) {
                        val linkedNodeId = value[0]?.toString()
                        val linkedNode = nodeMap[linkedNodeId]
                        val r = resolveNode(linkedNode, depth + 1)
                        if (r != null && !isLabely(r)) return r
                    } else if (value is String && value.trim().isNotEmpty() && !isLabely(value)) {
                         return value.trim()
                    }
                }
            }
            
            val widgetsValues = node["widgets_values"] as? List<*>
            if (widgetsValues != null) {
                for (v in widgetsValues) {
                    if (v is String && v.trim().isNotEmpty() && !isLabely(v)) return v.trim()
                }
            }
            return null
        }
        
        // Specific node type checks based on JavaScript
        val specificChecks = listOf(
            "ImpactWildcardProcessor",
            "WanVideoTextEncodeSingle",
            "WanVideoTextEncode"
        )
        for (typePattern in specificChecks) {
            for (node in nodes) {
                val nodeType = node["type"] as? String ?: node["class_type"] as? String ?: ""
                if (nodeType.contains(typePattern, ignoreCase = true)) {
                    val s = resolveNode(node)
                    if (s != null && s.isNotEmpty()) return s
                }
            }
        }

        for (node in nodes) {
            val nodeType = node["type"] as? String ?: node["class_type"] as? String ?: ""
            val title = node["title"] as? String ?: (node["_meta"] as? Map<String, String>)?.get("title") ?: ""
            if (nodeType.contains("CLIPTextEncode", ignoreCase = true) && title.contains("Positive", ignoreCase = true) && !title.contains("Negative", ignoreCase = true)) {
                 var s = bestStrFromInputs(node["inputs"])
                 if (s.isNullOrEmpty() && node["widgets_values"] is List<*>) {
                     s = (node["widgets_values"] as List<*>).getOrNull(0) as? String
                 }
                 if (s != null && s.trim().isNotEmpty() && !isLabely(s)) return s.trim()
            }
        }
         for (node in nodes) {
            val title = node["title"] as? String ?: (node["_meta"] as? Map<String, String>)?.get("title") ?: ""
            if (Regex("PointMosaic|Mosaic|Mask|TxtEmb|TextEmb", RegexOption.IGNORE_CASE).containsMatchIn(title)) continue

            var s = bestStrFromInputs(node["inputs"])
            if (s.isNullOrEmpty() && node["widgets_values"] is List<*>) {
                 s = (node["widgets_values"] as List<*>).getOrNull(0) as? String
            }
            if (s != null && s.trim().isNotEmpty() && !isLabely(s) && title.contains("Positive", ignoreCase = true) && !title.contains("Negative", ignoreCase = true) ) return s.trim()
        }
        return null
    }

    private fun scanHeuristically(obj: Map<String, Any>): String? {
        val EX_T = Regex("PointMosaic|Mosaic|Mask|TxtEmb|TextEmb", RegexOption.IGNORE_CASE)
        val EX_C = Regex("ShowText|Display|Note|Preview|VHS_|Image|Resize|Seed|INTConstant|SimpleMath|Any Switch|StringConstant(?!Multiline)", RegexOption.IGNORE_CASE)
        var best: String? = null
        var maxScore = -1_000_000_000.0 // Double for score

        val stack = mutableListOf<Any>(obj)

        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.size - 1)

            if (current !is Map<*, *>) continue
            
            val currentMap = current as Map<String, Any> // Ensure it's the correct type

            val classType = currentMap["class_type"] as? String ?: currentMap["type"] as? String ?: ""
            val meta = currentMap["_meta"] as? Map<String, Any>
            val title = meta?.get("title") as? String ?: currentMap["title"] as? String ?: ""
            
            var v = bestStrFromInputs(currentMap["inputs"])
            if (v.isNullOrEmpty()){
                 val widgetsValues = currentMap["widgets_values"] as? List<*>
                 if (widgetsValues != null && widgetsValues.isNotEmpty()) {
                     v = widgetsValues[0] as? String
                 }
            }


            if (v is String && v.trim().isNotEmpty()) {
                var score = 0.0
                if (title.contains("Positive", ignoreCase = true)) score += 1000
                if (title.contains("Negative", ignoreCase = true)) score -= 1000
                if (classType.contains("TextEncode", ignoreCase = true) || classType.contains("CLIPText", ignoreCase = true)) score += 120
                if (classType.contains("ImpactWildcardProcessor", ignoreCase = true) || classType.contains("WanVideoTextEncodeSingle", ignoreCase = true)) score += 300
                score += Math.min(220.0, Math.floor(v.length / 8.0))

                if (EX_T.containsMatchIn(title) || EX_T.containsMatchIn(classType)) score -= 900
                if (EX_C.containsMatchIn(classType)) score -= 400
                if (isLabely(v)) score -= 500
                
                if (score > maxScore) {
                    maxScore = score
                    best = v.trim()
                }
            }

            currentMap.values.forEach { value ->
                if (value is Map<*, *> || value is List<*>) {
                    stack.add(value)
                }
            }
        }
        return best
    }
}