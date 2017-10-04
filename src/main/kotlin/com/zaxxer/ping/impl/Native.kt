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

import jnr.constants.platform.AddressFamily.AF_INET
import jnr.constants.platform.AddressFamily.AF_INET6
import jnr.ffi.*
import jnr.ffi.annotations.In
import jnr.ffi.annotations.Out
import jnr.ffi.byref.IntByReference
import jnr.ffi.types.size_t
import jnr.ffi.types.socklen_t
import jnr.ffi.types.ssize_t
import jnr.posix.POSIXFactory
import java.nio.ByteBuffer

/**
 * Created by Brett Wooldridge on 2017/10/03.
 */

const val ICMP_MINLEN = 8

val runtime = jnr.ffi.Runtime.getSystemRuntime()
val platform = Platform.getNativePlatform()
val posix = POSIXFactory.getNativePOSIX()
val libc = LibraryLoader.create(LibC::class.java).load(platform.standardCLibraryName)

abstract class PingActor {
   companion object {
      @Volatile var sequence = 0.toShort()

      public val STATE_XMIT = 0
      public val STATE_RECV = 1
   }

   @Volatile internal var state = STATE_XMIT
      internal set

   abstract fun sendIcmp()

   abstract fun recvIcmp()
}

open class SockAddr : Struct(runtime)

class BSDSockAddr4 : SockAddr() {
   @field:JvmField val sin_len = Unsigned8()
   @field:JvmField val sin_family = Unsigned8()
   @field:JvmField val sin_port = Unsigned16()
   @field:JvmField val sin_addr = Unsigned32()
   @field:JvmField protected val sin_zero = Padding(NativeType.SCHAR, 8)
}

class BSDSockAddr6 : SockAddr() {
   @field:JvmField val sin_len = Unsigned8()
   @field:JvmField val sin_family = Unsigned8()
   @field:JvmField val sin_port = Unsigned16()
   @field:JvmField val flowinfo = Unsigned32()
   @field:JvmField val sin_addr = array(Array<Unsigned8>(4, {Unsigned8()}))
   @field:JvmField val sin_scope_id = Unsigned32()
}

class SockAddr4 : SockAddr() {
   @field:JvmField val sin_family = Unsigned16()
   @field:JvmField val sin_port = Unsigned16()
   @field:JvmField val sin_addr = Unsigned32()
   @field:JvmField protected val sin_zero = Padding(NativeType.SCHAR, 8)
}

class SockAddr6 : SockAddr() {
   @field:JvmField val sin_family = Unsigned16()
   @field:JvmField val sin_port = Unsigned16()
   @field:JvmField val flowinfo = Unsigned32()
   @field:JvmField val sin_addr = array(Array<Unsigned8>(16, {Unsigned8()}))
   @field:JvmField val sin_scope_id = Unsigned32()
}

/** Linux: netinetip_icmp.h
class echo_struct(enclosing : Struct) : Struct(runtime, enclosing) {
   val id = Unsigned16()
   val seq = Unsigned16()
}

class frag_struct(enclosing : Struct) : Struct(runtime, enclosing) {
   val unused = Unsigned16()
   val mtu = Unsigned16()
}

class icmp_union(enclosing : Struct) : Union(runtime) {
   val echo : echo_struct
   val frag : frag_struct

   init {
      this.echo = inner(echo_struct(enclosing))
      this.frag = inner(frag_struct(enclosing))
   }
}

class icmphdr : Struct(runtime) {
   val type = Unsigned8()      /* message type */
   val code = Unsigned8()      /* type sub-code */
   val checksum = Unsigned16()
   val un : icmp_union

   init {
      this.un = icmp_union(this)
   }
} */

open class Icmp : Struct(runtime)

val PF_INET = AF_INET.intValue()
val PF_INET6 = AF_INET6.intValue()
val SOCK_DGRAM = jnr.constants.platform.Sock.SOCK_DGRAM.intValue()

val IPPROTO_ICMP = 1
val IPPROTO_ICMPV6 = 58

val ICMP_ECHO = 8.toShort()
val ICMP_ECHOREPLY = 0.toShort()

val SOL_SOCKET = if (platform.isBSD) 0xffff else 1
val SO_TIMESTAMP = if (platform.isBSD) 0x0400 else 29
val SO_RCVBUF = if (platform.isBSD) 0x1002 else 8

interface LibC {
   fun socket(domain : Int, type : Int, protocol : Int) : Int

   fun setsockopt(fd : Int, level : Int, option : Int, @In value : IntByReference, @socklen_t len : Int) : Int

   fun inet_pton(af : Int, cp : String, buf : Pointer) : Int

   fun inet_network (@In cp : String) : Pointer

   fun close(fd : Int) : Int

   fun bind(fd : Int, addr : SockAddr, len : Int) : Int

   @ssize_t
   fun sendto (fd : Int, @In buf : ByteBuffer, @size_t len : Int, flags : Int, addr : SockAddr, @size_t addrLen : Int) : Int

   @ssize_t
   fun recvfrom(fd : Int, @Out buf : ByteBuffer, @size_t len : Int, flags : Int, addr : SockAddr, addrLen : Int) : Int

   @ssize_t
   fun read(fd : Int, @Out data : ByteBuffer, @size_t len : Int) : Int

   @ssize_t
   fun read(fd : Int, @Out data : ByteArray, @size_t len : Int) : Int

   @ssize_t
   fun write(fd : Int, @In data : ByteBuffer, @size_t len : Int) : Int

   fun strerror(error : Int) : String
}


fun htons(bytes : Short) : Short {
   return java.lang.Short.reverseBytes(bytes)
}

fun htonl(value : Long) : Long {
   return ((value and 0xff000000) shr 24) or
          ((value and 0x00ff0000) shr 8) or
          ((value and 0x0000ff00) shl 8) or
          ((value and 0x000000ff) shl 24)
}
