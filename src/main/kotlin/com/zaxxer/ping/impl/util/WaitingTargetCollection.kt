package com.zaxxer.ping.impl.util

import com.zaxxer.ping.PingTarget
import com.carrotsearch.hppc.ShortObjectHashMap
import java.util.PriorityQueue

private const val INITIAL_CAPACITY = 256

/**
 * This class is not thread safe.
 */
class WaitingTargetCollection {
   private val waitingTargetMap = ShortObjectHashMap<PingTarget>()
   private val targetTimeoutQueue = PriorityQueue<PingTarget>(INITIAL_CAPACITY)
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
         val pingTarget = targetTimeoutQueue.peek() ?: return null
         if (pingTarget.complete) {
            targetTimeoutQueue.poll()
            continue
         }
         return pingTarget.timeoutNs
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

   fun drain(): List<PingTarget> {
      val targets = targetTimeoutQueue.filterNot { it.complete }
      targetTimeoutQueue.clear()
      waitingTargetMap.clear()
      return targets
   }

   fun isNotEmpty() = !waitingTargetMap.isEmpty
}
