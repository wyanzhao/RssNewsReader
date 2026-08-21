package com.dailynews.data.files

import com.dailynews.data.db.DailyNewsDatabase
import com.dailynews.data.db.RunArtifactEntity
import com.dailynews.pipeline.ports.ArtifactSink
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Room-backed artifact sink; the filesystem is used only for caller-owned zip destinations. */
class ArtifactStore(
    private val database: DailyNewsDatabase,
    private val now: () -> Instant = Instant::now,
) : ArtifactSink {
    override suspend fun write(runId: String, relativePath: String, content: ByteArray) = withContext(Dispatchers.IO) {
        require(runId.isNotBlank()) { "runId is required" }
        validateName(relativePath)
        database.runArtifacts().upsert(
            RunArtifactEntity(runId, relativePath, gzip(content), now().toString()),
        )
    }

    /**
     * 这次运行留下的全部产物名。
     *
     * 诊断页此前只硬编码展示 `validation.json` 与 `context_budget.json` 两个文件，
     * 而每一次 LLM 被打回都会写 `contract_violations/<op>-attempt-N.json`——也就是说
     * 应用把答案写下来了，却是全仓库唯一不可见的那份。要看只能导出 ZIP 拿到电脑上。
     */
    suspend fun names(runId: String): List<String> = withContext(Dispatchers.IO) {
        database.runArtifacts().metadataForRun(runId).map { it.name }
    }

    suspend fun readText(runId: String, relativePath: String): String? = withContext(Dispatchers.IO) {
        validateName(relativePath)
        loadGzipBody(runId, relativePath)?.let { ungzip(it).toString(Charsets.UTF_8) }
    }

    suspend fun exportZip(runId: String, destination: OutputStream) = withContext(Dispatchers.IO) {
        val rows = database.runArtifacts().metadataForRun(runId)
        ZipOutputStream(destination).use { zip ->
            rows.forEach { row ->
                validateName(row.name)
                zip.putNextEntry(ZipEntry(row.name).apply { time = 0L })
                zip.write(ungzip(requireNotNull(loadGzipBody(row.runId, row.name))))
                zip.closeEntry()
            }
        }
    }

    private suspend fun loadGzipBody(runId: String, name: String): ByteArray? {
        val size = database.runArtifacts().bodySize(runId, name) ?: return null
        require(size <= MAX_COMPRESSED_ARTIFACT_BYTES) { "compressed artifact exceeds ${MAX_COMPRESSED_ARTIFACT_BYTES / 1_048_576} MiB" }
        val output = ByteArrayOutputStream(size)
        var offset = 1
        var remaining = size
        while (remaining > 0) {
            val requested = minOf(DATABASE_CHUNK_BYTES, remaining)
            val chunk = requireNotNull(database.runArtifacts().bodyChunk(runId, name, offset, requested)) {
                "artifact disappeared while reading: $runId/$name"
            }
            require(chunk.isNotEmpty()) { "artifact truncated while reading: $runId/$name" }
            output.write(chunk)
            offset += chunk.size
            remaining -= chunk.size
        }
        return output.toByteArray()
    }

    private fun validateName(relativePath: String) {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/') && ".." !in relativePath.split('/')) {
            "unsafe artifact path"
        }
    }

    private fun gzip(content: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(content) }
        output.toByteArray()
    }

    private fun ungzip(content: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(content)).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_ARTIFACT_BYTES) { "artifact exceeds ${MAX_ARTIFACT_BYTES / 1_048_576} MiB" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private companion object {
        const val MAX_ARTIFACT_BYTES = 32 * 1_048_576
        const val MAX_COMPRESSED_ARTIFACT_BYTES = 64 * 1_048_576
        const val DATABASE_CHUNK_BYTES = 512 * 1_024
    }
}
