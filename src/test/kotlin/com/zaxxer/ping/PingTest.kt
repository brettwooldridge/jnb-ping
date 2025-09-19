package com.zaxxer.ping

import com.zaxxer.ping.impl.*
import com.zaxxer.ping.impl.util.dumpBuffer
import jnr.ffi.Struct
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PingTest {
   private val runtime:jnr.ffi.Runtime = jnr.ffi.Runtime.getSystemRuntime()!!

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
   fun testSizesAlignmentsAndEndianess() {
      assertEquals(20, Struct.size(Ip()))
      assertEquals(28, Struct.size(Icmp()))
      assertEquals(2, Icmp().icmp_cksum.offset())
      assertEquals(4, Icmp().icmp_hun.ih_idseq.icd_id.offset())
      assertEquals(6, Icmp().icmp_hun.ih_idseq.icd_seq.offset())
      assertEquals(8, Icmp().icmp_dun.id_data.offset())
      assertEquals(8, Struct.size(PollFd()))

      val pollFdBuffer = ByteBuffer.allocateDirect(8)
      val pollFd = PollFd()
      pollFd.useMemory(runtime.memoryManager.newPointer(pollFdBuffer))

      pollFd.fd = 0xBADCAFE
      pollFd.events = 0xAABB
      pollFd.revents = 0x2233
      println(dumpBuffer("poll_fd memory dump:", pollFdBuffer))
      arrayOf(0xfe, 0xca, 0xad, 0x0b, 0xbb, 0xaa, 0x33, 0x22).forEachIndexed { i, expected ->
         assertEquals(expected.toByte(), pollFdBuffer.get(i))
      }
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
   @Timeout(60)
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

         override fun onFailure(pingTarget: PingTarget, failureReason: FailureReason) {
            println("  ${Thread.currentThread()} Failed: $failureReason")
            if (failureReason == FailureReason.TimedOut) {
               timeoutTargets.add(pingTarget)
            }
            semaphore.release()
         }
      }

      val pinger = IcmpPinger(PingHandler())

      val selectorThread = Thread { pinger.runSelector() }
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

      assertTrue(timeoutTargets.isEmpty(), "$timeoutTargets timed out.")
   }

   //
   @Timeout(60)
   @Throws(IOException::class)
   fun pingTestIpv6() {
      val semaphore = Semaphore(2)
      val failedTargets = ArrayList<PingTarget>()

      class PingHandler : PingResponseHandler {
         override fun onResponse(pingTarget: PingTarget, responseTimeSec: Double, byteCount: Int, seq: Int) {
            System.out.printf("  ${Thread.currentThread()} $byteCount bytes from ${pingTarget.toString()
                    .replace("%", "%%")}: icmp_seq=$seq time=%1.6f\n", responseTimeSec)
            println("  ${Thread.currentThread()} Calling semaphore.release()\n")
            semaphore.release()
         }

         override fun onFailure(pingTarget: PingTarget, failureReason: FailureReason) {
            println("  ${Thread.currentThread()} Failed: $failureReason")
            failedTargets.add(pingTarget)
            semaphore.release()
         }
      }

      val pinger = IcmpPinger(PingHandler())

      val selectorThread = Thread { pinger.runSelector() }
      selectorThread.isDaemon = false
      selectorThread.start()

      val pingTargets = ArrayList<PingTarget>()
      pingTargets.add(PingTarget(InetAddress.getByName("::1")))

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

      assertTrue(failedTargets.isEmpty(), "$failedTargets failed.")
   }

   @Test
   @Timeout(60)
   fun testTimeoutOrder() {
      val pings = 512
      val semaphore = Semaphore(pings)
      val targets = List(pings) { i ->
         PingTarget(
            // Ping a non existing ip address
            inetAddress = InetAddress.getByName("240.0.0.0"),
            timeoutMs = 10L * i
         )
      }.shuffled()

      val timeoutsOrder = ArrayList<Long>()

      class PingHandler : PingResponseHandler {
         override fun onResponse(pingTarget: PingTarget, responseTimeSec: Double, byteCount: Int, seq: Int) {
            semaphore.release()
         }

         override fun onFailure(pingTarget: PingTarget, failureReason: FailureReason) {
            if (failureReason == FailureReason.TimedOut) {
               timeoutsOrder.add(pingTarget.timeoutNs)
            }
            semaphore.release()
         }
      }

      val pinger = IcmpPinger(PingHandler())

      val selectorThread = Thread { pinger.runSelector() }
      selectorThread.isDaemon = false
      selectorThread.start()

      MILLISECONDS.sleep(100)

      semaphore.acquire(pings)

      for (target in targets) {
         pinger.ping(target)
      }

      semaphore.acquire(pings)

      pinger.stopSelector()

      assertEquals(pings, timeoutsOrder.size, "$pings targets must timeout.")
      assertEquals(timeoutsOrder.sorted(), timeoutsOrder, "Targets must timeout in order.")
   }

   @Test
   @Timeout(60)
   @Throws(IOException::class)
   fun testPingFailure() {

      var timedOut = false

      class PingHandler : PingResponseHandler {
         override fun onResponse(pingTarget: PingTarget, responseTimeSec: Double, byteCount: Int, seq: Int) {
            println("  ${Thread.currentThread()} Success response unexpected.")
         }

         override fun onFailure(pingTarget : PingTarget, failureReason: FailureReason) {
            println("  ${Thread.currentThread()} Failed: $failureReason")
            if (failureReason == FailureReason.TimedOut) {
               timedOut = true
            }
         }
      }

      val pinger = IcmpPinger(PingHandler())

      val selectorThread = Thread { pinger.runSelector() }
      selectorThread.name = "testPingFailureSelector"
      selectorThread.isDaemon = false
      selectorThread.start()

      // Ping a non existing ip address
      pinger.ping(PingTarget(InetAddress.getByName("240.0.0.0")))

      MILLISECONDS.sleep(100L)

      while (pinger.isPendingWork()) Thread.sleep(500L)

      pinger.stopSelector()

      assertTrue(timedOut, "Ping didn't timeout as expected.")
   }

   @Test
   @Timeout(60)
   fun testSimultaneousRequest() {
      val pingCount = 2

      val semaphore = Semaphore(pingCount)
      semaphore.acquireUninterruptibly(pingCount)

      val successCount = AtomicInteger(0)
      val pinger = IcmpPinger(object : PingResponseHandler {
         override fun onResponse(pingTarget: PingTarget, responseTimeSec: Double, byteCount: Int, seq: Int) {
            println("  ${Thread.currentThread()} Success response.")
            successCount.incrementAndGet()
            semaphore.release()
         }

         override fun onFailure(pingTarget: PingTarget, failureReason: FailureReason) {
            println("  ${Thread.currentThread()} Failed: $failureReason")
            semaphore.release()
         }
      })

      val selectorThread = Thread {pinger.runSelector()}
      selectorThread.isDaemon = false
      selectorThread.start()

      val target = PingTarget(InetAddress.getByName("192.0.2.1"))

      repeat(pingCount) {
         pinger.ping(target)
      }

      if (!semaphore.tryAcquire(pingCount, 5, TimeUnit.SECONDS)) {
         assertEquals(pingCount, semaphore.availablePermits(), "Every ping attempt should have a timeout")
      }

      assertEquals(0, successCount.get(), "There should be no successful pings")

      pinger.stopSelector()
   }

   private fun getIpv6Address() : String {
      return Runtime.getRuntime().exec(arrayOf("hostname", "-i"))
         .inputReader().readLine().substringBefore(" ")
   }
}
