package com.zaxxer.ping

import com.zaxxer.ping.impl.*
import com.zaxxer.ping.impl.util.dumpBuffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicInteger

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PingTest {

   @Test
   fun testChecksum() {
      Arena.ofConfined().use { arena ->
         val buffer = arena.allocate(64L)

         for (i in 0..63)
            buffer.set(JAVA_BYTE, i.toLong(), i.toByte())

         assertEquals(64539, icmpCksum(buffer, 64))

         for (i in 0..63)
            buffer.set(JAVA_BYTE, i.toLong(), (255 - i).toByte())

         assertEquals(996, icmpCksum(buffer, 64))
      }
   }

   @Test
   fun testSizesAlignmentsAndEndianess() {
      assertEquals(20L, IP_LAYOUT.byteSize())
      assertEquals(28L, ICMP_LAYOUT.byteSize())
      assertEquals(2L, ICMP_CKSUM_OFFSET)
      assertEquals(4L, ICMP_ID_OFFSET)
      assertEquals(6L, ICMP_SEQ_OFFSET)
      assertEquals(8L, ICMP_LAYOUT.byteOffset(groupElement("icmp_dun"), groupElement("id_data")))
      assertEquals(8L, POLLFD_LAYOUT.byteSize())
      assertEquals(8, SIZEOF_STRUCT_POLL_FD)

      Arena.ofConfined().use { arena ->
         val pollFdSegment = arena.allocate(POLLFD_LAYOUT)
         val pollFd = PollFd(pollFdSegment)

         pollFd.fd = 0xBADCAFE
         pollFd.events = 0xAABB
         pollFd.revents = 0x2233
         println(dumpBuffer("poll_fd memory dump:", pollFdSegment.asByteBuffer()))
         arrayOf(0xfe, 0xca, 0xad, 0x0b, 0xbb, 0xaa, 0x33, 0x22).forEachIndexed { i, expected ->
            assertEquals(expected.toByte(), pollFdSegment.get(JAVA_BYTE, i.toLong()))
         }
      }
   }

   @Test
   fun testSizesAndAlignmentsIpv6() {
      assertEquals(8L, ICMP6_LAYOUT.byteSize())
      assertEquals(0L, ICMP6_TYPE_OFFSET)
      assertEquals(1L, ICMP6_CODE_OFFSET)
      assertEquals(2L, ICMP6_CKSUM_OFFSET)
      assertEquals(4L, ICMP6_ID_OFFSET)
      assertEquals(6L, ICMP6_SEQ_OFFSET)

      assertEquals(28L, LINUX_SOCKADDR_IN6_LAYOUT.byteSize())
      assertEquals(0L, LINUX_SOCKADDR_IN6_LAYOUT.byteOffset(groupElement("sin6_family")))
      assertEquals(2L, LINUX_SOCKADDR_IN6_LAYOUT.byteOffset(groupElement("sin6_port")))
      assertEquals(4L, LINUX_SOCKADDR_IN6_LAYOUT.byteOffset(groupElement("sin6_flowinfo")))
      assertEquals(8L, LINUX_SOCKADDR_IN6_LAYOUT.byteOffset(groupElement("sin6_addr")))
      assertEquals(24L, LINUX_SOCKADDR_IN6_LAYOUT.byteOffset(groupElement("sin6_scope_id")))

      assertEquals(28L, BSD_SOCKADDR_IN6_LAYOUT.byteSize())
      assertEquals(0L, BSD_SOCKADDR_IN6_LAYOUT.byteOffset(groupElement("sin6_len")))
      assertEquals(1L, BSD_SOCKADDR_IN6_LAYOUT.byteOffset(groupElement("sin6_family")))
      assertEquals(2L, BSD_SOCKADDR_IN6_LAYOUT.byteOffset(groupElement("sin6_port")))
      assertEquals(4L, BSD_SOCKADDR_IN6_LAYOUT.byteOffset(groupElement("sin6_flowinfo")))
      assertEquals(8L, BSD_SOCKADDR_IN6_LAYOUT.byteOffset(groupElement("sin6_addr")))
      assertEquals(24L, BSD_SOCKADDR_IN6_LAYOUT.byteOffset(groupElement("sin6_scope_id")))

      // Both flavors must be constructible regardless of the host platform
      val loopback6 = InetAddress.getByName("::1") as Inet6Address
      assertEquals(28, LinuxSockAddr6(loopback6).size)
      assertEquals(28, BSDSockAddr6(loopback6).size)
      assertEquals(16, LinuxSockAddr4(InetAddress.getByName("127.0.0.1") as java.net.Inet4Address).size)
      assertEquals(16, BSDSockAddr4(InetAddress.getByName("127.0.0.1") as java.net.Inet4Address).size)
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
         PingTarget(InetAddress.getByName(System.getenv("PING_TEST_IP") ?: "8.8.8.8")),
         PingTarget(InetAddress.getByName(System.getenv("PING_TEST_HOST") ?: "youtube.com"))
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

      MILLISECONDS.sleep(100L)

      // Ping a non existing ip address
      pinger.ping(PingTarget(InetAddress.getByName("240.0.0.0")))

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
