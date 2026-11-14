// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV 21ec92206fbfca1d88127c1a4bd1e86a1bab79bf,
// kotlin-tv/core/.../PlaybackModels.kt. Kept independent of UI/codec capability discovery.
package com.hgtv.star.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Parse both current array models and legacy fallback models without fetching media. */
internal object PlaybackModels {
    fun objectValue(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrElse {
            runCatching { JSONObject(String(decode64(value), Charsets.UTF_8)) }.getOrNull()
        }
        else -> null
    }

    fun model(data: JSONObject, id: String): JSONObject? {
        val entry = data.optJSONObject(id) ?: data
        return objectValue(entry.opt("video_model")) ?: entry.takeIf { it.has("video_list") }
    }

    private fun url(raw: String): String? = cleanUrl(raw).trim().toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty()
    }?.toString()

    fun fallback(model: JSONObject): String? {
        val raw = model.opt("fallback_api")
        val value = when (raw) {
            is JSONArray -> raw.optString(0)
            else -> objectValue(raw)?.str("fallback_api") ?: (raw as? String).orEmpty()
        }
        return url(value)?.takeIf { it.startsWith("https://") }
    }

    fun candidates(model: JSONObject): List<StreamInfo> {
        val list = model.opt("video_list")
        val entries = when (list) {
            is JSONObject -> list.keys().asSequence().mapNotNull { name -> list.optJSONObject(name)?.let { name to it } }.toList()
            is JSONArray -> list.objects().map { "" to it }
            else -> emptyList()
        }
        return entries.mapNotNull { (name, entry) ->
            runCatching {
                val meta = entry.optJSONObject("video_meta")
                val codec = listOf(entry.str("codec_type"), entry.str("gear_des_key"), meta?.str("codec_type").orEmpty()).joinToString(" ").lowercase()
                if (codec.contains("bytevc2") || Regex("(^|[^a-z0-9])bvc2([^a-z0-9]|$)").containsMatchIn(codec)) return@runCatching null
                val encryption = entry.optJSONObject("encrypt_info")
                val key = encryption?.str("spade_a").orEmpty().ifEmpty { entry.str("spade_a") }
                    .takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
                if (encryption != null && key == null && encryption.opt("encrypt") !in listOf(false, 0, "0", "false")) return@runCatching null
                val seed = model.str("key_seed").takeIf { it.isNotEmpty() }?.let(::decode64)
                val resolved = listOf("main_url", "play_url", "backup_url", "backup_url_1").firstNotNullOfOrNull { field ->
                    runCatching {
                        val raw = cleanUrl(entry.str(field))
                        if (raw.isEmpty()) null else url(raw) ?: url(if (seed != null) MediaCrypto.decryptUrl(raw, seed) else String(decode64(raw), Charsets.UTF_8))
                    }.getOrNull()
                } ?: return@runCatching null
                val width = entry.optInt("vwidth", meta?.optInt("vwidth", 0) ?: 0)
                val height = entry.optInt("vheight", meta?.optInt("vheight", 0) ?: 0)
                val dimensions = if (width > 0 && height > 0) minOf(width, height).toString() else ""
                val definition = entry.str("quality_desc").ifEmpty { meta?.str("definition").orEmpty() }
                    .ifEmpty { dimensions.ifEmpty { entry.str("height").ifEmpty { entry.str("vheight").ifEmpty { entry.str("quality").ifEmpty { name } } } } }
                StreamInfo(resolved, key, ContentRepository.qualityNumber(definition).toString())
            }.getOrNull()
        }
    }

    fun pick(rows: List<StreamInfo>, maxQuality: Int): StreamInfo {
        val known = rows.filter { it.quality.toInt() > 0 }.sortedByDescending { it.quality.toInt() }
        val selected = known.firstOrNull { it.quality.toInt() <= maxQuality } ?: known.lastOrNull() ?: rows.firstOrNull()
            ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val quality = selected.quality.toInt()
        return selected.copy(quality = if (quality > 0) "${quality}P" + (if (quality > maxQuality) "（兼容资源）" else "") else "自动")
    }
}
