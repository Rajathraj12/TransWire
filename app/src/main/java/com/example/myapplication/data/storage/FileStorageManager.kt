package com.example.myapplication.data.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class FileStorageManager(private val context: Context) {

    fun createTempFile(fileId: String): File {
        return File(context.cacheDir, "$fileId.tmp").apply {
            if (exists()) delete()
            createNewFile()
        }
    }

    fun saveToDownloads(tempFile: File, fileName: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Transwire")
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val uri = resolver.insert(collection, contentValues)
        
        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(tempFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                tempFile.delete() // Clean up temp file
                return uri
            } catch (e: IOException) {
                resolver.delete(uri, null, null)
                e.printStackTrace()
            }
        }
        return null
    }

    fun getFileUri(fileId: String): Uri? {
        // Implementation for looking up saved files could go here
        return null
    }
}
