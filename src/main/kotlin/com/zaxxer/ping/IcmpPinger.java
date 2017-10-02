package com.zaxxer.ping;

import jnr.constants.platform.IPProto;
import jnr.enxio.channels.NativeSelectorProvider;
import jnr.ffi.Pointer;
import jnr.ffi.Runtime;
import jnr.ffi.Struct;
import jnr.ffi.Union;
import jnr.unixsocket.UnixDatagramChannel;
import jnr.unixsocket.UnixSocketOptions;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.AbstractSelector;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static jnr.constants.platform.IPProto.IPPROTO_ICMP;
import static jnr.constants.platform.ProtocolFamily.PF_INET;

/**
 * Created by brettw on 2017/09/27.
 */
public class IcmpPinger
{
   private static final String ANONYMOUS = String.valueOf('\000');

   private final AbstractSelector selector;

   interface PingResponseHandler
   {
      void onResponse(int rtt);

      void onTimeout();

      void onError();
   }

   public IcmpPinger()
   {
      try {
         selector = NativeSelectorProvider.getInstance().openSelector();
      } catch (IOException e) {
         throw new RuntimeException(e);
      }
   }

   public void startSelector()
   {
      try {
         while (selector.select() > 0) {
            final Set<SelectionKey> selectionKeys = selector.selectedKeys();
            final Iterator<SelectionKey> iterator = selectionKeys.iterator();
            while (iterator.hasNext()) {
               final SelectionKey key = iterator.next();
               PingActor pingActor = (PingActor) key.attachment();
               if (pingActor.getState() == PingActor.STATE_XMIT) {
                  pingActor.sendIcmp();
               }
               else {
                  pingActor.recvIcmp();
               }

               iterator.remove();
            }
         }
      } catch (IOException e) {
         throw new RuntimeException(e);
      }
   }

   public void stopSelector()
   {

   }

   /**
    * https://stackoverflow.com/questions/8290046/icmp-sockets-linux
    */
   public void ping(SocketAddress socketAddress, PingResponseHandler handler) throws IOException
   {
      UnixDatagramChannel pingChannel = UnixDatagramChannel.open(PF_INET, IPPROTO_ICMP.intValue());
      pingChannel.configureBlocking(false);
      pingChannel.setOption(UnixSocketOptions.IP_RECVTTL, 1);
      pingChannel.setOption(UnixSocketOptions.IP_RETOPTS, 1);
      pingChannel.register(selector, SelectionKey.OP_WRITE, new PingActor(pingChannel, socketAddress, selector, handler));
   }

   public void ping6(PingResponseHandler handler) throws IOException
   {
      UnixDatagramChannel pingDatagramChannel = UnixDatagramChannel.open(PF_INET, IPProto.IPPROTO_ICMPV6.intValue());
      pingDatagramChannel.configureBlocking(false);

   }

   private static final AtomicInteger sequence = new AtomicInteger();

   private static final class PingActor
   {
      private static final int STATE_XMIT = 0;
      private static final int STATE_RECV = 1;

      private final UnixDatagramChannel pingChannel;
      private final AbstractSelector selector;
      private final PingResponseHandler handler;
      private final ByteBuffer buf = ByteBuffer.allocateDirect(2048);
      private final SocketAddress socketAddress;
      private volatile int state = STATE_XMIT;

      PingActor(UnixDatagramChannel pingChannel, SocketAddress socketAddress, AbstractSelector selector, PingResponseHandler handler)
      {
         this.pingChannel = pingChannel;
         this.selector = selector;
         this.handler = handler;
         this.socketAddress = socketAddress;
      }

      int getState()
      {
         return state;
      }

      void sendIcmp()
      {
         try {
            icmphdr icmpHeader = new icmphdr(runtime);
            icmpHeader.type.set(ICMP_ECHO);
            icmpHeader.un.echo.id.set(1234); // arbitrary id ... really?
            icmpHeader.un.echo.sequence.set(sequence.incrementAndGet());

            final int icmpHdrSize = icmphdr.size(icmpHeader);

            buf.flip();
            Pointer bufPointer = runtime.getMemoryManager().newPointer(buf);

            Pointer icmpHeaderPointer = Struct.getMemory(icmpHeader);
            icmpHeaderPointer.transferTo(0, bufPointer, 0, icmpHdrSize);
            buf.limit(icmpHdrSize);
            // icmpHeaderPointer.transferTo(icmpHdrSize, bufPointer, 0);

            pingChannel.send(buf, socketAddress);

            state = STATE_RECV;
            pingChannel.register(selector, SelectionKey.OP_READ, this);
         }
         catch (IOException e) {
            handler.onError();
         }
      }

      void recvIcmp()
      {
         buf.flip();
         try {
            pingChannel.read(buf);
            System.err.println(HexDumpElf.dump(0, buf.array(), buf.arrayOffset(), buf.limit()));
         } catch (IOException e) {
            handler.onError();
         }
      }
   }

   static final jnr.ffi.Runtime runtime = jnr.ffi.Runtime.getSystemRuntime();

   static final short ICMP_ECHO = (short) 8; /* on both Linux and Mac OS X */

   /** Linux: netinetip_icmp.h */
   private static final class echo_struct extends Struct
   {
      public final Unsigned16 id = new Unsigned16();
      public final Unsigned16 sequence = new Unsigned16();

      echo_struct(Runtime runtime, Struct enclosing)
      {
         super(runtime, enclosing);
      }
   }

   private static final class frag_struct extends Struct
   {
      public final Unsigned16 unused = new Unsigned16();
      public final Unsigned16 mtu = new Unsigned16();

      frag_struct(Runtime runtime, Struct enclosing)
      {
         super(runtime, enclosing);
      }
   }

   private static final class icmp_union extends Union
   {
      public final echo_struct echo;
      public final frag_struct frag;

      icmp_union(Runtime runtime, Struct enclosing)
      {
         super(runtime);
         this.echo = inner(new echo_struct(runtime, enclosing));
         this.frag = inner(new frag_struct(runtime, enclosing));
      }
   }

   private static final class icmphdr extends Struct
   {
      public final Unsigned8 type = new Unsigned8();      /* message type */
      public final Unsigned8 code = new Unsigned8();      /* type sub-code */
      public final Unsigned16 checksum = new Unsigned16();
      public final icmp_union un;

      icmphdr(Runtime runtime)
      {
         super(runtime);
         this.un = new icmp_union(runtime, this);
      }
   }
}
