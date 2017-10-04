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

import com.zaxxer.ping.HexDumpElf
import com.zaxxer.ping.IcmpPinger
import jnr.ffi.Struct
import jnr.ffi.Union
import jnr.posix.DefaultNativeTimeval
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.spi.AbstractSelector
import java.util.concurrent.atomic.AtomicInteger

const val IP_MAXPACKET = 65535
const val DEFDATALEN = 56 /* default data length */

/**
 * Created by Brett Wooldridge on 2017/10/04.
 */
@Suppress("UNUSED_PARAMETER")
class BsdPingActor(private val selector : AbstractSelector,
                   private val pingChannel : NativeIcmpSocketChannel,
                   handler : IcmpPinger.PingResponseHandler,
                   private val datalen : Int = DEFDATALEN) : PingActor() {

   private val id = (ID_SEQUENCE.getAndIncrement() % 0xffff).toShort()
   private val buf = ByteBuffer.allocateDirect(128)

   override fun sendIcmp() {
      buf.position(SIZEOF_STRUCT_IP)
      val outpackBuffer = buf.slice()
      buf.clear()

      val outpacketPointer = runtime.memoryManager.newPointer(outpackBuffer)

      val maxPayload = IP_MAXPACKET - (SIZEOF_STRUCT_IP + ICMP_MINLEN)
      if (datalen > maxPayload) error("packet size too large: $datalen > $maxPayload")

      val timing = (datalen >= SIZEOF_STRUCT_TV32)

      val tmpBuffer = outpackBuffer.duplicate()
      tmpBuffer.position(ICMP_MINLEN + SIZEOF_STRUCT_TV32)
      for (i in SIZEOF_STRUCT_TV32..datalen)
         tmpBuffer.put(i.toByte())

      if (timing) {
         val timeval = DefaultNativeTimeval(runtime)
         posix.gettimeofday(timeval)
         val tv32 = Tv32()
         tv32.useMemory(outpacketPointer.slice(ICMP_MINLEN.toLong(), Struct.size(tv32).toLong()))
         tv32.tv32_sec.set(timeval.sec())   // htonl?
         tv32.tv32_usec.set(timeval.usec()) // htonl?
      }

      val icmp = BsdIcmp()
      icmp.useMemory(outpacketPointer)

      icmp.icmp_type.set(IcmpPinger.ICMP_ECHO)
      icmp.icmp_code.set(0)
      icmp.icmp_cksum.set(0)
      icmp.icmp_hun.ih_idseq.icd_seq.set(htons(sequence++))
      icmp.icmp_hun.ih_idseq.icd_id.set(htons(id))

      outpackBuffer.limit(ICMP_MINLEN + datalen)

      val cksumBuffer = outpackBuffer.slice()
      val cksum = bsd_cksum(cksumBuffer)
      icmp.icmp_cksum.set(htons(cksum.toShort()))

      dumpBuffer(message = "Final buffer", buffer = outpackBuffer)

      val rc = pingChannel.write(outpackBuffer)
      if (rc == outpackBuffer.remaining()) {
         state = STATE_RECV
         pingChannel.register(selector, SelectionKey.OP_READ, this)
      }
      else {
         error("sendto() returned $rc")
      }
   }

   override fun recvIcmp() {
      TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
   }

   private fun dumpBuffer(message : String, buffer : ByteBuffer) {
      val bytes = ByteArray(buffer.remaining())
      val tmpBuffer = buffer.duplicate()
      tmpBuffer.get(bytes, 0, bytes.size)

      println(message)
      println(HexDumpElf.dump(0, bytes, 0, bytes.size))
   }

   companion object {
      @JvmStatic
      val SIZEOF_STRUCT_IP = Struct.size(BsdIp())

      @JvmStatic
      val SIZEOF_STRUCT_TV32 = Struct.size(Tv32())

      @JvmStatic
      val ID_SEQUENCE = AtomicInteger()
   }
}


// /*
//  * Structure of an internet header, naked of options.
//  */
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
   val ih_idseq : BsdIdSeq = inner(BsdIdSeq())

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
   val ih_pmtu : BsdPmtu = inner(BsdPmtu())

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
   val ih_rtradv : BsdRtradv = inner(BsdRtradv())
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
   val id_ts : BsdIdTs = inner(BsdIdTs())

   // struct id_ip  {
   //    struct ip idi_ip;
   //    /* options and then 64 bits of data */
   // } id_ip;
   class BsdIdIp : Struct(runtime) {
      val idi_ip : BsdIp = inner(BsdIp())
   }
   val id_ip : BsdIdIp = inner(BsdIdIp())

   // struct icmp_ra_addr id_radv;
   val id_radv : BsdRaAddr = inner(BsdRaAddr())

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

// /*
//  * Structure of an icmp header.
//  */
// struct icmp {
class BsdIcmp : Icmp() {
   // u_char	icmp_type;		/* type of message, see below */
   // u_char	icmp_code;		/* type sub code */
   // u_short	icmp_cksum;		/* ones complement cksum of struct */
   val icmp_type = Unsigned8()
   val icmp_code = Unsigned8()
   val icmp_cksum = Unsigned16()

   // union {
   val icmp_hun : BsdHun = inner(BsdHun())
   // } icmp_hun;

   // union {
   val icmp_dun : BsdDun = inner(BsdDun())
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

// /*
//  * [XSI] Message header for recvmsg and sendmsg calls.
//  * Used value-result for recvmsg, value only for sendmsg.
//  */
// struct msghdr {
class BsdMsghdr : Struct(runtime) {
   // void		*msg_name;	/* [XSI] optional address */
   // socklen_t	msg_namelen;	/* [XSI] size of address */
   val msg_name = Pointer()
   val msg_namelen = socklen_t()

   // struct		iovec *msg_iov;	/* [XSI] scatter/gather array */
   val iovec = Pointer()

   // int		msg_iovlen;	/* [XSI] # elements in msg_iov */
   val msg_iovlen = Signed32()

   // void		*msg_control;	/* [XSI] ancillary data, see below */
   val msg_control = Pointer()

   // socklen_t	msg_controllen;	/* [XSI] ancillary data buffer len */
   val msg_controllen = socklen_t()

   // int		msg_flags;	/* [XSI] flags on received message */
   val msg_flags = Signed32()
}



// See https://opensource.apple.com/source/network_cmds/network_cmds-329.2/ping.tproj/ping.c
fun bsd_cksum(buf : ByteBuffer) : Int {
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
