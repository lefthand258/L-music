package dev.lyra.music.util

import java.util.Locale

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

fun formatSampleRate(hz: Int?): String? = hz?.let {
    val k = it / 1000.0
    if (k == k.toLong().toDouble()) String.format(Locale.US, "%.0f", k) else String.format(Locale.US, "%.1f", k)
}

/** Short technical summary e.g. "FLAC · 24-bit · 96 kHz" — only includes what is known. */
fun audioSummary(codec: String?, bitDepth: Int?, sampleRateHz: Int?, bitrateKbps: Int?, channels: Int?): String {
    val parts = ArrayList<String>(5)
    codec?.takeIf { it.isNotBlank() }?.let { parts += it.uppercase(Locale.US).replace("AUDIO/", "") }
    bitDepth?.let { parts += "$it-bit" }
    formatSampleRate(sampleRateHz)?.let { parts += "$it kHz" }
    if (bitDepth == null && bitrateKbps != null) parts += "$bitrateKbps kbps"
    channels?.let { parts += when (it) { 1 -> "Mono"; 2 -> "Stereo"; else -> "${it}ch" } }
    return parts.joinToString(" · ")
}
