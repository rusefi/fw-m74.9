package com.rusefi.m749;

import com.fazecast.jSerialComm.SerialPort;
import com.rusefi.io.can.CanAddress;
import com.rusefi.io.can.ClassicCanFrame;
import com.rusefi.io.can.slcan.SlcanCodec;
import com.rusefi.io.can.slcan.SlcanSetup;
import com.rusefi.io.can.slcan.SlcanVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;

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
    private int inputPosition, inputCount;
    private int acknowledgements;
    private boolean closed;
    private boolean recoveringClose;
    private boolean discardingStartupLine;
    private final Consumer<String> log;
    private String initializingCommand;
    private long receivedBytes;
    private boolean canable;
    private boolean versionReceived;
    private String adapterVersion;

    private static final class AckTimeout extends IOException {
        AckTimeout(String message) { super(message); }
    }

    static final class CommandRejected extends IOException {
        CommandRejected() { super("SLCAN adapter rejected a command or CAN transmission"); }
    }

    SlcanTransport(Port port, int bus) {
        this(port, bus, message -> { });
    }

    SlcanTransport(Port port, int bus, Consumer<String> log) {
        if (bus < 1 || bus > 3) { throw new IllegalArgumentException("SLCAN bus must be 1..3"); }
        this.log = log;
        this.port = port;
        this.bus = bus;
    }

    static SlcanTransport open(String name, int baud, int bus, Consumer<String> log) throws IOException {
        log.accept("SLCAN opening " + name + ": baud=" + baud +
                ", 8N1, flow control=disabled, nonblocking reads, CAN bus=" + bus);
        SerialPort serial = SerialPort.getCommPort(name);
        serial.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        serial.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        serial.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
        if (!serial.openPort()) {
            throw new IOException("Cannot open SLCAN serial port " + name + "; native error=" + serial.getLastErrorCode());
        }
        log.accept("SLCAN " + name + " opened; queued RX bytes=" + serial.bytesAvailable());
        // A newly attached port may contain partial or terminal-translated data
        // from before raw serial configuration. No request has been sent yet.
        serial.flushIOBuffers();
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
                log.accept("SLCAN closing " + name + "; open=" + serial.isOpen() +
                        ", queued RX bytes=" + serial.bytesAvailable() + ", native error=" + serial.getLastErrorCode());
                if (!serial.closePort()) { throw new IOException("Cannot close SLCAN serial port"); }
            }
        };
        SlcanTransport transport = new SlcanTransport(port, bus, log);
        try {
            // Explicit port only: never probe unrelated serial devices.
            transport.initialize();
            return transport;
        } catch (IOException e) {
            log.accept("SLCAN initialization failed before ECU communication: " + e.getMessage());
            log.accept("No ECU requests were sent; this adapter initialization failure does not require an ECU power cycle.");
            try { port.close(); } catch (IOException close) { e.addSuppressed(close); }
            throw e;
        }
    }

    void initialize() throws IOException {
        boolean closeAcknowledged = true;
        try { command("C"); }
        catch (AckTimeout e) {
            closeAcknowledged = false;
            log.accept("No close acknowledgement; checking for CANable firmware with V");
            command("V");
            if (!SlcanVersion.isCanableFamily(adapterVersion)) { throw e; }
            canable = true;
            if (bus != 1) { throw new IOException("CANable supports only SLCAN bus 1"); }
            log.accept("CANable firmware detected: commands have no acknowledgements; checking version replies during setup");
        }
        if (closeAcknowledged) {
            // An acknowledged WeAct still needs A1. Probe V before opening CAN so
            // the vendor-specific command is never sent to an unknown adapter.
            try { command("V", 700); }
            catch (AckTimeout | CommandRejected e) {
                log.accept("SLCAN version unavailable; skipping adapter-specific setup: " + e.getMessage());
            }
        }
        SlcanSetup.openClosedChannel(adapterVersion, 6, this::command, log);
    }

    private void command(String value) throws IOException {
        command(value, 2_000);
    }

    private void command(String value, int timeoutMs) throws IOException {
        acknowledgements = 0;
        versionReceived = false;
        recoveringClose = value.equals("C");
        initializingCommand = value;
        long start = System.nanoTime();
        long before = receivedBytes;
        boolean requireVersion = canable || value.equals("V");
        log.accept("SLCAN TX " + value + "<CR>; waiting up to " + timeoutMs + " ms for " +
                (requireVersion ? "version reply" : "acknowledgement"));
        try {
            write(value);
            if (canable && !value.equals("V")) { write("V"); }
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (requireVersion ? !versionReceived : acknowledgements == 0) {
                pump();
                if (System.nanoTime() >= deadline) { throw new AckTimeout("SLCAN " + value + " reply timeout after " +
                        (System.nanoTime() - start) / 1_000_000 + " ms; received=" + (receivedBytes - before) +
                        " bytes, partial line=" + hex(line.toString().getBytes(StandardCharsets.US_ASCII), line.length())); }
                try { Thread.sleep(1); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("SLCAN open interrupted", e); }
            }
            log.accept("SLCAN " + value + (requireVersion ? " version reply after " : " acknowledged after ") + (System.nanoTime() - start) / 1_000_000 +
                    " ms; received=" + (receivedBytes - before) + " bytes");
        } finally {
            if (recoveringClose) { line.setLength(0); discardingStartupLine = false; }
            recoveringClose = false;
            initializingCommand = null;
        }
    }

    private void write(String value) throws IOException {
        if (closed) { throw new IOException("SLCAN is closed"); }
        port.write((value + "\r").getBytes(StandardCharsets.US_ASCII));
    }

    public void sendCan(int id, byte[] data) throws IOException {
        if (id < 0 || id > 0x7FF || data.length > 8) { throw new IOException("Invalid classic CAN frame"); }
        write(SlcanCodec.encode(new ClassicCanFrame(new CanAddress(id, false), data), bus - 1));
    }

    public Frame receiveCan() throws IOException {
        pump();
        return frames.poll();
    }

    private void pump() throws IOException {
        if (inputPosition == inputCount) {
            inputCount = port.read(input);
            inputPosition = 0;
            receivedBytes += inputCount;
            if (inputCount > 0 && initializingCommand != null) {
                log.accept("SLCAN RX while waiting for " + initializingCommand + ": " + inputCount + " bytes: " + hex(input, inputCount));
            }
        }
        // Keep the unread suffix if a BELL interrupts this pump. A readiness
        // retry must still parse subsequent frames and report malformed input.
        while (inputPosition < inputCount) {
            int value = input[inputPosition++] & 255;
            if (value == 7) {
                // Some adapters reject C when the channel is already closed.
                if (recoveringClose) { acknowledgements++; line.setLength(0); continue; }
                if (line.length() != 0) {
                    throw new IOException("SLCAN rejection interrupted a partial line");
                }
                throw new CommandRejected();
            }
            if (value == '\r') {
                if (!discardingStartupLine) {
                    try { accept(line.toString()); }
                    catch (IOException e) {
                        if (!recoveringClose || !(e.getMessage().startsWith("Malformed SLCAN") ||
                                e.getMessage().startsWith("Unexpected SLCAN line"))) { throw e; }
                        log.accept("Discarding incomplete startup line while closing CAN: " + e.getMessage());
                    }
                }
                line.setLength(0);
                discardingStartupLine = false;
            } else if (value != '\n') {
                if (discardingStartupLine) { continue; }
                int limit = initializingCommand == null ? 64 : 128;
                if (value < 32 || value > 126 || line.length() >= limit) {
                    if (!recoveringClose) { throw new IOException("Malformed SLCAN line"); }
                    line.setLength(0);
                    discardingStartupLine = true;
                    continue;
                }
                line.append((char) value);
            }
        }
    }

    private static String hex(byte[] bytes, int count) {
        if (count == 0) { return "<empty>"; }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < Math.min(count, 64); i++) {
            if (i > 0) { result.append(' '); }
            result.append(String.format("%02X", bytes[i] & 255));
        }
        if (count > 64) { result.append(" ..."); }
        return result.toString();
    }

    private void accept(String value) throws IOException {
        if (initializingCommand != null && SlcanVersion.isVersionReply(value)) {
            // V identifies acknowledged WeAct adapters for A1 and is also the
            // setup barrier when a CANable-family adapter omits acknowledgements.
            adapterVersion = value;
            versionReceived = true;
            log.accept("SLCAN version: " + value);
            return;
        }
        if (value.isEmpty() || value.equals("z") || value.equals("Z")) { acknowledgements++; return; }
        if (value.matches("[VN][0-9A-Fa-f]{4}")) { return; }
        if (value.matches("F[0-9A-Fa-f]{2}")) {
            if (!value.equalsIgnoreCase("F00")) { throw new IOException("SLCAN error status: " + value); }
            return;
        }
        String frameLine = value;
        if (value.charAt(0) == '&' || value.charAt(0) == '$') {
            value = value.substring(1);
        }
        if (value.isEmpty()) { throw new IOException("Malformed SLCAN frame"); }
        char type = value.charAt(0);
        if (type != 't' && type != 'T' && type != 'r' && type != 'R') { throw new IOException("Unexpected SLCAN line: " + value); }
        SlcanCodec.Frame frame = SlcanCodec.decode(frameLine);
        if (frame == null) {
            throw new IOException("Malformed SLCAN frame: " + value);
        }
        if (frame.busIndex != bus - 1 || frame.address.isExtended() || frame.rtr) { return; }
        int id = frame.address.getId();
        // Only diagnostic and paired-authorization traffic is consumed here.
        if (id != 0x7E8 && id != 0x713 && id != 0x714) { return; }
        if (frames.size() >= 4096) { throw new IOException("SLCAN receive queue overflow; reduce read speed"); }
        frames.add(new Frame(id, frame.data));
    }

    public void close() throws IOException {
        if (closed) { return; }
        try { write("C"); }
        finally { closed = true; port.close(); }
    }
}
