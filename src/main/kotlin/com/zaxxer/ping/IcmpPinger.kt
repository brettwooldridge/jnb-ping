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
import jnr.enxio.channels.NativeSelectorProvider
import jnr.ffi.byref.IntByReference
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
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

      fd4 = libc.socket(PF_INET, SOCK_DGRAM, IPPROTO_ICMP)
      fd6 = libc.socket(PF_INET6, SOCK_DGRAM, IPPROTO_ICMPV6)

      val on = IntByReference(1)
      libc.setsockopt(fd4, SOL_SOCKET, SO_TIMESTAMP, on, on.nativeSize(runtime))
      libc.setsockopt(fd6, SOL_SOCKET, SO_TIMESTAMP, on, on.nativeSize(runtime))

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

   fun stopSelector() {
      if (fd4 > 0) libc.close(fd4)
      if (fd6 > 0) libc.close(fd6)
   }

   /**
    * https://stackoverflow.com/questions/8290046/icmp-sockets-linux
    */
   @Throws(IOException::class)
   fun ping(addr : InetAddress, handler : PingResponseHandler) {
      val isIPv4 = (addr is Inet4Address)

      val pingChannel = NativeIcmpSocketChannel(addr, if (isIPv4) fd4 else fd6)
      pingChannel.configureBlocking(false)
      pingChannel.register(selector, SelectionKey.OP_WRITE, BsdPingActor(selector, pingChannel, handler))
   }
}
