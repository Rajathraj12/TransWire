package com.example.myapplication.domain.model

import android.net.Uri

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val extension: String,
    val mimeType: String
)
