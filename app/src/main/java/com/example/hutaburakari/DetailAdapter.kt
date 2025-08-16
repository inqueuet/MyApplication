package com.example.hutaburakari

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent // Intent をインポート
import android.graphics.Color
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.util.Log // ★ Logをインポート
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.ViewSizeResolver // Added for ViewSizeResolver
import kotlinx.coroutines.launch
import java.util.regex.Pattern
import android.text.Spanned

class DetailAdapter : ListAdapter<DetailContent, RecyclerView.ViewHolder>(DetailDiffCallback()) {

    var onQuoteClickListener: ((quotedText: String) -> Unit)? = null
    var onSodaNeClickListener: ((resNum: String) -> Unit)? = null
    var onThreadEndTimeClickListener: (() -> Unit)? = null // ★ 新しいコールバックを追加
    private var currentSearchQuery: String? = null

    private val fileNamePattern = Pattern.compile("""\b([a-zA-Z0-9_.-]+\.(?:jpg|jpeg|png|gif|mp4|webm|mov|avi|flv|mkv))\b""", Pattern.CASE_INSENSITIVE)
    private val resNumPatternOriginal = Pattern.compile("""No\.(\d+)""") // 元のレス番号抽出用
    // ★「そうだねXX」または「No.数字の後に続く+」のパターンにマッチ
    private val sodaNePattern = Pattern.compile("""(そうだね\d*)|(No\.\d+\s*([+＋]))""")


    fun setSearchQuery(query: String?) {
        val oldQuery = currentSearchQuery
        currentSearchQuery = query
        if (oldQuery != query) {
            notifyDataSetChanged()
        }
    }

    private companion object {
        const val VIEW_TYPE_TEXT = 1
        const val VIEW_TYPE_IMAGE = 2
        const val VIEW_TYPE_VIDEO = 3
        const val VIEW_TYPE_THREAD_END_TIME = 4 // New ViewType

        // MediaViewActivity 用の定数
        const val EXTRA_TYPE = "EXTRA_TYPE"
        const val EXTRA_URL = "EXTRA_URL"
        const val EXTRA_TEXT = "EXTRA_TEXT" // プロンプトやテキスト表示用
        // const val EXTRA_PROMPT = "EXTRA_PROMPT" // EXTRA_TEXT に統一するためコメントアウト
        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"
        const val TYPE_TEXT = "text"
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is VideoViewHolder) {
            holder.releasePlayer()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is DetailContent.Text -> VIEW_TYPE_TEXT
            is DetailContent.Image -> VIEW_TYPE_IMAGE
            is DetailContent.Video -> VIEW_TYPE_VIDEO
            is DetailContent.ThreadEndTime -> VIEW_TYPE_THREAD_END_TIME // Handle new type
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_TEXT -> TextViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.detail_item_text, parent, false),
                onQuoteClickListener,
                onSodaNeClickListener,
                fileNamePattern,
                resNumPatternOriginal, // ★ ViewHolderには元のNo.xxx用パターンを渡す
                sodaNePattern // Pass the updated pattern
            )
            VIEW_TYPE_IMAGE -> ImageViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.detail_item_image, parent, false),
                onQuoteClickListener,
                fileNamePattern
            )
            VIEW_TYPE_VIDEO -> VideoViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.detail_item_video, parent, false),
                onQuoteClickListener,
                fileNamePattern
            )
            VIEW_TYPE_THREAD_END_TIME -> ThreadEndTimeViewHolder( // ★ リスナーを渡す
                LayoutInflater.from(parent.context).inflate(R.layout.detail_item_thread_end_time, parent, false),
                onThreadEndTimeClickListener
            )
            else -> throw IllegalArgumentException("Invalid view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DetailContent.Text -> (holder as TextViewHolder).bind(item, currentSearchQuery)
            is DetailContent.Image -> (holder as ImageViewHolder).bind(item, currentSearchQuery)
            is DetailContent.Video -> (holder as VideoViewHolder).bind(item, currentSearchQuery)
            is DetailContent.ThreadEndTime -> (holder as ThreadEndTimeViewHolder).bind(item)
        }
    }

    class TextViewHolder(
        view: View,
        private val onQuoteClickListener: ((quotedText: String) -> Unit)?,
        private val onSodaNeClickListener: ((resNum: String) -> Unit)?,
        private val fileNamePattern: Pattern,
        private val resNumPatternForMain: Pattern, // ★メインのレス番号取得用
        private val sodaNePatternForClick: Pattern // ★そうだね/＋ クリック処理用 (updated pattern comes here)
    ) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.detailTextView)

        fun bind(item: DetailContent.Text, searchQuery: String?) {
            val spannedFromHtml = Html.fromHtml(item.htmlContent, Html.FROM_HTML_MODE_COMPACT)
            val spannableBuilder = SpannableStringBuilder(spannedFromHtml)
            val contentString = spannableBuilder.toString() // spannableBuilderから取得する方が安全

            var mainResNum: String? = null
            val mainResMatcher = resNumPatternForMain.matcher(contentString)
            if (mainResMatcher.find()) {
                mainResNum = mainResMatcher.group(1)
                 Log.d("TextViewHolder", "Main resNum found: $mainResNum for content: ${contentString.take(50)}")
            } else {
                 Log.w("TextViewHolder", "Main resNum NOT found for content: ${contentString.take(50)}")
            }


            val quotePattern = Pattern.compile("^>(.+)$", Pattern.MULTILINE)
            val quoteMatcher = quotePattern.matcher(contentString)
            while (quoteMatcher.find()) {
                val quoteText = quoteMatcher.group(1)?.trim()
                if (quoteText != null) {
                    val start = quoteMatcher.start()
                    val end = quoteMatcher.end()
                    val clickableSpan = object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            onQuoteClickListener?.invoke(quoteText)
                        }
                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = false
                        }
                    }
                    spannableBuilder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            val fileMatcher = fileNamePattern.matcher(contentString)
            while (fileMatcher.find()) {
                val fileName = fileMatcher.group(1)
                if (fileName != null) {
                    val start = fileMatcher.start()
                    val end = fileMatcher.end()
                    val clickableSpan = object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            onQuoteClickListener?.invoke(fileName)
                        }
                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = true
                        }
                    }
                    spannableBuilder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            // ★★★ 「そうだね」と「＋」の処理を修正 ★★★
            if (mainResNum != null) { // mainResNum がないと「そうだね」の対象が不明確
                val sodaNeMatcher = sodaNePatternForClick.matcher(contentString)
                while (sodaNeMatcher.find()) {
                    var spanStart = -1
                    var spanEnd = -1
                    var logMessage = ""

                    val matchedSodaNeWithDigits = sodaNeMatcher.group(1) // (そうだね\d*)
                    val matchedNoDotPatternWithPlus = sodaNeMatcher.group(2) // (No\.\d+\s*([+＋]))

                    if (matchedSodaNeWithDigits != null) {
                        spanStart = sodaNeMatcher.start(1)
                        spanEnd = sodaNeMatcher.end(1)
                        logMessage = "Found 'そうだね': '$matchedSodaNeWithDigits' for mainResNum: '$mainResNum'. Clickable Range: $spanStart-$spanEnd"
                    } else if (matchedNoDotPatternWithPlus != null) {
                        val tempResNumPattern = Pattern.compile("No\\.(\\d+)")
                        val tempMatcher = tempResNumPattern.matcher(matchedNoDotPatternWithPlus)
                        if (tempMatcher.find()) {
                            val numInPlusContext = tempMatcher.group(1)
                            if (numInPlusContext == mainResNum) {
                                val actualPlusSign = sodaNeMatcher.group(3)
                                if (actualPlusSign != null) {
                                    spanStart = sodaNeMatcher.start(3)
                                    spanEnd = sodaNeMatcher.end(3)
                                    logMessage = "Found '+': '$actualPlusSign' (from '$matchedNoDotPatternWithPlus') associated with mainResNum: '$mainResNum'. Clickable Range: $spanStart-$spanEnd"
                                } else {
                                    Log.w("TextViewHolder", "Error: Matched NoPlusContainer '$matchedNoDotPatternWithPlus' but couldn't get group(3) for '+'. MainResNum: '$mainResNum'. Skipping.")
                                    continue
                                }
                            } else {
                                Log.d("TextViewHolder", "Found '$matchedNoDotPatternWithPlus', but its resNum '$numInPlusContext' does NOT match current item's mainResNum '$mainResNum'. Skipping span for this match.")
                                continue
                            }
                        } else {
                             Log.w("TextViewHolder", "Error: Could not extract resNum from NoPlusContainer '$matchedNoDotPatternWithPlus' even though it matched. MainResNum: '$mainResNum'. Skipping.")
                            continue
                        }
                    }

                    if (spanStart != -1 && spanEnd != -1) {
                        Log.d("TextViewHolder", logMessage) 
                        spannableBuilder.setSpan(
                            SodaNeClickableSpan(mainResNum, onSodaNeClickListener),
                            spanStart,
                            spanEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        Log.d("TextViewHolder", "Set SodaNeClickableSpan for resNum: '$mainResNum' on spannableBuilder at range $spanStart-$spanEnd")
                    }
                }
            } else {
                 Log.w("TextViewHolder", "Skipping SodaNe/Plus span setup because mainResNum is null for content: ${contentString.take(50)}")
            }
            // ★★★ ここまで修正 ★★★


            if (!searchQuery.isNullOrEmpty()) {
                val textLc = contentString.lowercase()
                val queryLc = searchQuery.lowercase()
                var startIndex = textLc.indexOf(queryLc)
                while (startIndex >= 0) {
                    val endIndex = startIndex + queryLc.length
                    spannableBuilder.setSpan(
                        BackgroundColorSpan(Color.YELLOW),
                        startIndex,
                        endIndex,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    startIndex = textLc.indexOf(queryLc, endIndex)
                }
            }

            textView.text = spannableBuilder
            textView.movementMethod = object : LinkMovementMethod() {
                override fun handleMovementKey(widget: TextView?, buffer: Spannable?, keyCode: Int, movementMetaState: Int, event: KeyEvent?): Boolean {
                    return false
                }

                override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
                    Log.d("CustomLinkMovement", "onTouchEvent: action=${event.action}, x=${event.x}, y=${event.y}")
                    val action = event.action

                    if (action == MotionEvent.ACTION_UP) { // Process click on ACTION_UP
                        var x = event.x.toInt()
                        var y = event.y.toInt()

                        x -= widget.totalPaddingLeft
                        y -= widget.totalPaddingTop
                        x += widget.scrollX
                        y += widget.scrollY

                        val layout = widget.layout
                        val line = layout.getLineForVertical(y)
                        if (line < 0 || line >= layout.lineCount) {
                            return super.onTouchEvent(widget, buffer, event)
                        }
                        val off = layout.getOffsetForHorizontal(line, x.toFloat())
                        if (off < 0 || off > buffer.length) {
                             return super.onTouchEvent(widget, buffer, event)
                        }


                        val spans = buffer.getSpans(off, off, ClickableSpan::class.java)
                        if (spans.isNotEmpty()) {
                            Log.d("CustomLinkMovement", "Found ${spans.size} ClickableSpan(s) at offset $off.")

                            for (span in spans) {
                                if (span is SodaNeClickableSpan) {
                                    Log.d("CustomLinkMovement", "Handling SodaNeClickableSpan: $span")
                                    span.onClick(widget)
                                    return true
                                }
                            }

                            for (span in spans) { 
                                if (span is URLSpan) {
                                    val url = span.url
                                    Log.d("CustomLinkMovement", "Found URLSpan with URL: $url")
                                    if (url != null && url.startsWith("javascript:")) {
                                        Log.d("CustomLinkMovement", "Ignoring javascript: link: $url")
                                    } else {
                                        Log.d("CustomLinkMovement", "Handling URLSpan: $url")
                                        span.onClick(widget)
                                    }
                                    return true 
                                } else if (span !is SodaNeClickableSpan) { 
                                    Log.d("CustomLinkMovement", "Handling other ClickableSpan: $span")
                                    span.onClick(widget)
                                    return true 
                                }
                            }
                            return true 
                        } else {
                            Log.d("CustomLinkMovement", "No ClickableSpan found at offset $off")
                        }
                    }
                    return super.onTouchEvent(widget, buffer, event)
                }
            }


            textView.setOnLongClickListener {
                showCopyDialog(it.context, spannableBuilder.toString())
                true
            }
        }

        private fun showCopyDialog(context: Context, textToCopy: String) {
            AlertDialog.Builder(context)
                .setItems(arrayOf("テキストをコピー")) { _, _ ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Copied Text", textToCopy)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "テキストをコピーしました", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    class ImageViewHolder(
        view: View,
        private val onQuoteClickListener: ((quotedText: String) -> Unit)?,
        private val fileNamePattern: Pattern
    ) : RecyclerView.ViewHolder(view) {
        private val imageView: ImageView = view.findViewById(R.id.detailImageView)
        private val promptTextView: TextView = view.findViewById(R.id.promptTextView)

        fun bind(item: DetailContent.Image, searchQuery: String?) {
            Log.d("DetailAdapter", "Binding image: ${item.imageUrl}, Prompt: ${item.prompt}")
            imageView.load(item.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_launcher_background)
                error(android.R.drawable.ic_dialog_alert)
                listener(
                    onStart = { _ ->
                        Log.d("DetailAdapter_Coil", "Image load START for: ${item.imageUrl}")
                    },
                    onSuccess = { _, metadata ->
                        Log.d("DetailAdapter_Coil", "Image load SUCCESS for: ${item.imageUrl}. Source: ${metadata.dataSource}")
                    },
                    onError = { _, result ->
                        Log.e("DetailAdapter_Coil", "Image load ERROR for: ${item.imageUrl}. Error: ${result.throwable}")
                    }
                )
                size(ViewSizeResolver(imageView))
            }

            imageView.setOnClickListener {
                val context = itemView.context
                val intent = Intent(context, MediaViewActivity::class.java)
                intent.putExtra(EXTRA_TYPE, TYPE_IMAGE)
                intent.putExtra(EXTRA_URL, item.imageUrl)
                item.prompt?.let { intent.putExtra(EXTRA_TEXT, it) } // プロンプトを EXTRA_TEXT で渡す
                context.startActivity(intent)
            }

            if (!item.prompt.isNullOrBlank()) {
                promptTextView.isVisible = true
                val promptText = item.prompt
                val spannableBuilder = SpannableStringBuilder(promptText)

                val fileMatcher = fileNamePattern.matcher(promptText)
                while (fileMatcher.find()) {
                    val fileName = fileMatcher.group(1)
                    if (fileName != null) {
                        val start = fileMatcher.start()
                        val end = fileMatcher.end()
                        val clickableSpan = object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                onQuoteClickListener?.invoke(fileName)
                            }
                            override fun updateDrawState(ds: TextPaint) {
                                super.updateDrawState(ds)
                                ds.isUnderlineText = true
                            }
                        }
                        spannableBuilder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }

                if (!searchQuery.isNullOrEmpty()) {
                    val textLc = promptText.lowercase()
                    val queryLc = searchQuery.lowercase()
                    var startPos = textLc.indexOf(queryLc)
                    while (startPos >= 0) {
                        val endPos = startPos + queryLc.length
                        spannableBuilder.setSpan(BackgroundColorSpan(Color.YELLOW), startPos, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        startPos = textLc.indexOf(queryLc, endPos)
                    }
                }
                promptTextView.text = spannableBuilder
                promptTextView.movementMethod = LinkMovementMethod.getInstance()

                promptTextView.setOnClickListener {
                    val context = itemView.context
                    val intent = Intent(context, MediaViewActivity::class.java)
                    intent.putExtra(EXTRA_TYPE, TYPE_TEXT)
                    intent.putExtra(EXTRA_TEXT, promptText)
                    context.startActivity(intent)
                }
            } else {
                promptTextView.isVisible = false
                promptTextView.text = null
                promptTextView.setOnClickListener(null)
            }

            imageView.setOnLongClickListener {
                showSaveDialog(item)
                true
            }
            item.prompt?.takeIf { it.isNotBlank() }?.let { textToCopy ->
                promptTextView.setOnLongClickListener {
                    showCopyDialog(itemView.context, textToCopy)
                    true
                }
            }
        }

        private fun showSaveDialog(item: DetailContent.Image) {
            val context = itemView.context
            AlertDialog.Builder(context)
                .setTitle("画像の保存")
                .setMessage("この画像を保存しますか？")
                .setPositiveButton("保存") { _, _ ->
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                        MediaSaver.saveImage(context, item.imageUrl)
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }

        private fun showCopyDialog(context: Context, textToCopy: String) {
            AlertDialog.Builder(context)
                .setItems(arrayOf("テキストをコピー")) { _, _ ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Copied Text", textToCopy)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "テキストをコピーしました", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    class VideoViewHolder(
        view: View,
        private val onQuoteClickListener: ((quotedText: String) -> Unit)?,
        private val fileNamePattern: Pattern
    ) : RecyclerView.ViewHolder(view) {
        private val playerView: PlayerView = view.findViewById(R.id.playerView)
        private val promptTextView: TextView = view.findViewById(R.id.promptTextView)
        private var exoPlayer: ExoPlayer? = null

        fun bind(item: DetailContent.Video, searchQuery: String?) {
            releasePlayer()
            val context = itemView.context
            Log.d("DetailAdapter", "Binding video: ${item.videoUrl}, Prompt: ${item.prompt}")
            exoPlayer = ExoPlayer.Builder(context).build().also { player ->
                playerView.player = player
                val mediaItem = MediaItem.fromUri(item.videoUrl)
                player.setMediaItem(mediaItem)
                player.playWhenReady = false
                player.prepare()
            }

            playerView.setOnClickListener { 
                val context = itemView.context
                val intent = Intent(context, MediaViewActivity::class.java)
                intent.putExtra(EXTRA_TYPE, TYPE_VIDEO)
                intent.putExtra(EXTRA_URL, item.videoUrl)
                item.prompt?.let { intent.putExtra(EXTRA_TEXT, it) } // プロンプトを EXTRA_TEXT で渡す
                context.startActivity(intent)
            }


            if (!item.prompt.isNullOrBlank()) {
                promptTextView.isVisible = true
                val promptText = item.prompt
                val spannableBuilder = SpannableStringBuilder(promptText)

                val fileMatcher = fileNamePattern.matcher(promptText)
                while (fileMatcher.find()) {
                    val fileName = fileMatcher.group(1)
                    if (fileName != null) {
                        val start = fileMatcher.start()
                        val end = fileMatcher.end()
                        val clickableSpan = object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                onQuoteClickListener?.invoke(fileName)
                            }
                            override fun updateDrawState(ds: TextPaint) {
                                super.updateDrawState(ds)
                                ds.isUnderlineText = true
                            }
                        }
                        spannableBuilder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }

                if (!searchQuery.isNullOrEmpty()) {
                    val textLc = promptText.lowercase()
                    val queryLc = searchQuery.lowercase()
                    var startPos = textLc.indexOf(queryLc)
                    while (startPos >= 0) {
                        val endPos = startPos + queryLc.length
                        spannableBuilder.setSpan(BackgroundColorSpan(Color.YELLOW), startPos, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        startPos = textLc.indexOf(queryLc, endPos)
                    }
                }
                promptTextView.text = spannableBuilder
                promptTextView.movementMethod = LinkMovementMethod.getInstance()

                promptTextView.setOnClickListener {
                    val context = itemView.context
                    val intent = Intent(context, MediaViewActivity::class.java)
                    intent.putExtra(EXTRA_TYPE, TYPE_TEXT)
                    intent.putExtra(EXTRA_TEXT, promptText)
                    context.startActivity(intent)
                }
            } else {
                promptTextView.isVisible = false
                promptTextView.text = null
                promptTextView.setOnClickListener(null)
            }

            playerView.setOnLongClickListener {
                showSaveDialog(item)
                true
            }
            item.prompt?.takeIf { it.isNotBlank() }?.let { textToCopy ->
                promptTextView.setOnLongClickListener {
                    showCopyDialog(itemView.context, textToCopy)
                    true
                }
            }
        }

        private fun showSaveDialog(item: DetailContent.Video) {
            val context = itemView.context
            AlertDialog.Builder(context)
                .setTitle("動画の保存")
                .setMessage("この動画を保存しますか？")
                .setPositiveButton("保存") { _, _ ->
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                        MediaSaver.saveVideo(context, item.videoUrl)
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }

        private fun showCopyDialog(context: Context, textToCopy: String) {
            AlertDialog.Builder(context)
                .setItems(arrayOf("テキストをコピー")) { _, _ ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Copied Text", textToCopy)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "テキストをコピーしました", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        fun releasePlayer() {
            exoPlayer?.let { player ->
                player.release()
                exoPlayer = null
                playerView.player = null
            }
        }
    }

    class ThreadEndTimeViewHolder( // ★ リスナーを受け取るように変更
        view: View,
        private val onThreadEndTimeClickListener: (() -> Unit)?
    ) : RecyclerView.ViewHolder(view) {
        private val endTimeTextView: TextView = view.findViewById(R.id.endTimeTextView)

        fun bind(item: DetailContent.ThreadEndTime) {
            endTimeTextView.text = item.endTime
            // ★ クリックリスナーを設定
            endTimeTextView.setOnClickListener {
                onThreadEndTimeClickListener?.invoke()
            }
            endTimeTextView.setOnLongClickListener {
                val context = itemView.context
                AlertDialog.Builder(context)
                    .setItems(arrayOf("テキストをコピー")) { _, _ ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Copied End Time", item.endTime)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "終了時刻をコピーしました", Toast.LENGTH_SHORT).show()
                    }
                    .show()
                true
            }
        }
    }

    class SodaNeClickableSpan(
        private val resNum: String,
        private val listener: ((String) -> Unit)?
    ) : ClickableSpan() {
        override fun onClick(widget: View) {
            Log.d("SodaNeClickableSpan", "onClick called with resNum: $resNum")
            listener?.invoke(resNum)
        }

        override fun updateDrawState(ds: TextPaint) {
            super.updateDrawState(ds)
            ds.isUnderlineText = true
            ds.color = Color.MAGENTA
        }
    }

    class DetailDiffCallback : DiffUtil.ItemCallback<DetailContent>() {
        override fun areItemsTheSame(oldItem: DetailContent, newItem: DetailContent): Boolean {
            return when {
                oldItem is DetailContent.Image && newItem is DetailContent.Image -> oldItem.imageUrl == newItem.imageUrl
                oldItem is DetailContent.Video && newItem is DetailContent.Video -> oldItem.videoUrl == newItem.videoUrl
                oldItem is DetailContent.Text && newItem is DetailContent.Text -> oldItem.id == newItem.id
                oldItem is DetailContent.ThreadEndTime && newItem is DetailContent.ThreadEndTime -> oldItem.id == newItem.id
                else -> oldItem.javaClass == newItem.javaClass && oldItem.id == newItem.id
            }
        }

        override fun areContentsTheSame(oldItem: DetailContent, newItem: DetailContent): Boolean {
            return oldItem == newItem
        }
    }
}
