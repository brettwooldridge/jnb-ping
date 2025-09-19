package com.zaxxer.ping.impl.util

import com.zaxxer.ping.PingTarget
import com.carrotsearch.hppc.ShortObjectHashMap
import java.util.TreeSet

/**
 * This class is not thread safe.
 */
class WaitingTargetCollection {
    private val waitingTargetMap = ShortObjectHashMap<PingTarget>()
    /**
     * Values are ordered by timeout and sequence.
     */
    private val targetTimeoutQueue = TreeSet<PingTarget>()
    val size
        get() = waitingTargetMap.size()

    fun add(target: PingTarget) {
        waitingTargetMap.put(target.sequence, target)
        targetTimeoutQueue.add(target)
    }

    /**
     * This method is only called by the timeout handling code.  It returns the timestamp of the
     * first item in the targetTimeoutQueue in nanoseconds.
     */
    fun peekTimeoutQueue(): Long? {
        do {
            val pingTarget = targetTimeoutQueue.firstOrNull() ?: return null
            if (pingTarget.complete) {
                targetTimeoutQueue.removeFirst()
                waitingTargetMap.remove(pingTarget.sequence)
                continue
            }
            return pingTarget.timeoutNs
        } while (true)
    }

    /**
     * This method is only called by the timeout handling code.
     */
    fun take(): PingTarget {
        val pingTarget = targetTimeoutQueue.removeFirst()
        pingTarget.complete = true
        return waitingTargetMap.remove(pingTarget.sequence)
    }

    /**
     * This method is only called upon successful receipt of a response.
     */
    fun remove(sequence: Short): PingTarget? {
        val pingTarget = waitingTargetMap.remove(sequence) ?: return null
        pingTarget.complete = true
        targetTimeoutQueue.remove(pingTarget)
        return pingTarget
    }

    fun isNotEmpty() = !waitingTargetMap.isEmpty
}
