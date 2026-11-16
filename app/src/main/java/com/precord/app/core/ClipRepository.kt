package com.precord.app.core

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clips live in `filesDir/clips` (private, shareable through FileProvider).
 * Titles/tags/duration cache are kept in `index.json` next to them.
 */
class ClipRepository(context: Context) {
    val dir: File = File(context.filesDir, "clips").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private var index: JSONObject = runCatching { JSONObject(indexFile.readText()) }.getOrDefault(JSONObject())
    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()

    /** `REC_2026-11-16_03-33-12.wav` (adds `_2`, `_3`… on collision). */
    fun newFile(format: ClipFormat, now: Long = System.currentTimeMillis()): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(now))
        var f = File(dir, "REC_$stamp.${format.ext}")
        var n = 2
        while (f.exists()) f = File(dir, "REC_${stamp}_${n++}.${format.ext}")
        return f
    }

    @Synchronized
    fun refresh() {
        val files = dir.listFiles { f -> f.isFile && ClipFormat.entries.any { it.ext == f.extension.lowercase() } }.orEmpty()
        val known = files.map { it.name }.toSet()
        var dirty = false
        index.keys().asSequence().toList().filter { it !in known }.forEach { index.remove(it); dirty = true }
        val list = files.map { f ->
            var meta = index.optJSONObject(f.name)
            if (meta == null || !meta.has("durationMs")) {
                val (d, sr) = probe(f)
                meta = (meta ?: JSONObject()).put("durationMs", d).put("sampleRate", sr)
                index.put(f.name, meta)
                dirty = true
            }
            toClip(f, meta!!)
        }.sortedByDescending { it.createdAt }
        if (dirty) saveIndex()
        _clips.value = list
    }

    @Synchronized
    fun register(file: File, durationMs: Long, sampleRate: Int): Clip {
        val meta = JSONObject().put("durationMs", durationMs).put("sampleRate", sampleRate)
        index.put(file.name, meta)
        saveIndex()
        refresh()
        return toClip(file, meta)
    }

    @Synchronized
    fun updateMeta(file: File, title: String, tags: List<String>) {
        val meta = index.optJSONObject(file.name) ?: JSONObject().also { index.put(file.name, it) }
        meta.put("title", title.trim())
        meta.put("tags", JSONArray(tags.map { it.trim() }.filter { it.isNotEmpty() }))
        saveIndex()
        refresh()
    }

    @Synchronized
    fun delete(file: File) {
        file.delete()
        index.remove(file.name)
        saveIndex()
        refresh()
    }

    /**
     * Trims [clip] to [startMs, endMs). `replace = true` swaps the original in place (keeping title/tags),
     * otherwise a new `REC_*` file is created. Blocking.
     */
    fun trimClip(clip: Clip, startMs: Long, endMs: Long, replace: Boolean): Boolean {
        val len = endMs - startMs
        if (len <= 0) return false
        return if (replace) {
            val tmp = File(dir, "trim_tmp.${clip.format.ext}")
            if (!ClipEditor.trim(clip.file, tmp, startMs, endMs)) return false
            synchronized(this) {
                if (!clip.file.delete() || !tmp.renameTo(clip.file)) { tmp.delete(); return false }
                val meta = index.optJSONObject(clip.file.name) ?: JSONObject().also { index.put(clip.file.name, it) }
                meta.put("durationMs", len)
                saveIndex()
            }
            refresh()
            true
        } else {
            val dst = newFile(clip.format)
            if (!ClipEditor.trim(clip.file, dst, startMs, endMs)) return false
            register(dst, len, clip.sampleRate)
            updateMeta(dst, "${clip.title} (trim)", clip.tags)
            true
        }
    }

    private fun toClip(f: File, meta: JSONObject): Clip {
        val tags = meta.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return Clip(
            file = f,
            title = meta.optString("title").ifBlank { f.nameWithoutExtension },
            tags = tags,
            durationMs = meta.optLong("durationMs"),
            sizeBytes = f.length(),
            createdAt = f.lastModified(),
            sampleRate = meta.optInt("sampleRate", 0),
        )
    }

    private fun saveIndex() {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(index.toString())
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(index.toString())
            tmp.delete()
        }
    }

    private fun probe(f: File): Pair<Long, Int> {
        if (ClipFormat.fromFile(f) == ClipFormat.WAV) {
            val out = LongArray(2)
            if (NativeBridge.nativeWavInfo(f.path, out) == 0 && out[0] > 0) return (out[1] * 1000 / out[0]) to out[0].toInt()
        }
        return runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.path)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                    ?.let { it to (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0) }
                    ?: (0L to 0)
            } finally {
                r.release()
            }
        }.getOrDefault(0L to 0)
    }
}
