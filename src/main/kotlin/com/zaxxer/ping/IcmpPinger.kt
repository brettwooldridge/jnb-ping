/*
 * Copyright (C) 2017 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.ping

import com.zaxxer.ping.impl.*
import com.zaxxer.ping.impl.util.WaitingTargetCollection
import com.zaxxer.ping.impl.util.dumpBuffer
import java.lang.System.nanoTime
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Created by Brett Wooldridge on 2017/10/03.
 *
 * Ref:
 *   https://stackoverflow.com/questions/8290046/icmp-sockets-linux
 */

const val DEFAULT_TIMEOUT_MS = 1000L
const val BUFFER_SIZE = 128L
const val PENDING_QUEUE_SIZE = 8192
const val POLLIN_OR_PRI = POLLIN or POLLPRI

// Negative file descriptors are ignored by libc.poll(), no need to change the count, or memory order of disabled FDs
private const val FDS = 3

typealias FD = Int

class PingTarget : Comparable<PingTarget> {
   // Assigned at construction
   val inetAddress: InetAddress
   val userObject: Any?
   private val timeoutMs: Long
   internal val id: Short
   internal val sockAddr: SockAddr

   // Assigned during operation
   internal var sequence: Short = 0
   internal var timestampNs: Long = 0L
   internal var timeoutNs = 0L
   @Volatile internal var complete = false

   @JvmOverloads constructor(inetAddress: InetAddress,
                             userObject: Any? = null,
                             timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
      this.inetAddress = inetAddress
      this.userObject = userObject
      this.timeoutMs = timeoutMs
      this.id = 0

      this.sockAddr = when (inetAddress) {
         is Inet4Address ->
            if (isBSD) BSDSockAddr4(inetAddress)
            else LinuxSockAddr4(inetAddress)
         is Inet6Address ->
            if (isBSD) BSDSockAddr6(inetAddress)
            else LinuxSockAddr6(inetAddress)
         else -> throw IllegalArgumentException("Unsupported address type: ${inetAddress.javaClass.name}")
      }
   }

   internal constructor(pingTarget: PingTarget) {
      this.inetAddress = pingTarget.inetAddress
      this.userObject = pingTarget.userObject
      this.timeoutMs = pingTarget.timeoutMs
      this.sockAddr = pingTarget.sockAddr
      this.id = (ID_SEQUENCE.getAndIncrement() and 0xffff).toShort()
   }

   internal fun isIPv4() = this.inetAddress is Inet4Address

   internal fun timestamp() {
      timestampNs = nanoTime()
      timeoutNs = timestampNs + MILLISECONDS.toNanos(timeoutMs)
      complete = false
   }

    override fun compareTo(other: PingTarget): Int = timeoutNs.compareTo(other.timeoutNs)

   override fun toString(): String = inetAddress.toString()
}

interface PingResponseHandler {
   fun onResponse(pingTarget: PingTarget, responseTimeSec: Double, byteCount: Int, seq: Int)

   fun onFailure(pingTarget: PingTarget, failureReason: FailureReason)
}

enum class FailureReason {
   /**
    * Ping timed out due to not receiving an ICMP ECHO response in time.
    */
   TimedOut,
   /**
    * Unable to create IPv4 or IPv6 socket. Could mean that the user the application is running with doesn't have enough
    * privileges, or a specific IP family might be disabled.
    * If this is Linux, you might need to set `sysctl net.ipv4.ping_group_range` or `sysctl net.ipv6.ping_group_range`.
    */
   UnableToCreateSocket,
   /**
    * Unable to send ICMP ping.
    */
   UnableToSendIcmpPing,
   /**
    * Triggered when [IcmpPinger.stopSelector] gets called or when `libc.poll` returns an error
    * and [IcmpPinger.runSelector] ends.
    */
   SelectorStopped,
   ;

   override fun toString(): String =
      when (this) {
         TimedOut -> "Ping timed out"
         UnableToCreateSocket -> "Unable to create socket"
         UnableToSendIcmpPing -> "Unable to send ICMP ping"
         SelectorStopped -> "Selector stopped"
      }
}

private val ID_SEQUENCE = AtomicInteger(0xCAFE)

private const val SEQUENCE_INIT = 0xBABE

private val LOGGER = Logger.getLogger(IcmpPinger::class.java.name)

class IcmpPinger(private val responseHandler:PingResponseHandler) {

   private val waitingTargets4 = WaitingTargetCollection()
   private val waitingTargets6 = WaitingTargetCollection()

   private val pending4Pings = LinkedBlockingQueue<PingTarget>(PENDING_QUEUE_SIZE)
   private val pending6Pings = LinkedBlockingQueue<PingTarget>(PENDING_QUEUE_SIZE)

   // GC-managed arena: native memory lives as long as this IcmpPinger instance
   private val arena:Arena = Arena.ofAuto()

   private val prebuiltPacket:MemorySegment = arena.allocate(BUFFER_SIZE, 8L)
   private val socketBuffer:MemorySegment = arena.allocate(BUFFER_SIZE, 8L)
   private val fdsSegment:MemorySegment = arena.allocate(SIZEOF_STRUCT_POLL_FD.toLong() * FDS, 8L)
   private val errnoState:MemorySegment = arena.allocate(LibC.ERRNO_STATE_LAYOUT)

   private val outpacket:MemorySegment = socketBuffer.asSlice(SIZEOF_STRUCT_IP.toLong())
   private val socketByteBuffer = socketBuffer.asByteBuffer()

   private val fdPipe:PollFd = PollFd(fdsSegment.asSlice(0L, SIZEOF_STRUCT_POLL_FD.toLong()))
   private val fd4:PollFd = PollFd(fdsSegment.asSlice(SIZEOF_STRUCT_POLL_FD.toLong(), SIZEOF_STRUCT_POLL_FD.toLong()))
   private val fd6:PollFd = PollFd(fdsSegment.asSlice(SIZEOF_STRUCT_POLL_FD.toLong() * 2, SIZEOF_STRUCT_POLL_FD.toLong()))

   private val awoken = AtomicBoolean(false)
   private val pipefd = IntArray(2) { -1 }
   private val wakeupWriteSegment:MemorySegment = arena.allocate(1L)
   private val wakeupReadSegment:MemorySegment = arena.allocate(1L)

   private var running = AtomicBoolean(false)
   private var sequenceCounter = SEQUENCE_INIT

   init {
      for (i in 0 until DEFAULT_DATALEN)
         prebuiltPacket.set(JAVA_BYTE, (SIZEOF_STRUCT_IP + ICMP_MINLEN + i).toLong(), i.toByte())

      arrayOf(fdPipe, fd4, fd6).forEach { fd ->
         fd.events = POLLIN_OR_PRI or POLLOUT or POLLERR
         fd.revents = 0
      }

      fd4.fd = -1
      fd6.fd = -1
   }

   fun ping(pingTarget: PingTarget) {
      val pendingPings = if (pingTarget.isIPv4()) pending4Pings else pending6Pings
      pendingPings.offer(PingTarget(pingTarget))
      // Declining must happen after insertion to avoid a race condition
      if (!running.get()) {
         declinePending(pendingPings, FailureReason.SelectorStopped)
      } else if (awoken.compareAndSet(false, true)) {
         wakeup()
      }
   }

   fun runSelector() {
      var pollTimeoutMs:Int = -1 // infinite

      try {
         check(running.compareAndSet(false, true)) {
            LOGGER.severe("Selector is already running")
            "Selector is already running"
         }

         // To avoid leaks of file descriptors, creation and destruction
         // of pipe channels is done during the runSelector call
         synchronized(pipefd) {
            LibC.pipe(pipefd)
            setNonBlocking(pipefd[0])
            setNonBlocking(pipefd[1])
            fdPipe.fd = pipefd[0]
         }

         while (running.get()) {
            val rc = LibC.poll(errnoState, fdsSegment, FDS, pollTimeoutMs)
            if (rc < 0) {
               val errno = LibC.errno(errnoState)
               if (errno != EINTR) {
                  LOGGER.severe("poll() returned errno $errno")
                  break
               }
            }

            val wokeUp = fdPipe.revents and POLLIN_OR_PRI != 0
            if (wokeUp) {
               wakeupReceived()
               awoken.compareAndSet(true, false)
            }

            if (rc > 0) {
               val revents4 = fd4.revents // memoize for performance
               val revents6 = fd6.revents // memoize for performance

               if (revents4 and POLLERR != 0 || revents6 and POLLERR != 0) {
                  LOGGER.severe("poll() created a POLLERR event")
                  break
               }

               if (fd4.fd > 0 && revents4 and POLLIN_OR_PRI != 0) processReceives(fd4.fd, true)
               if (fd6.fd > 0 && revents6 and POLLIN_OR_PRI != 0) processReceives(fd6.fd, false)

               if (wokeUp || revents4 and POLLOUT != 0) processSends(pending4Pings, waitingTargets4, fd4, isIPv4 = true)
               if (wokeUp || revents6 and POLLOUT != 0) processSends(pending6Pings, waitingTargets6, fd6, isIPv4 = false)

               fdPipe.revents = 0
               fd4.revents = 0
               fd6.revents = 0
            }

            val next4TimeoutMs = processTimeouts(waitingTargets4)
            val next6TimeoutMs = processTimeouts(waitingTargets6)
            pollTimeoutMs = minOf(next4TimeoutMs, next6TimeoutMs)

            LOGGER.fine(::logPendingPingsAndActors)

            val isPending4reads = waitingTargets4.isNotEmpty()
            val isPending6reads = waitingTargets6.isNotEmpty()
            val isPending4writes = pending4Pings.isNotEmpty()
            val isPending6writes = pending6Pings.isNotEmpty()

            var fd4events = 0
            var fd6events = 0
            if (isPending4reads) fd4events = fd4events or POLLIN_OR_PRI
            if (isPending6reads) fd6events = fd6events or POLLIN_OR_PRI
            if (isPending4writes) fd4events = fd4events or POLLOUT
            if (isPending6writes) fd6events = fd6events or POLLOUT
            fd4.events = fd4events
            fd6.events = fd6events
         }
      } finally {
         if (fd4.fd > 0) LibC.close(fd4.fd)
         if (fd6.fd > 0) LibC.close(fd6.fd)
         fd4.fd = -1
         fd6.fd = -1

         synchronized(pipefd) {
            running.set(false)
            LibC.close(pipefd[0])
            LibC.close(pipefd[1])
            pipefd[0] = -1
            pipefd[1] = -1
         }

         declinePending(pending4Pings, FailureReason.SelectorStopped)
         declinePending(pending6Pings, FailureReason.SelectorStopped)

         declineInflight(waitingTargets4)
         declineInflight(waitingTargets6)
      }
   }

   fun stopSelector() {
      if (running.compareAndSet(true, false)) {
         wakeup()
      }
   }

   fun isPendingWork() = pending4Pings.isNotEmpty() || pending6Pings.isNotEmpty() || waitingTargets4.isNotEmpty() || waitingTargets6.isNotEmpty()

   /**********************************************************************************************
    *                                     Private Methods
    */

   private fun processSends(pendingPings: LinkedBlockingQueue<PingTarget>, waitingTargets: WaitingTargetCollection, fd: PollFd, isIPv4: Boolean) {
      if (pendingPings.isEmpty()) return

      if (fd.fd < 0) {
         fd.fd = createSocket(isIPv4)
         if (fd.fd < 0) {
            if (isIPv4) {
               LOGGER.warning("Unable to create IPv4 socket. If this is Linux, you might need to set sysctl net.ipv4.ping_group_range")
            } else {
               LOGGER.warning("Unable to create IPv6 socket. If this is Linux, you might need to set sysctl net.ipv6.ping_group_range")
            }

            declinePending(pendingPings, FailureReason.UnableToCreateSocket)
            return
         }
      }

      while (true) {
         val pingTarget = pendingPings.poll() ?: return
         if (sendIcmp(pingTarget, fd.fd)) {
            waitingTargets.add(pingTarget)
         } else try {
            responseHandler.onFailure(pingTarget, FailureReason.UnableToSendIcmpPing)
         } catch (_:Exception) {}
      }
   }

   private fun processReceives(fd: FD, isIPv4: Boolean) {
      do {
         val more = recvIcmp(fd, isIPv4) // read and look up actor id in map
      } while (more)
   }

   private fun processTimeouts(targets: WaitingTargetCollection) : Int {
      val nowNs = nanoTime()
      while (true) {
         val timeoutNs = targets.peekTimeoutQueue() ?: return Int.MAX_VALUE
         val remainingMs = NANOSECONDS.toMillis(timeoutNs - nowNs).toInt() // NOTE: would roll over if timeout is longer than 35 minutes
         if (remainingMs > 0) {
            return minOf(remainingMs, Int.MAX_VALUE)
         }

         try {
           responseHandler.onFailure(targets.take(), FailureReason.TimedOut)
         } catch (_:Exception) {}
     }
   }

   private fun declinePending(pendingPings: LinkedBlockingQueue<PingTarget>, failureReason: FailureReason) {
      while (true) {
         // Taking atomically to avoid race conditions
         val pendingPing = pendingPings.poll() ?: break
         try {
            responseHandler.onFailure(pendingPing, failureReason)
         } catch (_:Exception) {}
      }
   }

   private fun declineInflight(waitingTargets: WaitingTargetCollection) {
      waitingTargets.drain().forEach { target ->
         try {
            responseHandler.onFailure(target, FailureReason.SelectorStopped)
         } catch (_:Exception) {}
      }
   }

   private fun sendIcmp(pingTarget: PingTarget, fd: FD) : Boolean {
      MemorySegment.copy(prebuiltPacket, 0L, socketBuffer, 0L, (SIZEOF_STRUCT_IP + SEND_PACKET_SIZE).toLong())

      pingTarget.sequence = (sequenceCounter++ and 0xffff).toShort()

      // Note: the ICMP id and sequence are stored in native (little-endian) byte order rather
      // than network order; only this library reads them back, and the echo reply returns the
      // bytes verbatim, so they round-trip correctly.
      if (pingTarget.isIPv4()) {
         outpacket.set(JAVA_SHORT, ICMP_SEQ_OFFSET, pingTarget.sequence)
         outpacket.set(JAVA_SHORT, ICMP_ID_OFFSET, pingTarget.id)
         outpacket.set(JAVA_BYTE, ICMP_TYPE_OFFSET, ICMP_ECHO.toByte())
         outpacket.set(JAVA_BYTE, ICMP_CODE_OFFSET, 0)
         outpacket.set(JAVA_SHORT, ICMP_CKSUM_OFFSET, 0)
         // In BSD, we are responsible for the entire payload, including checksum.  Linux mucks with the payload (replacing
         // the identity field, and therefore recalculates the checksum, so don't waste our time doing it here).
         if (isBSD) {
            val cksum = icmpCksum(outpacket, SEND_PACKET_SIZE)
            outpacket.set(JAVA_SHORT, ICMP_CKSUM_OFFSET, cksum.toShort())
         }
      } else {
         outpacket.set(JAVA_SHORT, ICMP6_SEQ_OFFSET, pingTarget.sequence)
         outpacket.set(JAVA_BYTE, ICMP6_TYPE_OFFSET, ICMPV6_ECHO_REQUEST.toByte())
         outpacket.set(JAVA_BYTE, ICMP6_CODE_OFFSET, 0)
         outpacket.set(JAVA_SHORT, ICMP6_CKSUM_OFFSET, 0)
         if (isBSD) {
            val cksum = icmpCksum(outpacket, SEND_PACKET_SIZE)
            outpacket.set(JAVA_SHORT, ICMP6_CKSUM_OFFSET, cksum.toShort())
         }
      }

      if (LOGGER.level == Level.FINEST) LOGGER.finest { dumpBuffer(message = "Send buffer:", buffer = socketByteBuffer) }

      pingTarget.timestamp()

      val rc = LibC.sendto(fd, outpacket, SEND_PACKET_SIZE, 0, pingTarget.sockAddr)
      return if (rc == SEND_PACKET_SIZE) {
          if (LOGGER.level == Level.FINE) LOGGER.fine {"   ICMP packet(seq=${pingTarget.sequence}) send to ${pingTarget.inetAddress} successful"}
          true
      }
      else {
         if (LOGGER.level == Level.FINE) LOGGER.fine {"   icmp sendto() to ${pingTarget.inetAddress} for seq=${pingTarget.sequence} returned $rc"}
         false
      }
   }

   private fun recvIcmp(fd: FD, isIPv4: Boolean) : Boolean {

      val cc = LibC.recvfrom(errnoState, fd, socketBuffer, BUFFER_SIZE.toInt(), 0)
      if (cc < 0) {
         val errno = LibC.errno(errnoState)
         if (errno == EAGAIN) return false
         if (errno == EINTR) return true
         LOGGER.fine {"   Error code $errno returned from recvfrom()"}
         return false
      }

      if (LOGGER.level == Level.FINEST) LOGGER.finest(dumpBuffer("Ping response", socketByteBuffer))

      val seq: Short
      val waitingTargets: WaitingTargetCollection

      if (isIPv4) {
         // BSD SOCK_DGRAM ICMP sockets deliver the IP header before the ICMP message; Linux does not
         val icmpSegment = if (isBSD) {
            val headerLen = (socketBuffer.get(JAVA_BYTE, 0L).toInt() and 0x0f) shl 2
            socketBuffer.asSlice(headerLen.toLong())
         } else {
            socketBuffer
         }
         val icmpType = icmpSegment.get(JAVA_BYTE, ICMP_TYPE_OFFSET).toInt() and 0xff
         if (icmpType != ICMP_ECHOREPLY.toInt()) {
            LOGGER.fine("   ^ Opps, not our response.")
            return true
         }
         seq = icmpSegment.get(JAVA_SHORT, ICMP_SEQ_OFFSET)
         waitingTargets = waitingTargets4
      } else {
         val icmpType = socketBuffer.get(JAVA_BYTE, ICMP6_TYPE_OFFSET).toInt() and 0xff
         if (icmpType != ICMPV6_ECHO_REPLY.toInt()) {
            LOGGER.fine("   ^ Opps, not our response.")
            return true
         }
         seq = socketBuffer.get(JAVA_SHORT, ICMP6_SEQ_OFFSET)
         waitingTargets = waitingTargets6
      }

      waitingTargets.remove(seq)
         ?.let { pingTarget ->
            val now = nanoTime()
            val tripTimeSec = (now - pingTarget.timestampNs) / 1_000_000_000.0
            try {
               responseHandler.onResponse(pingTarget, tripTimeSec, cc, seq.toInt())
            } catch(_: Exception) {}
         }

      return true
   }

   private fun wakeup() {
      synchronized(pipefd) {
         if (pipefd[1] > 0) {
            LibC.write(pipefd[1], wakeupWriteSegment, 1L)
         }
      }
   }

   private fun wakeupReceived() {
      while (LibC.read(pipefd[0], wakeupReadSegment, 1L) > 0) {
         // drain the wakeup pipe
      }
   }

    private fun createSocket(isIPv4: Boolean): FD {
       val fd = when (isIPv4) {
          true -> LibC.socket(PF_INET, SOCK_DGRAM, IPPROTO_ICMP)
          false -> LibC.socket(PF_INET6, SOCK_DGRAM, IPPROTO_ICMPV6)
       }

       if (fd > 0) {
          LibC.setsockopt(fd, SOL_SOCKET, SO_TIMESTAMP, 1)

          // Better handled by altering the OS default rcvbuf size
          // LibC.setsockopt(fd, SOL_SOCKET, SO_RCVBUF, 2048)

          setNonBlocking(fd)
       }

       return fd
    }

   private fun logPendingPingsAndActors(): String =
      "   Pending ping count ${pending4Pings.size + pending6Pings.size}\n" +
      "   Pending actor count ${waitingTargets4.size + waitingTargets6.size}"
}

private fun setNonBlocking(fd: FD) {
   val flags = LibC.fcntl(fd, F_GETFL, 0) or O_NONBLOCK
   LibC.fcntl(fd, F_SETFL, flags)
}
