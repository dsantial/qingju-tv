// SPDX-License-Identifier: GPL-3.0-only
package local.qingju.tv

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** System MediaCodec preferences only. No bundled software decoder is implied by these modes. */
@OptIn(UnstableApi::class)
object DecoderPolicy {
    enum class Mode(val value: String, val label: String) {
        AUTO("auto", "自动（推荐）"),
        HARDWARE("hardware", "优先硬解"),
        SOFTWARE("software", "优先系统软解");

        companion object {
            fun fromStored(value: String?): Mode = entries.firstOrNull { it.value == value } ?: AUTO
        }
    }

    data class Decoder(
        val name: String,
        val hardware: Boolean,
        val software: Boolean,
        val supportsFormat: Boolean,
    )

    data class Availability(
        val mimeType: String?,
        val decoders: List<Decoder>,
        val problem: String? = null,
    ) {
        val hardwareAvailable: Boolean get() = decoders.any { it.hardware && it.supportsFormat }
        val softwareAvailable: Boolean get() = decoders.any { it.software && it.supportsFormat }
    }

    /** Media3 computes these flags on API21–28 too; never call API29 platform flag methods. */
    fun inspect(format: Format?): Availability {
        val mime = format?.sampleMimeType
        if (mime == null || !mime.startsWith("video/")) {
            return Availability(mime, emptyList(), "视频格式尚未识别，请开始播放后再选择解码方式。")
        }
        return try {
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false).map { info ->
                Decoder(
                    info.name,
                    info.hardwareAccelerated,
                    info.softwareOnly,
                    runCatching { info.isFormatSupported(format) }.getOrDefault(false),
                )
            }
            Availability(mime, decoders)
        } catch (_: Exception) {
            Availability(mime, emptyList(), "暂时无法查询系统解码器，请使用自动模式。")
        }
    }

    /** Validate the current stream before offering a manual change; keep the old mode on failure. */
    fun selectionProblem(mode: Mode, format: Format?): String? {
        if (mode == Mode.AUTO) return null
        val available = inspect(format)
        available.problem?.let { return it }
        return when {
            mode == Mode.HARDWARE && !available.hardwareAvailable ->
                "系统未提供支持当前视频的硬件解码器，请使用自动模式。"
            mode == Mode.SOFTWARE && !available.softwareAvailable ->
                "系统未提供支持当前视频的软解码器，请使用自动或优先硬解。"
            else -> null
        }
    }

    /**
     * A preference, not a hard filter: retain all platform fallbacks for initialization failures
     * and for later episodes whose format differs. Audio keeps the default platform ordering.
     */
    fun selector(mode: Mode): MediaCodecSelector {
        if (mode == Mode.AUTO) return MediaCodecSelector.DEFAULT
        return MediaCodecSelector { mimeType, secure, tunneling ->
            val original = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            if (!mimeType.startsWith("video/")) original
            else original.sortedBy { info ->
                val preferred = if (mode == Mode.HARDWARE) info.hardwareAccelerated else info.softwareOnly
                if (preferred) 0 else 1
            }
        }
    }
}
