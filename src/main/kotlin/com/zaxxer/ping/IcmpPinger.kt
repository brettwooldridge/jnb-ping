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
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap
import jnr.constants.platform.Errno
import jnr.enxio.channels.NativeSelectorProvider
import jnr.ffi.byref.IntByReference
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.spi.AbstractSelector
import java.util.concurrent.TimeUnit

/**
 * Created by Brett Wooldridge on 2017/10/03.
 */
class IcmpPinger {

   private val selector : AbstractSelector
   private val ipv4Channel : NativeIcmpSocketChannel
   private var ipv6Channel : NativeIcmpSocketChannel
   private val actorMap = Short2ObjectOpenHashMap<PingActor>()

   interface PingResponseHandler {
      fun onResponse(rtt : Double, bytes : Int, seq : Int)

      fun onTimeout()

      fun onError(message : String)
   }

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

      libc.setsockopt(fd4, SOL_SOCKET, 0x0200, on, on.nativeSize(runtime))
      libc.setsockopt(fd6, SOL_SOCKET, 0x0200, on, on.nativeSize(runtime))

      val lowRcvWaterMark = IntByReference(84)
      libc.setsockopt(fd4, SOL_SOCKET, 0x1004, lowRcvWaterMark, lowRcvWaterMark.nativeSize(runtime))
      libc.setsockopt(fd6, SOL_SOCKET, 0x1004, lowRcvWaterMark, lowRcvWaterMark.nativeSize(runtime))

      ipv4Channel = NativeIcmpSocketChannel(fd4)
      ipv4Channel.configureBlocking(false)

      ipv6Channel = NativeIcmpSocketChannel(fd6)
      ipv6Channel.configureBlocking(false)

      // Better handled by altering the OS default rcvbuf size
      // val rcvbuf = IntByReference(2048)
      // libc.setsockopt(fd, SOL_SOCKET, SO_RCVBUF, rcvbuf, rcvbuf.nativeSize(runtime))
   }

   fun startSelector() {
      try {
         while (selector.select() > 0) {
            val selectionKeys = selector.selectedKeys()
            val iterator = selectionKeys.iterator()
            while (iterator.hasNext()) {
               val key = iterator.next()

               val attachement = key.attachment()
               if (attachement is PingActor) {
                  val pingActor = key.attachment() as PingActor
                  pingActor.sendIcmp()
               }
               else {
                  // read and lookup actor id in map
                  recvIcmp((attachement as NativeIcmpSocketChannel).fd)
               }

               iterator.remove()
            }

            ipv4Channel.register(selector, SelectionKey.OP_READ, this)
         }
      }
      catch (e : IOException) {
         throw RuntimeException(e)
      }

   }

   fun stopSelector() {
      if (ipv4Channel.fd > 0) libc.close(ipv4Channel.fd)
      if (ipv6Channel.fd > 0) libc.close(ipv6Channel.fd)
   }

   /**
    * https://stackoverflow.com/questions/8290046/icmp-sockets-linux
    */
   @Throws(IOException::class)
   fun ping(pingTarget : PingTarget, handler : PingResponseHandler) {
      val isIPv6 = (pingTarget.sockAddr is SockAddr6)
      val channel = if (isIPv6) ipv6Channel else ipv4Channel

      val actor = PingActor(selector, channel.fd, pingTarget.sockAddr, handler)
      actorMap.put(actor.id, actor)

      //val pingChannel = NativeIcmpSocketChannel(pingTarget, if (isIPv6) fd6 else fd4)
      // pingChannel.configureBlocking(false)

      channel.register(selector, SelectionKey.OP_WRITE, actor)
   }

   private fun recvIcmp(fd : Int) {
      val packetBuffer = ByteBuffer.allocateDirect(128)

      val msgHdr = posix.allocateMsgHdr()
      msgHdr.iov = arrayOf(packetBuffer)
      @Suppress("UNUSED_VARIABLE")
      val cmsgHdr = msgHdr.allocateControl(PingActor.SIZEOF_STRUCT_TIMEVAL)

      var cc = posix.recvmsg(fd, msgHdr, 0)
      if (cc > 0) {
         packetBuffer.limit(cc)
//         dumpBuffer("Ping response", packetBuffer)

         val packetPointer = runtime.memoryManager.newPointer(packetBuffer)
         val ip = Ip()
         ip.useMemory(packetPointer)
         val headerLen = (ip.ip_vhl.get().toInt() and 0x0f shl 2)
         cc -= headerLen

         val icmp = Icmp()
         icmp.useMemory(packetPointer.slice(headerLen.toLong()))
         val id = ntohs(icmp.icmp_hun.ih_idseq.icd_id.get().toShort())
         if (icmp.icmp_type.get() != ICMP_ECHOREPLY) {
            return // 'Twas not our ECHO
         }

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

         val actor: PingActor? = actorMap.get(id)
         if (actor != null) {
            val usElapsed = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - actor.sendTimestamp)
            val triptime = (usElapsed.toDouble() / 1000.0)
            val seq = ntohs(icmp.icmp_hun.ih_idseq.icd_seq.shortValue())

            actor.handler.onResponse(triptime, cc, seq.toInt())
         }
      }
      else {
         val errno = posix.errno()
         if (posix.errno() != Errno.EINTR.intValue()) {
            println("Error code $errno returned from pingChannel.read()")
         }
      }
   }
}
