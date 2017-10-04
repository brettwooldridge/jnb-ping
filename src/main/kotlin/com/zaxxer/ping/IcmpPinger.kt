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

import com.zaxxer.ping.impl.BsdPingActor
import com.zaxxer.ping.impl.NativeIcmpSocketChannel
import com.zaxxer.ping.impl.PingActor
import jnr.enxio.channels.NativeSelectorProvider
import java.io.IOException
import java.net.InetAddress
import java.nio.channels.SelectionKey
import java.nio.channels.spi.AbstractSelector

/**
 * Created by Brett Wooldridge on 2017/10/03.
 */
class IcmpPinger {

   private val selector : AbstractSelector

   interface PingResponseHandler {
      fun onResponse(rtt : Int)

      fun onTimeout()

      fun onError()
   }

   init {
      try {
         selector = NativeSelectorProvider.getInstance().openSelector()
      }
      catch (e : IOException) {
         throw RuntimeException(e)
      }

   }

   fun startSelector() {
      try {
         while (selector.select() > 0) {
            val selectionKeys = selector.selectedKeys()
            val iterator = selectionKeys.iterator()
            while (iterator.hasNext()) {
               val key = iterator.next()
               val pingActor = key.attachment() as PingActor
               if (pingActor.state == PingActor.STATE_XMIT) {
                  pingActor.sendIcmp()
               }
               else {
                  pingActor.recvIcmp()
               }

               iterator.remove()
            }
         }
      }
      catch (e : IOException) {
         throw RuntimeException(e)
      }

   }

//   fun stopSelector() {
//
//   }

   /**
    * https://stackoverflow.com/questions/8290046/icmp-sockets-linux
    */
   @Throws(IOException::class)
   fun ping(addr : InetAddress, handler : PingResponseHandler) {
      val pingChannel = NativeIcmpSocketChannel.create(addr)
      pingChannel.configureBlocking(false)
      pingChannel.register(selector, SelectionKey.OP_WRITE, BsdPingActor(selector, pingChannel, handler))
   }

//   private class XPingActor internal constructor(private val selector : AbstractSelector,
//                                                private val pingChannel : NativeIcmpSocketChannel,
//                                                private val handler : PingResponseHandler) {
//
//      internal fun sendIcmp() {
//         try {
//            val icmpHeader = if (Platform.getNativePlatform().isBSD) BsdIcmp() else BsdIcmp()
//            val bufPointer = runtime.memoryManager.newPointer(buf)
//            icmpHeader.useMemory(bufPointer)
//
//            icmpHeader.icmp_type.set(ICMP_ECHO)
//            icmpHeader.icmp_hun.ih_idseq.icd_id.set(1234) // arbitrary id ... really?
//            icmpHeader.icmp_hun.ih_idseq.icd_seq.set(htons(sequence.incrementAndGet().toShort()))
//
//            val icmpHdrSize = Struct.size(icmpHeader)
//
//            val datalen = "hello".length
//
//            val cc = ICMP_MINLEN + 0 /*phdr_len*/ + datalen;
//            icmpHeader.icmp_cksum.set(bsd_cksum(buf, cc))
//
//            buf.limit(icmpHdrSize + datalen)
//            buf.position(icmpHeader.icmp_dun.id_data.offset().toInt())
//            buf.put("hello".toByteArray())
//            buf.position(0)
//
//
//            val bytes = ByteArray(256)
//            buf.get(bytes, 0, icmpHdrSize + datalen)
//            println(HexDumpElf.dump(0, bytes, 0, 127))
//
//            buf.flip()
//
//            val rc = pingChannel.write(buf)
//            if (rc != 0) {
//               println("Non-zero return code from sendto(): $rc")
//            }
//
//            state = STATE_RECV
//            pingChannel.register(selector, SelectionKey.OP_READ, this)
//         }
//         catch (e : Error) {
//            handler.onError()
//         }
//
//      }
//
//      internal fun recvIcmp() {
//         try {
//            pingChannel.read(buf)
//            System.err.println(HexDumpElf.dump(0, buf.array(), buf.arrayOffset(), buf.limit()))
//         }
//         catch (e : IOException) {
//            handler.onError()
//         }
//
//      }
//   }

   companion object {
      internal val ICMP_ECHO = 8.toShort() /* on both Linux and Mac OS X */
   }
}
