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

/**
 * Created by Brett Wooldridge on 2017/10/03.
 */

@file:Suppress("PropertyName", "unused", "ProtectedInFinal", "FunctionName", "ClassName")

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
import jnr.posix.POSIX
import jnr.posix.POSIXFactory
import jnr.posix.Timeval
import java.net.Inet4Address
import java.nio.ByteBuffer

val runtime : jnr.ffi.Runtime = jnr.ffi.Runtime.getSystemRuntime()!!
val platform : Platform = Platform.getNativePlatform()
val isBSD = platform.isBSD
val posix : POSIX = POSIXFactory.getNativePOSIX()
val libc : LibC = LibraryLoader.create(LibC::class.java).load(platform.standardCLibraryName)

open class SockAddr : Struct(runtime)
open class SockAddr6 : SockAddr()

class BSDSockAddr4(address : Inet4Address) : SockAddr() {
   @field:JvmField val sin_len = Unsigned8()
   @field:JvmField val sin_family = Unsigned8()
   @field:JvmField val sin_port = Unsigned16()
   @field:JvmField val sin_addr = Unsigned32()
   @field:JvmField protected val sin_zero = Padding(NativeType.SCHAR, 8)

   init {
      val bytes = address.address
      val inAddr : Int = (bytes[3].toInt() and 0xff shl 24) or (bytes[2].toInt() and 0xff shl 16) or (bytes[1].toInt() and 0xff shl 8) or (bytes[0].toInt() and 0xff)
      sin_addr.set(inAddr)
      sin_family.set(PF_INET)
   }
}

class BSDSockAddr6 : SockAddr6() {
   @field:JvmField val sin_len = Unsigned8()
   @field:JvmField val sin_family = Unsigned8()
   @field:JvmField val sin_port = Unsigned16()
   @field:JvmField val flowinfo = Unsigned32()
   @field:JvmField val sin_addr : Array<out Unsigned8> = array(Array(4, {Unsigned8()}))
   @field:JvmField val sin_scope_id = Unsigned32()

   init {
      sin_family.set(PF_INET6)
   }
}

class LinuxSockAddr4(address : Inet4Address) : SockAddr() {
   @field:JvmField val sin_family = Unsigned16()
   @field:JvmField val sin_port = Unsigned16()
   @field:JvmField val sin_addr = Unsigned32()
   @field:JvmField protected val sin_data = Padding(NativeType.SCHAR, 8)

   init {
      val bytes = address.address
      val inAddr : Int = (bytes[3].toInt() and 0xff shl 24) or (bytes[2].toInt() and 0xff shl 16) or (bytes[1].toInt() and 0xff shl 8) or (bytes[0].toInt() and 0xff)
      sin_addr.set(inAddr)
      sin_family.set(PF_INET)
   }
}

class LinuxSockAddr6 : SockAddr6() {
   @field:JvmField val sin_family = Unsigned16()
   @field:JvmField val sin_port = Unsigned16()
   @field:JvmField val flowinfo = Unsigned32()
   @field:JvmField val sin_addr : Array<out Unsigned8> = array(Array(16, {Unsigned8()}))
   @field:JvmField val sin_scope_id = Unsigned32()
}

const val ICMP_MINLEN = 8
const val IP_MAXPACKET = 65535
const val DEFAULT_DATALEN = 56
const val SEND_PACKET_SIZE = ICMP_MINLEN + DEFAULT_DATALEN

val PF_INET = AF_INET.intValue()
val PF_INET6 = AF_INET6.intValue()
val SOCK_DGRAM = jnr.constants.platform.Sock.SOCK_DGRAM.intValue()

val IPPROTO_ICMP = 1
val IPPROTO_ICMPV6 = 58

val ICMP_ECHO = 8.toShort()
val ICMP_ECHOREPLY = 0.toShort()

val SOL_SOCKET = if (isBSD) 0xffff else 1

val SO_TIMESTAMP = if (isBSD) 0x0400 else 29
val SO_RCVBUF = if (isBSD) 0x1002 else 8
val SO_REUSEPORT = if (isBSD) 0x0200 else jnr.constants.platform.SocketOption.SO_REUSEADDR.intValue()
val SCM_TIMESTAMP = if (isBSD) 0x02 else SO_TIMESTAMP

val F_GETFL = jnr.constants.platform.Fcntl.F_GETFL.intValue()
val F_SETFL = jnr.constants.platform.Fcntl.F_SETFL.intValue()
val O_NONBLOCK = jnr.constants.platform.OpenFlags.O_NONBLOCK.intValue()

val SIZEOF_STRUCT_IP = Struct.size(Ip())
val SIZEOF_STRUCT_TV32 = Struct.size(Tv32())
val SIZEOF_STRUCT_TIMEVAL = Struct.size(posix.allocateTimeval())

interface LibC {
   fun socket(domain : Int, type : Int, protocol : Int) : Int

   fun setsockopt(fd : Int, level : Int, option : Int, @In value : IntByReference, @socklen_t len : Int) : Int

   fun fcntl(fd : Int, cmd : Int, data : Int) : Int

   fun pipe(@Out fds : IntArray) : Int

   fun select(fd : Int, read_set : Pointer, write_set : Pointer, @In @Out error_set : Fd_set?, @In @Out timeval : Timeval?) : Int

   fun inet_pton(af : Int, cp : String, buf : Pointer) : Int

   fun inet_network (@In cp : String) : Pointer

   fun close(fd : Int) : Int

   @ssize_t fun sendto (fd : Int, @In buf : Pointer, @size_t len : Int, flags : Int, addr : SockAddr, @size_t addrLen : Int) : Int

   @ssize_t fun recvfrom(fd : Int, @Out buf : ByteBuffer, @size_t len : Int, flags : Int, addr : SockAddr, addrLen : Int) : Int

   @ssize_t fun read(fd : Int, @Out data : ByteArray, @size_t size : Long) : Int

   @ssize_t fun write(fd : Int, @In data : ByteArray, @size_t size : Long) : Int
}

fun htons(s : Short) = java.lang.Short.reverseBytes(s)

fun ntohs(s : Short) = java.lang.Short.reverseBytes(s)

fun htonl(value : Long) : Long {
   return ((value and 0xff000000) shr 24) or
          ((value and 0x00ff0000) shr 8) or
          ((value and 0x0000ff00) shl 8) or
          ((value and 0x000000ff) shl 24)
}

// #define __DARWIN_FD_SETSIZE   1024
// #define __DARWIN_NBBY            8                                /* bits in a byte */
// #define __DARWIN_NFDBITS      (sizeof(__int32_t) * __DARWIN_NBBY) /* bits per mask */
//
// --> __DARWIN_NFDBITS = 4 * 8 = 32
// --> __DARWIN_howmany = __DARWIN_FD_SETSIZE / __DARWIN_NFDBITS = 1024 / 32 = 32
//
// typedef struct fd_set {
//    __int32_t  fds_bits[__DARWIN_howmany(__DARWIN_FD_SETSIZE, __DARWIN_NFDBITS)];
// } fd_set;
class Fd_set : Struct(runtime) {

   @field:JvmField val fds_bits : Array<out Signed32> = Array(32, { Signed32() })

   init {
      val memory = this.runtime.memoryManager.allocateDirect(size(this))
      this.useMemory(memory)
   }
}

fun FD_SET(fd : Int, fd_set : Fd_set) {
   // ((fd_set*)->fds_bits[ (unsigned long)__fd / 32 ] |= ((__int32_t) (((unsigned long) 1) << ((unsigned long) __fd % 32))))
   val ndx = fd / 32
   val currvalue = fd_set.fds_bits[ndx].get()
   val orValue = (1L shl (fd % 32)).toInt()
   val newValue = currvalue or orValue
   fd_set.fds_bits[ndx].set(newValue)
}

fun FD_ISSET(fd : Int, fd_set : Fd_set) : Boolean {
   // return (_p->fds_bits[(unsigned long)_n/__DARWIN_NFDBITS] & ((__int32_t)(((unsigned long)1)<<((unsigned long)_n % __DARWIN_NFDBITS))))
   val ndx = fd / 32
   val currvalue = fd_set.fds_bits[ndx].get()
   val andValue = (1L shl (fd % 32)).toInt()

   return currvalue and andValue != 0
}

fun FD_ZERO(fd_set : Fd_set) {
   for (bits in fd_set.fds_bits) {
      bits.set(0L)
   }
}

/*****************************************************************************
 *                              ICMP Definitions
 */



// /*
//  * Structure of an internet header, naked of options.
//  */
// struct ip {
class Ip : Struct(runtime) {
   // u_char	ip_vhl;			/* version << 4 | header length >> 2 */
   val ip_vhl = Unsigned8()
   // u_char	ip_tos;			/* type of service */
   // u_short	ip_len;			/* total length */
   // u_short	ip_id;			/* identification */
   // u_short	ip_off;			/* fragment offset field */
   val ip_tos = Unsigned8()
   val ip_len = Unsigned16()
   val ip_id = Unsigned16()
   val ip_off = Unsigned16()
   // u_char	ip_ttl;			/* time to live */
   // u_char	ip_p;			/* protocol */
   // u_short	ip_sum;			/* checksum */
   val ip_ttl = Unsigned8()
   val ip_p = Unsigned8()
   val ip_sum = Unsigned16()
   // struct in_addr  ip_src, ip_dst; /* source and dest address */
   val ip_src = Unsigned32()
   val ip_dst = Unsigned32()
}

// union {
class Hun : Union(runtime) {
   // u_char ih_pptr;			   /* ICMP_PARAMPROB */
   // struct in_addr ih_gwaddr;	/* ICMP_REDIRECT */
   val ih_pptr = Unsigned8()
   val ih_gwaddr = Unsigned32()
   // struct ih_idseq {
   //    n_short	icd_id;
   //    n_short	icd_seq;
   // } ih_idseq;
   class IdSeq : Struct(runtime) {
      val icd_id = Unsigned16()
      val icd_seq = Unsigned16()
   }
   val ih_idseq : IdSeq = inner(IdSeq())
   // int ih_void;
   val ih_void = Unsigned32()
   // /* ICMP_UNREACH_NEEDFRAG -- Path MTU Discovery (RFC1191) */
   // struct ih_pmtu {
   //    n_short ipm_void;
   //    n_short ipm_nextmtu;
   // } ih_pmtu;
   class Pmtu : Struct(runtime) {
      val ipm_void = Unsigned16()
      val ipm_nextmtu = Unsigned16()
   }
   val ih_pmtu : Pmtu = inner(Pmtu())
   // struct ih_rtradv {
   //    u_char irt_num_addrs;
   //    u_char irt_wpa;
   //    u_int16_t irt_lifetime;
   // } ih_rtradv;
   class Rtradv : Struct(runtime) {
      val irt_num_addrs = Unsigned8()
      val irt_wpa = Unsigned8()
      val irt_lifetime = Unsigned16()
   }
   val ih_rtradv : Rtradv = inner(Rtradv())
}

// union {
class Dun : Union(runtime) {
   // struct id_ts {
   //    n_time its_otime;
   //    n_time its_rtime;
   //    n_time its_ttime;
   // } id_ts;
   class IdTs : Struct(runtime) {
      var its_otime = Unsigned32()
      var its_rtime = Unsigned32()
      var its_ttime = Unsigned32()
   }
   val id_ts : IdTs = inner(IdTs())
   // struct id_ip  {
   //    struct ip idi_ip;
   //    /* options and then 64 bits of data */
   // } id_ip;
   class IdIp : Struct(runtime) {
      val idi_ip : Ip = inner(Ip())
   }
   val id_ip : IdIp = inner(IdIp())
   // struct icmp_ra_addr id_radv;
   val id_radv : RaAddr = inner(RaAddr())
   // /*
   //  * Internal of an ICMP Router Advertisement
   //  */
   // struct icmp_ra_addr {
   //    u_int32_t ira_addr;
   //    u_int32_t ira_preference;
   // };
   class RaAddr : Struct(runtime) {
      val ira_addr = Unsigned32()
      val ira_preference = Unsigned32()
   }
   // u_int32_t id_mask;
   val id_mask = Unsigned32()
   // char id_data[1];
   val id_data = Unsigned8()
}

// /*
//  * Structure of an icmp header.
//  */
// struct icmp {
class Icmp : Struct(runtime) {
   // u_char	icmp_type;		/* type of message, see below */
   // u_char	icmp_code;		/* type sub code */
   // u_short	icmpCksum;		/* ones complement cksum of struct */
   val icmp_type = Unsigned8()
   val icmp_code = Unsigned8()
   val icmp_cksum = Unsigned16()
   // union {
   val icmp_hun : Hun = inner(Hun())
   // } icmp_hun;
   // union {
   val icmp_dun : Dun = inner(Dun())
   // } icmp_dun
}


// struct tv32 {
//     u_int32_t tv32_sec;
//     u_int32_t tv32_usec;
// };
class Tv32 : Struct(runtime) {
   val tv32_sec = Unsigned32()
   val tv32_usec = Unsigned32()
}

// See https://opensource.apple.com/source/network_cmds/network_cmds-329.2/ping.tproj/ping.c
fun icmpCksum(buf : Pointer, len : Int) : Int {
   var sum = 0

   var nleft = len

   var offset = 0L
   while (nleft > 1) {
      // sum += ((buf.get().toInt() and 0xff) or ((buf.get().toInt() and 0xff) shl 8))  // read 16-bit unsigned
      sum += ((buf.getByte(offset).toInt() and 0xff) or ((buf.getByte(offset + 1).toInt() and 0xff) shl 8))
      nleft -= 2
      offset += 2
   }

   if (nleft == 1) {
      sum += buf.getByte(offset).toShort()
   }

   sum = (sum shr 16) + (sum and 0xffff)
   sum += (sum shr 16)
   return (sum.inv() and 0xffff)
}
