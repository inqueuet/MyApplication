package com.example.myapplication

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.example.myapplication.databinding.ActivityImageEditBinding
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import java.util.zip.InflaterInputStream
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.min


class ImageEditActivity : AppCompatActivity() {
    private lateinit var binding: ActivityImageEditBinding
    private var originalBitmap: Bitmap? = null
    private lateinit var canvasBitmap: Bitmap
    private lateinit var editCanvas: Canvas
    private val mosaicPaint = Paint()
    private val erasePaint = Paint()
    private var isErasing = false
    
    private var originalFileBytes: ByteArray? = null
    private var originalExifData: ExifInterface? = null
    private var extractedInitialPrompt: String? = null

    private val mosaicBlockSize = 20f
    private val tempMosaicPaint = Paint().apply { style = Paint.Style.FILL }

    private lateinit var saveImageLauncher: ActivityResultLauncher<String>

    // For advanced metadata extraction
    private val GSON_PARSER: Gson = Gson()
    private val METADATA_PROMPT_KEYS: Set<String> = setOf("parameters", "Description", "Comment", "prompt")

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val imageUriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (imageUriString == null) {
            Toast.makeText(this, "画像 URI がありません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val imageUri = Uri.parse(imageUriString)
        try {
            originalFileBytes = contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
            if (originalFileBytes == null) {
                Toast.makeText(this, "画像の読み込みに失敗しました (bytes)", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            originalBitmap = BitmapFactory.decodeByteArray(originalFileBytes, 0, originalFileBytes!!.size)
            if (originalBitmap == null) {
                Toast.makeText(this, "画像のデコードに失敗しました", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            canvasBitmap = Bitmap.createBitmap(originalBitmap!!.width, originalBitmap!!.height, Bitmap.Config.ARGB_8888)
            editCanvas = Canvas(canvasBitmap)
            binding.imageView.setImageBitmap(originalBitmap)

            try {
                originalExifData = ExifInterface(ByteArrayInputStream(originalFileBytes))
            } catch (e: IOException) {
                Log.w("ImageEditActivity", "Could not read EXIF data from input stream", e)
                // Not fatal, other extraction methods might work
            }

            _extractInitialPromptFromFileBytes(originalFileBytes!!, imageUri)
            Log.d("ImageEditActivity", "Extracted initial prompt: $extractedInitialPrompt")

        } catch (e: Exception) { // Catch more general exceptions during initial loading
            e.printStackTrace()
            Toast.makeText(this, "画像の読み込み処理中にエラーが発生しました: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupSaveImageLauncher()
        setupPaint()
        setupTouchListener()
        setupButtons()
    }

    private fun setupSaveImageLauncher() {
        saveImageLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri: Uri? ->
            if (uri != null) {
                saveImageToUri(uri)
            } else {
                Toast.makeText(this, "保存先が選択されませんでした", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupPaint() {
        mosaicPaint.apply {
            style = Paint.Style.FILL
            strokeWidth = 50f
        }
        erasePaint.apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            strokeWidth = 50f
            isAntiAlias = true
        }
    }

    private fun setupTouchListener() {
        binding.imageView.setOnTouchListener { _, event ->
            if (originalBitmap == null || !::editCanvas.isInitialized) return@setOnTouchListener false

            val touchPoint = floatArrayOf(event.x, event.y)
            val inverseMatrix = Matrix()
            binding.imageView.imageMatrix.invert(inverseMatrix)
            inverseMatrix.mapPoints(touchPoint)
            val bitmapX = touchPoint[0]
            val bitmapY = touchPoint[1]

            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    drawOnCanvas(bitmapX, bitmapY)
                    true
                }
                else -> false
            }
        }
    }

    private fun drawOnCanvas(x: Float, y: Float) {
        if (originalBitmap == null || !::editCanvas.isInitialized) return

        if (isErasing) {
            editCanvas.drawCircle(x, y, erasePaint.strokeWidth / 2f, erasePaint)
        } else {
            val brushRadius = mosaicPaint.strokeWidth / 2f
            val imageWidth = originalBitmap!!.width
            val imageHeight = originalBitmap!!.height

            val minAffectedX = (x - brushRadius)
            val maxAffectedX = (x + brushRadius)
            val minAffectedY = (y - brushRadius)
            val maxAffectedY = (y + brushRadius)

            var currentBlockX = floor(minAffectedX / mosaicBlockSize) * mosaicBlockSize
            while (currentBlockX < maxAffectedX) {
                var currentBlockY = floor(minAffectedY / mosaicBlockSize) * mosaicBlockSize
                while (currentBlockY < maxAffectedY) {
                    val blockCenterX = currentBlockX + mosaicBlockSize / 2f
                    val blockCenterY = currentBlockY + mosaicBlockSize / 2f

                    if ((blockCenterX - x).pow(2) + (blockCenterY - y).pow(2) <= brushRadius.pow(2)) {
                        val sampleX = blockCenterX.toInt().coerceIn(0, imageWidth - 1)
                        val sampleY = blockCenterY.toInt().coerceIn(0, imageHeight - 1)
                        val sampledColor = originalBitmap!!.getPixel(sampleX, sampleY)
                        tempMosaicPaint.color = sampledColor
                        editCanvas.drawRect(currentBlockX, currentBlockY, currentBlockX + mosaicBlockSize, currentBlockY + mosaicBlockSize, tempMosaicPaint)
                    }
                    currentBlockY += mosaicBlockSize
                }
                currentBlockX += mosaicBlockSize
            }
        }

        val displayBitmap = originalBitmap!!.copy(Bitmap.Config.ARGB_8888, true)
        val tempCanvas = Canvas(displayBitmap)
        tempCanvas.drawBitmap(canvasBitmap, 0f, 0f, null)
        binding.imageView.setImageBitmap(displayBitmap)
    }

    private fun setupButtons() {
        binding.buttonMosaic.setOnClickListener {
            isErasing = false
            Toast.makeText(this, "モザイクモード", Toast.LENGTH_SHORT).show()
        }
        binding.buttonErase.setOnClickListener {
            isErasing = true
            Toast.makeText(this, "消しゴムモード", Toast.LENGTH_SHORT).show()
        }
        binding.buttonSave.setOnClickListener {
            promptToSaveImage()
        }
    }

    private fun promptToSaveImage() {
        if (originalBitmap == null || !::editCanvas.isInitialized) {
            Toast.makeText(this, "保存する画像がありません", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = "edited_image_${System.currentTimeMillis()}.jpg"
        saveImageLauncher.launch(fileName)
    }

    private fun saveImageToUri(saveUri: Uri) {
        if (originalBitmap == null || !::editCanvas.isInitialized) {
            Toast.makeText(this, "保存する画像がありません", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmapToSave = originalBitmap!!.copy(Bitmap.Config.ARGB_8888, true)
        val finalCanvas = Canvas(bitmapToSave)
        finalCanvas.drawBitmap(canvasBitmap, 0f, 0f, null)

        try {
            contentResolver.openOutputStream(saveUri)?.use { fos ->
                bitmapToSave.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                fos.flush()
            } ?: throw IOException("Failed to get output stream for URI: $saveUri")

            // Exif saving part
            contentResolver.openFileDescriptor(saveUri, "rw")?.use { pfd ->
                val newExif = ExifInterface(pfd.fileDescriptor)
                
                // Copy standard Exif tags from originalExifData if available
                originalExifData?.let { exifData ->
                    val attributesToCopy = listOf(
                        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_DIGITIZED,
                        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_MAKE,
                        ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
                        ExifInterface.TAG_EXIF_VERSION // TAG_IMAGE_DESCRIPTION and TAG_USER_COMMENT handled below
                    )
                    for (tag in attributesToCopy) {
                        exifData.getAttribute(tag)?.let { value ->
                            newExif.setAttribute(tag, value)
                        }
                    }
                }

                // Set user comment using extractedInitialPrompt or fallback
                var commentToSave = ""
                if (!extractedInitialPrompt.isNullOrBlank()) {
                    commentToSave = "Edited with MyMosaicApp. Original prompt: $extractedInitialPrompt"
                } else if (originalExifData != null) {
                    val promptFromStdExif = originalExifData!!.getAttribute(ExifInterface.TAG_USER_COMMENT)
                        ?: originalExifData!!.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
                        ?: ""
                    commentToSave = if (promptFromStdExif.isNotBlank()) {
                        "Edited with MyMosaicApp. Original prompt: $promptFromStdExif"
                    } else {
                        "Edited with MyMosaicApp."
                    }
                } else {
                    commentToSave = "Edited with MyMosaicApp."
                }
                newExif.setAttribute(ExifInterface.TAG_USER_COMMENT, commentToSave)
                Log.d("ImageEditActivity", "Setting user comment in Exif: $commentToSave")
                newExif.saveAttributes()
            } ?: throw IOException("Failed to get FileDescriptor for URI: $saveUri for Exif")


            Toast.makeText(this, "画像を保存しました: ${saveUri.path}", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Log.e("ImageEditActivity", "Error saving image or Exif data", e)
            e.printStackTrace()
            Toast.makeText(this, "画像の保存に失敗しました: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                       result = cursor.getString(displayNameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1 && cut != null) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun _extractInitialPromptFromFileBytes(bytes: ByteArray, uri: Uri) {
        val type = contentResolver.getType(uri)
        val fileName = getFileName(uri)
        Log.d("ImageEditActivity", "Extracting prompt. Type: $type, FileName: $fileName")

        if (type?.startsWith("video/") == true || fileName?.endsWith(".mp4", ignoreCase = true) == true) {
            Log.d("ImageEditActivity", "Attempting MP4 extraction.")
            extractedInitialPrompt = extractFromMp4Internal(bytes)
            if (!extractedInitialPrompt.isNullOrBlank()) {
                Log.d("ImageEditActivity", "Prompt from MP4: $extractedInitialPrompt")
                return
            }
        }

        Log.d("ImageEditActivity", "Attempting Exif extraction.")
        extractedInitialPrompt = extractFromExifInternal(bytes)
        if (!extractedInitialPrompt.isNullOrBlank()) {
            Log.d("ImageEditActivity", "Prompt from Exif: $extractedInitialPrompt")
            return
        }

        if (isPngInternal(bytes)) {
            Log.d("ImageEditActivity", "Attempting PNG chunk extraction.")
            extractedInitialPrompt = extractFromPngChunksInternal(bytes)
            if (!extractedInitialPrompt.isNullOrBlank()) {
                Log.d("ImageEditActivity", "Prompt from PNG: $extractedInitialPrompt")
                return
            }
        }
        Log.d("ImageEditActivity", "No prompt found through advanced extraction.")
    }

    // --- Start of Advanced Metadata Extraction Methods (Internal) ---

    private fun extractFromExifInternal(fileBytes: ByteArray): String? {
        return try {
            val exifInterface = ExifInterface(ByteArrayInputStream(fileBytes))
            // Prefer UserComment, fallback to ImageDescription
            exifInterface.getAttribute(ExifInterface.TAG_USER_COMMENT) 
                ?: exifInterface.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
        } catch (e: Exception) {
            Log.e("ImageEditActivity", "Error extracting from ExifInternal", e)
            null
        }
    }

    private fun isPngInternal(fileBytes: ByteArray): Boolean {
        if (fileBytes.size < 8) return false
        return fileBytes[0] == 137.toByte() &&
                fileBytes[1] == 80.toByte() &&
                fileBytes[2] == 78.toByte() &&
                fileBytes[3] == 71.toByte() &&
                fileBytes[4] == 13.toByte() &&
                fileBytes[5] == 10.toByte() &&
                fileBytes[6] == 26.toByte() &&
                fileBytes[7] == 10.toByte()
    }

    private fun extractFromPngChunksInternal(bytes: ByteArray): String? {
        val prompts = mutableListOf<String>()
        var offset = 8 

        try {
            while (offset < bytes.size - 12) { // Ensure space for length, type, and CRC
                if (offset + 4 > bytes.size) break // Not enough bytes for length
                val length = ByteBuffer.wrap(bytes, offset, 4).int
                if (length < 0) { // Invalid length
                    Log.w("ImageEditActivity", "PNG chunk invalid length: $length at offset $offset")
                    break
                }

                if (offset + 8 > bytes.size) break // Not enough bytes for type
                val type = String(bytes, offset + 4, 4, StandardCharsets.US_ASCII)

                val dataStart = offset + 8
                val dataEnd = dataStart + length

                if (dataEnd > bytes.size) {
                     Log.w("ImageEditActivity", "PNG chunk dataEnd ($dataEnd) exceeds byte array size (${bytes.size}) for type $type")
                     break
                }


                when (type) {
                    "tEXt", "iTXt", "zTXt" -> {
                        val dataBytes = bytes.sliceArray(dataStart until dataEnd)
                        val nullSeparatorIndex = dataBytes.indexOf(0.toByte())
                        if (nullSeparatorIndex > 0) { // Key must exist
                            val key = String(dataBytes, 0, nullSeparatorIndex, StandardCharsets.UTF_8)
                            if (METADATA_PROMPT_KEYS.contains(key.lowercase())) { // Ensure case-insensitive key matching
                                val valueBytes: ByteArray = if (type == "zTXt") {
                                    if (nullSeparatorIndex + 2 < dataBytes.size) { // Check for compression method byte and data
                                        decompressInternal(dataBytes.sliceArray(nullSeparatorIndex + 2 until dataBytes.size))
                                    } else {
                                        Log.w("ImageEditActivity", "zTXt chunk too short for key $key")
                                        continue // Skip this chunk
                                    }
                                } else {
                                    dataBytes.sliceArray(nullSeparatorIndex + 1 until dataBytes.size)
                                }
                                prompts.add(valueBytes.toString(StandardCharsets.UTF_8))
                            }
                        }
                    }
                    "IEND" -> {
                        return prompts.joinToString("\n\n").ifEmpty { null }
                    }
                }
                if (12 + length <= 0) { // Avoid infinite loop or negative offset
                    Log.w("ImageEditActivity", "PNG chunk invalid offset increment: 12 + $length")
                    break
                }
                offset += (12 + length) 
            }
        } catch (e: Exception) {
            Log.e("ImageEditActivity", "Error parsing PNG chunks", e)
        }
        return prompts.joinToString("\n\n").ifEmpty { null }
    }

    private fun decompressInternal(compressedData: ByteArray): ByteArray {
        val inflater = InflaterInputStream(ByteArrayInputStream(compressedData))
        val outputStream = ByteArrayOutputStream()
        try {
            inflater.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception){
            Log.e("ImageEditActivity", "Error decompressing zTXt data", e)
            return ByteArray(0) // Return empty on error
        }
        return outputStream.toByteArray()
    }

    private fun extractFromMp4Internal(fileBytes: ByteArray): String? {
        try {
            val content = String(fileBytes, StandardCharsets.UTF_8) // Potential for large string, be mindful
            var result: String? = null

            val promptPattern = Pattern.compile("""\"prompt\"\s*:\s*(\"([^\"\\]*(\\.[^\"\\]*)*)\"|\{.*?\})""", Pattern.DOTALL)
            var matcher = promptPattern.matcher(content)
            if (matcher.find()) {
                val promptJsonCandidate = matcher.group(1)
                result = parsePromptJsonInternal(promptJsonCandidate)
                if (!result.isNullOrBlank()) return result
            }

            val workflowPattern = Pattern.compile("""\"workflow\"\s*:\s*(\{.*?\})""", Pattern.DOTALL)
            matcher = workflowPattern.matcher(content)
            if (matcher.find()) {
                val workflowJsonCandidate = matcher.group(1)
                result = parseWorkflowJsonInternal(workflowJsonCandidate)
                if (!result.isNullOrBlank()) return result
            }
            
            val clipTextEncodePattern = Pattern.compile("""\"CLIPTextEncode\"[\s\S]{0,2000}?\"title\"\s*:\s*\"[^\"]*Positive[^\"]*\"[\s\S]{0,1000}?\"(?:text|string)\"\s*:\s*\"((?:\\.|[^\"])*)""", Pattern.CASE_INSENSITIVE)
            matcher = clipTextEncodePattern.matcher(content)
            if (matcher.find()) {
                result = matcher.group(1)?.replace("\"", "")
                if (!result.isNullOrBlank()) return result
            }
             return null
        } catch (e: OutOfMemoryError) {
            Log.e("ImageEditActivity", "OutOfMemoryError decoding MP4 file bytes to string.", e)
            return null
        } catch (e: Exception) {
            Log.e("ImageEditActivity", "Exception in extractFromMp4Internal", e)
            return null
        }
    }

    private fun parsePromptJsonInternal(jsonCandidate: String): String? {
        try {
            if (jsonCandidate.startsWith("\"") && jsonCandidate.endsWith("\"")) {
                val unescapedJson = GSON_PARSER.fromJson(jsonCandidate, String::class.java)
                val dataMap = GSON_PARSER.fromJson<Map<String, Any>>(unescapedJson, object : TypeToken<Map<String, Any>>() {}.type)
                return extractDataFromMapInternal(dataMap)
            } else {
                 val dataMap = GSON_PARSER.fromJson<Map<String, Any>>(jsonCandidate, object : TypeToken<Map<String, Any>>() {}.type)
                 return extractDataFromMapInternal(dataMap)
            }
        } catch (e: JsonSyntaxException) {
            Log.w("ImageEditActivity", "JsonSyntaxException in parsePromptJsonInternal", e)
        } catch (e: Exception) {
            Log.e("ImageEditActivity", "Exception in parsePromptJsonInternal", e)
        }
        return null
    }
    
    private fun parseWorkflowJsonInternal(jsonCandidate: String): String? {
        try {
            val dataMap = GSON_PARSER.fromJson<Map<String, Any>>(jsonCandidate, object : TypeToken<Map<String, Any>>() {}.type)
            return extractDataFromMapInternal(dataMap)
        } catch (e: JsonSyntaxException) {
             Log.w("ImageEditActivity", "JsonSyntaxException in parseWorkflowJsonInternal", e)
        } catch (e: Exception) {
            Log.e("ImageEditActivity", "Exception in parseWorkflowJsonInternal", e)
        }
        return null
    }

    private fun extractDataFromMapInternal(dataMap: Map<String, Any>): String? {
        try {
            val nodes = dataMap["nodes"] as? List<Map<String, Any>>
            if (nodes != null) {
                return pickFromNodesInternal(nodes) ?: scanHeuristicallyInternal(dataMap)
            }
            return scanHeuristicallyInternal(dataMap)
        } catch (e: Exception) {
            Log.e("ImageEditActivity", "Exception in extractDataFromMapInternal", e)
            return null
        }
    }

    private fun isLabelyInternal(text: String?): Boolean {
        val t = text?.trim() ?: return false
        // Adjusted regex to be Kotlin compliant
        return t.matches(Regex("^(TxtEmb|TextEmb)", RegexOption.IGNORE_CASE)) ||
                (!t.contains(Regex("""\s""")) && t.length < 24)
    }

    private fun bestStrFromInputsInternal(inputs: Any?): String? {
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
                if (best == null || value.length > best!!.length) { // Ensure best is not null for length comparison
                    best = value
                }
            }
        }
        return best?.trim()
    }

    private fun pickFromNodesInternal(nodes: List<Map<String, Any>>): String? {
        try {
            val nodeMap = nodes.filterNotNull().associateBy { (it["id"]?.toString()) }

            fun resolveNode(node: Map<String, Any>?, depth: Int = 0): String? {
                if (node == null || depth > 4) return null

                val inputs = node["inputs"]
                var s = bestStrFromInputsInternal(inputs)
                if (s != null && s.isNotEmpty() && !isLabelyInternal(s)) return s
                
                if (inputs is Map<*, *>) {
                    for ((_, value) in inputs) {
                        if (value is List<*> && value.isNotEmpty()) {
                            val linkedNodeId = value[0]?.toString()
                            val linkedNode = nodeMap[linkedNodeId]
                            val r = resolveNode(linkedNode, depth + 1)
                            if (r != null && !isLabelyInternal(r)) return r
                        } else if (value is String && value.trim().isNotEmpty() && !isLabelyInternal(value)) {
                             return value.trim()
                        }
                    }
                }
                
                val widgetsValues = node["widgets_values"] as? List<*>
                if (widgetsValues != null) {
                    for (v_widget in widgetsValues) { // Renamed v to v_widget
                        if (v_widget is String && v_widget.trim().isNotEmpty() && !isLabelyInternal(v_widget)) return v_widget.trim()
                    }
                }
                return null
            }
            
            val specificChecks = listOf(
                "ImpactWildcardProcessor",
                "WanVideoTextEncodeSingle",
                "WanVideoTextEncode"
            )
            for (typePattern in specificChecks) {
                for (node in nodes) {
                    val nodeType = node["type"] as? String ?: node["class_type"] as? String ?: ""
                    if (nodeType.contains(typePattern, ignoreCase = true)) {
                        val sCheck = resolveNode(node) // Renamed s to sCheck
                        if (sCheck != null && sCheck.isNotEmpty()) return sCheck // s was already defined
                    }
                }
            }

            for (node in nodes) {
                val nodeType = node["type"] as? String ?: node["class_type"] as? String ?: ""
                val title = node["title"] as? String ?: (node["_meta"] as? Map<String, String>)?.get("title") ?: ""
                if (nodeType.contains("CLIPTextEncode", ignoreCase = true) && title.contains("Positive", ignoreCase = true) && !title.contains("Negative", ignoreCase = true)) {
                     var sCLIP = bestStrFromInputsInternal(node["inputs"]) // Renamed s to sCLIP
                     if (sCLIP.isNullOrEmpty() && node["widgets_values"] is List<*>) {
                         sCLIP = (node["widgets_values"] as List<*>).getOrNull(0) as? String
                     }
                     if (sCLIP != null && sCLIP.trim().isNotEmpty() && !isLabelyInternal(sCLIP)) return sCLIP.trim()
                }
            }
             for (node in nodes) {
                val title = node["title"] as? String ?: (node["_meta"] as? Map<String, String>)?.get("title") ?: ""
                if (Regex("PointMosaic|Mosaic|Mask|TxtEmb|TextEmb", RegexOption.IGNORE_CASE).containsMatchIn(title)) continue

                var sFallback = bestStrFromInputsInternal(node["inputs"]) // Renamed s to sFallback
                if (sFallback.isNullOrEmpty() && node["widgets_values"] is List<*>) {
                     sFallback = (node["widgets_values"] as List<*>).getOrNull(0) as? String
                }
                if (sFallback != null && sFallback.trim().isNotEmpty() && !isLabelyInternal(sFallback) && title.contains("Positive", ignoreCase = true) && !title.contains("Negative", ignoreCase = true) ) return sFallback.trim()
            }
            return null
        } catch (e: Exception) {
            Log.e("ImageEditActivity", "Exception in pickFromNodesInternal", e)
            return null
        }
    }

    @Suppress("UNCHECKED_CAST", "ComplexMethod") // Suppress for casting and complexity, review if possible
    private fun scanHeuristicallyInternal(obj: Map<String, Any>): String? {
        try {
            val EX_T = Regex("PointMosaic|Mosaic|Mask|TxtEmb|TextEmb", RegexOption.IGNORE_CASE)
            val EX_C = Regex("ShowText|Display|Note|Preview|VHS_|Image|Resize|Seed|INTConstant|SimpleMath|Any Switch|StringConstant(?!Multiline)", RegexOption.IGNORE_CASE)
            var best: String? = null
            var maxScore = -1_000_000_000.0 

            val stack = mutableListOf<Any>(obj)

            while (stack.isNotEmpty()) {
                val current = stack.removeAt(stack.size - 1)

                if (current !is Map<*, *>) continue
                
                val currentMap = current as Map<String, Any> 

                val classType = currentMap["class_type"] as? String ?: currentMap["type"] as? String ?: ""
                val meta = currentMap["_meta"] as? Map<String, Any>
                val title = meta?.get("title") as? String ?: currentMap["title"] as? String ?: ""
                
                var v = bestStrFromInputsInternal(currentMap["inputs"])
                if (v.isNullOrEmpty()){
                     val widgetsValues = currentMap["widgets_values"] as? List<*>
                     if (widgetsValues != null && widgetsValues.isNotEmpty()) {
                         v = widgetsValues[0] as? String
                     }
                }

                if (v is String && v.trim().isNotEmpty()) {
                    var score = 0.0
                    if (title.contains("Positive", ignoreCase = true)) score += 1000.0
                    if (title.contains("Negative", ignoreCase = true)) score -= 1000.0
                    if (classType.contains("TextEncode", ignoreCase = true) || classType.contains("CLIPText", ignoreCase = true)) score += 120.0
                    if (classType.contains("ImpactWildcardProcessor", ignoreCase = true) || classType.contains("WanVideoTextEncodeSingle", ignoreCase = true)) score += 300.0
                    score += min(220.0, floor(v.length / 8.0)) // Ensure double arithmetic

                    if (EX_T.containsMatchIn(title) || EX_T.containsMatchIn(classType)) score -= 900.0
                    if (EX_C.containsMatchIn(classType)) score -= 400.0
                    if (isLabelyInternal(v)) score -= 500.0
                    
                    if (score > maxScore) {
                        maxScore = score
                        best = v.trim()
                    }
                }

                currentMap.values.forEach { value ->
                    if (value is Map<*, *> || value is List<*>) { // Check if it's a map or list before adding
                        stack.add(value)
                    }
                }
            }
            return best
        } catch (e: Exception) {
            Log.e("ImageEditActivity", "Exception in scanHeuristicallyInternal", e)
            return null
        }
    }
    // --- End of Advanced Metadata Extraction Methods (Internal) ---
}
