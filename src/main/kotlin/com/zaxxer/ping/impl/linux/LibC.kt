package com.zaxxer.ping.impl.linux;

import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import java.lang.System.getProperty


class LibC {
   companion object {
      @JvmStatic
      val O_NONBLOCK = if (isBSD()) 0x0004 else 0x800;

      @JvmStatic
      val PF_INET = 2

      @JvmStatic
      val PF_INET6 = 10

      @JvmStatic
      val IPPROTO_ICMP = 1

      @JvmStatic
      val IPPROTO_ICMPV6 = 58

      @JvmStatic
      val SOCK_DGRAM = 2

      @JvmStatic
      val IP_RECVTTL = 0x18L

      @JvmStatic
      val IP_RETOPTS = 0x8L

      @JvmStatic
      val IP_TTL = 0x4L

      @JvmStatic
      val SO_SNDBUF = 0x1001L

      @JvmStatic
      val SO_RCVBUF = 0x1002L

      @JvmStatic
      val SO_REUSEPORT = 0x200L

      @JvmStatic
      val SO_ERROR = 0x1007L

      @JvmStatic
      val SO_TIMESTAMP = 0x400L

      init {
         Native.register(NativeLibrary.getProcess());
      }

      private fun isBSD() = ("mac" in getProperty("os.name").toLowerCase() || "freebsd" in getProperty("os.name").toLowerCase())
   }
}
