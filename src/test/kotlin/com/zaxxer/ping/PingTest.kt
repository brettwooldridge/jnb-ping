package com.zaxxer.ping

import com.zaxxer.ping.impl.*
import com.zaxxer.ping.impl.util.dumpBuffer
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
      assertEquals(128, Struct.size(Fd_set()))

      val buffer = ByteBuffer.allocateDirect(128)
      val fdSet = Fd_set()
      fdSet.useMemory(runtime.memoryManager.newPointer(buffer))
      FD_SET(76, fdSet)
      dumpBuffer("fd_set memory dump:", buffer)
      assertEquals(4096, fdSet.fds_bits[2].get())
   }

   @Test
   @Throws(IOException::class)
   fun pingTest1() {
      val semaphore = Semaphore(2)

      class PingHandler : PingResponseHandler {
         override fun onResponse(pingTarget : PingTarget, rtt : Double, bytes : Int, seq : Int) {
            println("  ${Thread.currentThread()} $bytes bytes from $pingTarget: icmp_seq=$seq time=$rtt")

            println("  ${Thread.currentThread()} Calling semaphore.release()\n")
            semaphore.release()
         }

         override fun onTimeout(pingTarget : PingTarget) {
            println("  ${Thread.currentThread()} Timeout")
            semaphore.release()
         }

         override fun onError(pingTarget : PingTarget, message : String) {
            println("  ${Thread.currentThread()} Error: $message")
         }
      }

      val pinger = IcmpPinger(PingHandler())

      val selectorThread = Thread( {pinger.runSelector()})
      selectorThread.isDaemon = false
      selectorThread.start()

      val pingTargets = arrayOf(
         PingTarget(InetAddress.getByName("192.168.1.4")),
         PingTarget(InetAddress.getByName("192.168.1.5"))
      )

      for (i in 0..(100 * pingTargets.size)) {
         if (!semaphore.tryAcquire()) {
            println("$i: Blocking on semaphore.acquire()")
            semaphore.acquire()

            // TimeUnit.MILLISECONDS.sleep(30)
         }
         println("$i: Calling pinger.ping(${pingTargets[i % 2].inetAddress})")
         pinger.ping(pingTargets[i % 2])
      }

      while (pinger.isPending()) Thread.sleep(500)

      pinger.stopSelector()
   }
}
