package com.zaxxer.ping

import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class PingTest {
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

      Thread.sleep(300000)
   }
}
