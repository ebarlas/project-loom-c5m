package loomtest;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public class EchoClient {

    final Args args;
    final LongAdder connections;
    final LongAdder messages;
    final AtomicReference<Exception> error;

    EchoClient(Args args) {
        this.args = args;
        connections = new LongAdder();
        messages = new LongAdder();
        error = new AtomicReference<>();
    }

    void run() throws InterruptedException {
        for (int i = 0; i < args.portCount; i++) {
            int port = args.port + i;
            for (int j = 0; j < args.numConnections; j++) {
                int id = i * args.portCount + j;
                Thread.startVirtualThread(() -> connect(id, port));
            }
        }
        long start = System.nanoTime();
        while (error.get() == null) {
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            System.out.printf("[%d] connections=%d, messages=%d\n", elapsed, connections.sum(), messages.sum());
            Thread.sleep(1_000);
        }
        error.get().printStackTrace();
    }

    void connect(int id, int port) {
        try (Socket s = new Socket()) {
            Thread.sleep((int) (Math.random() * args.warmUp));
            s.connect(new InetSocketAddress(args.host, port), args.socketTimeout);
            s.setSoTimeout(args.socketTimeout);
            connections.add(1);
            ByteBuffer buffer = ByteBuffer.allocate(4);
            buffer.putInt(id);
            byte[] writeBuffer = buffer.array();
            byte[] readBuffer = new byte[4];
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            while (error.get() == null) {
                out.write(writeBuffer);
                int offset = 0;
                while (offset < readBuffer.length) {
                    int numBytes = in.read(readBuffer, offset, readBuffer.length - offset);
                    assert numBytes >= 0;
                    offset += numBytes;
                }
                assert Arrays.equals(writeBuffer, readBuffer);
                messages.add(1);
                Thread.sleep(args.sleep);
            }
        } catch (Exception e) {
            error.set(e);
        }
    }

    record Args(String host, int port, int portCount, int numConnections, int socketTimeout, int warmUp, int sleep) {
        static Args parse(String[] args) {
            return new Args(
                    args.length >= 1 ? args[0] : "localhost",
                    args.length >= 2 ? Integer.parseInt(args[1]) : 8000,
                    args.length >= 3 ? Integer.parseInt(args[2]) : 1,
                    args.length >= 4 ? Integer.parseInt(args[3]) : 1,
                    args.length >= 5 ? Integer.parseInt(args[4]) : 10_000,
                    args.length >= 6 ? Integer.parseInt(args[5]) : 1_000,
                    args.length >= 7 ? Integer.parseInt(args[6]) : 1_000);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Args a = Args.parse(args);
        System.out.println(a);
        new EchoClient(a).run();
    }

}
