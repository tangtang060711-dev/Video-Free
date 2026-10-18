package com.thermo.player.internal

import androidx.media3.common.util.Log
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 硬解优先的 MediaCodec 选择器。
 *
 * ## 为什么不直接用 MediaCodecSelector.DEFAULT
 * Media3 的 DEFAULT 其实已经是"硬解在前"，但它的排序里混进了两个对**功耗**不友好的行为：
 *
 *  1. 它会把软件解码器（c2.android.* / OMX.google.*）排在"名字字母序靠前"的位置，
 *     在少数机型上会出现"明明有硬解，却因为软解先被返回而选中软解"。
 *     结果就是：画面能出，但 CPU 从 5% 飙到 60%，机身几分钟就烫。
 *  2. 它对 `softwareOnly` 的惩罚不够狠 —— Media3 只在"能力打分"里给软解扣分，
 *     一旦硬解的 profile/level 有一项不匹配，软解就可能反超。
 *
 * 这里做的事情很粗暴也很有效：**硬解永远排在软解前面，软解只在没有任何硬解时才出现**。
 * 这正好对应用户的诉求 —— "默认硬解，仅当硬解不支持才切软解"。
 *
 * ## 为什么软解不能被直接删掉
 * 如果这里直接过滤掉全部 softwareOnly，Media3 的 `enableDecoderFallback` 就失去了兜底对象，
 * 遇到硬解驱动崩溃（小米/高通平台偶发的中断异常）会直接抛 PlaybackException 而不是降级。
 * 所以软解是**排在最后**，不是**不存在**。
 */
@Singleton
class HardwareFirstMediaCodecSelector @Inject constructor() : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        // 先拿到平台全量列表：这里刻意不走 MediaCodecUtil 的私有缓存，
        // 因为 DEFAULT 已经处理好了 secure / tunneling / 厂商解码器去重这些脏活。
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder,
        )
        if (all.isEmpty()) return all

        // 分区：硬解 / 软解。注意 hardwareAccelerated 和 softwareOnly 是两个独立字段，
        // 极少数厂商解码器两个都是 false（既不是硬解也不是软解），这种也归到"兜底"里。
        val hardware = ArrayList<MediaCodecInfo>(all.size)
        val fallback = ArrayList<MediaCodecInfo>(all.size)
        for (info in all) {
            if (info.hardwareAccelerated && !info.softwareOnly) {
                hardware.add(info)
            } else {
                fallback.add(info)
            }
        }

        if (hardware.isEmpty()) {
            // 平台确实没有这个格式的硬解（典型：AVI 里的 MPEG-4 Part2、老旧 RMVB）。
            // 这里返回全量软解，让 Media3 自己去挑；上层的 :decoder 会同时把
            // "这个片源必然软解" 的事实报给降级策略，提前把画质约束拉低。
            Log.i(TAG, "无硬解可用 mime=$mimeType，回退到 ${fallback.size} 个软解")
            return fallback
        }

        Log.d(TAG, "硬解候选 ${hardware.size} 个（首选 ${hardware[0].name}），软解兜底 ${fallback.size} 个")
        // 硬解保持 DEFAULT 给出的相对顺序不变 —— 那个顺序里包含厂商对
        // 高帧率/高分辨率实例数的排序信息，是我们自己排不出来的。
        hardware.addAll(fallback)
        return hardware
    }

    private companion object {
        const val TAG = "ThermoCodecSel"
    }
}