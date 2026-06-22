# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

jnb-ping is a high-performance, non-blocking ICMP ping library for the JVM. Written in Kotlin, it uses JNR/JNA to call native POSIX APIs (`poll`, `sendto`, `recvmsg`) directly, enabling thousands of simultaneous pings without thread-per-connection overhead. Supports Linux (kernel >= 4.19.10) and macOS.

Published to Maven Central as `com.zaxxer:jnb-ping`.

## Build & Test

```bash
bazel build //:jnb-ping                                   # compile library
bazel test //src/test/kotlin/com/zaxxer/ping:ping-tests   # tests (JUnit 5)
bazel build //:jnb_ping_maven                             # Maven export jar
bazel build //:dist_maven_artifacts                       # all publishing artifacts (jar, sources, javadoc, pom)
```

Tests require ICMP socket permissions. On Linux, set `sysctl net.ipv4.ping_group_range` (and `net.ipv6.ping_group_range` for IPv6). macOS works without special config. Some tests ping external hosts (8.8.8.8, youtube.com) so network access is required. Tests run with `local = True` (no sandbox) and `timeout = "long"`.

CI runs on CircleCI with a self-hosted Linux machine executor (`brettwooldridge/ubuntu`).

Bazel 8.7.x, Kotlin 2.1 (language version), JVM 21. Bzlmod for dependency management (MODULE.bazel, no WORKSPACE).

## Architecture

Single-threaded, event-driven I/O loop using Unix `poll()`:

- **`IcmpPinger`** (`src/main/kotlin/com/zaxxer/ping/IcmpPinger.kt`) — public API and event loop. User threads call `ping()` which enqueues to `LinkedBlockingQueue`; the selector thread (in `runSelector()`) polls for I/O readiness, sends ICMP packets, receives responses, and fires timeout callbacks. Self-pipe trick for cross-thread wakeup.
- **`IcmpNative`** (`src/main/kotlin/com/zaxxer/ping/impl/IcmpNative.kt`) — JNR struct definitions mirroring C structs (`struct icmp`, `struct ip`, `struct pollfd`, `struct sockaddr_in/in6`), LibC interface binding, byte-order helpers, ICMP checksum (RFC 1071). Platform-specific socket address structs for BSD vs Linux (different `sockaddr` layouts, checksum responsibility, IP header presence).
- **`WaitingTargetCollection`** (`src/main/kotlin/com/zaxxer/ping/impl/util/WaitingTargetCollection.kt`) — dual-indexed collection: `ShortObjectHashMap` (HPPC primitive map) for O(1) sequence-number lookup on response, `PriorityQueue` for O(log n) timeout processing. Not thread-safe; confined to selector thread.
- **`HexDumpElf`** — debug hex-dump utility for ByteBuffers.

Key design points:
- Separate IPv4/IPv6 sockets, queues, and waiting-target collections.
- Lazy socket creation (sockets opened on first ping of each IP family).
- `PingTarget.complete` is `@Volatile` to allow the priority queue to skip already-completed entries without removal (lazy cleanup).
- Kotlin compiler flags disable param/call/receiver assertions for performance (`-Xno-param-assertions`, `-Xno-call-assertions`, `-Xno-receiver-assertions`).
- Direct `ByteBuffer`s for zero-copy native I/O; packet template pre-built once and copied per send.
- HPPC `ShortObjectHashMap` avoids autoboxing overhead for sequence-number keys.

## Publishing

```bash
bazel build //:dist_maven_artifacts   # generates dist/ with versioned jar, sources, javadoc, pom
```

Artifacts land in `bazel-bin/dist/`. Version is read from the `VERSION` file at the repo root. Sign and upload to Maven Central (Sonatype OSSRH) manually or via CI script. The POM template is in `pom-template.xml`.
