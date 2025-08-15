package com.example.hutaburakari

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.Gson

sealed class ReplyResult {
    data class Success(val message: String) : ReplyResult()
    data class Error(val errorMessage: String) : ReplyResult()
    object Loading : ReplyResult()
}

class ReplyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ReplyRepository(NetworkModule.okHttpClient, Gson())

    private val _replyStatus = MutableLiveData<ReplyResult>()
    val replyStatus: LiveData<ReplyResult> = _replyStatus

    fun submitReply(
        boardUrl: String,
        threadId: String,
        name: String?,
        email: String?,
        comment: String,
        password: String?, // このパラメータ名はActivity側と合わせているので変更なし
        selectedFileUri: Uri?,
        isTextOnly: Boolean
    ) {
        _replyStatus.value = ReplyResult.Loading
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.postReply(
                        boardUrl = boardUrl,
                        resto = threadId,
                        name = name,
                        email = email,
                        sub = null, // 題名はUIにないのでnull固定
                        com = comment,
                        inputPwd = password, // Repositoryのパラメータ名に合わせて inputPwd に変更
                        upfileUri = selectedFileUri,
                        textOnly = isTextOnly,
                        context = getApplication()
                    )
                }

                if (result.isSuccess) {
                    _replyStatus.postValue(ReplyResult.Success(result.getOrNull() ?: "成功"))
                } else {
                    _replyStatus.postValue(ReplyResult.Error(result.exceptionOrNull()?.message ?: "不明なエラー"))
                }
            } catch (e: Exception) {
                _replyStatus.postValue(ReplyResult.Error("投稿中に例外が発生: ${e.message}"))
            }
        }
    }
}