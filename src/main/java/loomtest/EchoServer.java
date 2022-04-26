package loomtest;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardSocketOptions;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

public class EchoServer {

    final Args args;
    final AtomicLong connections;
    final AtomicLong messages;

    EchoServer(Args args) {
        this.args = args;
        connections = new AtomicLong();
        messages = new AtomicLong();
    }

    void run() throws InterruptedException {
        for (int i = 0; i < args.portCount; i++) {
            int port = args.port + i;
            Thread.startVirtualThread(() -> serve(port));
        }
        long start = System.nanoTime();
        while (true) {
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            System.out.printf("[%d] connections=%d, messages=%d\n", elapsed, connections.get(), messages.get());
            Thread.sleep(1_000);
        }
    }

    void serve(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port, args.backlog, InetAddress.getByName(args.host))) {
            serverSocket.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            serverSocket.setOption(StandardSocketOptions.SO_REUSEPORT, true);
            while (true) {
                Socket socket = serverSocket.accept();
                connections.incrementAndGet();
                Thread.startVirtualThread(() -> handle(socket));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void handle(Socket socket) {
        try (Socket s = socket) {
            byte[] buffer = new byte[args.bufferSize];
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            while (true) {
                int numBytes = in.read(buffer);
                if (numBytes < 0) {
                    break;
                }
                out.write(buffer, 0, numBytes);
                messages.incrementAndGet();
            }
        } catch (Exception ignore) {
            // auto-close
        } finally {
            connections.decrementAndGet();
        }
    }

    record Args(String host, int port, int portCount, int backlog, int bufferSize) {
        static Args parse(String[] args) {
            return new Args(
                    args.length >= 1 ? args[0] : "0.0.0.0",
                    args.length >= 2 ? Integer.parseInt(args[1]) : 8000,
                    args.length >= 3 ? Integer.parseInt(args[2]) : 1,
                    args.length >= 4 ? Integer.parseInt(args[3]) : 0,
                    args.length >= 5 ? Integer.parseInt(args[4]) : 32);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Args a = Args.parse(args);
        System.out.println(a);
        new EchoServer(a).run();
    }

}
