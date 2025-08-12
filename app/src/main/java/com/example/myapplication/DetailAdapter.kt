package com.example.myapplication

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
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

    // ★★★ 追加: 引用タップをActivityに通知するためのコールバック ★★★
    var onQuoteClickListener: ((quotedText: String) -> Unit)? = null

    // (companion object から onBindViewHolder までは変更なし)
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
        // ★★★ 修正: TextViewHolderにコールバックを渡す ★★★
        return when (viewType) {
            VIEW_TYPE_TEXT -> TextViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.detail_item_text, parent, false),
                onQuoteClickListener // コールバックを渡す
            )
            VIEW_TYPE_IMAGE -> ImageViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.detail_item_image, parent, false))
            VIEW_TYPE_VIDEO -> VideoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.detail_item_video, parent, false))
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DetailContent.Text -> (holder as TextViewHolder).bind(item)
            is DetailContent.Image -> (holder as ImageViewHolder).bind(item)
            is DetailContent.Video -> (holder as VideoViewHolder).bind(item)
        }
    }

    // --- 各ViewHolder ---

    // ★★★ TextViewHolder をここから大幅に修正 ★★★
    class TextViewHolder(
        view: View,
        private val onQuoteClickListener: ((quotedText: String) -> Unit)?
    ) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.detailTextView)

        fun bind(item: DetailContent.Text) {
            // HTMLから基本的な装飾を反映したSpannedテキストを生成
            val spannedFromHtml = Html.fromHtml(item.htmlContent, Html.FROM_HTML_MODE_COMPACT)
            val spannableBuilder = SpannableStringBuilder(spannedFromHtml)

            // 正規表現で行頭の「>」に続く引用テキストを検索
            // Pattern.MULTILINE で複数行に対応
            val pattern = Pattern.compile("^>(.+)$", Pattern.MULTILINE)
            val matcher = pattern.matcher(spannedFromHtml.toString())

            while (matcher.find()) {
                val quoteText = matcher.group(1)?.trim() // 引用されているテキスト部分
                if (quoteText != null) {
                    val start = matcher.start()
                    val end = matcher.end()

                    // 引用部分にクリックイベントを設定
                    val clickableSpan = object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            onQuoteClickListener?.invoke(quoteText)
                        }

                        // 引用符の下線を消す（任意）
                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = false
                        }
                    }
                    spannableBuilder.setSpan(clickableSpan, start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            textView.text = spannableBuilder
            // LinkMovementMethodをセットすることでClickableSpanが反応するようになる
            textView.movementMethod = LinkMovementMethod.getInstance()

            // テキスト全体のロングタップでのコピー機能は維持
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
    // ★★★ TextViewHolder の修正はここまで ★★★

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imageView: ImageView = view.findViewById(R.id.detailImageView)
        private val promptTextView: TextView = view.findViewById(R.id.promptTextView)

        fun bind(item: DetailContent.Image) {
            imageView.load(item.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_launcher_background)
                error(android.R.drawable.ic_dialog_alert)
                size(coil.size.Size.ORIGINAL)
            }
            promptTextView.isVisible = !item.prompt.isNullOrBlank()
            promptTextView.text = item.prompt
            
            // 画像の長押しで保存ダイアログを表示
            imageView.setOnLongClickListener {
                showSaveDialog(item)
                true
            }

            // ★★★ 追加: promptTextView の長押しでコピー機能を呼び出す ★★★
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

        // ★★★ 追加: テキストコピー用のダイアログ表示メソッド ★★★
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

    class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val playerView: PlayerView = view.findViewById(R.id.playerView)
        private val promptTextView: TextView = view.findViewById(R.id.promptTextView)
        private var exoPlayer: ExoPlayer? = null

        fun bind(item: DetailContent.Video) {
            releasePlayer()

            val context = itemView.context
            exoPlayer = ExoPlayer.Builder(context).build().also { player ->
                playerView.player = player
                val mediaItem = MediaItem.fromUri(item.videoUrl)
                player.setMediaItem(mediaItem)
                player.playWhenReady = false
                player.prepare()
            }

            promptTextView.isVisible = !item.prompt.isNullOrBlank()
            promptTextView.text = item.prompt

            playerView.setOnLongClickListener {
                showSaveDialog(item)
                true
            }
            
            // ★★★ 追加: promptTextView の長押しでコピー機能を呼び出す ★★★
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
        
        // ★★★ 追加: テキストコピー用のダイアログ表示メソッド (VideoViewHolder用) ★★★
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

    // (DiffCallbackは変更なし)
    class DetailDiffCallback : DiffUtil.ItemCallback<DetailContent>() {
        override fun areItemsTheSame(oldItem: DetailContent, newItem: DetailContent): Boolean {
            return when {
                oldItem is DetailContent.Image && newItem is DetailContent.Image -> oldItem.imageUrl == newItem.imageUrl
                oldItem is DetailContent.Video && newItem is DetailContent.Video -> oldItem.videoUrl == newItem.videoUrl
                oldItem is DetailContent.Text && newItem is DetailContent.Text -> oldItem.hashCode() == newItem.hashCode()
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: DetailContent, newItem: DetailContent): Boolean {
            return oldItem == newItem
        }
    }
}
