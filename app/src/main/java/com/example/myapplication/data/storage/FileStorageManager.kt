package com.example.myapplication.data.storage

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

class FileStorageManager(private val context: Context) {

    companion object {
        private const val TEMP_SUBDIR = "transfers"
        private const val SAFETY_STORAGE_MARGIN_BYTES = 5L * 1024L * 1024L // 5 MiB

        /**
         * Strips directory traversal segments, control characters, and reserved filename characters
         * so sender-supplied filenames can never escape target storage directories.
         */
        fun sanitizeFileName(rawName: String?): String {
            if (rawName.isNullOrBlank()) return "received_file"
            val leaf = rawName
                .replace('\u0000', '_')
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()

            val cleaned = leaf
                .replace(Regex("[\\x00-\\x1F\\x7F<>:\"/\\\\|?*]"), "_")
                .trim('.', ' ')

            if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
                return "received_file"
            }

            if (cleaned.length <= 180) return cleaned
            val dotIndex = cleaned.lastIndexOf('.')
            return if (dotIndex > 0 && dotIndex > cleaned.length - 16) {
                val ext = cleaned.substring(dotIndex)
                val base = cleaned.substring(0, (180 - ext.length).coerceAtLeast(1))
                base + ext
            } else {
                cleaned.take(180)
            }
        }
    }

    private fun getTempDirectory(): File {
        return File(context.cacheDir, TEMP_SUBDIR).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun safeTempFileForId(fileId: String): File {
        val tempDir = getTempDirectory()
        val safeId = fileId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(128).ifEmpty { "file" }
        val target = File(tempDir, "$safeId.tmp")
        val canonicalRoot = tempDir.canonicalPath + File.separator
        if (!target.canonicalPath.startsWith(canonicalRoot)) {
            throw SecurityException("Invalid temp file path")
        }
        return target
    }

    fun createTempFile(fileId: String): File {
        return safeTempFileForId(fileId).apply {
            if (exists()) delete()
            createNewFile()
        }
    }

    fun getOrCreateTempFile(fileId: String, existingPath: String?, maxExpectedBytes: Long): Pair<File, Boolean> {
        val tempDir = getTempDirectory()
        val canonicalRoot = tempDir.canonicalPath + File.separator
        if (!existingPath.isNullOrBlank()) {
            val existing = File(existingPath)
            val isInsideTemp = runCatching { existing.canonicalPath.startsWith(canonicalRoot) }.getOrDefault(false)
            if (isInsideTemp && existing.exists() && existing.isFile && existing.length() <= maxExpectedBytes) {
                return existing to true
            }
        }
        return createTempFile(fileId) to false
    }

    fun deleteTempFile(file: File?) {
        if (file == null) return
        try {
            val tempDir = getTempDirectory()
            val canonicalRoot = tempDir.canonicalPath + File.separator
            val isInsideCache = file.canonicalPath.startsWith(canonicalRoot) ||
                file.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator)
            if (isInsideCache && file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun cleanupStaleTempFiles(protectedPaths: Set<String>, maxAgeMs: Long = 24L * 60L * 60L * 1000L) {
        val now = System.currentTimeMillis()
        val protectedCanonical = protectedPaths.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet()

        getTempDirectory().listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val canonical = runCatching { file.canonicalPath }.getOrNull()
            val isProtected = canonical != null && canonical in protectedCanonical
            val isExpired = (now - file.lastModified()) > maxAgeMs
            if (!isProtected || isExpired) {
                file.delete()
            }
        }

        // Clean any legacy *.tmp files created directly under cacheDir
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".tmp") && (now - file.lastModified()) > 60L * 60L * 1000L) {
                file.delete()
            }
        }
    }

    fun getAvailableStorageBytes(): Long {
        val cacheAvailable = runCatching { StatFs(context.cacheDir.absolutePath).availableBytes }.getOrDefault(0L)
        val externalBase = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val destAvailable = runCatching { StatFs(externalBase.absolutePath).availableBytes }.getOrDefault(cacheAvailable)
        return minOf(cacheAvailable, destAvailable)
    }

    fun hasSufficientStorage(requiredBytes: Long): Boolean {
        if (requiredBytes <= 0L) return true
        return getAvailableStorageBytes() >= requiredBytes + SAFETY_STORAGE_MARGIN_BYTES
    }

    fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun saveToDownloads(tempFile: File, rawFileName: String, mimeType: String): Uri? {
        val safeName = sanitizeFileName(rawFileName)
        val resolvedMime = resolveMimeType(safeName, mimeType)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = insertIntoDownloadsMediaStore(safeName, resolvedMime)
                ?: insertIntoDownloadsMediaStore(safeName, "application/octet-stream")
            if (uri != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        FileInputStream(tempFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } ?: throw IllegalStateException("Could not open MediaStore output stream")

                    val updateValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    context.contentResolver.update(uri, updateValues, null, null)
                    deleteTempFile(tempFile)
                    return uri
                } catch (e: Exception) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    e.printStackTrace()
                }
            }
        }

        // Fallback for Android 9 and below (or if MediaStore insert fails)
        return try {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val transwireDir = File(baseDir, "Transwire").apply { mkdirs() }
            val targetFile = generateUniqueFile(transwireDir, safeName)
            val canonicalRoot = transwireDir.canonicalPath + File.separator
            if (!targetFile.canonicalPath.startsWith(canonicalRoot)) {
                throw SecurityException("Blocked path traversal in destination file")
            }
            FileInputStream(tempFile).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            deleteTempFile(tempFile)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun resolveSavedDisplayName(uri: Uri, fallbackName: String): String {
        val sanitizedFallback = sanitizeFileName(fallbackName)
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (idx != -1) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return sanitizeFileName(name)
                    }
                }
            }
            sanitizedFallback
        } catch (_: Exception) {
            sanitizedFallback
        }
    }

    private fun insertIntoDownloadsMediaStore(fileName: String, mimeType: String): Uri? {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Transwire")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        } catch (_: Exception) {
            null
        }
    }

    private fun generateUniqueFile(directory: File, fileName: String): File {
        var candidate = File(directory, fileName)
        if (!candidate.exists()) return candidate
        val dotIndex = fileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "$baseName ($counter)$extension")
            counter++
        }
        return candidate
    }

    fun findSavedFileUri(fileName: String): Uri? {
        val safeName = sanitizeFileName(fileName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
            )
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(safeName)
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            try {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    var fallbackId: Long? = null
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val relPath = if (pathCol != -1) cursor.getString(pathCol) else null
                        if (relPath != null && relPath.contains("Transwire", ignoreCase = true)) {
                            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        }
                        if (fallbackId == null) {
                            fallbackId = id
                        }
                    }
                    if (fallbackId != null) {
                        return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, fallbackId)
                    }
                }
            } catch (_: Exception) {
            }
        }

        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val transwireDir = File(baseDir, "Transwire")
        val appLocalFile = File(transwireDir, safeName)
        val isSafe = runCatching {
            appLocalFile.canonicalPath.startsWith(transwireDir.canonicalPath + File.separator)
        }.getOrDefault(false)
        if (isSafe && appLocalFile.exists()) {
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", appLocalFile)
        }
        return null
    }

    fun getFileUri(fileId: String): Uri? {
        return null
    }

    fun openCompletedFile(
        fileUriString: String?,
        fileName: String,
        mimeType: String,
        status: String
    ): Boolean {
        if (status != "COMPLETED") {
            Toast.makeText(
                context,
                "Cannot open file: transfer status is ${status.lowercase()}.",
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        return openFile(fileUriString, fileName, mimeType)
    }

    fun openFile(fileUriString: String?, fileName: String, mimeType: String): Boolean {
        val parsedUri = fileUriString?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val resolvedUri = when {
            parsedUri != null && isUriReadable(parsedUri) -> parsedUri
            else -> findSavedFileUri(fileName)?.takeIf { isUriReadable(it) }
        }

        if (resolvedUri == null) {
            Toast.makeText(
                context,
                "Unable to find \"$fileName\". It may have been moved or deleted.",
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        val resolverMime = runCatching { context.contentResolver.getType(resolvedUri) }.getOrNull()
        val effectiveMime = when {
            !resolverMime.isNullOrBlank() && resolverMime != "*/*" && resolverMime != "application/octet-stream" -> resolverMime
            else -> resolveMimeType(fileName, mimeType)
        }.let { if (it == "application/octet-stream") "*/*" else it }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(resolvedUri, effectiveMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(viewIntent)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                "No app installed to open \"$fileName\".",
                Toast.LENGTH_SHORT
            ).show()
            false
        } catch (_: Exception) {
            Toast.makeText(
                context,
                "Could not open \"$fileName\".",
                Toast.LENGTH_SHORT
            ).show()
            false
        }
    }

    private fun isUriReadable(uri: Uri): Boolean {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun resolveMimeType(fileName: String, mimeType: String?): String {
        val candidate = mimeType?.trim().orEmpty()
        if (candidate.isNotEmpty() && candidate != "*/*" && candidate != "application/octet-stream") {
            return candidate
        }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        }
        return if (candidate.isNotEmpty() && candidate != "*/*") candidate else "application/octet-stream"
    }
}
