package com.zaxxer.ping;

import jnr.unixsocket.UnixSocketAddress;
import org.junit.Test;

import java.io.IOException;

public class PingTest {
   @Test
   public void pingTest1() throws IOException {
      IcmpPinger pinger = new IcmpPinger();

      class PingHandler implements IcmpPinger.PingResponseHandler
      {
         public void onResponse(int rtt) {
            System.err.println("Response rtt: " + rtt);
         }

         public void onTimeout() {
            System.err.println("Timeout");
         }

         public void onError() {
            System.err.println("Error");
         }
      }

      UnixSocketAddress
      pinger.ping(new PingHandler());
   }
}
