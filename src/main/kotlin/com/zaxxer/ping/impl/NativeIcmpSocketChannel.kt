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

package com.zaxxer.ping.impl

import jnr.enxio.channels.NativeSocketChannel
import jnr.ffi.Platform
import jnr.ffi.Struct
import jnr.ffi.byref.IntByReference
import jnr.ffi.provider.ParameterFlags
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Created by Brett Wooldridge on 2017/10/03.
 */
@Suppress("unused")
class NativeIcmpSocketChannel(private val inetAddress : InetAddress, fd : Int) : NativeSocketChannel(fd) {

   override fun write(src : ByteBuffer) : Int {
      begin()
      try {
         val sockAddr : SockAddr
         if (inetAddress is Inet4Address) {
            if (Platform.getNativePlatform().isBSD) {
               sockAddr = BSDSockAddr4()
               val memory = Struct.getMemory(sockAddr, ParameterFlags.DIRECT)
               sockAddr.useMemory(memory)

               sockAddr.sin_family.set(PF_INET.toShort())
               val rc = libc.inet_pton(PF_INET, inetAddress.hostAddress, memory.slice(sockAddr.sin_addr.offset()))
               if (rc != 1) {
                  println("Error return code from inet_pton(), should be 1 but is $rc")
               }
            }
            else {
               sockAddr = SockAddr4()
               sockAddr.sin_family.set(htons(PF_INET.toShort()))
               libc.inet_pton(PF_INET, inetAddress.hostAddress, Struct.getMemory(sockAddr).getPointer(sockAddr.sin_addr.offset()))
            }
         }
         else {  // IPv6
            error("Not implemented")
         }

         return libc.sendto(fd, src, src.limit(), 0, sockAddr, Struct.size(sockAddr))
      }
      finally {
         end(true)
      }
   }

//   override fun read(dst : ByteBuffer?) : Int {
//      return super.read(dst)
//   }
}
