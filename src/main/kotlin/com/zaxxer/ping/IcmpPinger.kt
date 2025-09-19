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
import com.zaxxer.ping.impl.NativeStatic.Companion.isBSD
import com.zaxxer.ping.impl.NativeStatic.Companion.libc
import com.zaxxer.ping.impl.NativeStatic.Companion.posix
import com.zaxxer.ping.impl.NativeStatic.Companion.runtime
import com.zaxxer.ping.impl.util.WaitingTargetCollection
import com.zaxxer.ping.impl.util.dumpBuffer
import jnr.constants.platform.Errno
import jnr.ffi.Pointer
import jnr.ffi.Struct
import jnr.ffi.byref.IntByReference
import jnr.posix.MsgHdr
import java.lang.System.nanoTime
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
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

    override fun compareTo(other: PingTarget): Int {
       val timeoutDelta = timeoutNs.compareTo(other.timeoutNs)
       if (timeoutDelta != 0)
          return timeoutDelta
       return sequence.compareTo(other.sequence)
    }

   override fun toString(): String = inetAddress.toString()
}

interface PingResponseHandler {
   fun onResponse(pingTarget: PingTarget, responseTimeSec: Double, byteCount: Int, seq: Int)

   fun onTimeout(pingTarget: PingTarget)
}

private val ID_SEQUENCE = AtomicInteger(0xCAFE)

private val SEQUENCE_SEQUENCE = AtomicInteger(0xBABE)

private val LOGGER = Logger.getLogger(IcmpPinger::class.java.name)

class IcmpPinger(private val responseHandler:PingResponseHandler) {

   private val waitingTargets4 = WaitingTargetCollection()
   private val waitingTargets6 = WaitingTargetCollection()

   private val pending4Pings = LinkedBlockingQueue<PingTarget>(PENDING_QUEUE_SIZE)
   private val pending6Pings = LinkedBlockingQueue<PingTarget>(PENDING_QUEUE_SIZE)

   private val prebuiltBuffer:ByteBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE.toInt())
   private val socketBuffer:ByteBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE.toInt())
   private val fdBuffer:ByteBuffer = ByteBuffer.allocateDirect(SIZEOF_STRUCT_POLL_FD * FDS)

   private val prebuiltBufferPointer:Pointer = runtime.memoryManager.newPointer(prebuiltBuffer)
   private val socketBufferPointer:Pointer = runtime.memoryManager.newPointer(socketBuffer)
   private val outpacketPointer:Pointer = socketBufferPointer.slice(SIZEOF_STRUCT_IP.toLong())
   private val fdBufferPointer:Pointer = runtime.memoryManager.newPointer(fdBuffer)

   private val icmp:Icmp = Icmp()
   private val icmp6:Icmp6 = Icmp6()
   private val recvIp:Ip = Ip()
   private val msgHdr:MsgHdr = posix.allocateMsgHdr()

   private val fdPipe:PollFd = PollFd()
   private val fd4:PollFd = PollFd()
   private val fd6:PollFd = PollFd()

   private val awoken = AtomicBoolean(false)
   private val pipefd = IntArray(2) { -1 }

   @Volatile private var running = true

   init {
      val tmpBuffer = prebuiltBuffer.duplicate()
      tmpBuffer.position(SIZEOF_STRUCT_IP + ICMP_MINLEN)
      for (i in 0 until DEFAULT_DATALEN)
         tmpBuffer.put(i.toByte())

      icmp.useMemory(outpacketPointer)
      recvIp.useMemory(socketBufferPointer)

      arrayOf(fdPipe, fd4, fd6).forEachIndexed { i, fd ->
         fd.useMemory(fdBufferPointer.slice(SIZEOF_STRUCT_POLL_FD.toLong() * i))
         fd.events = POLLIN_OR_PRI or POLLOUT or POLLERR
         fd.revents = 0
      }

      fd4.fd = -1
      fd6.fd = -1

      msgHdr.iov = arrayOf(socketBuffer)
   }

   fun ping(pingTarget: PingTarget) {
      val pendingPings = if (pingTarget.isIPv4()) pending4Pings else pending6Pings
      pendingPings.offer(PingTarget(pingTarget))
      if (awoken.compareAndSet(false, true)) {
         wakeup()
      }
   }

   fun runSelector() {
      val infinite:Int = -1
      var pollTimeoutMs:Int = infinite

      try {
         // To avoid leaks of file descriptors, creation and destruction
         // of pipe channels is done during the runSelector call
         libc.pipe(pipefd)
         setNonBlocking(pipefd[0])
         setNonBlocking(pipefd[1])
         fdPipe.fd = pipefd[0]

         while (running) {
            val rc = libc.poll(fdBufferPointer, FDS, pollTimeoutMs)
            if (rc < 0) {
               val errno = posix.errno()
               if (errno != Errno.EINTR.intValue()) {
                  LOGGER.severe("poll() returned errno $errno")
                  break
               }
            }

            awoken.compareAndSet(true, false)

            if (fdPipe.revents and POLLIN_OR_PRI != 0) wakeupReceived()

            if (rc > 0) {
               if (fd4.revents and POLLERR != 0 || fd6.revents and POLLERR != 0) {
                  LOGGER.severe("poll() created a POLLERR event")
                  break
               }

               if (fd4.revents and POLLIN_OR_PRI != 0) processReceives(fd4.fd, true)
               if (fd6.revents and POLLIN_OR_PRI != 0) processReceives(fd6.fd, false)

               if (fd4.revents and POLLOUT != 0) processSends(pending4Pings, fd4.fd)
               if (fd6.revents and POLLOUT != 0) processSends(pending6Pings, fd6.fd)

               fdPipe.revents = 0
               fd4.revents = 0
               fd6.revents = 0
            }

            val next4TimeoutMs = processTimeouts(waitingTargets4)
            val next6TimeoutMs = processTimeouts(waitingTargets6)
            if (next4TimeoutMs != null && next6TimeoutMs != null) {
               pollTimeoutMs = minOf(next4TimeoutMs, next6TimeoutMs)
            } else {
               pollTimeoutMs = next4TimeoutMs ?: next6TimeoutMs ?: infinite
            }

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
         if (fd4.fd > 0) libc.close(fd4.fd)
         if (fd6.fd > 0) libc.close(fd6.fd)
         fd4.fd = -1
         fd6.fd = -1

         running = false
         libc.close(pipefd[0])
         libc.close(pipefd[1])
         pipefd[0] = -1
         pipefd[1] = -1
      }
   }

   fun stopSelector() {
      running = false
      wakeup()
   }

   fun isPendingWork() = pending4Pings.isNotEmpty() || pending6Pings.isNotEmpty() || waitingTargets4.isNotEmpty() || waitingTargets6.isNotEmpty()

   /**********************************************************************************************
    *                                     Private Methods
    */

   private fun processSends(pendingPings:LinkedBlockingQueue<PingTarget>, fd:FD) {
      while (pendingPings.isNotEmpty()) {
         val pingTarget = pendingPings.take()
         if (sendIcmp(pingTarget, fd)) {
            if (pingTarget.isIPv4()) {
               waitingTargets4.add(pingTarget)
            } else {
               waitingTargets6.add(pingTarget)
            }
         }
         else {
            responseHandler.onTimeout(pingTarget)
            break
         }
      }
   }

   private fun processReceives(fd: FD, isIPv4: Boolean) {
      do {
         val more = recvIcmp(fd, isIPv4) // read and lookup actor id in map
      } while (more)
   }

   private fun processTimeouts(targets: WaitingTargetCollection) : Int? {
      while (true) {
         val timeoutNs = targets.peekTimeoutQueue() ?: return null
         val nowNs = nanoTime()
         val remainingMs = NANOSECONDS.toMillis(timeoutNs - nowNs)
         if (remainingMs > 0) {
            return minOf(remainingMs, Int.MAX_VALUE.toLong()).toInt()
         }

         try {
            responseHandler.onFailure(targets.take(), FailureReason.TimedOut)
         } catch (_:Exception) {}
      }
   }

   private fun sendIcmp(pingTarget: PingTarget, fd: FD) : Boolean {
      prebuiltBufferPointer.transferTo(0, socketBufferPointer, 0, BUFFER_SIZE)

      pingTarget.sequence = (SEQUENCE_SEQUENCE.getAndIncrement() and 0xffff).toShort()

      if (pingTarget.isIPv4()) {
         icmp.useMemory(outpacketPointer)
         icmp.icmp_hun.ih_idseq.icd_seq.set(htons(pingTarget.sequence))
         icmp.icmp_hun.ih_idseq.icd_id.set(htons(pingTarget.id))
         icmp.icmp_type.set(ICMP_ECHO)
         icmp.icmp_code.set(0)
         icmp.icmp_cksum.set(0)
         // In BSD we are responsible for the entire payload, including checksum.  Linux mucks with the payload (replacing
         // the identity field, and therefore recalculates the checksum (so don't waste our time doing it here).
         if (isBSD) {
            val cksum = icmpCksum(outpacketPointer, SEND_PACKET_SIZE)
            icmp.icmp_cksum.set(htons(cksum.toShort()))
         }
      } else {
         icmp6.useMemory(outpacketPointer)
         icmp6.icmp6_dataun.icmp6_un_data32[0].set(htons(pingTarget.sequence))
         icmp6.icmp6_type.set(ICMPV6_ECHO_REQUEST)
         icmp6.icmp6_code.set(0)
         icmp6.icmp6_cksum.set(0)
         if (isBSD) {
            val cksum = icmpCksum(outpacketPointer, SEND_PACKET_SIZE)
            icmp6.icmp6_cksum.set(htons(cksum.toShort()))
         }
      }

      if (LOGGER.level == Level.FINEST) LOGGER.finest { dumpBuffer(message = "Send buffer:", buffer = socketBuffer) }

      pingTarget.timestamp()

      val rc = libc.sendto(fd, outpacketPointer, SEND_PACKET_SIZE, 0, pingTarget.sockAddr, Struct.size(pingTarget.sockAddr))
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

      val cc = posix.recvmsg(fd, msgHdr, 0)
      if (cc < 0) {
         val errno = posix.errno()
         if (errno == Errno.EINTR.intValue() && errno == Errno.EAGAIN.intValue()) {
            return true
         }
         LOGGER.fine {"   Error code $errno returned from pingChannel.read()"}
         return false
      }

      if (LOGGER.level == Level.FINEST) LOGGER.finest(dumpBuffer("Ping response", socketBuffer))

      val seq: Short
      val waitingTargets: WaitingTargetCollection

      if (isIPv4) {
         val headerLen= if (isBSD) (recvIp.ip_vhl.longValue() and 0x0f) shl 2 else 0
         icmp.useMemory(socketBufferPointer.slice(headerLen))
         val icmpType = icmp.icmp_type.get()
         if (icmpType != ICMP_ECHOREPLY) {
            LOGGER.fine("   ^ Opps, not our response.")
            return true
         }
         seq = ntohs(icmp.icmp_hun.ih_idseq.icd_seq.shortValue())
         waitingTargets = waitingTargets4
      } else {
         icmp6.useMemory(socketBufferPointer)
         val icmpType = icmp6.icmp6_type.get()
         if (icmpType != ICMPV6_ECHO_REPLY) {
            LOGGER.fine("   ^ Opps, not our response.")
            return true
         }
         seq = ntohs(icmp6.icmp6_dataun.icmp6_un_data32[0].shortValue())
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

   private fun wakeup() = libc.write(pipefd[1], ByteArray(1), 1)

   private fun wakeupReceived() {
      while (libc.read(pipefd[0], ByteArray(1), 1) > 0) {
         // drain the wakeup pipe
      }

      if (fd4.fd == 0) {
         createSockets()
      }
   }

   private fun createSockets() {
      fd4.fd = libc.socket(PF_INET, SOCK_DGRAM, IPPROTO_ICMP)
      if (fd4.fd < 0)
         error("Unable to create IPv4 socket.  If this is Linux, you might need to set sysctl net.ipv4.ping_group_range")
      fd6.fd = libc.socket(PF_INET6, SOCK_DGRAM, IPPROTO_ICMPV6)
      if (fd6.fd < 0)
         error("Unable to create IPv6 socket.  If this is Linux, you might need to set sysctl net.ipv6.ping_group_range")

      val on = IntByReference(1)
      libc.setsockopt(fd4.fd, SOL_SOCKET, SO_TIMESTAMP, on, on.nativeSize(runtime))
      libc.setsockopt(fd6.fd, SOL_SOCKET, SO_TIMESTAMP, on, on.nativeSize(runtime))

      libc.setsockopt(fd4.fd, SOL_SOCKET, SO_REUSEPORT, on, on.nativeSize(runtime))
      libc.setsockopt(fd6.fd, SOL_SOCKET, SO_REUSEPORT, on, on.nativeSize(runtime))

      // Better handled by altering the OS default rcvbuf size
      // val rcvbuf = IntByReference(2048)
      // libc.setsockopt(fd, SOL_SOCKET, SO_RCVBUF, rcvbuf, rcvbuf.nativeSize(runtime))

      setNonBlocking(fd4.fd)
      setNonBlocking(fd6.fd)

   }

   private fun logPendingPingsAndActors(): String =
      "   Pending ping count ${pending4Pings.size + pending6Pings.size}\n" +
      "   Pending actor count ${waitingTargets4.size + waitingTargets6.size}"
}

private fun setNonBlocking(fd: FD) {
   val flags4 = libc.fcntl(fd, F_GETFL, 0) or O_NONBLOCK
   libc.fcntl(fd, F_SETFL, flags4.toLong())
}
