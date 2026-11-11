package com.example.myapplication.presentation.send

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.domain.model.SelectedFile
import com.example.myapplication.domain.repository.FileSelectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SendViewModel(application: Application) : AndroidViewModel(application) {

    private val _selectedFiles = MutableStateFlow<List<SelectedFile>>(emptyList())
    val selectedFiles: StateFlow<List<SelectedFile>> = _selectedFiles.asStateFlow()

    fun addFiles(uris: List<Uri>) {
        viewModelScope.launch {
            val contentResolver = getApplication<Application>().contentResolver
            val newFiles = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    var name = "Unknown File"
                    var size = 0L
                    val mimeType = contentResolver.getType(uri) ?: "*/*"

                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)

                            if (nameIndex != -1) {
                                name = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: "Unknown File"
                            }
                            if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                                size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                            }
                        }
                    }

                    val extension = name.substringAfterLast('.', "")

                    SelectedFile(
                        uri = uri,
                        name = name,
                        size = size,
                        extension = extension,
                        mimeType = mimeType
                    )
                }
            }

            _selectedFiles.value = (_selectedFiles.value + newFiles).distinctBy { it.uri }
            FileSelectionRepository.selectedFiles = _selectedFiles.value
        }
    }
    
    fun removeFile(file: SelectedFile) {
        _selectedFiles.value = _selectedFiles.value.filter { it.uri != file.uri }
        FileSelectionRepository.selectedFiles = _selectedFiles.value
    }
    
    fun clearFiles() {
        _selectedFiles.value = emptyList()
        FileSelectionRepository.clear()
    }
}
