@file:Suppress("PropertyName", "unused")

package com.zaxxer.ping.impl

import jnr.constants.platform.AddressFamily.AF_INET
import jnr.constants.platform.AddressFamily.AF_INET6
import jnr.ffi.LibraryLoader
import jnr.ffi.NativeType
import jnr.ffi.Platform.getNativePlatform
import jnr.ffi.Pointer
import jnr.ffi.Struct
import jnr.ffi.Union
import jnr.ffi.annotations.In
import jnr.ffi.annotations.Out
import jnr.ffi.types.size_t
import jnr.ffi.types.ssize_t
import java.nio.ByteBuffer


/**
 * Created by brettw on 2017/10/03.
 */

val runtime = jnr.ffi.Runtime.getSystemRuntime()

val libc = LibraryLoader.create(LibC::class.java).load(getNativePlatform().standardCLibraryName)

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

/*
 * Structure of an internet header, naked of options.
 */
// struct ip {
class BsdIp : Struct(runtime) {
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
class BsdHun : Union(runtime) {
   // u_char ih_pptr;			   /* ICMP_PARAMPROB */
   // struct in_addr ih_gwaddr;	/* ICMP_REDIRECT */
   val ih_pptr = Unsigned8()
   val ih_gwaddr = Unsigned32()

   // struct ih_idseq {
   //    n_short	icd_id;
   //    n_short	icd_seq;
   // } ih_idseq;
   class BsdIdSeq : Struct(runtime) {
      val icd_id = Unsigned16()
      val icd_seq = Unsigned16()
   }
   val ih_idseq = inner(BsdIdSeq())

   // int ih_void;
   val ih_void = Unsigned32()

   // /* ICMP_UNREACH_NEEDFRAG -- Path MTU Discovery (RFC1191) */
   // struct ih_pmtu {
   //    n_short ipm_void;
   //    n_short ipm_nextmtu;
   // } ih_pmtu;
   class BsdPmtu : Struct(runtime) {
      val ipm_void = Unsigned16()
      val ipm_nextmtu = Unsigned16()
   }
   val ih_pmtu = inner(BsdPmtu())

   // struct ih_rtradv {
   //    u_char irt_num_addrs;
   //    u_char irt_wpa;
   //    u_int16_t irt_lifetime;
   // } ih_rtradv;
   class BsdRtradv : Struct(runtime) {
      val irt_num_addrs = Unsigned8()
      val irt_wpa = Unsigned8()
      val irt_lifetime = Unsigned16()
   }
   val ih_rtradv = inner(BsdRtradv())
}

// union {
class BsdDun : Union(runtime) {
   // struct id_ts {
   //    n_time its_otime;
   //    n_time its_rtime;
   //    n_time its_ttime;
   // } id_ts;
   class BsdIdTs : Struct(runtime) {
      var its_otime = Unsigned32()
      var its_rtime = Unsigned32()
      var its_ttime = Unsigned32()
   }
   val id_ts = inner(BsdIdTs())

   // struct id_ip  {
   //    struct ip idi_ip;
   //    /* options and then 64 bits of data */
   // } id_ip;
   class BsdIdIp : Struct(runtime) {
      val idi_ip = inner(BsdIp())
   }
   val id_ip = inner(BsdIdIp())

   // struct icmp_ra_addr id_radv;
   val id_radv = inner(BsdRaAddr())

   // /*
   //  * Internal of an ICMP Router Advertisement
   //  */
   // struct icmp_ra_addr {
   //    u_int32_t ira_addr;
   //    u_int32_t ira_preference;
   // };
   class BsdRaAddr : Struct(runtime) {
      val ira_addr = Unsigned32()
      val ira_preference = Unsigned32()
   }

   // u_int32_t id_mask;
   val id_mask = Unsigned32()

   // char id_data[1];
   val id_data = Unsigned8()
}

open class Icmp : Struct(runtime)

/*
 * Structure of an icmp header.
 */
// struct icmp {
class BsdIcmp : Icmp() {
   // u_char	icmp_type;		/* type of message, see below */
   // u_char	icmp_code;		/* type sub code */
   // u_short	icmp_cksum;		/* ones complement cksum of struct */
   val icmp_type = Unsigned8()
   val icmp_code = Unsigned8()
   val icmp_cksum = Unsigned16()

   // union {
   val icmp_hun = inner(BsdHun())
   // } icmp_hun;

   // union {
   val icmp_dun = inner(BsdDun())
   // } icmp_dun
}



interface LibC {
   fun socket(domain : Int, type : Int, protocol : Int) : Int

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

   companion object {
      val PF_INET = AF_INET.intValue()
      val PF_INET6 = AF_INET6.intValue()
      val SOCK_DGRAM = jnr.constants.platform.Sock.SOCK_DGRAM.intValue()
      val ICMP_MINLEN = 8
   }
}


fun htons(bytes : Short) : Short {
   return java.lang.Short.reverseBytes(bytes)
}


// See https://opensource.apple.com/source/network_cmds/network_cmds-329.2/ping.tproj/ping.c
fun bsd_cksum(buf : ByteBuffer, len : Int) : Int {
   var sum = 0

   buf.mark()
   var nleft = len

   while (nleft > 1) {
      sum += (buf.get().toInt() shl 8) + (buf.get())   // read 16-bit unsigned
      nleft -= 2
   }

   if (nleft == 1) {
      sum += buf.get().toShort()
   }

   buf.reset()

   sum = (sum shr 16) + (sum and 0xffff)
   sum += (sum shr 16)
   return (sum.inv() and 0xffff)
}
