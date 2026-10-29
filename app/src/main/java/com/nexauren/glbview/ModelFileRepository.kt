package com.nexauren.glbview

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale

data class ModelFile(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long
) {
    val extension: String get() = name.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val isGlb: Boolean get() = extension == "GLB"
    val isGltf: Boolean get() = extension == "GLTF"
}

private const val PREFS_NAME = "glb_view_preferences"
private const val LIBRARY_KEY = "saved_model_library"
private const val LANGUAGE_KEY = "ui_language"

fun isSupportedModelName(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    return name.substringAfterLast('.', "").uppercase(Locale.ROOT) in
        setOf("GLB", "GLTF", "3MF", "STL", "OBJ", "PLY")
}

fun readModelFileBlocking(context: Context, uri: Uri): ModelFile? {
    var name = ""
    var size = -1L
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameColumn >= 0) name = cursor.getString(nameColumn).orEmpty()
            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
        }
    }

    val document = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
    if (name.isBlank()) name = document?.name.orEmpty()
    if (!isSupportedModelName(name)) return null
    if (size < 0L) size = document?.length()?.takeIf { it >= 0L } ?: -1L

    return ModelFile(
        uri = uri.toString(),
        name = name,
        sizeBytes = size,
        modifiedAt = document?.lastModified() ?: 0L
    )
}

suspend fun readModelFile(context: Context, uri: Uri): ModelFile? =
    withContext(Dispatchers.IO) { readModelFileBlocking(context, uri) }

suspend fun scanModelFolder(context: Context, treeUri: Uri): List<ModelFile> =
    withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext emptyList()

        val pending = ArrayDeque<DocumentFile>()
        val discovered = mutableListOf<ModelFile>()
        pending.addLast(root)

        while (pending.isNotEmpty()) {
            val folder = pending.removeFirst()
            if (!folder.isDirectory) continue
            val children = runCatching { folder.listFiles().toList() }.getOrElse { emptyList() }
            for (child in children) {
                if (child.isDirectory) {
                    pending.addLast(child)
                } else if (isSupportedModelName(child.name)) {
                    discovered.add(
                        ModelFile(
                            uri = child.uri.toString(),
                            name = child.name ?: "model.glb",
                            sizeBytes = child.length().takeIf { it >= 0L } ?: -1L,
                            modifiedAt = child.lastModified()
                        )
                    )
                }
            }
        }
        discovered.distinctBy { it.uri }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

fun mergeModelFiles(existing: List<ModelFile>, incoming: List<ModelFile>): List<ModelFile> {
    val byUri = LinkedHashMap<String, ModelFile>()
    existing.forEach { byUri[it.uri] = it }
    incoming.forEach { byUri[it.uri] = it }
    return byUri.values.toList()
}

fun loadSavedLibrary(context: Context): List<ModelFile> {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(LIBRARY_KEY, null) ?: return emptyList()
    return runCatching {
        val json = JSONArray(raw)
        buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                val uri = item.optString("uri")
                val name = item.optString("name")
                if (uri.isNotBlank() && isSupportedModelName(name)) {
                    add(
                        ModelFile(
                            uri = uri,
                            name = name,
                            sizeBytes = item.optLong("size", -1L),
                            modifiedAt = item.optLong("modified", 0L)
                        )
                    )
                }
            }
        }.distinctBy { it.uri }
    }.getOrElse { emptyList() }
}

fun saveLibrary(context: Context, files: List<ModelFile>) {
    val json = JSONArray()
    files.forEach { file ->
        json.put(
            JSONObject()
                .put("uri", file.uri)
                .put("name", file.name)
                .put("size", file.sizeBytes)
                .put("modified", file.modifiedAt)
        )
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(LIBRARY_KEY, json.toString()).apply()
}

fun loadLanguage(context: Context): String =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(LANGUAGE_KEY, "pt") ?: "pt"

fun saveLanguage(context: Context, language: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(LANGUAGE_KEY, language).apply()
}

fun formatFileSize(bytes: Long, language: UiLanguage): String {
    if (bytes < 0L) return if (language == UiLanguage.PT) "Tamanho desconhecido" else "Size unavailable"
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    do {
        value /= 1024.0
        unitIndex++
    } while (value >= 1024.0 && unitIndex < units.lastIndex)
    return String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex])
}
