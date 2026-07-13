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

@file:Suppress("PropertyName", "unused", "FunctionName", "ClassName", "SpellCheckingInspection")

package com.zaxxer.ping.impl

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemoryLayout.PathElement.sequenceElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle
import java.net.Inet4Address
import java.net.Inet6Address

val isBSD = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("bsd") }

const val ICMP_MINLEN = 8
const val IP_MAXPACKET = 65535
const val DEFAULT_DATALEN = 56
const val SEND_PACKET_SIZE = ICMP_MINLEN + DEFAULT_DATALEN

const val PF_INET = 2
val PF_INET6 = if (isBSD) 30 else 10
const val SOCK_DGRAM = 2

const val IPPROTO_ICMP = 1
const val IPPROTO_ICMPV6 = 58

const val ICMP_ECHO = 8.toShort()
const val ICMPV6_ECHO_REQUEST = 128.toShort()
const val ICMP_ECHOREPLY = 0.toShort()
const val ICMPV6_ECHO_REPLY = 129.toShort()

val SOL_SOCKET = if (isBSD) 0xffff else 1

val SO_TIMESTAMP = if (isBSD) 0x0400 else 29
val SO_RCVBUF = if (isBSD) 0x1002 else 8
val SO_REUSEPORT = if (isBSD) 0x0200 else 2
val SCM_TIMESTAMP = if (isBSD) 0x02 else SO_TIMESTAMP

const val F_GETFL = 3
const val F_SETFL = 4
val O_NONBLOCK = if (isBSD) 0x0004 else 0x0800

const val EINTR = 4
val EAGAIN = if (isBSD) 35 else 11

/*****************************************************************************
 *                            struct sockaddr
 *
 * BSD and Linux differ in the layout of the leading bytes: BSD has a one
 * byte sa_len followed by a one byte sa_family, Linux has a two byte
 * sa_family.
 */

// struct sockaddr_in (BSD)
val BSD_SOCKADDR_IN_LAYOUT: StructLayout = MemoryLayout.structLayout(
   JAVA_BYTE.withName("sin_len"),
   JAVA_BYTE.withName("sin_family"),
   JAVA_SHORT.withName("sin_port"),
   JAVA_INT.withName("sin_addr"),
   MemoryLayout.sequenceLayout(8, JAVA_BYTE).withName("sin_zero")
).withName("sockaddr_in")

// struct sockaddr_in (Linux)
val LINUX_SOCKADDR_IN_LAYOUT: StructLayout = MemoryLayout.structLayout(
   JAVA_SHORT.withName("sin_family"),
   JAVA_SHORT.withName("sin_port"),
   JAVA_INT.withName("sin_addr"),
   MemoryLayout.sequenceLayout(8, JAVA_BYTE).withName("sin_zero")
).withName("sockaddr_in")

// struct sockaddr_in6 (BSD)
val BSD_SOCKADDR_IN6_LAYOUT: StructLayout = MemoryLayout.structLayout(
   JAVA_BYTE.withName("sin6_len"),
   JAVA_BYTE.withName("sin6_family"),
   JAVA_SHORT.withName("sin6_port"),
   JAVA_INT.withName("sin6_flowinfo"),
   MemoryLayout.sequenceLayout(16, JAVA_BYTE).withName("sin6_addr"),
   JAVA_INT.withName("sin6_scope_id")
).withName("sockaddr_in6")

// struct sockaddr_in6 (Linux)
val LINUX_SOCKADDR_IN6_LAYOUT: StructLayout = MemoryLayout.structLayout(
   JAVA_SHORT.withName("sin6_family"),
   JAVA_SHORT.withName("sin6_port"),
   JAVA_INT.withName("sin6_flowinfo"),
   MemoryLayout.sequenceLayout(16, JAVA_BYTE).withName("sin6_addr"),
   JAVA_INT.withName("sin6_scope_id")
).withName("sockaddr_in6")

private const val SIN6_ADDR_OFFSET = 8L
private const val SIN6_SCOPE_ID_OFFSET = 24L

open class SockAddr internal constructor(@JvmField internal val segment: MemorySegment) {
   internal val size: Int
      get() = segment.byteSize().toInt()
}

open class SockAddr6 internal constructor(segment: MemorySegment) : SockAddr(segment)

// Each sockaddr gets its own automatic (GC-managed) arena so its native memory
// lives exactly as long as the PingTarget that references it.
private fun allocate(layout: StructLayout): MemorySegment = Arena.ofAuto().allocate(layout)

class BSDSockAddr4(address: Inet4Address) : SockAddr(allocate(BSD_SOCKADDR_IN_LAYOUT)) {
   init {
      segment.set(JAVA_BYTE, 1L, PF_INET.toByte())                      // sin_family
      MemorySegment.copy(address.address, 0, segment, JAVA_BYTE, 4L, 4) // sin_addr
   }
}

class BSDSockAddr6(address: Inet6Address) : SockAddr6(allocate(BSD_SOCKADDR_IN6_LAYOUT)) {
   init {
      segment.set(JAVA_BYTE, 1L, PF_INET6.toByte())                     // sin6_family
      MemorySegment.copy(address.address, 0, segment, JAVA_BYTE, SIN6_ADDR_OFFSET, 16)
      if (address.isLinkLocalAddress) {
         segment.set(JAVA_INT, SIN6_SCOPE_ID_OFFSET, address.scopeId)
      }
   }
}

class LinuxSockAddr4(address: Inet4Address) : SockAddr(allocate(LINUX_SOCKADDR_IN_LAYOUT)) {
   init {
      segment.set(JAVA_SHORT, 0L, PF_INET.toShort())                    // sin_family
      MemorySegment.copy(address.address, 0, segment, JAVA_BYTE, 4L, 4) // sin_addr
   }
}

class LinuxSockAddr6(address: Inet6Address) : SockAddr6(allocate(LINUX_SOCKADDR_IN6_LAYOUT)) {
   init {
      segment.set(JAVA_SHORT, 0L, PF_INET6.toShort())                   // sin6_family
      MemorySegment.copy(address.address, 0, segment, JAVA_BYTE, SIN6_ADDR_OFFSET, 16)
      if (address.isLinkLocalAddress) {
         segment.set(JAVA_INT, SIN6_SCOPE_ID_OFFSET, address.scopeId)
      }
   }
}

/*****************************************************************************
 *                            struct pollfd
 */

// /* Data structure describing a polling request.  */
// struct pollfd {
//    int fd;            /* File descriptor to poll. */
//    short int events;  /* Types of events poller cares about.  */
//    short int revents; /* Types of events that actually occurred.  */
// };
val POLLFD_LAYOUT: StructLayout = MemoryLayout.structLayout(
   JAVA_INT.withName("fd"),
   JAVA_SHORT.withName("events"),
   JAVA_SHORT.withName("revents")
).withName("pollfd")

val SIZEOF_STRUCT_POLL_FD = POLLFD_LAYOUT.byteSize().toInt()

class PollFd internal constructor(private val segment: MemorySegment) {
   var fd: Int
      get() = segment.get(JAVA_INT, 0L)
      set(value) = segment.set(JAVA_INT, 0L, value)

   var events: Int
      get() = segment.get(JAVA_SHORT, 4L).toInt()
      set(value) = segment.set(JAVA_SHORT, 4L, value.toShort())

   var revents: Int
      get() = segment.get(JAVA_SHORT, 6L).toInt()
      set(value) = segment.set(JAVA_SHORT, 6L, value.toShort())
}

// #define POLLIN		01              /* There is data to read.  */
// #define POLLPRI		02              /* There is urgent data to read.  */
// #define POLLOUT		04              /* Writing now will not block.  */
// /* Event types always implicitly polled for.  These bits need not be set in
//    'events', but they will appear in 'revents' to indicate the status of
//    the file descriptor.  */
// #define POLLERR         010             /* Error condition.  */
const val POLLIN = 0x1
const val POLLPRI = 0x2
const val POLLOUT = 0x4
const val POLLERR = 0x8

/*****************************************************************************
 *                              ICMP Definitions
 */

// /*
//  * Structure of an internet header, naked of options.
//  */
// struct ip {
//    u_char   ip_vhl;       /* version << 4 | header length >> 2 */
//    u_char   ip_tos;       /* type of service */
//    u_short  ip_len;       /* total length */
//    u_short  ip_id;        /* identification */
//    u_short  ip_off;       /* fragment offset field */
//    u_char   ip_ttl;       /* time to live */
//    u_char   ip_p;         /* protocol */
//    u_short  ip_sum;       /* checksum */
//    struct in_addr ip_src, ip_dst; /* source and dest address */
// };
val IP_LAYOUT: StructLayout = MemoryLayout.structLayout(
   JAVA_BYTE.withName("ip_vhl"),
   JAVA_BYTE.withName("ip_tos"),
   JAVA_SHORT.withName("ip_len"),
   JAVA_SHORT.withName("ip_id"),
   JAVA_SHORT.withName("ip_off"),
   JAVA_BYTE.withName("ip_ttl"),
   JAVA_BYTE.withName("ip_p"),
   JAVA_SHORT.withName("ip_sum"),
   JAVA_INT.withName("ip_src"),
   JAVA_INT.withName("ip_dst")
).withName("ip")

val SIZEOF_STRUCT_IP = IP_LAYOUT.byteSize().toInt()

// /*
//  * Structure of an icmp header.
//  */
// struct icmp {
//    u_char  icmp_type;   /* type of message, see below */
//    u_char  icmp_code;   /* type sub code */
//    u_short icmp_cksum;  /* ones complement cksum of struct */
//    union icmp_hun { ... };
//    union icmp_dun { ... };
// };
val ICMP_LAYOUT: StructLayout = MemoryLayout.structLayout(
   JAVA_BYTE.withName("icmp_type"),
   JAVA_BYTE.withName("icmp_code"),
   JAVA_SHORT.withName("icmp_cksum"),
   MemoryLayout.unionLayout(
      JAVA_BYTE.withName("ih_pptr"),                              // ICMP_PARAMPROB
      JAVA_INT.withName("ih_gwaddr"),                             // ICMP_REDIRECT
      MemoryLayout.structLayout(
         JAVA_SHORT.withName("icd_id"),
         JAVA_SHORT.withName("icd_seq")
      ).withName("ih_idseq"),
      JAVA_INT.withName("ih_void"),
      // ICMP_UNREACH_NEEDFRAG -- Path MTU Discovery (RFC1191)
      MemoryLayout.structLayout(
         JAVA_SHORT.withName("ipm_void"),
         JAVA_SHORT.withName("ipm_nextmtu")
      ).withName("ih_pmtu"),
      MemoryLayout.structLayout(
         JAVA_BYTE.withName("irt_num_addrs"),
         JAVA_BYTE.withName("irt_wpa"),
         JAVA_SHORT.withName("irt_lifetime")
      ).withName("ih_rtradv")
   ).withName("icmp_hun"),
   MemoryLayout.unionLayout(
      MemoryLayout.structLayout(
         JAVA_INT.withName("its_otime"),
         JAVA_INT.withName("its_rtime"),
         JAVA_INT.withName("its_ttime")
      ).withName("id_ts"),
      MemoryLayout.structLayout(
         IP_LAYOUT.withName("idi_ip")
         /* options and then 64 bits of data */
      ).withName("id_ip"),
      // Internal of an ICMP Router Advertisement
      MemoryLayout.structLayout(
         JAVA_INT.withName("ira_addr"),
         JAVA_INT.withName("ira_preference")
      ).withName("id_radv"),
      JAVA_INT.withName("id_mask"),
      JAVA_BYTE.withName("id_data")
   ).withName("icmp_dun")
).withName("icmp")

// /**
//  * Structure of an icmp6 header
//  */
// struct icmp6_hdr {
//    u_int8_t  icmp6_type;  /* type of message, see below */
//    u_int8_t  icmp6_code;  /* type sub code */
//    u_int16_t icmp6_cksum; /* ones complement cksum of struct */
//    union {
//       u_int32_t icmp6_un_data32[1]; /* type-specific field */
//       u_int16_t icmp6_un_data16[2]; /* type-specific field */
//       u_int8_t  icmp6_un_data8[4];  /* type-specific field */
//    } icmp6_dataun;
// };
val ICMP6_LAYOUT: StructLayout = MemoryLayout.structLayout(
   JAVA_BYTE.withName("icmp6_type"),
   JAVA_BYTE.withName("icmp6_code"),
   JAVA_SHORT.withName("icmp6_cksum"),
   MemoryLayout.unionLayout(
      MemoryLayout.sequenceLayout(1, JAVA_INT).withName("icmp6_un_data32"),
      MemoryLayout.sequenceLayout(2, JAVA_SHORT).withName("icmp6_un_data16"),
      MemoryLayout.sequenceLayout(4, JAVA_BYTE).withName("icmp6_un_data8")
   ).withName("icmp6_dataun")
).withName("icmp6_hdr")

// Offsets of the fields actually touched on the hot send/receive paths.
val ICMP_TYPE_OFFSET = ICMP_LAYOUT.byteOffset(groupElement("icmp_type"))
val ICMP_CODE_OFFSET = ICMP_LAYOUT.byteOffset(groupElement("icmp_code"))
val ICMP_CKSUM_OFFSET = ICMP_LAYOUT.byteOffset(groupElement("icmp_cksum"))
val ICMP_ID_OFFSET = ICMP_LAYOUT.byteOffset(groupElement("icmp_hun"), groupElement("ih_idseq"), groupElement("icd_id"))
val ICMP_SEQ_OFFSET = ICMP_LAYOUT.byteOffset(groupElement("icmp_hun"), groupElement("ih_idseq"), groupElement("icd_seq"))

val ICMP6_TYPE_OFFSET = ICMP6_LAYOUT.byteOffset(groupElement("icmp6_type"))
val ICMP6_CODE_OFFSET = ICMP6_LAYOUT.byteOffset(groupElement("icmp6_code"))
val ICMP6_CKSUM_OFFSET = ICMP6_LAYOUT.byteOffset(groupElement("icmp6_cksum"))
val ICMP6_ID_OFFSET = ICMP6_LAYOUT.byteOffset(groupElement("icmp6_dataun"), groupElement("icmp6_un_data16"), sequenceElement(0))
val ICMP6_SEQ_OFFSET = ICMP6_LAYOUT.byteOffset(groupElement("icmp6_dataun"), groupElement("icmp6_un_data16"), sequenceElement(1))

/*****************************************************************************
 *                              libc bindings
 */

object LibC {
   private val LINKER: Linker = Linker.nativeLinker()
   private val LIBC: SymbolLookup = LINKER.defaultLookup()

   @JvmStatic val ERRNO_STATE_LAYOUT: StructLayout = Linker.Option.captureStateLayout()
   private val CAPTURE_ERRNO: Linker.Option = Linker.Option.captureCallState("errno")
   private val ERRNO_HANDLE: VarHandle = ERRNO_STATE_LAYOUT.varHandle(groupElement("errno"))

   private fun handle(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
      LINKER.downcallHandle(LIBC.findOrThrow(name), descriptor, *options)

   // int socket(int domain, int type, int protocol)
   private val SOCKET = handle("socket", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT))
   // int setsockopt(int fd, int level, int option, const void *value, socklen_t len)
   private val SETSOCKOPT = handle("setsockopt", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT))
   // int fcntl(int fd, int cmd, ...)
   private val FCNTL = handle("fcntl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), Linker.Option.firstVariadicArg(2))
   // int pipe(int fds[2])
   private val PIPE = handle("pipe", FunctionDescriptor.of(JAVA_INT, ADDRESS))
   // int poll(struct pollfd *fds, nfds_t nfds, int timeout) -- nfds_t is unsigned int on BSD, unsigned long on Linux
   private val POLL = handle("poll", FunctionDescriptor.of(JAVA_INT, ADDRESS, if (isBSD) JAVA_INT else JAVA_LONG, JAVA_INT), CAPTURE_ERRNO)
   // int close(int fd)
   private val CLOSE = handle("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
   // ssize_t sendto(int fd, const void *buf, size_t len, int flags, const struct sockaddr *addr, socklen_t addrlen)
   private val SENDTO = handle("sendto", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT))
   // ssize_t recvfrom(int fd, void *buf, size_t len, int flags, struct sockaddr *addr, socklen_t *addrlen)
   private val RECVFROM = handle("recvfrom", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS), CAPTURE_ERRNO)
   // ssize_t read(int fd, void *buf, size_t count)
   private val READ = handle("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG))
   // ssize_t write(int fd, const void *buf, size_t count)
   private val WRITE = handle("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG))

   fun socket(domain: Int, type: Int, protocol: Int): Int =
      SOCKET.invokeExact(domain, type, protocol) as Int

   fun setsockopt(fd: Int, level: Int, option: Int, value: Int): Int =
      Arena.ofConfined().use { arena ->
         val valueSegment = arena.allocate(JAVA_INT)
         valueSegment.set(JAVA_INT, 0L, value)
         SETSOCKOPT.invokeExact(fd, level, option, valueSegment, JAVA_INT.byteSize().toInt()) as Int
      }

   fun fcntl(fd: Int, cmd: Int, data: Int): Int =
      FCNTL.invokeExact(fd, cmd, data) as Int

   fun pipe(fds: IntArray): Int =
      Arena.ofConfined().use { arena ->
         val fdsSegment = arena.allocate(JAVA_INT, 2)
         val rc = PIPE.invokeExact(fdsSegment) as Int
         fds[0] = fdsSegment.getAtIndex(JAVA_INT, 0L)
         fds[1] = fdsSegment.getAtIndex(JAVA_INT, 1L)
         rc
      }

   fun poll(errnoState: MemorySegment, fds: MemorySegment, nfds: Int, timeoutMs: Int): Int =
      if (isBSD) POLL.invokeExact(errnoState, fds, nfds, timeoutMs) as Int
      else POLL.invokeExact(errnoState, fds, nfds.toLong(), timeoutMs) as Int

   fun close(fd: Int): Int =
      CLOSE.invokeExact(fd) as Int

   fun sendto(fd: Int, buf: MemorySegment, len: Int, flags: Int, addr: SockAddr): Int =
      (SENDTO.invokeExact(fd, buf, len.toLong(), flags, addr.segment, addr.size) as Long).toInt()

   fun recvfrom(errnoState: MemorySegment, fd: Int, buf: MemorySegment, len: Int, flags: Int): Int =
      (RECVFROM.invokeExact(errnoState, fd, buf, len.toLong(), flags, MemorySegment.NULL, MemorySegment.NULL) as Long).toInt()

   fun read(fd: Int, buf: MemorySegment, size: Long): Int =
      (READ.invokeExact(fd, buf, size) as Long).toInt()

   fun write(fd: Int, buf: MemorySegment, size: Long): Int =
      (WRITE.invokeExact(fd, buf, size) as Long).toInt()

   fun errno(errnoState: MemorySegment): Int =
      ERRNO_HANDLE.get(errnoState, 0L) as Int
}

fun htons(s: Short) = java.lang.Short.reverseBytes(s)

fun ntohs(s: Short) = java.lang.Short.reverseBytes(s)

fun htoni(i: Int) = Integer.reverseBytes(i)

fun htonl(value: Long): Long {
   return ((value and 0xff000000) shr 24) or
           ((value and 0x00ff0000) shr 8) or
           ((value and 0x0000ff00) shl 8) or
           ((value and 0x000000ff) shl 24)
}

// See https://opensource.apple.com/source/network_cmds/network_cmds-329.2/ping.tproj/ping.c
fun icmpCksum(buf: MemorySegment, len: Int): Int {
   var sum = 0

   var nleft = len

   var offset = 0L
   while (nleft > 1) {
      sum += ((buf.get(JAVA_BYTE, offset).toInt() and 0xff) or ((buf.get(JAVA_BYTE, offset + 1).toInt() and 0xff) shl 8))  // read 16-bit unsigned
      nleft -= 2
      offset += 2
   }

   if (nleft == 1) {
      sum += buf.get(JAVA_BYTE, offset).toShort()
   }

   sum = (sum shr 16) + (sum and 0xffff)
   sum += (sum shr 16)
   return (sum.inv() and 0xffff)
}
