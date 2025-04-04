package com.zaxxer.ping.impl.util

import com.zaxxer.ping.PingTarget
import com.carrotsearch.hppc.ShortObjectHashMap
import java.util.concurrent.PriorityBlockingQueue

private const val INITIAL_CAPACITY = 16

class WaitingTargetCollection {
    private val waitingTargetMap = ShortObjectHashMap<PingTarget>()
    private val targetTimeoutQueue = PriorityBlockingQueue<PingTarget>(INITIAL_CAPACITY)
    val size
        get() = waitingTargetMap.size()

    fun add(target: PingTarget) {
        waitingTargetMap.put(target.sequence, target)
        targetTimeoutQueue.put(target)
    }

    /**
     * This method is only called by the timeout handling code.  It returns the timestamp of the
     * first item in the targetTimeoutQueue.
     *
     * We know this will never be called when the targetTimeoutQueue is empty.
     */
    fun peekTimeoutQueue(): Long? {
        do {
            val pingTarget = targetTimeoutQueue.peek() ?: return null
            if (pingTarget.complete) {
                targetTimeoutQueue.take()
                continue
            }
            else
                return pingTarget.timeout
        } while (true)
    }

    /**
     * This method is only called by the timeout handling code.
     */
    fun take(): PingTarget {
        val pingTarget = targetTimeoutQueue.poll()
        pingTarget.complete = true
        return waitingTargetMap.remove(pingTarget.sequence)
    }

    /**
     * This method is only called upon successful receipt of a response.
     */
    fun remove(sequence: Short): PingTarget? {
        val pingTarget = waitingTargetMap.remove(sequence) ?: return null
        pingTarget.complete = true
        return pingTarget
    }

    fun size() = waitingTargetMap.size()

    fun isNotEmpty() = !waitingTargetMap.isEmpty

    fun isEmpty() = waitingTargetMap.isEmpty
}
