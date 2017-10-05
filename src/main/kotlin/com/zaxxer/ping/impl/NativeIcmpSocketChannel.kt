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
import jnr.ffi.Struct
import jnr.posix.MsgHdr
import java.nio.ByteBuffer

/**
 * Created by Brett Wooldridge on 2017/10/03.
 */
@Suppress("unused")
class NativeIcmpSocketChannel(internal val pingTarget : PingTarget, fd : Int) : NativeSocketChannel(fd) {

   override fun write(src : ByteBuffer) : Int {
      try {
         begin()

         return libc.sendto(fd, src, src.limit(), 0, pingTarget.sockAddr, Struct.size(pingTarget.sockAddr))
      }
      finally {
         end(true)
      }
   }

   fun read(msgHdr : MsgHdr) : Int {
      return posix.recvmsg(fd, msgHdr, 0)
   }

   override fun read(dst : ByteBuffer?) : Int {
      error("read(ByteBuffer) should not be called, only read(MsgHdr) is supported")
   }
}
