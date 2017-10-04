package com.zaxxer.ping

import com.zaxxer.ping.impl.BsdIcmp
import com.zaxxer.ping.impl.BsdIp
import com.zaxxer.ping.impl.Tv32
import com.zaxxer.ping.impl.bsd_cksum
import jnr.ffi.Struct
import jnr.ffi.StructLayout
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer

class PingTest {

   @Test
   fun testChecksum() {
      val buffer = ByteBuffer.allocate(64)

      for (i in 0..63)
         buffer.put(i.toByte())

      buffer.flip()
      assertEquals(64539, bsd_cksum(buffer))

      buffer.clear()
      for (i in 0..63)
         buffer.put((255 - i).toByte())

      buffer.flip()
      assertEquals(996, bsd_cksum(buffer))
   }

   @Test
   fun testSizesAndAlignments() {
      assertEquals(20, Struct.size(BsdIp()))
      assertEquals(28, Struct.size(BsdIcmp()))
      assertEquals(2, BsdIcmp().icmp_cksum.offset())
      assertEquals(4, BsdIcmp().icmp_hun.ih_idseq.icd_id.offset())
      assertEquals(6, BsdIcmp().icmp_hun.ih_idseq.icd_seq.offset())
      assertEquals(8, BsdIcmp().icmp_dun.id_data.offset())
      assertEquals(8, Struct.size(Tv32()))
      println("Checksum offset: " + BsdIcmp().icmp_cksum.offset())
   }

   @Test
   @Throws(IOException::class)
   fun pingTest1() {
      val pinger = IcmpPinger()

      class PingHandler : IcmpPinger.PingResponseHandler {
         override fun onResponse(rtt : Int) {
            System.err.println("Response rtt: " + rtt)
         }

         override fun onTimeout() {
            System.err.println("Timeout")
         }

         override fun onError() {
            System.err.println("Error")
         }
      }

      val selectorThread = Thread( {pinger.startSelector()})
      selectorThread.isDaemon = false
      selectorThread.start()

      pinger.ping(InetAddress.getByName("8.8.8.8"), PingHandler())

      Thread.sleep(3000)
   }
}
