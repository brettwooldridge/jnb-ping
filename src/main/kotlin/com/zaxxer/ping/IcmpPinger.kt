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

import com.zaxxer.ping.impl.ICMP_ECHOREPLY
import com.zaxxer.ping.impl.IPPROTO_ICMP
import com.zaxxer.ping.impl.IPPROTO_ICMPV6
import com.zaxxer.ping.impl.Icmp
import com.zaxxer.ping.impl.Ip
import com.zaxxer.ping.impl.NativeIcmpSocketChannel
import com.zaxxer.ping.impl.PF_INET
import com.zaxxer.ping.impl.PF_INET6
import com.zaxxer.ping.impl.PingActor
import com.zaxxer.ping.impl.SOCK_DGRAM
import com.zaxxer.ping.impl.SOL_SOCKET
import com.zaxxer.ping.impl.SO_REUSEPORT
import com.zaxxer.ping.impl.SO_TIMESTAMP
import com.zaxxer.ping.impl.libc
import com.zaxxer.ping.impl.ntohs
import com.zaxxer.ping.impl.posix
import com.zaxxer.ping.impl.runtime
import com.zaxxer.ping.impl.util.dumpBuffer
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap
import jnr.constants.platform.Errno
import jnr.enxio.channels.NativeSelectorProvider
import jnr.ffi.byref.IntByReference
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder

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

class IcmpPinger {

   private val selector : Selector
   private val ipv4Channel : NativeIcmpSocketChannel
   private var ipv6Channel : NativeIcmpSocketChannel

   private val actorMap = Short2ObjectOpenHashMap<PingActor>()
   private val pendingPings = LinkedBlockingQueue<PingActor>()
   private val pendingResponseCount = LongAdder()

   private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
   init {
      try {
         selector = NativeSelectorProvider.getInstance().openSelector()
      }
      catch (e : IOException) {
         throw RuntimeException(e)
      }

      val fd4 = libc.socket(PF_INET, SOCK_DGRAM, IPPROTO_ICMP)
      val fd6 = libc.socket(PF_INET6, SOCK_DGRAM, IPPROTO_ICMPV6)

      val on = IntByReference(1)
      libc.setsockopt(fd4, SOL_SOCKET, SO_TIMESTAMP, on, on.nativeSize(runtime))
      libc.setsockopt(fd6, SOL_SOCKET, SO_TIMESTAMP, on, on.nativeSize(runtime))

      libc.setsockopt(fd4, SOL_SOCKET, SO_REUSEPORT, on, on.nativeSize(runtime))
      libc.setsockopt(fd6, SOL_SOCKET, SO_REUSEPORT, on, on.nativeSize(runtime))

      ipv4Channel = NativeIcmpSocketChannel(fd4)
      ipv4Channel.configureBlocking(false)

      ipv6Channel = NativeIcmpSocketChannel(fd6)
      ipv6Channel.configureBlocking(false)

      // Better handled by altering the OS default rcvbuf size
      // val rcvbuf = IntByReference(2048)
      // libc.setsockopt(fd, SOL_SOCKET, SO_RCVBUF, rcvbuf, rcvbuf.nativeSize(runtime))
   }

   @Throws(IOException::class)
   fun ping(pingTarget : PingTarget, handler : PingResponseHandler) {
      val isIPv4 = (pingTarget.inetAddress is Inet4Address)
      val channel = if (isIPv4) ipv4Channel else ipv6Channel

      val actor = PingActor(selector, channel.fd, pingTarget, handler)
      pendingPings.offer(actor)

      // selector.wakeup()
      channel.register(selector, SelectionKey.OP_WRITE or SelectionKey.OP_READ, channel)
   }

   fun runSelector() {
      try {
         while (selector.isOpen) {
            val selectedCount = selector.select()
            if (DEBUG) println("   ${dateFormat.format(Date())} ${if (selectedCount > 0) "triggered" else "awoken"}")

            if (selectedCount > 0) {
               val selectionKeys = selector.selectedKeys()
               val iterator = selectionKeys.iterator()
               while (iterator.hasNext()) {
                  val key = iterator.next()

                  val readyOps = key.readyOps()
                  if (readyOps and SelectionKey.OP_WRITE != 0) {
                     while (pendingPings.isNotEmpty()) {
                        val pingActor = pendingPings.take()
                        pingActor.sendIcmp()
                        actorMap.put(pingActor.id, pingActor)
                        pendingResponseCount.increment()
                     }
                  }

                  if (readyOps and SelectionKey.OP_READ != 0) {
                     do {
                        // read and lookup actor id in map
                        val more = recvIcmp((key.attachment() as NativeIcmpSocketChannel).fd)
                     } while (more)
                  }

                  iterator.remove()
               }
            }

            if (DEBUG) {
               println("   Pending ping count ${pendingPings.size}")
               println("   Pending actor count ${pendingResponseCount.sum()}")
            }

            try {
               val interestedOps = (if (pendingPings.isNotEmpty()) SelectionKey.OP_WRITE else 0) or SelectionKey.OP_READ
               if (DEBUG) println("   ${dateFormat.format(Date())} Registering interest in ops ${Integer.toBinaryString(interestedOps)}")
               ipv4Channel.register(selector, interestedOps, ipv4Channel)
            }
            catch (e : ClosedChannelException) {
               // ignore, we'll exit the loop at the top
            }
         }
      }
      catch (e : IOException) {
         throw RuntimeException(e)
      }

   }

   fun stopSelector() {
      selector.close()

      if (ipv4Channel.fd > 0) libc.close(ipv4Channel.fd)
      if (ipv6Channel.fd > 0) libc.close(ipv6Channel.fd)
   }

   fun isPending() = pendingPings.isNotEmpty() || pendingResponseCount.sum() > 0

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

            val actor : PingActor? = actorMap.remove(id)
            if (actor != null) {
               val usElapsed = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - actor.sendTimestamp)
               val triptime = (usElapsed.toDouble() / 1000.0)
               val seq = ntohs(icmp.icmp_hun.ih_idseq.icd_seq.shortValue())

               actor.onResponse(triptime, cc, seq.toInt())
               pendingResponseCount.decrement()
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
}

val DEBUG = java.lang.Boolean.getBoolean("com.zaxxer.ping.debug")
