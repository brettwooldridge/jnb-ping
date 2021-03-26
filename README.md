# jnb-ping - Java Non-Blocking ICMP Ping

[![][Build Status img]][Build Status]
[![][license img]][license]
[![][Maven Central img]][Maven Central]
[![][Javadocs img]][Javadocs]

A non-blocking ICMP library for Java, using JNA to access native APIs, supporting thousands of simultaneous ICMP ping targets.  Written in Kotlin, but compatible with Java (or any JVM-hosted language).

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

Thread( { pinger.runSelector() } ).start()

pinger.ping( PingTarget(InetAddress.getByName("8.8.8.8")) )
pinger.ping( PingTarget(InetAddress.getByName("youtube.com")) )

while (pinger.isPendingWork()) Thread.sleep(500)

pinger.stopSelector()
```

The minimum supported Linux kernel version is v4.19.10. It *may* work with older kernels (some reported working on v3.13.), depending on the kernel configuration parameters, but only v4.19.10+ has been tested. I am fairly certain that IPv6 is not supported (by this library) on any Linux kernel version less than v4.19.

[Build Status]:https://travis-ci.org/brettwooldridge/jnb-ping
[Build Status img]:https://travis-ci.org/brettwooldridge/jnb-ping.svg?branch=master

[license]:LICENSE
[license img]:https://img.shields.io/badge/license-Apache%202-blue.svg

[Maven Central]:https://maven-badges.herokuapp.com/maven-central/com.zaxxer/jnb-ping
[Maven Central img]:https://maven-badges.herokuapp.com/maven-central/com.zaxxer/jnb-ping/badge.svg

[Javadocs]:http://javadoc.io/doc/com.zaxxer/jnb-ping
[Javadocs img]:http://javadoc.io/badge/com.zaxxer/jnb-ping.svg
