package com.example.util

import com.example.model.FileCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {

    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "Unknown size"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun formatTimestamp(millis: Long): String {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(millis))
    }

    fun categorizeFile(name: String, mimeType: String): FileCategory {
        val lowerMime = mimeType.lowercase(Locale.US)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)

        return when {
            lowerMime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg") ->
                FileCategory.IMAGE
            lowerMime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "avi", "webm", "3gp") ->
                FileCategory.VIDEO
            lowerMime.startsWith("audio/") || ext in setOf("mp3", "wav", "aac", "flac", "ogg", "m4a") ->
                FileCategory.AUDIO
            ext in setOf("apk", "xapk", "aab", "exe", "dmg", "deb") || lowerMime == "application/vnd.android.package-archive" ->
                FileCategory.APP
            ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2") || lowerMime.contains("zip") || lowerMime.contains("compressed") ->
                FileCategory.ARCHIVE
            ext in setOf("txt", "md", "json", "xml", "csv", "log", "kt", "java", "py", "js", "html", "css") || lowerMime.startsWith("text/") ->
                FileCategory.TEXT
            ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub") || lowerMime.contains("pdf") || lowerMime.contains("document") ->
                FileCategory.DOCUMENT
            else -> FileCategory.OTHER
        }
    }

    fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "pdf" -> "application/pdf"
            "txt", "log", "md" -> "text/plain; charset=utf-8"
            "html", "htm" -> "text/html; charset=utf-8"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> "application/octet-stream"
        }
    }

    fun sanitizeFileName(raw: String): String {
        val trimmed = raw.trim().replace('\\', '/').substringAfterLast('/')
        val cleaned = trimmed.replace(Regex("[<>:\"/|?*\\p{Cntrl}]"), "_")
        return cleaned.ifBlank { "file_${System.currentTimeMillis()}" }
    }
}
