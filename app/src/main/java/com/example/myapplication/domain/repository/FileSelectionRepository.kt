package com.example.myapplication.domain.repository

import com.example.myapplication.domain.model.SelectedFile

object FileSelectionRepository {
    private var _selectedFiles: List<SelectedFile> = emptyList()
    
    var selectedFiles: List<SelectedFile>
        get() = _selectedFiles
        set(value) {
            _selectedFiles = value
        }
        
    fun clear() {
        _selectedFiles = emptyList()
    }
}
