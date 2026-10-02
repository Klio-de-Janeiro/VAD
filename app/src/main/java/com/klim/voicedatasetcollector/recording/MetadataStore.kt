package com.klim.voicedatasetcollector.recording

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sidecar JSON is authoritative; JSONL is an atomically rebuilt daily index. */
object MetadataStore {
    val lock = Any()

    fun append(segment: SegmentRecorder.Completed, audioSource: String, processing: Boolean, noiseFrames: Int) {
        val q = segment.quality
        val json = base(segment.file, segment.startedAtMs, segment.samples)
            .put("conversation_id", segment.conversationId).put("part_index", segment.partIndex)
            .put("offset_samples", segment.offsetSamples).put("end_reason", segment.endReason)
            .put("duration_limit_ms", AudioConfig.MAX_SEGMENT_MINUTES * 60_000)
            .put("audio_source", audioSource)
            .put("quality", JSONObject().put("rms_dbfs", q.rmsDbfs).put("peak_dbfs", q.peakDbfs)
                .put("dc_offset", q.dcOffset).put("clipped_fraction", q.clippedFraction)
                .put("warnings", JSONArray(q.warnings)))
            .put("processing", JSONObject().put("status", if (processing) "pending" else "disabled")
                .put("noise_profile_frames", noiseFrames))
        save(segment.file, json)
    }

    fun recovered(file: File) {
        save(file, base(file, file.lastModified(), (file.length() - 44) / 2)
            .put("start_is_approximate", true).put("end_reason", "recovered_after_interruption")
            .put("processing", JSONObject().put("status", "not_processed")))
    }

    fun updateProcessing(file: File, processing: JSONObject) = synchronized(lock) {
        val sidecar = File(file.parentFile, "${file.nameWithoutExtension}.json")
        if (sidecar.exists()) {
            val json = JSONObject(AtomicFile(sidecar).openRead().bufferedReader().use { it.readText() })
            val previous = json.optJSONObject("processing")
            if (previous?.has("noise_profile_frames") == true) processing.put("noise_profile_frames", previous.getInt("noise_profile_frames"))
            save(file, json.put("processing", processing))
        }
    }

    /** Caller holds the same lock while deleting the original and its derivative. */
    fun remove(directory: File, names: Set<String>) = synchronized(lock) {
        val index = File(directory, "metadata.jsonl")
        if (index.exists()) {
            val lines = index.readLines().filter { line ->
                runCatching { JSONObject(line).optString("file") !in names }.getOrDefault(true)
            }
            atomicWrite(index, lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
        }
        names.forEach { name -> AtomicFile(File(directory, "${name.removeSuffix(".wav")}.json")).delete() }
    }

    private fun base(file: File, startedAtMs: Long, samples: Long) = JSONObject()
        .put("schema_version", 2).put("file", file.name)
        .put("start", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(startedAtMs)))
        .put("duration_ms", samples * 1000 / AudioConfig.SAMPLE_RATE).put("samples", samples)
        .put("sample_rate", AudioConfig.SAMPLE_RATE).put("channels", 1).put("format", "PCM16_WAV")

    private fun save(file: File, json: JSONObject) = synchronized(lock) {
        val sidecar = File(file.parentFile, "${file.nameWithoutExtension}.json")
        atomicWrite(sidecar, json.toString(2) + "\n")
        val index = File(file.parentFile, "metadata.jsonl")
        val kept = if (index.exists()) index.readLines().filter { line ->
            runCatching { JSONObject(line).optString("file") != file.name }.getOrDefault(true)
        } else emptyList()
        atomicWrite(index, (kept + json.toString()).joinToString("\n", postfix = "\n"))
    }

    private fun atomicWrite(file: File, value: String) {
        val atomic = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(value.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}
