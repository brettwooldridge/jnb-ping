package com.zaxxer.ping

import com.zaxxer.ping.impl.Icmp
import com.zaxxer.ping.impl.Ip
import com.zaxxer.ping.impl.PingTarget
import com.zaxxer.ping.impl.Tv32
import com.zaxxer.ping.impl.icmpCksum
import jnr.ffi.Struct
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore

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
      val semaphore = Semaphore(1)

      class PingHandler : IcmpPinger.PingResponseHandler {
         private var count : Int = 0

         override fun onResponse(rtt : Double, bytes : Int, seq : Int) {
            println("$bytes bytes from 172.16.0.4: icmp_seq=$seq time=$rtt")
            semaphore.release()
         }

         override fun onTimeout() {
            println("Timeout")
         }

         override fun onError(message : String) {
            println("Error: $message")
         }
      }

      val selectorThread = Thread( {pinger.startSelector()})
      selectorThread.isDaemon = false
      selectorThread.start()

      val inetAddress = InetAddress.getByName("172.16.0.4")
      val pingTarget = PingTarget(inetAddress)
      val pingHandler = PingHandler()

      for (i in 0..10000) {
         semaphore.acquire()
         pinger.ping(pingTarget, pingHandler)
      }

      Thread.sleep(3000)

      pinger.stopSelector()
   }
}
