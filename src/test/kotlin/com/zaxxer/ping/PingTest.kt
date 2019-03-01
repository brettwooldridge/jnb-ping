package com.zaxxer.ping

import com.zaxxer.ping.impl.*
import com.zaxxer.ping.impl.util.dumpBuffer
import jnr.ffi.Platform
import jnr.ffi.Struct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore

class PingTest {

   val runtime:jnr.ffi.Runtime = jnr.ffi.Runtime.getSystemRuntime()!!
   val platform: Platform = Platform.getNativePlatform()
   val isBSD = platform.isBSD

   @Test
   fun testChecksum() {
      val buffer = ByteBuffer.allocateDirect(64)

      for (i in 0..63)
         buffer.put(i.toByte())

      val pointer = runtime.memoryManager.newPointer(buffer)
      assertEquals(64539, icmpCksum(pointer, 64))

      buffer.clear()
      for (i in 0..63)
         buffer.put((255 - i).toByte())

      assertEquals(996, icmpCksum(pointer, 64))
   }

   @Test
   fun testSizesAndAlignments() {
      assertEquals(20, Struct.size(Ip()))
      assertEquals(28, Struct.size(Icmp()))
      assertEquals(2, Icmp().icmp_cksum.offset())
      assertEquals(4, Icmp().icmp_hun.ih_idseq.icd_id.offset())
      assertEquals(6, Icmp().icmp_hun.ih_idseq.icd_seq.offset())
      assertEquals(8, Icmp().icmp_dun.id_data.offset())
      assertEquals(128, Struct.size(Fd_set()))

      val buffer = ByteBuffer.allocateDirect(128)
      val fdSet = Fd_set()
      fdSet.useMemory(runtime.memoryManager.newPointer(buffer))
      FD_SET(76, fdSet)
      dumpBuffer("fd_set memory dump:", buffer)
      assertEquals(4096, fdSet.fds_bits[2].get())
   }

   @Test
   fun testSizesAndAlignmentsIpv6() {
      assertEquals(8, Struct.size(Icmp6()))
      assertEquals(0, Icmp6().icmp6_type.offset())
      assertEquals(1, Icmp6().icmp6_code.offset())
      assertEquals(2, Icmp6().icmp6_cksum.offset())

      val linuxSockAddr6 = LinuxSockAddr6(InetAddress.getByName("::1") as Inet6Address)
      assertEquals(28, Struct.size(linuxSockAddr6))
      assertEquals(0, linuxSockAddr6.sin6_family.offset())
      assertEquals(2, linuxSockAddr6.sin6_port.offset())
      assertEquals(4, linuxSockAddr6.sin6_flowinfo.offset())
      assertEquals(24, linuxSockAddr6.sin6_scope_id.offset())

      val bsdSockAddr6 = BSDSockAddr6(InetAddress.getByName("::1") as Inet6Address)
      assertEquals(28, Struct.size(linuxSockAddr6))
      assertEquals(0, bsdSockAddr6.sin6_len.offset())
      assertEquals(1, bsdSockAddr6.sin6_family.offset())
      assertEquals(2, bsdSockAddr6.sin6_port.offset())
      assertEquals(4, bsdSockAddr6.sin6_flowinfo.offset())
      assertEquals(24, bsdSockAddr6.sin6_scope_id.offset())
   }

   @Test
   @Throws(IOException::class)
   fun pingTest1() {
      val semaphore = Semaphore(2)
      val timeoutTargets = HashSet<PingTarget>()

      class PingHandler : PingResponseHandler {
         override fun onResponse(pingTarget : PingTarget, responseTimeSec : Double, byteCount : Int, seq : Int) {
            System.out.printf("  ${Thread.currentThread()} $byteCount bytes from $pingTarget: icmp_seq=$seq time=%1.6f\n", responseTimeSec)
            println("  ${Thread.currentThread()} Calling semaphore.release()\n")
            semaphore.release()
         }

         override fun onTimeout(pingTarget : PingTarget) {
            println("  ${Thread.currentThread()} Timeout")
            timeoutTargets.add(pingTarget)
            semaphore.release()
         }
      }

      val pinger = IcmpPinger(PingHandler())

      val selectorThread = Thread( {pinger.runSelector()})
      selectorThread.isDaemon = false
      selectorThread.start()

      val pingTargets = arrayOf(
         PingTarget(InetAddress.getByName("8.8.8.8")),
         PingTarget(InetAddress.getByName("youtube.com"))
      )

      for (i in 0..(10 * pingTargets.size)) {
         if (!semaphore.tryAcquire()) {
            println("$i: Blocking on semaphore.acquire()")
            semaphore.acquire()

            // TimeUnit.MILLISECONDS.sleep(30)
         }
         println("$i: Calling pinger.ping(${pingTargets[i % pingTargets.size].inetAddress})")
         pinger.ping(pingTargets[i % pingTargets.size])
      }

      while (pinger.isPendingWork()) Thread.sleep(500)

      pinger.stopSelector()

      assertTrue("$timeoutTargets timed out.", timeoutTargets.isEmpty())
   }

   @Test
   @Throws(IOException::class)
   fun pingTestIpv6() {
      val semaphore = Semaphore(2)
      val timeoutTargets = HashSet<PingTarget>()

      class PingHandler : PingResponseHandler {
         override fun onResponse(pingTarget: PingTarget, responseTimeSec: Double, byteCount: Int, seq: Int) {
            System.out.printf("  ${Thread.currentThread()} $byteCount bytes from ${pingTarget.toString()
                    .replace("%", "%%")}: icmp_seq=$seq time=%1.6f\n", responseTimeSec)
            println("  ${Thread.currentThread()} Calling semaphore.release()\n")
            semaphore.release()
         }

         override fun onTimeout(pingTarget: PingTarget) {
            println("  ${Thread.currentThread()} Timeout")
            timeoutTargets.add(pingTarget)
            semaphore.release()
         }
      }

      val pinger = IcmpPinger(PingHandler())

      val selectorThread = Thread( {pinger.runSelector()})
      selectorThread.isDaemon = false
      selectorThread.start()

      val pingTargets = ArrayList<PingTarget>()
      if (isBSD) {
         pingTargets.add(PingTarget(InetAddress.getByName("2001:4860:4860::8888")))
      } else {
         pingTargets.add(PingTarget(InetAddress.getByName(getIpv6Address())))
      }

      for (i in 0..(10 * pingTargets.size)) {
         if (!semaphore.tryAcquire()) {
            println("$i: Blocking on semaphore.acquire()")
            semaphore.acquire()
         }
         println("$i: Calling pinger.ping(${pingTargets[i % pingTargets.size].inetAddress})")
         pinger.ping(pingTargets[i % pingTargets.size])
      }

      while (pinger.isPendingWork()) Thread.sleep(500)

      pinger.stopSelector()

      assertTrue("$timeoutTargets timed out.", timeoutTargets.isEmpty())
   }

   @Test
   @Throws(IOException::class)
   fun testPingFailure() {

      var timedOut = false

      class PingHandler : PingResponseHandler {

         override fun onResponse(pingTarget: PingTarget, responseTimeSec: Double, byteCount: Int, seq: Int) {
            println("  ${Thread.currentThread()} Success response unexpected.")
         }

         override fun onTimeout(pingTarget : PingTarget) {
            println("  ${Thread.currentThread()} Timeout")
            timedOut = true
         }

      }

      val pinger = IcmpPinger(PingHandler())

      val selectorThread = Thread( {pinger.runSelector()})
      selectorThread.isDaemon = false
      selectorThread.start()

      // Ping a non existing ip address
      pinger.ping(PingTarget(InetAddress.getByName("240.0.0.0")))

      while (pinger.isPendingWork()) Thread.sleep(500)

      pinger.stopSelector()

      assertTrue("Ping didn't timeout as expected.", timedOut)
   }

   private fun getIpv6Address() : String {
      val proc = Runtime.getRuntime().exec("hostname -i")
      val stdInput = BufferedReader(InputStreamReader(proc.inputStream))
      return stdInput.readLine().split(" ")[0]
   }

}
