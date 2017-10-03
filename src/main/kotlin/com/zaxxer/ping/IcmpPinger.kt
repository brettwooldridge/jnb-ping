package com.zaxxer.ping

import com.zaxxer.ping.impl.BsdIcmp
import com.zaxxer.ping.impl.LibC.Companion.ICMP_MINLEN
import com.zaxxer.ping.impl.NativeIcmpSocketChannel
import com.zaxxer.ping.impl.bsd_cksum
import com.zaxxer.ping.impl.htons
import com.zaxxer.ping.impl.runtime
import jnr.enxio.channels.NativeSelectorProvider
import jnr.ffi.Platform
import jnr.ffi.Struct
import jnr.ffi.provider.ParameterFlags
import java.io.IOException
import java.lang.Error
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.spi.AbstractSelector
import java.util.concurrent.atomic.AtomicInteger

/**
 * Created by brettw on 2017/09/27.
 */
class IcmpPinger {

   private val selector : AbstractSelector

   interface PingResponseHandler {
      fun onResponse(rtt : Int)

      fun onTimeout()

      fun onError()
   }

   init {
      try {
         selector = NativeSelectorProvider.getInstance().openSelector()
      }
      catch (e : IOException) {
         throw RuntimeException(e)
      }

   }

   fun startSelector() {
      try {
         while (selector.select() > 0) {
            val selectionKeys = selector.selectedKeys()
            val iterator = selectionKeys.iterator()
            while (iterator.hasNext()) {
               val key = iterator.next()
               val pingActor = key.attachment() as PingActor
               if (pingActor.state == STATE_XMIT) {
                  pingActor.sendIcmp()
               }
               else {
                  pingActor.recvIcmp()
               }

               iterator.remove()
            }
         }
      }
      catch (e : IOException) {
         throw RuntimeException(e)
      }

   }

//   fun stopSelector() {
//
//   }

   /**
    * https://stackoverflow.com/questions/8290046/icmp-sockets-linux
    */
   @Throws(IOException::class)
   fun ping(addr : InetAddress, handler : PingResponseHandler) {
      val pingChannel = NativeIcmpSocketChannel.create(addr)
      pingChannel.configureBlocking(false)
      pingChannel.register(selector, SelectionKey.OP_WRITE, PingActor(selector, pingChannel, handler))
   }

   private class PingActor internal constructor(private val selector : AbstractSelector,
                                                private val pingChannel : NativeIcmpSocketChannel,
                                                private val handler : PingResponseHandler) {

      private val buf = ByteBuffer.allocateDirect(2048)
      @Volatile internal var state = STATE_XMIT
         private set

      internal fun sendIcmp() {
         try {
            val icmpHeader = if (Platform.getNativePlatform().isBSD) BsdIcmp() else BsdIcmp()
            val bufPointer = runtime.memoryManager.newPointer(buf)
            icmpHeader.useMemory(bufPointer)

            icmpHeader.icmp_type.set(ICMP_ECHO)
            icmpHeader.icmp_hun.ih_idseq.icd_id.set(1234) // arbitrary id ... really?
            icmpHeader.icmp_hun.ih_idseq.icd_seq.set(htons(sequence.incrementAndGet().toShort()))

            val icmpHdrSize = Struct.size(icmpHeader)

            val datalen = "hello".length

            val cc = ICMP_MINLEN + 0 /*phdr_len*/ + datalen;
            icmpHeader.icmp_cksum.set(bsd_cksum(buf, cc))

            buf.limit(icmpHdrSize + datalen)
            buf.position(icmpHeader.icmp_dun.id_data.offset().toInt())
            buf.put("hello".toByteArray())
            buf.position(0)


            val bytes = ByteArray(256)
            buf.get(bytes, 0, icmpHdrSize + datalen)
            println(HexDumpElf.dump(0, bytes, 0, 127))

            buf.flip()

            val rc = pingChannel.write(buf)
            if (rc != 0) {
               println("Non-zero return code from sendto(): $rc")
            }

            state = STATE_RECV
            pingChannel.register(selector, SelectionKey.OP_READ, this)
         }
         catch (e : Error) {
            handler.onError()
         }

      }

      internal fun recvIcmp() {
         try {
            pingChannel.read(buf)
            System.err.println(HexDumpElf.dump(0, buf.array(), buf.arrayOffset(), buf.limit()))
         }
         catch (e : IOException) {
            handler.onError()
         }

      }
   }

   companion object {
      private val sequence = AtomicInteger()

      internal val ICMP_ECHO = 8.toShort() /* on both Linux and Mac OS X */
      internal val STATE_XMIT = 0
      internal val STATE_RECV = 1
   }
}
