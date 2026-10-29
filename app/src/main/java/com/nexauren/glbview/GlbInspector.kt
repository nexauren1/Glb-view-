package com.nexauren.glbview

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class InspectionResult(
    val file: ModelFile,
    val valid: Boolean,
    val format: String,
    val version: String,
    val meshes: Int?,
    val nodes: Int?,
    val materials: Int?,
    val animations: Int?,
    val note: String,
    val inspected: Boolean = true
)

private const val GLB_MAGIC = 0x46546C67
private const val JSON_CHUNK_TYPE = 0x4E4F534A
private const val MAX_JSON_BYTES = 4 * 1024 * 1024

suspend fun inspectModel(context: Context, file: ModelFile): InspectionResult =
    withContext(Dispatchers.IO) { inspectModelBlocking(context, file) }

private fun inspectModelBlocking(context: Context, file: ModelFile): InspectionResult {
    if (!file.isGlb && !file.isGltf) {
        return InspectionResult(
            file, false, file.extension, "—", null, null, null, null,
            "Viewing is supported; batch metadata inspection is available for GLB/glTF.",
            inspected = false
        )
    }
    val stream = runCatching {
        context.contentResolver.openInputStream(Uri.parse(file.uri))
    }.getOrNull() ?: return failure(file, "Unable to open the file")

    return stream.use { input ->
        if (file.isGltf) inspectGltfJson(file, input) else inspectGlb(file, input)
    }
}

private fun inspectGlb(file: ModelFile, input: InputStream): InspectionResult {
    val header = ByteArray(12)
    if (!readFully(input, header)) return failure(file, "File is shorter than a GLB header")

    val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    val magic = headerBuffer.int
    val versionNumber = headerBuffer.int
    val declaredLength = headerBuffer.int.toLong() and 0xFFFFFFFFL

    if (magic != GLB_MAGIC) return failure(file, "Invalid GLB magic header")
    if (versionNumber != 2) {
        return InspectionResult(
            file, false, "GLB", versionNumber.toString(), null, null, null, null,
            "Unsupported GLB version; expected version 2"
        )
    }
    if (declaredLength < 20L) return failure(file, "Declared GLB length is too small")
    if (file.sizeBytes >= 0L && declaredLength > file.sizeBytes) {
        return failure(file, "Header declares more bytes than the file contains")
    }

    val chunkHeader = ByteArray(8)
    if (!readFully(input, chunkHeader)) return failure(file, "GLB JSON chunk header is missing")
    val chunkBuffer = ByteBuffer.wrap(chunkHeader).order(ByteOrder.LITTLE_ENDIAN)
    val jsonLength = chunkBuffer.int.toLong() and 0xFFFFFFFFL
    val chunkType = chunkBuffer.int

    if (chunkType != JSON_CHUNK_TYPE) return failure(file, "First GLB chunk is not JSON")
    if (jsonLength < 2L) return failure(file, "GLB JSON chunk is empty")
    if (jsonLength > MAX_JSON_BYTES) {
        return InspectionResult(
            file, true, "GLB 2.0", versionNumber.toString(), null, null, null, null,
            "Header is valid; JSON chunk exceeds the 4 MiB inspection limit"
        )
    }

    val jsonBytes = ByteArray(jsonLength.toInt())
    if (!readFully(input, jsonBytes)) return failure(file, "GLB JSON chunk is truncated")
    val json = runCatching {
        JSONObject(String(jsonBytes, StandardCharsets.UTF_8).trim().trimEnd('\u0000', ' '))
    }.getOrElse { return failure(file, "GLB JSON chunk is not valid JSON") }

    val assetVersion = json.optJSONObject("asset")?.optString("version").orEmpty()
    val effectiveVersion = if (assetVersion.isBlank()) versionNumber.toString() else assetVersion
    return stats(file, true, "GLB 2.0", effectiveVersion, json, "Header and JSON chunk are valid")
}

private fun inspectGltfJson(file: ModelFile, input: InputStream): InspectionResult {
    val bytes = readBounded(input, MAX_JSON_BYTES)
    if (bytes.size > MAX_JSON_BYTES) return failure(file, "glTF JSON exceeds the 4 MiB inspection limit")
    val json = runCatching {
        JSONObject(String(bytes, StandardCharsets.UTF_8).trim().trimEnd('\u0000', ' '))
    }.getOrElse { return failure(file, "File is not valid glTF JSON") }
    val assetVersion = json.optJSONObject("asset")?.optString("version").orEmpty()
    if (assetVersion.isBlank()) return failure(file, "The glTF asset.version field is missing")
    return stats(file, true, "glTF JSON", assetVersion, json, "JSON structure is valid; external resources are not checked")
}

private fun stats(
    file: ModelFile,
    valid: Boolean,
    format: String,
    version: String,
    json: JSONObject,
    note: String
) = InspectionResult(
    file = file,
    valid = valid,
    format = format,
    version = version,
    meshes = json.optJSONArray("meshes")?.length() ?: 0,
    nodes = json.optJSONArray("nodes")?.length() ?: 0,
    materials = json.optJSONArray("materials")?.length() ?: 0,
    animations = json.optJSONArray("animations")?.length() ?: 0,
    note = note
)

private fun failure(file: ModelFile, note: String) = InspectionResult(
    file = file,
    valid = false,
    format = if (file.isGlb) "GLB" else if (file.isGltf) "glTF JSON" else file.extension,
    version = "—",
    meshes = null,
    nodes = null,
    materials = null,
    animations = null,
    note = note
)

private fun readFully(input: InputStream, bytes: ByteArray): Boolean {
    var offset = 0
    while (offset < bytes.size) {
        val count = input.read(bytes, offset, bytes.size - offset)
        if (count <= 0) return false
        offset += count
    }
    return true
}

private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (output.size() <= maxBytes) {
        val remaining = maxBytes + 1 - output.size()
        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

fun inspectionResultsToCsv(results: List<InspectionResult>): String = buildString {
    appendLine("file,format,inspected,valid,version,meshes,nodes,materials,animations,note")
    results.forEach { result ->
        appendLine(
            listOf(
                result.file.name,
                result.format,
                result.inspected.toString(),
                result.valid.toString(),
                result.version,
                result.meshes?.toString().orEmpty(),
                result.nodes?.toString().orEmpty(),
                result.materials?.toString().orEmpty(),
                result.animations?.toString().orEmpty(),
                result.note
            ).joinToString(",") { csvEscape(it) }
        )
    }
}

private fun csvEscape(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
