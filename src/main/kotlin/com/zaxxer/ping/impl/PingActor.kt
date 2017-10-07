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

@file:Suppress("PropertyName", "unused")

package com.zaxxer.ping.impl

import com.zaxxer.ping.DEBUG
import com.zaxxer.ping.PingResponseHandler
import com.zaxxer.ping.PingTarget
import jnr.ffi.Struct
import java.net.Inet4Address
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

const val IP_MAXPACKET = 65535
const val DEFAULT_DATALEN = 56

/**
 * Created by Brett Wooldridge on 2017/10/04.
 */
@Suppress("UNUSED_PARAMETER")
internal class PingActor(private val pingTarget : PingTarget,
                         private val handler : PingResponseHandler) {

   val id = (ID_SEQUENCE.getAndIncrement() % 0xffff).toShort()
   val isIPv4 : Boolean

   internal var sendTimestamp : Long = 0

   private val buf = ByteBuffer.allocateDirect(128)

   companion object {
      var sequence = 0.toShort()

      @JvmStatic
      val SIZEOF_STRUCT_IP = Struct.size(Ip())

      @JvmStatic
      val SIZEOF_STRUCT_TV32 = Struct.size(Tv32())

      @JvmStatic
      val SIZEOF_STRUCT_TIMEVAL = Struct.size(posix.allocateTimeval())

      @JvmStatic
      val ID_SEQUENCE = AtomicInteger()
   }

   private val sockAddr : SockAddr

   init {
      if (pingTarget.inetAddress is Inet4Address) {
         isIPv4 = true
         if (isBSD) {
            sockAddr = BSDSockAddr4(pingTarget.inetAddress)
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

   fun sendIcmp(fd : Int) : Boolean {
      buf.position(SIZEOF_STRUCT_IP)
      val outpackBuffer = buf.slice()
      buf.clear()

      val outpacketPointer = runtime.memoryManager.newPointer(outpackBuffer)

      val tmpBuffer = outpackBuffer.duplicate()
      tmpBuffer.position(ICMP_MINLEN + SIZEOF_STRUCT_TV32)
      for (i in SIZEOF_STRUCT_TV32..DEFAULT_DATALEN)
         tmpBuffer.put(i.toByte())

      val instant = Instant.now()
      val tv32 = Tv32()
      tv32.useMemory(outpacketPointer.slice(ICMP_MINLEN.toLong(), SIZEOF_STRUCT_TV32.toLong()))
      tv32.tv32_sec.set(instant.epochSecond)
      tv32.tv32_usec.set(instant.nano / 1000)

      val seq = sequence++
      val icmp = Icmp()
      icmp.useMemory(outpacketPointer)

      icmp.icmp_type.set(ICMP_ECHO)
      icmp.icmp_code.set(0)
      icmp.icmp_cksum.set(0)
      icmp.icmp_hun.ih_idseq.icd_seq.set(htons(seq))
      icmp.icmp_hun.ih_idseq.icd_id.set(htons(id))

      outpackBuffer.limit(ICMP_MINLEN + DEFAULT_DATALEN)

      val cksumBuffer = outpackBuffer.slice()
      val cksum = icmpCksum(cksumBuffer)
      icmp.icmp_cksum.set(htons(cksum.toShort()))

      // dumpBuffer(message = "Send buffer:", buffer = outpackBuffer)

      sendTimestamp = System.nanoTime()

      val rc = libc.sendto(fd, outpackBuffer, outpackBuffer.remaining(), 0, sockAddr, Struct.size(sockAddr))
      if (rc == outpackBuffer.remaining()) {
         if (DEBUG) println("   ICMP packet(seq=$seq) send to ${pingTarget.inetAddress} successful")
         return true
      }
      else {
         if (DEBUG) println("   Error: icmp sendto() to ${pingTarget.inetAddress} for seq=$seq returned $rc")
         return false
      }
   }

   fun onResponse(rtt : Double, bytes : Int, seq : Int) {
      handler.onResponse(pingTarget, rtt, bytes, seq)
   }
}
