## How to publish (note to self)

```bash
bazel build //:dist_maven_artifacts
```

This generates versioned artifacts in `bazel-bin/dist/`:
- `jnb-ping-<version>.jar`
- `jnb-ping-<version>-sources.jar`
- `jnb-ping-<version>-javadoc.jar`
- `jnb-ping-<version>.pom`

Sign with GPG and upload to Maven Central (Sonatype OSSRH).

## Performance Analysis

The library is already very well tuned — single-threaded event loop, primitive collections
to avoid autoboxing, direct ByteBuffers for zero-copy I/O, pre-built packet templates, and
compiler flags to strip assertions. The items below are what remains.

### Bug: `recvIcmp` errno check (IcmpPinger.kt:443)

```kotlin
if (errno == Errno.EINTR.intValue() && errno == Errno.EAGAIN.intValue()) {
```

This is always false — `errno` can't be both values simultaneously. Should be separate checks:
EAGAIN → return false (no more data, normal for non-blocking), EINTR → return true (retry).
As written, EINTR exits the receive loop and falls back to `poll()`, costing an extra syscall
round-trip. EAGAIN also logs a spurious FINE-level "Error code" message on every normal
drain completion.

### Hot-path allocations

**Wakeup pipe byte arrays (IcmpPinger.kt:491, 497)** — `ByteArray(1)` is allocated on every
`wakeup()` call and on every iteration of the `wakeupReceived()` drain loop. These are called
on the critical path of every ping submission and every selector wake. Pre-allocating one byte
array for the write side (can be shared under the existing `synchronized(pipefd)`) and one for
the read side (selector thread only) eliminates these.

**`Pointer.slice()` in receive path (IcmpPinger.kt:457)** — On every IPv4 receive,
`socketBufferPointer.slice(headerLen)` allocates a new Pointer. On Linux, `headerLen` is
always 0, so `icmp.useMemory(socketBufferPointer)` could be used directly (no slice). On BSD,
the typical case is `headerLen == 20` (no IP options) — a pre-sliced pointer at that offset
could cover the common case, falling back to a fresh `slice()` only when options are present.

**`LinkedBlockingQueue` node allocation** — LBQ allocates a `Node` wrapper per enqueue. Under
high throughput (thousands of pings/sec), this creates steady GC pressure. A JCTools
`MpscArrayQueue` (multiple-producer single-consumer, array-backed, bounded) would provide the
same semantics with zero allocation on enqueue/dequeue. Trade-off: adds a dependency.

### Syscall and computation savings

**`Struct.size()` per send (IcmpPinger.kt:427)** — `Struct.size(pingTarget.sockAddr)` is
called on every `sendto()`. The sockAddr size is fixed per type (16 for IPv4, 28 for IPv6).
Caching this as a field on PingTarget (or in two constants) eliminates the repeated
computation.

**`nanoTime()` per timeout (IcmpPinger.kt:363)** — `processTimeouts()` calls `nanoTime()` on
every iteration. When processing a batch of expired targets, a single call before the loop is
sufficient — the expired targets are already past their deadline, so sub-microsecond freshness
doesn't matter.

**Buffer over-copy in sendIcmp (IcmpPinger.kt:394)** — `transferTo` copies `BUFFER_SIZE`
(128) bytes, but only `SIZEOF_STRUCT_IP + SEND_PACKET_SIZE` (84) bytes are meaningful. Minor
— it's a memcpy of 44 extra bytes — but trivial to fix.

**`SEQUENCE_SEQUENCE` doesn't need to be atomic** — It's only read/written from the selector
thread (in `sendIcmp`). A plain `Int` field on `IcmpPinger` would avoid the `lock xadd`
overhead. `ID_SEQUENCE` does need to be atomic since it's used in the PingTarget copy
constructor called from user threads.

### Larger-scale opportunities

**`sendmmsg`/`recvmmsg` batching (Linux only)** — The current code makes one `sendto()` and
one `recvmsg()` syscall per packet. Linux's `sendmmsg()`/`recvmmsg()` can batch multiple
packets in a single syscall, significantly reducing syscall overhead at high throughput.
Would require extending the `LibC` interface and is Linux-only, so the existing per-packet
path would remain for macOS.

**`eventfd` instead of pipe (Linux only)** — `eventfd` uses one FD instead of two, has atomic
counter semantics (read resets to 0, no drain loop needed), and avoids the per-wakeup byte
array allocation entirely. macOS doesn't support `eventfd`, so the pipe path would remain as
fallback. Complexity cost may not be worth the modest gain.

### Not worth changing

- `poll()` with 3 FDs — `epoll`/`kqueue` have higher setup cost and no advantage at n=3.
- Pre-built packet template + copy-per-send — already optimal for the single-buffer design.
- PingTarget defensive copy in `ping()` — necessary by design, and the internal constructor
  shares the sockAddr reference, making it lightweight.
- JNR Struct field access overhead — provides type safety with acceptable cost.
- `htons`/`htoni` byte swaps — JVM intrinsics, compile to single BSWAP instructions.
