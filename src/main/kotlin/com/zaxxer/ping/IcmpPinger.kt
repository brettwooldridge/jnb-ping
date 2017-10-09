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
import com.zaxxer.ping.impl.util.dumpBuffer
import it.unimi.dsi.fastutil.shorts.Short2ObjectLinkedOpenHashMap
import jnr.constants.platform.Errno
import jnr.ffi.Pointer
import jnr.ffi.Struct
import jnr.ffi.byref.IntByReference
import jnr.posix.CmsgHdr
import jnr.posix.MsgHdr
import jnr.posix.Timeval
import java.lang.Exception
import java.lang.System.currentTimeMillis
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Created by Brett Wooldridge on 2017/10/03.
 *
 * Ref:
 *   https://stackoverflow.com/questions/8290046/icmp-sockets-linux
 */

const val DEFAULT_TIMEOUT_MS = 1000L
const val BUFFER_SIZE = 128L

data class PingTarget(val inetAddress : InetAddress, private val timeoutMs : Long = DEFAULT_TIMEOUT_MS) {
   internal val id = (ID_SEQUENCE.getAndIncrement() % 0xffff).toShort()
   internal var sequence = 0.toShort()
   internal val sockAddr : SockAddr
   internal val isIPv4 : Boolean
   internal var timeout = 0L
      set(value) {
         field = value + timeoutMs
      }

   init {
      if (inetAddress is Inet4Address) {
         isIPv4 = true
         if (isBSD) {
            sockAddr = BSDSockAddr4(inetAddress)
         }
         else {
            sockAddr = LinuxSockAddr4()
            // sockAddr.sin_addr.set(inAddr)
         }
      }
      else {  // IPv6
         isIPv4 = false
         error("Not implemented")
      }
   }
}

interface PingResponseHandler {
   fun onResponse(pingTarget : PingTarget, rtt : Double, bytes : Int, seq : Int)

   fun onTimeout(pingTarget : PingTarget)

   fun onError(pingTarget : PingTarget, message : String)
}

class IcmpPinger(private val responseHandler : PingResponseHandler) {

   private val waitingTarget4Map = Short2ObjectLinkedOpenHashMap<PingTarget>()
   private val waitingTarget6Map = Short2ObjectLinkedOpenHashMap<PingTarget>()

   private val pending4Pings = LinkedBlockingQueue<PingTarget>()
   private val pending6Pings = LinkedBlockingQueue<PingTarget>()

   private lateinit var prebuiltBuffer : ByteBuffer
   private lateinit var socketBuffer : ByteBuffer

   private lateinit var prebuiltBufferPointer : Pointer
   private lateinit var socketBufferPointer : Pointer
   private lateinit var outpacketPointer : Pointer

   private lateinit var sendTv32 : Tv32
   private lateinit var sendIcmp : Icmp

   private lateinit var msgHdr : MsgHdr
   private lateinit var cmsgHdr : CmsgHdr

   // private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
   private var fd4 : Int = 0
   private var fd6 : Int = 0
   private val pipefd = IntArray(2)
   private val readSet = Fd_set()
   private val writeSet = Fd_set()
   private @Volatile var running = true

   companion object {
      @JvmStatic
      internal val ID_SEQUENCE = AtomicInteger()
   }

   init {
      // Better handled by altering the OS default rcvbuf size
      // val rcvbuf = IntByReference(2048)
      // libc.setsockopt(fd, SOL_SOCKET, SO_RCVBUF, rcvbuf, rcvbuf.nativeSize(runtime))

      pipefd[0] = -1
      pipefd[1] = -1
      libc.pipe(pipefd)

      setNonBlocking(pipefd[0])
      setNonBlocking(pipefd[1])

      resetFdSets()

      buildBuffers()
   }

   fun ping(pingTarget : PingTarget) {
      if (pingTarget.isIPv4) {
         pending4Pings.offer(pingTarget)
      }
      else {
         pending6Pings.offer(pingTarget)
      }

      wakeup()
   }

   fun runSelector() {
      val readSetPtr = Struct.getMemory(readSet)
      val writeSetPtr = Struct.getMemory(writeSet)

      val timeoutTimeval = posix.allocateTimeval()
      val infinite : Timeval? = null
      var timeVal: Timeval? = infinite

      var noopLoops = 0
      while (running) {
         val nfds = maxOf(maxOf(fd4, fd4), pipefd[0]) + 1
         val rc = libc.select(nfds, readSetPtr, writeSetPtr, null /*errorSet*/, timeVal)
         if (rc < 0) {
            println("select() returned errno ${posix.errno()}")
            break
         }

         if (FD_ISSET(pipefd[0], readSet)) wakeupReceived()

         val now = currentTimeMillis()

         if (rc > 0) {
            if (FD_ISSET(fd4, writeSet)) processPending(pending4Pings, fd4, now)
            if (FD_ISSET(fd6, writeSet)) processPending(pending4Pings, fd6, now)

            if (FD_ISSET(fd4, readSet)) processWaiting(fd4)
            if (FD_ISSET(fd6, readSet)) processWaiting(fd6)
         }

         val next4timeoutMs = processTimeouts(waitingTarget4Map, now)
         val next6timeoutMs = processTimeouts(waitingTarget6Map, now)
         val nextTimeoutMs = minOf(next4timeoutMs, next6timeoutMs)

         // must be reset each time because select() on Linux modifies it
         val timeoutSecs = nextTimeoutMs / 1000L
         val timeoutUsecs = (nextTimeoutMs % 1000) * 1000L
         timeoutTimeval.sec(timeoutSecs)
         timeoutTimeval.usec(timeoutUsecs)

         if (DEBUG) {
            println("   Pending ping count ${pending4Pings.size + pending6Pings.size}")
            println("   Pending actor count ${waitingTarget4Map.size + waitingTarget6Map.size}")
         }

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
            timeVal = timeoutTimeval
            noopLoops = 0
         }
         else {
            if (noopLoops++ > 10) {
               closeSockets()
               timeVal = infinite
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

   fun isPending() = pending4Pings.isNotEmpty() || pending6Pings.isNotEmpty() || waitingTarget4Map.isNotEmpty() || waitingTarget6Map.isNotEmpty()

   /**********************************************************************************************
    *                                     Private Methods
    */

   private fun processPending(pendingPings : LinkedBlockingQueue<PingTarget>, fd : Int, now : Long) {
      val epochSecs = now / 1000L
      val epochUsecs = (now % 1000L) * 1000L

      while (pendingPings.isNotEmpty()) {
         val pingActor = pendingPings.peek()
         if (sendIcmp(pingActor, fd, epochSecs, epochUsecs)) {
            pendingPings.take()
            waitingTarget4Map.put(pingActor.id, pingActor)
         }
         else {
            break
         }
      }
   }

   private fun processWaiting(fd : Int) {
      do {
         val more = recvIcmp(fd) // read and lookup actor id in map
      } while (more)
   }

   private fun processTimeouts(targets : Short2ObjectLinkedOpenHashMap<PingTarget>, now : Long) : Long {
      if (targets.isEmpty()) return DEFAULT_TIMEOUT_MS

      // Optimization to avoid creation if iterator if nothing is ready to timeout
      val firstTimeout = targets.get(targets.firstShortKey()).timeout
      if (now < firstTimeout) return firstTimeout - now

      val iterator = targets.values.iterator()
      while (iterator.hasNext()) {
         val pingTarget = iterator.next()
         if (now < pingTarget.timeout) pingTarget.timeout - now

         try {
            responseHandler.onTimeout(pingTarget)
         }
         catch (e : Exception) {
            continue
         }
         finally {
            iterator.remove()
         }
      }

      return DEFAULT_TIMEOUT_MS
   }

   private fun sendIcmp(pingTarget : PingTarget, fd : Int, epochSecs : Long, epochUsecs : Long) : Boolean {
      prebuiltBufferPointer.transferFrom(0, socketBufferPointer, 0, BUFFER_SIZE)

      sendTv32.tv32_sec.set(epochSecs)
      sendTv32.tv32_usec.set(epochUsecs)

      val seq = pingTarget.sequence++
      sendIcmp.icmp_hun.ih_idseq.icd_seq.set(htons(seq))
      sendIcmp.icmp_hun.ih_idseq.icd_id.set(htons(pingTarget.id))
      sendIcmp.icmp_type.set(ICMP_ECHO)
      sendIcmp.icmp_code.set(0)
      sendIcmp.icmp_cksum.set(0)

      val packetSize = ICMP_MINLEN + DEFAULT_DATALEN

      val cksum = icmpCksum(outpacketPointer, packetSize)
      sendIcmp.icmp_cksum.set(htons(cksum.toShort()))

      // dumpBuffer(message = "Send buffer:", buffer = outpackBuffer)

      val rc = libc.sendto(fd, outpacketPointer, packetSize, 0, pingTarget.sockAddr, Struct.size(pingTarget.sockAddr))
      if (rc == packetSize) {
         pingTarget.timeout = currentTimeMillis()
         if (DEBUG) println("   ICMP packet(seq=$seq) send to ${pingTarget.inetAddress} successful")
         return true
      }
      else {
         if (DEBUG) println("   Error: icmp sendto() to ${pingTarget.inetAddress} for seq=$seq returned $rc")
         return false
      }
   }

   private fun recvIcmp(fd : Int) : Boolean {

      var cc = posix.recvmsg(fd, msgHdr, 0)
      if (cc > 0) {
         if (DEBUG) dumpBuffer("Ping response", socketBuffer)

         val ip = Ip()
         ip.useMemory(socketBufferPointer)
         val headerLen = (ip.ip_vhl.get().toInt() and 0x0f shl 2)
         cc -= headerLen

         val icmp = Icmp()
         icmp.useMemory(socketBufferPointer.slice(headerLen.toLong()))
         val id = ntohs(icmp.icmp_hun.ih_idseq.icd_id.get().toShort())
         if (icmp.icmp_type.get() != ICMP_ECHOREPLY) {
            if (DEBUG) println("   ^ Opps, not our response.")
         }
         else {
            //         var triptime : Double = 0.0
            //         if (cc - ICMP_MINLEN >= SIZEOF_STRUCT_TIMEVAL) {
            //            val tp = packetPointer.slice(headerLen.toLong() + icmp.icmp_dun.id_data.offset())
            //            val tv1 = posix.allocateTimeval()
            //            val tv1Pointer = runtime.memoryManager.newPointer(sendBuffer)
            //            tv1.useMemory(tv1Pointer)
            //            tp.transferTo(0, tv1Pointer, 0, SIZEOF_STRUCT_TV32.toLong())
            //
            //            val usec = tv32.tv32_usec.get() - tv1.usec()
            //            if (usec < 0) {
            //               tv32.tv32_sec.set(tv32.tv32_sec.get() - 1)
            //               tv32.tv32_usec.set(usec + 1000000)
            //            }
            //            tv32.tv32_sec.set(tv32.tv32_sec.get() - tv1.sec())
            //
            //            triptime = (tv32.tv32_sec.get().toDouble()) * 1000.0 + (tv32.tv32_usec.get().toDouble()) / 1000.0
            //         }

            val pingTarget : PingTarget? = waitingTarget4Map.remove(id)
            if (pingTarget != null) {
               val triptime = 1.0 // (usElapsed.toDouble() / 1000.0)
               val seq = ntohs(icmp.icmp_hun.ih_idseq.icd_seq.shortValue())

               responseHandler.onResponse(pingTarget, triptime, cc, seq.toInt())
            }
         }
      }
      else {
         val errno = posix.errno()
         if (errno != Errno.EINTR.intValue() && errno != Errno.EAGAIN.intValue()) {
            if (DEBUG) println("   Error code $errno returned from pingChannel.read()")
         }
      }

      return cc > 0
   }

   private fun createSockets() {
      fd4 = libc.socket(PF_INET, SOCK_DGRAM, IPPROTO_ICMP)
      fd6 = libc.socket(PF_INET6, SOCK_DGRAM, IPPROTO_ICMPV6)

      val on = IntByReference(1)
      libc.setsockopt(fd4, SOL_SOCKET, SO_TIMESTAMP, on, on.nativeSize(runtime))
      libc.setsockopt(fd6, SOL_SOCKET, SO_TIMESTAMP, on, on.nativeSize(runtime))

      libc.setsockopt(fd4, SOL_SOCKET, SO_REUSEPORT, on, on.nativeSize(runtime))
      libc.setsockopt(fd6, SOL_SOCKET, SO_REUSEPORT, on, on.nativeSize(runtime))

      setNonBlocking(fd4)
      setNonBlocking(fd6)
   }

   private fun closeSockets() {
      if (fd4 > 0) libc.close(fd4)
      if (fd6 > 0) libc.close(fd6)
      fd4 = 0
      fd6 = 0
   }

   private fun setNonBlocking(fd : Int) {
      val flags4 = libc.fcntl(fd, F_GETFL, 0) or O_NONBLOCK
      libc.fcntl(fd, F_SETFL, flags4)
   }

   private fun wakeupReceived() {
      while (libc.read(pipefd[0], ByteArray(1), 1) > 0) {
         // drain the wakeup pipe
      }

      if (fd4 == 0) {
         createSockets()
      }
   }

   private fun wakeup() = libc.write(pipefd[1], ByteArray(1), 1)

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
      tmpBuffer.position(SIZEOF_STRUCT_IP + ICMP_MINLEN + SIZEOF_STRUCT_TV32)
      for (i in SIZEOF_STRUCT_TV32..DEFAULT_DATALEN)
         tmpBuffer.put(i.toByte())

      sendTv32 = Tv32()
      sendTv32.useMemory(outpacketPointer.slice(ICMP_MINLEN.toLong(), SIZEOF_STRUCT_TV32.toLong()))

      sendIcmp = Icmp()
      sendIcmp.useMemory(outpacketPointer)

      msgHdr = posix.allocateMsgHdr()
      msgHdr.iov = arrayOf(socketBuffer)

      cmsgHdr = msgHdr.allocateControl(SIZEOF_STRUCT_TIMEVAL)
   }
}

val DEBUG = java.lang.Boolean.getBoolean("com.zaxxer.ping.debug")
