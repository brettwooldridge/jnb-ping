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

import com.zaxxer.ping.IcmpPinger
import com.zaxxer.ping.impl.util.HexDumpElf
import jnr.ffi.Struct
import jnr.ffi.Union
import java.nio.ByteBuffer
import java.nio.channels.spi.AbstractSelector
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

const val IP_MAXPACKET = 65535
const val DEFAULT_DATALEN = 56

/**
 * Created by Brett Wooldridge on 2017/10/04.
 */
@Suppress("UNUSED_PARAMETER")
internal class PingActor(private val selector : AbstractSelector,
                         private val fd : Int,
                         private val sockAddr : SockAddr,
                         val handler : IcmpPinger.PingResponseHandler) {

   val id = (ID_SEQUENCE.getAndIncrement() % 0xffff).toShort()
   private val buf = ByteBuffer.allocateDirect(128)
   var sendTimestamp : Long = 0

   internal var state = STATE_XMIT

   companion object {
      var sequence = 0.toShort()

      val STATE_XMIT = 0
      val STATE_RECV = 1

      @JvmStatic
      val SIZEOF_STRUCT_IP = Struct.size(Ip())

      @JvmStatic
      val SIZEOF_STRUCT_TV32 = Struct.size(Tv32())

      @JvmStatic
      val SIZEOF_STRUCT_TIMEVAL = Struct.size(posix.allocateTimeval())

      @JvmStatic
      val ID_SEQUENCE = AtomicInteger()
   }

   fun sendIcmp() {
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

      val icmp = Icmp()
      icmp.useMemory(outpacketPointer)

      icmp.icmp_type.set(ICMP_ECHO)
      icmp.icmp_code.set(0)
      icmp.icmp_cksum.set(0)
      icmp.icmp_hun.ih_idseq.icd_seq.set(htons(sequence++))
      icmp.icmp_hun.ih_idseq.icd_id.set(htons(id))

      outpackBuffer.limit(ICMP_MINLEN + DEFAULT_DATALEN)

      val cksumBuffer = outpackBuffer.slice()
      val cksum = icmpCksum(cksumBuffer)
      icmp.icmp_cksum.set(htons(cksum.toShort()))

//      dumpBuffer(message = "Final buffer", buffer = outpackBuffer)

      sendTimestamp = System.nanoTime()

      val rc = libc.sendto(fd, outpackBuffer, outpackBuffer.remaining(), 0, sockAddr, Struct.size(sockAddr))
      if (rc == outpackBuffer.remaining()) {
         // ok
      }
      else {
         error("sendto() returned $rc")
      }
   }

   private fun dumpBuffer(message : String, buffer : ByteBuffer) {
      val bytes = ByteArray(buffer.remaining())
      val tmpBuffer = buffer.duplicate()
      tmpBuffer.get(bytes, 0, bytes.size)

      println(message)
      println(HexDumpElf.dump(0, bytes, 0, bytes.size))
   }
}


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
fun icmpCksum(buf : ByteBuffer) : Int {
   var sum = 0

   buf.mark()
   var nleft = buf.remaining()

   while (nleft > 1) {
      sum += ((buf.get().toInt() and 0xff) or ((buf.get().toInt() and 0xff) shl 8))  // read 16-bit unsigned
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
