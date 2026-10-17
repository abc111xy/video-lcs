package com.streamcast.app

/**
 * 拥塞跳帧闸门。
 *
 * 直播场景下，发送队列一旦积压就不能靠"慢慢等"消化——消费和产生都是实时速率，
 * 积压只会永久留存，表现为「越播延迟越高」。正确做法是主动丢帧跳到直播边缘。
 *
 * 用高低水位做滞回，避免在阈值附近反复抖动：
 *  - 积压超过高水位 → 进入丢帧状态；
 *  - 积压降到低水位以下 → 退出丢帧，并标记需要一次「重同步」（请求关键帧 + 通知接收端）。
 *
 * 纯逻辑，可直接单元测试。
 */
class CongestionGate(
    private val highBytes: Long = 512 * 1024,
    private val lowBytes: Long = 128 * 1024
) {

    private var dropping = false

    @Volatile
    private var resyncPending = false

    fun isDropping(): Boolean = dropping

    /** @return 当前帧是否应当丢弃 */
    fun shouldDrop(pendingBytes: Long): Boolean {
        if (!dropping && pendingBytes > highBytes) {
            dropping = true
        } else if (dropping && pendingBytes <= lowBytes) {
            dropping = false
            resyncPending = true
        }
        return dropping
    }

    /** 取出「需要重同步」标记（取一次即清除） */
    fun consumeResync(): Boolean {
        if (!resyncPending) return false
        resyncPending = false
        return true
    }

    fun reset() {
        dropping = false
        resyncPending = false
    }
}
