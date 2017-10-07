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
import com.zaxxer.ping.impl.util.dumpBuffer
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap
import jnr.constants.platform.Errno
import jnr.ffi.Struct
import jnr.ffi.byref.IntByReference
import jnr.posix.Timeval
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Created by Brett Wooldridge on 2017/10/03.
 *
 * Ref:
 *   https://stackoverflow.com/questions/8290046/icmp-sockets-linux
 */

data class PingTarget(val inetAddress : InetAddress)

interface PingResponseHandler {
   fun onResponse(pingTarget : PingTarget, rtt : Double, bytes : Int, seq : Int)

   fun onTimeout(pingTarget : PingTarget)

   fun onError(pingTarget : PingTarget, message : String)
}

class IcmpPinger(private val responseHandler : PingResponseHandler) {

   private val actor4Map = Short2ObjectOpenHashMap<PingActor>()
   private val actor6Map = Short2ObjectOpenHashMap<PingActor>()

   private val pending4Pings = LinkedBlockingQueue<PingActor>()
   private val pending6Pings = LinkedBlockingQueue<PingActor>()

   // private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
   private var fd4 : Int = 0
   private var fd6 : Int = 0
   private val pipefd = IntArray(2)
   private val readSet = Fd_set()
   private val writeSet = Fd_set()
   private @Volatile var running = true

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
   }

   fun ping(pingTarget : PingTarget) {
      val pingActor = PingActor(pingTarget, responseHandler)

      if (pingActor.isIPv4) {
         pending4Pings.offer(pingActor)
      }
      else {
         pending6Pings.offer(pingActor)
      }

      wakeup()
   }

   fun runSelector() {
      val readSetPtr = Struct.getMemory(readSet)
      val writeSetPtr = Struct.getMemory(writeSet)

      val oneHundredMs = posix.allocateTimeval()
      val infinite : Timeval? = null

      var noopLoops = 0
      var timeVal: Timeval? = infinite
      while (running) {
         // must be reset because select() can modify it
         oneHundredMs.sec(0)
         oneHundredMs.usec(100_000)

         val nfds = maxOf(maxOf(fd4, fd4), pipefd[0]) + 1
         val rc = libc.select(nfds, readSetPtr, writeSetPtr, null /*errorSet*/, timeVal)
         if (rc < 0) {
            println("select() returned errno ${posix.errno()}")
            break
         }

         if (FD_ISSET(pipefd[0], readSet)) wakeupReceived()

         if (FD_ISSET(fd4, writeSet)) processPending(pending4Pings, fd4)
         if (FD_ISSET(fd6, writeSet)) processPending(pending4Pings, fd6)

         if (FD_ISSET(fd4, readSet)) processWaiting(fd4)
         if (FD_ISSET(fd6, readSet)) processWaiting(fd6)

         if (DEBUG) {
            println("   Pending ping count ${pending4Pings.size + pending6Pings.size}")
            println("   Pending actor count ${actor4Map.size + actor6Map.size}")
         }

         val isPending4reads = actor4Map.isNotEmpty()
         val isPending6reads = actor6Map.isNotEmpty()
         val isPending4writes = pending4Pings.isNotEmpty()
         val isPending6writes = pending6Pings.isNotEmpty()

         resetFdSets()
         if (isPending4reads) FD_SET(fd4, readSet)
         if (isPending6reads) FD_SET(fd6, readSet)
         if (isPending4writes) FD_SET(fd4, writeSet)
         if (isPending6writes) FD_SET(fd6, writeSet)

         if (isPending4reads || isPending6reads || isPending4writes || isPending6writes) {
            timeVal = oneHundredMs
            noopLoops = 0
         }
         else {
            if (noopLoops++ > 100) {
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

   fun isPending() = pending4Pings.isNotEmpty() || pending6Pings.isNotEmpty() || actor4Map.isNotEmpty() || actor6Map.isNotEmpty()

   private fun processPending(pendingPings : LinkedBlockingQueue<PingActor>, fd : Int) {
      while (pendingPings.isNotEmpty()) {
         val pingActor = pendingPings.peek()
         if (pingActor.sendIcmp(fd)) {
            pendingPings.take()
            actor4Map.put(pingActor.id, pingActor)
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

   private fun recvIcmp(fd : Int) : Boolean {
      val packetBuffer = ByteBuffer.allocateDirect(128)

      val msgHdr = posix.allocateMsgHdr()
      msgHdr.iov = arrayOf(packetBuffer)
      @Suppress("UNUSED_VARIABLE")
      val cmsgHdr = msgHdr.allocateControl(PingActor.SIZEOF_STRUCT_TIMEVAL)

      var cc = posix.recvmsg(fd, msgHdr, 0)
      if (cc > 0) {
         packetBuffer.limit(cc)
         if (DEBUG) dumpBuffer("Ping response", packetBuffer)

         val packetPointer = runtime.memoryManager.newPointer(packetBuffer)
         val ip = Ip()
         ip.useMemory(packetPointer)
         val headerLen = (ip.ip_vhl.get().toInt() and 0x0f shl 2)
         cc -= headerLen

         val icmp = Icmp()
         icmp.useMemory(packetPointer.slice(headerLen.toLong()))
         val id = ntohs(icmp.icmp_hun.ih_idseq.icd_id.get().toShort())
         if (icmp.icmp_type.get() != ICMP_ECHOREPLY) {
            if (DEBUG) println("   ^ Opps, not our response.")
         }
         else {
            //         var triptime : Double = 0.0
            //         if (cc - ICMP_MINLEN >= SIZEOF_STRUCT_TIMEVAL) {
            //            val tp = packetPointer.slice(headerLen.toLong() + icmp.icmp_dun.id_data.offset())
            //            val tv1 = posix.allocateTimeval()
            //            val tv1Pointer = runtime.memoryManager.newPointer(buf)
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

            val actor : PingActor? = actor4Map.remove(id)
            if (actor != null) {
               val usElapsed = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - actor.sendTimestamp)
               val triptime = (usElapsed.toDouble() / 1000.0)
               val seq = ntohs(icmp.icmp_hun.ih_idseq.icd_seq.shortValue())

               actor.onResponse(triptime, cc, seq.toInt())
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
}

val DEBUG = java.lang.Boolean.getBoolean("com.zaxxer.ping.debug")
