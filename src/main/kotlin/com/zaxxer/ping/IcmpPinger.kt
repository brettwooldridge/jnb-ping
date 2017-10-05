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

import com.zaxxer.ping.impl.IPPROTO_ICMP
import com.zaxxer.ping.impl.IPPROTO_ICMPV6
import com.zaxxer.ping.impl.NativeIcmpSocketChannel
import com.zaxxer.ping.impl.PF_INET
import com.zaxxer.ping.impl.PF_INET6
import com.zaxxer.ping.impl.PingActor
import com.zaxxer.ping.impl.PingTarget
import com.zaxxer.ping.impl.SOCK_DGRAM
import com.zaxxer.ping.impl.SOL_SOCKET
import com.zaxxer.ping.impl.SO_TIMESTAMP
import com.zaxxer.ping.impl.SockAddr6
import com.zaxxer.ping.impl.libc
import com.zaxxer.ping.impl.runtime
import jnr.enxio.channels.NativeSelectorProvider
import jnr.ffi.byref.IntByReference
import java.io.IOException
import java.net.SocketOptions.SO_REUSEADDR
import java.nio.channels.SelectionKey
import java.nio.channels.spi.AbstractSelector

/**
 * Created by Brett Wooldridge on 2017/10/03.
 */
class IcmpPinger {

   private val selector : AbstractSelector
   private val fd4 : Int
   private val fd6 : Int

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

      fd4 = libc.socket(PF_INET, SOCK_DGRAM, IPPROTO_ICMP)
      fd6 = libc.socket(PF_INET6, SOCK_DGRAM, IPPROTO_ICMPV6)

      val on = IntByReference(1)
      libc.setsockopt(fd4, SOL_SOCKET, SO_TIMESTAMP, on, on.nativeSize(runtime))
      libc.setsockopt(fd6, SOL_SOCKET, SO_TIMESTAMP, on, on.nativeSize(runtime))
      libc.setsockopt(fd4, SOL_SOCKET, 0x0200, on, on.nativeSize(runtime))

      val lowRcvWaterMark = IntByReference(84)
      libc.setsockopt(fd4, SOL_SOCKET, 0x1004, lowRcvWaterMark, lowRcvWaterMark.nativeSize(runtime))

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
               val pingActor = key.attachment() as PingActor
               if (pingActor.state == PingActor.STATE_XMIT) {
                  pingActor.sendIcmp()
               }
               else if (pingActor.state == PingActor.STATE_RECV) {
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

   fun stopSelector() {
      if (fd4 > 0) libc.close(fd4)
      if (fd6 > 0) libc.close(fd6)
   }

   /**
    * https://stackoverflow.com/questions/8290046/icmp-sockets-linux
    */
   @Throws(IOException::class)
   fun ping(pingTarget : PingTarget, handler : PingResponseHandler) {
      val isIPv6 = (pingTarget.sockAddr is SockAddr6)

      val pingChannel = NativeIcmpSocketChannel(pingTarget, if (isIPv6) fd6 else fd4)
      pingChannel.configureBlocking(false)
      pingChannel.register(selector, SelectionKey.OP_WRITE, PingActor(selector, pingChannel, handler))
   }
}
