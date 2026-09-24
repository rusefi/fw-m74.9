package com.rusefi.m749;

import com.fazecast.jSerialComm.SerialPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;

/** Owned Lawicel serial channel. Parsing preserves partial lines across polls. */
final class SlcanTransport implements RawCanTransport {
    interface Port extends AutoCloseable {
        int read(byte[] buffer) throws IOException;
        void write(byte[] data) throws IOException;
        void close() throws IOException;
    }

    private final Port port;
    private final int bus;
    private final Queue<Frame> frames = new ArrayDeque<>();
    private final StringBuilder line = new StringBuilder();
    private final byte[] input = new byte[4096];
    private int acknowledgements;
    private boolean closed;
    private boolean recoveringClose;

    SlcanTransport(Port port, int bus) {
        if (bus < 1 || bus > 3) { throw new IllegalArgumentException("SLCAN bus must be 1..3"); }
        this.port = port;
        this.bus = bus;
    }

    static SlcanTransport open(String name, int baud, int bus) throws IOException {
        SerialPort serial = SerialPort.getCommPort(name);
        serial.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        serial.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        serial.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
        if (!serial.openPort()) { throw new IOException("Cannot open SLCAN serial port " + name); }
        Port port = new Port() {
            public int read(byte[] buffer) throws IOException {
                if (!serial.isOpen()) { throw new IOException("SLCAN serial port disconnected"); }
                int available = serial.bytesAvailable();
                if (available < 0) { throw new IOException("SLCAN serial read failed"); }
                if (available == 0) { return 0; }
                int count = serial.readBytes(buffer, Math.min(available, buffer.length));
                if (count < 0) { throw new IOException("SLCAN serial read failed"); }
                return count;
            }
            public void write(byte[] data) throws IOException {
                if (!serial.isOpen() || serial.writeBytes(data, data.length) != data.length) {
                    throw new IOException("SLCAN serial write failed or was incomplete");
                }
            }
            public void close() throws IOException {
                if (!serial.closePort()) { throw new IOException("Cannot close SLCAN serial port"); }
            }
        };
        SlcanTransport transport = new SlcanTransport(port, bus);
        try {
            // Explicit port only: never probe unrelated serial devices.
            transport.initialize();
            return transport;
        } catch (IOException e) {
            try { port.close(); } catch (IOException close) { e.addSuppressed(close); }
            throw e;
        }
    }

    void initialize() throws IOException {
        command("C");
        command("S6");
        command("O");
    }

    private void command(String value) throws IOException {
        acknowledgements = 0;
        recoveringClose = value.equals("C");
        try {
            write(value);
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (acknowledgements == 0) {
                pump();
                if (System.nanoTime() >= deadline) { throw new IOException("SLCAN " + value + " acknowledgement timeout"); }
                try { Thread.sleep(1); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("SLCAN open interrupted", e); }
            }
        } finally { recoveringClose = false; }
    }

    private void write(String value) throws IOException {
        if (closed) { throw new IOException("SLCAN is closed"); }
        port.write((value + "\r").getBytes(StandardCharsets.US_ASCII));
    }

    public void sendCan(int id, byte[] data) throws IOException {
        if (id < 0 || id > 0x7FF || data.length > 8) { throw new IOException("Invalid classic CAN frame"); }
        StringBuilder command = new StringBuilder(bus == 1 ? "" : bus == 2 ? "&" : "$");
        command.append(String.format("t%03X%X", id, data.length));
        for (byte value : data) { command.append(String.format("%02X", value & 255)); }
        write(command.toString());
    }

    public Frame receiveCan() throws IOException {
        pump();
        return frames.poll();
    }

    private void pump() throws IOException {
        int count = port.read(input);
        for (int i = 0; i < count; i++) {
            int value = input[i] & 255;
            if (value == 7) {
                // Some adapters reject C when the channel is already closed.
                if (recoveringClose) { acknowledgements++; line.setLength(0); continue; }
                throw new IOException("SLCAN adapter rejected a command or CAN transmission");
            }
            if (value == '\r') {
                accept(line.toString());
                line.setLength(0);
            } else if (value != '\n') {
                if (value < 32 || value > 126 || line.length() >= 64) { throw new IOException("Malformed SLCAN line"); }
                line.append((char) value);
            }
        }
    }

    private void accept(String value) throws IOException {
        if (value.isEmpty() || value.equals("z") || value.equals("Z")) { acknowledgements++; return; }
        if (value.matches("[VN][0-9A-Fa-f]{4}")) { return; }
        if (value.matches("F[0-9A-Fa-f]{2}")) {
            if (!value.equalsIgnoreCase("F00")) { throw new IOException("SLCAN error status: " + value); }
            return;
        }
        int sourceBus = 1;
        if (value.charAt(0) == '&' || value.charAt(0) == '$') {
            sourceBus = value.charAt(0) == '&' ? 2 : 3;
            value = value.substring(1);
        }
        if (value.isEmpty()) { throw new IOException("Malformed SLCAN frame"); }
        char type = value.charAt(0);
        boolean extended = type == 'T' || type == 'R', remote = type == 'r' || type == 'R';
        if (type != 't' && type != 'T' && type != 'r' && type != 'R') { throw new IOException("Unexpected SLCAN line: " + value); }
        int digits = extended ? 8 : 3;
        try {
            if (!value.substring(1).matches("[0-9a-fA-F]+")) { throw new IllegalArgumentException(); }
            int id = Integer.parseInt(value.substring(1, digits + 1), 16);
            int dlc = Integer.parseInt(value.substring(digits + 1, digits + 2), 16);
            if (dlc > 8 || id > (extended ? 0x1FFFFFFF : 0x7FF)) { throw new IllegalArgumentException(); }
            int end = digits + 2 + (remote ? 0 : 2 * dlc);
            if (value.length() != end && value.length() != end + 4) { throw new IllegalArgumentException(); }
            if (sourceBus != bus || extended || remote) { return; }
            // Only diagnostic and paired-authorization traffic is consumed here.
            if (id != 0x7E8 && id != 0x713 && id != 0x714) { return; }
            byte[] data = new byte[dlc];
            for (int i = 0; i < dlc; i++) {
                data[i] = (byte) Integer.parseInt(value.substring(digits + 2 + i * 2, digits + 4 + i * 2), 16);
            }
            if (frames.size() >= 4096) { throw new IOException("SLCAN receive queue overflow; reduce read speed"); }
            frames.add(new Frame(id, data));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new IOException("Malformed SLCAN frame: " + value, e);
        }
    }

    public void close() throws IOException {
        if (closed) { return; }
        try { write("C"); }
        finally { closed = true; port.close(); }
    }
}
