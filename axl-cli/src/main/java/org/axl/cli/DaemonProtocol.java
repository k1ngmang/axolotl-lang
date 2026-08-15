package org.axl.cli;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * The messages between `axl` and a compiler daemon over a loopback socket. The launcher sends the
 * magic number, the protocol version, the daemon's token, the request and, for a compile request, its
 * fingerprint; the daemon closes the connection on a wrong token and otherwise answers with one byte.
 */
final class DaemonProtocol {

    static final int MAGIC = 0x41584c44;
    static final int VERSION = 1;
    static final String COMPILE = "compile";
    static final String STATUS = "status";
    static final String STOP = "stop";

    static final byte ACCEPTED = 'K';
    static final byte MISMATCH = 'M';
    /** Followed by text the job printed to System.out or System.err. */
    static final byte STDOUT = 'O';
    static final byte STDERR = 'E';
    /** Followed by whether the job succeeded and the main class of `run`, or an empty string. */
    static final byte RESULT = 'R';
    /** Followed by the reason: the launcher compiles the job itself. */
    static final byte FAILED = 'F';
    static final byte TEXT = 'S';

    static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    static final int ANSWER_TIMEOUT_MILLIS = 10_000;

    private DaemonProtocol() {}

    record Connection(Socket socket, DataInputStream in, DataOutputStream out) implements Closeable {
        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** A connection whose request the daemon accepted, or null when no daemon answers as the entry says. */
    static @Nullable Connection open(DaemonFiles.Entry entry, String request, @Nullable String fingerprint) {
        // After a crash the port may belong to another program, which would not answer for a while.
        if (!ProcessHandle.of(entry.pid()).map(ProcessHandle::isAlive).orElse(false)) {
            return null;
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), entry.port()), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(ANSWER_TIMEOUT_MILLIS);
            var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            writeString(out, entry.token());
            writeString(out, request);
            if (fingerprint != null) {
                writeString(out, fingerprint);
            }
            out.flush();
            if (in.read() == ACCEPTED) {
                return new Connection(socket, in, out);
            }
        } catch (IOException e) {
            // Not a daemon of this axl, or not running any more.
        }
        closeQuietly(socket);
        return null;
    }

    /** Asks for the status or the stop of a daemon: its answer, or null. */
    static @Nullable String ask(DaemonFiles.Entry entry, String request) {
        Connection connection = open(entry, request, null);
        if (connection == null) {
            return null;
        }
        try (connection) {
            return connection.in().read() == TEXT ? readString(connection.in(), 64 * 1024) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** writeUTF stops at 64 KB, which the output of a large build passes. */
    static void writeString(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    static String readString(DataInputStream in) throws IOException {
        return readString(in, Integer.MAX_VALUE - 8);
    }

    static String readString(DataInputStream in, int limit) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > limit) {
            throw new IOException("bad message from the compiler daemon");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Nothing to release.
        }
    }
}
