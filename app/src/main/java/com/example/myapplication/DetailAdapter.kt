package com.example.myapplication

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.LayoutInflater
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
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class DetailAdapter : ListAdapter<DetailContent, RecyclerView.ViewHolder>(DetailDiffCallback()) {

    var onQuoteClickListener: ((quotedText: String) -> Unit)? = null
    private var currentSearchQuery: String? = null

    // Pattern to detect file names like xxx.jpg, xxx.mp4, etc.
    private val fileNamePattern = Pattern.compile("\\b([a-zA-Z0-9_.-]+\\.(?:jpg|jpeg|png|gif|mp4|webm|mov|avi|flv|mkv))\\b", Pattern.CASE_INSENSITIVE)


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
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_TEXT -> TextViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.detail_item_text, parent, false),
                onQuoteClickListener,
                fileNamePattern // Pass pattern
            )
            VIEW_TYPE_IMAGE -> ImageViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.detail_item_image, parent, false),
                onQuoteClickListener,
                fileNamePattern // Pass pattern
            )
            VIEW_TYPE_VIDEO -> VideoViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.detail_item_video, parent, false),
                onQuoteClickListener,
                fileNamePattern // Pass pattern
            )
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DetailContent.Text -> (holder as TextViewHolder).bind(item, currentSearchQuery)
            is DetailContent.Image -> (holder as ImageViewHolder).bind(item, currentSearchQuery)
            is DetailContent.Video -> (holder as VideoViewHolder).bind(item, currentSearchQuery)
        }
    }

    class TextViewHolder(
        view: View,
        private val onQuoteClickListener: ((quotedText: String) -> Unit)?,
        private val fileNamePattern: Pattern
    ) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.detailTextView)

        fun bind(item: DetailContent.Text, searchQuery: String?) {
            val spannedFromHtml = Html.fromHtml(item.htmlContent, Html.FROM_HTML_MODE_COMPACT)
            val spannableBuilder = SpannableStringBuilder(spannedFromHtml)
            val contentString = spannedFromHtml.toString()

            // Handle standard quotes like >quoted text
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
                            ds.isUnderlineText = false // No underline for quotes
                        }
                    }
                    spannableBuilder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            // Handle file name quotes like xxx.jpg
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
                            // Optionally style file name quotes differently, e.g., underline
                            ds.isUnderlineText = true
                        }
                    }
                    spannableBuilder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }


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
                    startIndex = textLc.indexOf(queryLc, endIndex) // Start next search after the current find
                }
            }

            textView.text = spannableBuilder
            textView.movementMethod = LinkMovementMethod.getInstance()
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
            imageView.load(item.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_launcher_background)
                error(android.R.drawable.ic_dialog_alert)
                size(coil.size.Size.ORIGINAL)
            }

            if (!item.prompt.isNullOrBlank()) {
                promptTextView.isVisible = true
                val promptText = item.prompt
                val spannableBuilder = SpannableStringBuilder(promptText)

                // Handle file name quotes in prompt
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
                promptTextView.movementMethod = LinkMovementMethod.getInstance() // Make ClickableSpans work
            } else {
                promptTextView.isVisible = false
                promptTextView.text = null
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
            exoPlayer = ExoPlayer.Builder(context).build().also { player ->
                playerView.player = player
                val mediaItem = MediaItem.fromUri(item.videoUrl)
                player.setMediaItem(mediaItem)
                player.playWhenReady = false
                player.prepare()
            }

            if (!item.prompt.isNullOrBlank()) {
                promptTextView.isVisible = true
                val promptText = item.prompt
                val spannableBuilder = SpannableStringBuilder(promptText)

                // Handle file name quotes in prompt
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
                promptTextView.movementMethod = LinkMovementMethod.getInstance() // Make ClickableSpans work
            } else {
                promptTextView.isVisible = false
                promptTextView.text = null
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

    class DetailDiffCallback : DiffUtil.ItemCallback<DetailContent>() {
        override fun areItemsTheSame(oldItem: DetailContent, newItem: DetailContent): Boolean {
            return when {
                oldItem is DetailContent.Image && newItem is DetailContent.Image -> oldItem.imageUrl == newItem.imageUrl
                oldItem is DetailContent.Video && newItem is DetailContent.Video -> oldItem.videoUrl == newItem.videoUrl
                oldItem is DetailContent.Text && newItem is DetailContent.Text -> oldItem.htmlContent.hashCode() == newItem.htmlContent.hashCode() // Compare content
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: DetailContent, newItem: DetailContent): Boolean {
            return oldItem == newItem
        }
    }
}
