package com.precord.app.service

/**
 * Small thread-safe FIFO between the playback-capture thread (producer) and the microphone thread (consumer).
 * The two AudioRecords run on independent clocks, so the FIFO absorbs jitter: a short fill is zero-padded
 * (the Rust delay tracker re-aligns), and an over-full FIFO drops the oldest samples instead of growing.
 */
class ReferenceRing(private val capacity: Int, private val targetKeep: Int) {
    private val buf = ShortArray(capacity)
    private var head = 0 // next read
    private var size = 0

    @Synchronized
    fun push(src: ShortArray, n: Int) {
        var from = 0
        var count = n
        if (count > capacity) { from = count - capacity; count = capacity }
        val overflow = size + count - capacity
        if (overflow > 0) { head = (head + overflow) % capacity; size -= overflow }
        var w = (head + size) % capacity
        for (i in 0 until count) {
            buf[w] = src[from + i]
            w = (w + 1) % capacity
        }
        size += count
    }

    /** Fills [dst] with [n] samples; missing samples are zeros. Returns how many were real. */
    @Synchronized
    fun pull(dst: ShortArray, n: Int): Int {
        // far ahead of the microphone -> clock drift or a stall: keep only the freshest `targetKeep`
        if (size > n + targetKeep * 3) {
            val drop = size - n - targetKeep
            head = (head + drop) % capacity
            size -= drop
        }
        val real = minOf(n, size)
        for (i in 0 until real) {
            dst[i] = buf[head]
            head = (head + 1) % capacity
        }
        size -= real
        for (i in real until n) dst[i] = 0
        return real
    }

    @Synchronized
    fun clear() { head = 0; size = 0 }
}
