package com.streamcast.app

/**
 * 发送端自适应码率控制（应用层拥塞控制）。
 *
 * 只依赖本地可观测信号：WebSocket 发送队列的积压字节数。
 * 队列持续积压说明链路吞吐跟不上，此时主动降码率换取流畅；链路长时间空闲再逐步回升。
 *
 * 采用 AIMD（乘性降 / 加性升）思路，并加入冷却时间，避免画质来回抖动。
 * 这不依赖任何服务端改动，也不需要额外的网络探测包。
 *
 * 纯逻辑实现（不引用 Android 类），可直接单元测试。
 */
class AdaptiveBitrate(
    private val minBitrate: Int,
    private val maxBitrate: Int,
    private val highWaterBytes: Long = 384 * 1024,
    private val lowWaterBytes: Long = 64 * 1024,
    private val downSamples: Int = 2,
    private val upSamples: Int = 6,
    private val cooldownMs: Long = 1500L
) {

    private var current: Int = maxBitrate
    private var congested: Int = 0
    private var clear: Int = 0
    private var lastChangeAt: Long = -cooldownMs

    /** 是否发生过降档（用于界面提示） */
    var degraded: Boolean = false
        private set

    fun currentBitrate(): Int = current

    fun maxBitrate(): Int = maxBitrate

    /**
     * 采样一次发送队列积压。
     * @param queueBytes 当前待发送字节数
     * @param nowMs 单调时钟毫秒
     * @return 码率需要调整时返回新码率，否则返回 null
     */
    fun onSample(queueBytes: Long, nowMs: Long): Int? {
        if (queueBytes > highWaterBytes) {
            congested++
            clear = 0
        } else if (queueBytes < lowWaterBytes) {
            congested = 0
            clear++
        } else {
            congested = 0
            clear = 0
        }

        if (nowMs - lastChangeAt < cooldownMs) return null

        if (congested >= downSamples) {
            val next = maxOf(minBitrate, (current * 0.7).toInt())
            congested = 0
            if (next < current) {
                current = next
                degraded = true
                lastChangeAt = nowMs
                return current
            }
            return null
        }

        if (clear >= upSamples && current < maxBitrate) {
            val next = minOf(maxBitrate, (current * 1.15).toInt())
            clear = 0
            if (next > current) {
                current = next
                if (current >= maxBitrate) degraded = false
                lastChangeAt = nowMs
                return current
            }
        }
        return null
    }
}
