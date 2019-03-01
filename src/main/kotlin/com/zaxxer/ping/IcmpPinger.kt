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

import com.zaxxer.ping.IcmpPinger.Companion.ID_SEQUENCE
import com.zaxxer.ping.impl.*
import com.zaxxer.ping.impl.NativeStatic.Companion.isBSD
import com.zaxxer.ping.impl.NativeStatic.Companion.libc
import com.zaxxer.ping.impl.NativeStatic.Companion.posix
import com.zaxxer.ping.impl.NativeStatic.Companion.runtime
import com.zaxxer.ping.impl.util.dumpBuffer
import it.unimi.dsi.fastutil.shorts.Short2ObjectLinkedOpenHashMap
import jnr.constants.platform.Errno
import jnr.ffi.Pointer
import jnr.ffi.Struct
import jnr.ffi.byref.IntByReference
import jnr.posix.MsgHdr
import jnr.posix.Timeval
import java.lang.System.nanoTime
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

/**
 * Created by Brett Wooldridge on 2017/10/03.
 *
 * Ref:
 *   https://stackoverflow.com/questions/8290046/icmp-sockets-linux
 */

const val DEFAULT_TIMEOUT_MS = 1000L
const val DEFAULT_TIMEOUT_USEC = 1000 * DEFAULT_TIMEOUT_MS
const val BUFFER_SIZE = 128L
const val PENDING_QUEUE_SIZE = 8192

typealias FD = Int

data class PingTarget @JvmOverloads constructor(val inetAddress:InetAddress,
                                                val userObject:Any? = null,
                                                private val timeoutMs:Long = DEFAULT_TIMEOUT_MS) {

   internal val id = (ID_SEQUENCE.getAndIncrement() % 0xffff).toShort()
   internal var sequence:Short = 0
   internal val sockAddr:SockAddr
   internal val isIPv4:Boolean
   internal var timestampNs:Long = 0L
   internal var timeout = 0L

   init {
      if (inetAddress is Inet4Address) {
         isIPv4 = true
         sockAddr = if (isBSD) {
            BSDSockAddr4(inetAddress)
         } else {
            LinuxSockAddr4(inetAddress)
         }
      }
      else {  // IPv6
         isIPv4 = false
         sockAddr = if (isBSD) {
            BSDSockAddr6(inetAddress as Inet6Address)
         } else {
            LinuxSockAddr6(inetAddress as Inet6Address)
         }
      }
   }

   internal fun timestamp() {
      timestampNs = nanoTime()
      timeout = timestampNs + MILLISECONDS.toNanos(timeoutMs)
   }
}

interface PingResponseHandler {
   fun onResponse(pingTarget:PingTarget, responseTimeSec:Double, byteCount:Int, seq:Int)

   fun onTimeout(pingTarget:PingTarget)
}

private val LOGGER = Logger.getLogger(IcmpPinger::class.java.name)

class IcmpPinger(private val responseHandler:PingResponseHandler) {

   private val waitingTarget4Map = Short2ObjectLinkedOpenHashMap<PingTarget>()
   private val waitingTarget6Map = Short2ObjectLinkedOpenHashMap<PingTarget>()

   private val pending4Pings = LinkedBlockingQueue<PingTarget>(PENDING_QUEUE_SIZE)
   private val pending6Pings = LinkedBlockingQueue<PingTarget>(PENDING_QUEUE_SIZE)

   private lateinit var prebuiltBuffer:ByteBuffer
   private lateinit var socketBuffer:ByteBuffer

   private lateinit var prebuiltBufferPointer:Pointer
   private lateinit var socketBufferPointer:Pointer
   private lateinit var outpacketPointer:Pointer

   private lateinit var icmp:Icmp
   private lateinit var icmp6:Icmp6
   private lateinit var recvIp:Ip
   private lateinit var msgHdr:MsgHdr

   private val awoken = AtomicBoolean()
   private var fd4:FD = 0
   private var fd6:FD = 0
   private val pipefd = IntArray(2)
   private val readSet = Fd_set()
   private val writeSet = Fd_set()
   @Volatile private var running = true

   companion object {
      @JvmStatic
      internal val ID_SEQUENCE = AtomicInteger(0xCAFE)

      @JvmStatic
      internal val SEQUENCE_SEQUENCE = AtomicInteger(0xBABE)
   }

   init {
      pipefd[0] = -1
      pipefd[1] = -1
      libc.pipe(pipefd)

      setNonBlocking(pipefd[0])
      setNonBlocking(pipefd[1])

      resetFdSets()

      buildBuffers()
   }

   fun ping(pingTarget:PingTarget) {
      if (pingTarget.isIPv4) {
         pending4Pings.offer(pingTarget)
      }
      else {
         pending6Pings.offer(pingTarget)
      }

      if (awoken.compareAndSet(false, true)) {
         wakeup()
      }
   }

   fun runSelector() {
      val readSetPtr = Struct.getMemory(readSet)
      val writeSetPtr = Struct.getMemory(writeSet)

      val timeoutTimeval = posix.allocateTimeval()
      val infinite:Timeval? = null
      var selectTimeout: Timeval? = infinite

      val logPendingPings: () -> String = {"   Pending ping count ${pending4Pings.size + pending6Pings.size}"}
      val logPendingActor: () -> String = {"   Pending actor count ${waitingTarget4Map.size + waitingTarget6Map.size}"}

      var noopLoops = 0
      while (running) {
         val nfds = maxOf(maxOf(fd4, fd6), pipefd[0]) + 1
         val rc = libc.select(nfds, readSetPtr, writeSetPtr, null /*errorSet*/, selectTimeout)
         if (rc < 0) {
            LOGGER.fine({"select() returned errno ${posix.errno()}"})
            break
         }

         awoken.compareAndSet(true, false)

         if (FD_ISSET(pipefd[0], readSet)) wakeupReceived()

         if (rc > 0) {
            if (FD_ISSET(fd4, writeSet)) processSends(pending4Pings, fd4)
            if (FD_ISSET(fd6, writeSet)) processSends(pending6Pings, fd6)

            if (FD_ISSET(fd4, readSet)) processReceives(fd4)
            if (FD_ISSET(fd6, readSet)) processReceives(fd6)
         }

         val next4timeoutUsec = processTimeouts(waitingTarget4Map)
         val next6timeoutUsec = processTimeouts(waitingTarget6Map)
         val nextTimeoutUsec = maxOf(minOf(next4timeoutUsec, next6timeoutUsec), 500)

         timeoutTimeval.sec(nextTimeoutUsec / 1_000_000L)
         timeoutTimeval.usec(nextTimeoutUsec % 1_000_000L)

         LOGGER.fine(logPendingPings)
         LOGGER.fine(logPendingActor)

         val isPending4reads = waitingTarget4Map.isNotEmpty()
         val isPending6reads = waitingTarget6Map.isNotEmpty()
         val isPending4writes = pending4Pings.isNotEmpty()
         val isPending6writes = pending6Pings.isNotEmpty()

         resetFdSets()
         if (isPending4reads) FD_SET(fd4, readSet)
         if (isPending6reads) FD_SET(fd6, readSet)
         if (isPending4writes) FD_SET(fd4, writeSet)
         if (isPending6writes) FD_SET(fd6, writeSet)

         if (isPending4reads || isPending6reads || isPending4writes || isPending6writes) {
            selectTimeout = timeoutTimeval
            noopLoops = 0
         }
         else {
            if (noopLoops++ > 10) {
               closeSockets()
               selectTimeout = infinite
            }
            else {
               timeoutTimeval.sec(1)
               timeoutTimeval.usec(0)
            }
         }
      }

      libc.close(pipefd[0])
      libc.close(pipefd[1])
      closeSockets()
   }

   fun stopSelector() {
      running = false
      wakeup()
   }

   fun isPendingWork() = pending4Pings.isNotEmpty() || pending6Pings.isNotEmpty() || waitingTarget4Map.isNotEmpty() || waitingTarget6Map.isNotEmpty()

   /**********************************************************************************************
    *                                     Private Methods
    */

   private fun processSends(pendingPings:LinkedBlockingQueue<PingTarget>, fd:FD) {
      while (pendingPings.isNotEmpty()) {
         val pingTarget = pendingPings.peek()
         if (sendIcmp(pingTarget, fd)) {
            pendingPings.take()
            if (pingTarget.isIPv4) {
               waitingTarget4Map[pingTarget.sequence] = pingTarget
            } else {
               waitingTarget6Map[pingTarget.sequence] = pingTarget
            }
         }
         else {
            pendingPings.take()
            responseHandler.onTimeout(pingTarget)
            break
         }
      }
   }

   private fun processReceives(fd:FD) {
      do {
         val more = recvIcmp(fd) // read and lookup actor id in map
      } while (more)
   }

   private fun processTimeouts(targets:Short2ObjectLinkedOpenHashMap<PingTarget>) : Long {
      if (targets.isEmpty()) return DEFAULT_TIMEOUT_USEC

      // Optimization to avoid creation if iterator if nothing is ready to timeout
      val firstTimeout = targets.get(targets.firstShortKey()).timeout
      val now = nanoTime()
      if (now < firstTimeout) return NANOSECONDS.toMicros(firstTimeout - now)

      val iterator = targets.values.iterator()
      while (iterator.hasNext()) {
         val pingTarget = iterator.next()
         if (now < pingTarget.timeout) return NANOSECONDS.toMicros(pingTarget.timeout - now)

         try {
            responseHandler.onTimeout(pingTarget)
         }
         catch (e:Exception) {
            continue
         }
         finally {
            iterator.remove()
         }
      }

      return DEFAULT_TIMEOUT_USEC
   }

   private fun sendIcmp(pingTarget:PingTarget, fd:FD) : Boolean {
      prebuiltBufferPointer.transferTo(0, socketBufferPointer, 0, BUFFER_SIZE)

      pingTarget.sequence = (SEQUENCE_SEQUENCE.getAndIncrement() % 0xffff).toShort()

      if (pingTarget.isIPv4) {
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

      LOGGER.finest({dumpBuffer(message = "Send buffer:", buffer = socketBuffer)})

      pingTarget.timestamp()

      val rc = libc.sendto(fd, outpacketPointer, SEND_PACKET_SIZE, 0, pingTarget.sockAddr, Struct.size(pingTarget.sockAddr))
      return if (rc == SEND_PACKET_SIZE) {
         LOGGER.fine({"   ICMP packet(seq=${pingTarget.sequence}) send to ${pingTarget.inetAddress} successful"})
         true
      }
      else {
         LOGGER.fine({"   icmp sendto() to ${pingTarget.inetAddress} for seq=${pingTarget.sequence} returned $rc"})
         false
      }
   }

   private fun recvIcmp(fd:FD) : Boolean {

      var cc = posix.recvmsg(fd, msgHdr, 0)
      if (cc > 0) {
         LOGGER.finest({dumpBuffer("Ping response", socketBuffer)})

         val isV4 = (recvIp.ip_vhl.get().toInt() and 0xf0) shr 4 == 4

         val headerLen= if (isBSD && isV4) (recvIp.ip_vhl.get().toInt() and 0x0f shl 2) else 0

         icmp.useMemory(socketBufferPointer.slice(headerLen.toLong()))
         icmp6.useMemory(socketBufferPointer)

         val icmpType = icmp.icmp_type.get()

         if (icmpType != ICMP_ECHOREPLY && icmpType != ICMPV6_ECHO_REPLY) {
            LOGGER.fine({ "   ^ Opps, not our response." })
         } else {
            val seq = if (icmpType == ICMP_ECHOREPLY) {
               ntohs(icmp.icmp_hun.ih_idseq.icd_seq.shortValue())
            } else {
               ntohs(icmp6.icmp6_dataun.icmp6_un_data32[0].shortValue())
            }

            waitingTarget4Map
               .remove(seq)
               ?.let { pingTarget ->
                  val elapsedus = NANOSECONDS.toMicros(nanoTime() - pingTarget.timestampNs)
                  val triptime = elapsedus.toDouble() / 1_000_000.0

                  responseHandler.onResponse(pingTarget, triptime, cc, seq.toInt())
               }
            waitingTarget6Map
               .remove(seq)
               ?.let { pingTarget ->
                  val elapsedus = NANOSECONDS.toMicros(nanoTime() - pingTarget.timestampNs)
                  val triptime = elapsedus.toDouble() / 1_000_000.0

                  responseHandler.onResponse(pingTarget, triptime, cc, seq.toInt())
            }
         }
      }
      else {
         val errno = posix.errno()
         if (errno != Errno.EINTR.intValue() && errno != Errno.EAGAIN.intValue()) {
            LOGGER.fine({"   Error code $errno returned from pingChannel.read()"})
         }
      }

      return cc > 0
   }

   private fun wakeup() = libc.write(pipefd[1], ByteArray(1), 1)

   private fun wakeupReceived() {
      while (libc.read(pipefd[0], ByteArray(1), 1) > 0) {
         // drain the wakeup pipe
      }

      if (fd4 == 0) {
         createSockets()
      }
   }

   private fun createSockets() {
      fd4 = libc.socket(PF_INET, SOCK_DGRAM, IPPROTO_ICMP)
      if (fd4 < 0)
         error("Unable to create IPv4 socket.  If this is Linux, you might need to set sysctl net.ipv4.ping_group_range")
      fd6 = libc.socket(PF_INET6, SOCK_DGRAM, IPPROTO_ICMPV6)
      if (fd6 < 0)
         error("Unable to create IPv6 socket.  If this is Linux, you might need to set sysctl net.ipv6.ping_group_range")

      val on = IntByReference(1)
      libc.setsockopt(fd4, SOL_SOCKET, SO_TIMESTAMP, on, on.nativeSize(runtime))
      libc.setsockopt(fd6, SOL_SOCKET, SO_TIMESTAMP, on, on.nativeSize(runtime))

      libc.setsockopt(fd4, SOL_SOCKET, SO_REUSEPORT, on, on.nativeSize(runtime))
      libc.setsockopt(fd6, SOL_SOCKET, SO_REUSEPORT, on, on.nativeSize(runtime))

      // Better handled by altering the OS default rcvbuf size
      // val rcvbuf = IntByReference(2048)
      // libc.setsockopt(fd, SOL_SOCKET, SO_RCVBUF, rcvbuf, rcvbuf.nativeSize(runtime))

      setNonBlocking(fd4)
      setNonBlocking(fd6)
   }

   private fun closeSockets() {
      if (fd4 > 0) libc.close(fd4)
      if (fd6 > 0) libc.close(fd6)
      fd4 = 0
      fd6 = 0
   }

   private fun resetFdSets() {
      FD_ZERO(readSet)
      FD_ZERO(writeSet)
      FD_SET(pipefd[0], readSet)
   }

   private fun buildBuffers() {
      prebuiltBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE.toInt())
      prebuiltBufferPointer = runtime.memoryManager.newPointer(prebuiltBuffer)

      socketBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE.toInt())
      socketBufferPointer = runtime.memoryManager.newPointer(socketBuffer)

      outpacketPointer = socketBufferPointer.slice(SIZEOF_STRUCT_IP.toLong())

      val tmpBuffer = prebuiltBuffer.duplicate()
      tmpBuffer.position(SIZEOF_STRUCT_IP + ICMP_MINLEN)
      for (i in 0 until DEFAULT_DATALEN)
         tmpBuffer.put(i.toByte())

      icmp = Icmp()
      icmp.useMemory(outpacketPointer)

      icmp6 = Icmp6()

      recvIp = Ip()
      recvIp.useMemory(socketBufferPointer)

      msgHdr = posix.allocateMsgHdr()
      msgHdr.iov = arrayOf(socketBuffer)
   }
}

private fun setNonBlocking(fd:FD) {
   val flags4 = libc.fcntl(fd, F_GETFL, 0) or O_NONBLOCK
   libc.fcntl(fd, F_SETFL, flags4)
}
