package com.zaxxer.ping

import com.zaxxer.ping.impl.Icmp
import com.zaxxer.ping.impl.Ip
import com.zaxxer.ping.impl.Tv32
import com.zaxxer.ping.impl.icmpCksum
import jnr.ffi.Struct
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class PingTest {

   @Test
   fun testChecksum() {
      val buffer = ByteBuffer.allocate(64)

      for (i in 0..63)
         buffer.put(i.toByte())

      buffer.flip()
      assertEquals(64539, icmpCksum(buffer))

      buffer.clear()
      for (i in 0..63)
         buffer.put((255 - i).toByte())

      buffer.flip()
      assertEquals(996, icmpCksum(buffer))
   }

   @Test
   fun testSizesAndAlignments() {
      assertEquals(20, Struct.size(Ip()))
      assertEquals(28, Struct.size(Icmp()))
      assertEquals(2, Icmp().icmp_cksum.offset())
      assertEquals(4, Icmp().icmp_hun.ih_idseq.icd_id.offset())
      assertEquals(6, Icmp().icmp_hun.ih_idseq.icd_seq.offset())
      assertEquals(8, Icmp().icmp_dun.id_data.offset())
      assertEquals(8, Struct.size(Tv32()))
      println("Checksum offset: " + Icmp().icmp_cksum.offset())
   }

   @Test
   @Throws(IOException::class)
   fun pingTest1() {
      val pinger = IcmpPinger()
      val semaphore = Semaphore(2)

      class PingHandler : PingResponseHandler {
         override fun onResponse(pingTarget : PingTarget, rtt : Double, bytes : Int, seq : Int) {
            println("$bytes bytes from $pingTarget: icmp_seq=$seq time=$rtt")

            println("Calling semaphore.release()\n")
            semaphore.release()
         }

         override fun onTimeout(pingTarget : PingTarget) {
            println("Timeout")
         }

         override fun onError(pingTarget : PingTarget, message : String) {
            println("Error: $message")
         }
      }

      val selectorThread = Thread( {pinger.runSelector()})
      selectorThread.isDaemon = false
      selectorThread.start()

      val pingTargets = arrayOf(
         PingTarget(InetAddress.getByName("172.16.0.5")),
         PingTarget(InetAddress.getByName("172.16.0.6"))
      )
      val pingHandler = PingHandler()

      for (i in 0..(1 * pingTargets.size)) {
         if (!semaphore.tryAcquire()) {
            println("$i: Blocking on semaphore.acquire()")
            semaphore.acquire()

            // TimeUnit.MILLISECONDS.sleep(30)
         }
         println("$i: Calling pinger.ping()")
         pinger.ping(pingTargets[i % 2], pingHandler)
      }

      Thread.sleep(2000)
      pinger.stopSelector()
   }
}
