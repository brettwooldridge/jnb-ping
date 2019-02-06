# jnb-ping - Java Non-Blocking ICMP Ping

A non-blocking ICMP library for Java, using JNA to access native APIs, supporting thousands of simultaneous ICMP ping targets.  Written in Kotlin, but compatible with Java
(or any JVM-hosted language).

Currently, only Linux and MacOS X are supported.

Example:
```kotlin
class PingHandler : PingResponseHandler {
   override fun onResponse(pingTarget: PingTarget, responseTimeSec: Double, byteCount: Int, seq: Int) {
      System.out.printf("  ${Thread.currentThread()} $byteCount bytes from $pingTarget: icmp_seq=$seq time=%1.6f\n", responseTimeSec)
   }

   override fun onTimeout(pingTarget: PingTarget) {
      System.out.println("  ${Thread.currentThread()} Timeout $pingTarget")
   }
}

val pinger = IcmpPinger(PingHandler())
pinger.ping( PingTarget(InetAddress.getByName("8.8.8.8")) )
pinger.ping( PingTarget(InetAddress.getByName("youtube.com")) )

while (pinger.isPendingWork()) Thread.sleep(500)

pinger.stopSelector()
```
