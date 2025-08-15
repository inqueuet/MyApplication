package com.example.hutaburakari

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.hutaburakari.databinding.ActivityReplyBinding

class ReplyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReplyBinding
    private val viewModel: ReplyViewModel by viewModels()

    private var threadId: String? = null
    private var threadTitle: String? = null
    private var boardUrl: String? = null
    private var selectedFileUri: Uri? = null

    companion object {
        const val EXTRA_THREAD_ID = "extra_thread_id"
        const val EXTRA_THREAD_TITLE = "extra_thread_title"
        const val EXTRA_BOARD_URL = "extra_board_url"
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            // 永続的なパーミッションを取得 (API 19以上)
            try {
                // contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                Log.e("ReplyActivity", "Failed to take persistable URI permission", e)
                // 必要であればユーザーに通知
            }
            selectedFileUri = it
            binding.textViewSelectedFileName.text = getFileNameFromUri(it) ?: "ファイル名不明"
            binding.checkBoxTextOnly.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReplyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "レスを投稿"

        threadId = intent.getStringExtra(EXTRA_THREAD_ID)
        threadTitle = intent.getStringExtra(EXTRA_THREAD_TITLE)
        boardUrl = intent.getStringExtra(EXTRA_BOARD_URL)

        if (threadId == null || boardUrl == null) {
            Toast.makeText(this, "エラー: スレッド情報またはボードURLがありません", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.textViewThreadTitle.text = threadTitle ?: "タイトルなし"

        binding.buttonSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        binding.checkBoxTextOnly.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedFileUri = null
                binding.textViewSelectedFileName.text = "ファイル未選択"
                // 必要に応じてファイル選択ボタンを無効化/有効化
            }
        }

        binding.buttonSubmit.setOnClickListener {
            submitReply()
        }

        observeViewModel()
    }

    private fun submitReply() {
        val comment = binding.editTextComment.text.toString().trim()
        if (comment.isBlank()) {
            Toast.makeText(this, "コメントを入力してください", Toast.LENGTH_SHORT).show()
            return
        }

        val name = binding.editTextName.text.toString().trim()
        val email = binding.editTextEmail.text.toString().trim()
        val password = binding.editTextPassword.text.toString().trim()
        val isTextOnly = binding.checkBoxTextOnly.isChecked

        // ViewModelに処理を依頼
        viewModel.submitReply(
            boardUrl = boardUrl!!, // onCreateでnullチェック済み
            threadId = threadId!!, // onCreateでnullチェック済み
            name = name.ifEmpty { null },
            email = email.ifEmpty { null },
            comment = comment,
            password = password.ifEmpty { null },
            selectedFileUri = if (isTextOnly) null else selectedFileUri,
            isTextOnly = isTextOnly
        )
    }

    private fun observeViewModel() {
        viewModel.replyStatus.observe(this) { result ->
            when (result) {
                is ReplyResult.Loading -> {
                    binding.progressBar.isVisible = true
                    binding.buttonSubmit.isEnabled = false
                    binding.nestedScrollView.alpha = 0.5f
                }
                is ReplyResult.Success -> {
                    binding.progressBar.isVisible = false
                    binding.buttonSubmit.isEnabled = true
                    binding.nestedScrollView.alpha = 1.0f
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    // 成功したらActivityを終了するなどの処理
                    finish()
                }
                is ReplyResult.Error -> {
                    binding.progressBar.isVisible = false
                    binding.buttonSubmit.isEnabled = true
                    binding.nestedScrollView.alpha = 1.0f
                    Toast.makeText(this, "エラー: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var fileName: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex != -1) {
                        fileName = cursor.getString(displayNameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ReplyActivity", "Error getting file name from URI: $uri", e)
        }
        if (fileName == null) {
            fileName = uri.path?.substringAfterLast('/')
        }
        return fileName
    }
}
