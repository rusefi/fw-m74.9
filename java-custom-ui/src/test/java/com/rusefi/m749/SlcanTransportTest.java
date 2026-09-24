package com.rusefi.m749;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.rusefi.m749.M749Identification.bytes;

class SlcanTransportTest {
    static class Port implements SlcanTransport.Port {
        Queue<byte[]> incoming = new ArrayDeque<>();
        List<String> writes = new ArrayList<>();
        boolean acknowledge, closed;
        void offer(String value) { incoming.add(value.getBytes(StandardCharsets.US_ASCII)); }
        public int read(byte[] buffer) {
            byte[] next = incoming.poll();
            if (next == null) { return 0; }
            System.arraycopy(next, 0, buffer, 0, next.length); return next.length;
        }
        public void write(byte[] data) {
            writes.add(new String(data, StandardCharsets.US_ASCII));
            if (acknowledge) { offer("\r"); }
        }
        public void close() { closed = true; }
    }

    @Test void initializesBitrateAndEncodesTaggedFramesAndCloses() throws Exception {
        Port port = new Port(); port.acknowledge = true;
        try (SlcanTransport t = new SlcanTransport(port, 2)) {
            t.initialize(); t.sendCan(0x7e0, bytes(7, 0x23, 8, 0, 0, 4, 0, 4));
            assertEquals(Arrays.asList("C\r", "S6\r", "O\r", "&t7E080723080000040004\r"), port.writes);
        }
        assertTrue(port.closed); assertEquals("C\r", port.writes.get(4));
    }

    @Test void preservesPartialLinesAndIgnoresEchoOtherBusRtrAndExtendedFrames() throws Exception {
        Port port = new Port(); SlcanTransport t = new SlcanTransport(port, 1);
        port.offer("\rz\rV1234\rF00\rt7E8205");
        assertNull(t.receiveCan()); assertNull(t.receiveCan());
        port.offer("630ABC\r&t7E8101\rr7E88\rT000007E8101\rt7E0101\rt123101\rt7E8110\r");
        RawCanTransport.Frame first = t.receiveCan();
        assertEquals(0x7e8, first.id); assertArrayEquals(bytes(5, 0x63), first.data);
        assertArrayEquals(bytes(0x10), t.receiveCan().data); assertNull(t.receiveCan());
    }

    @Test void rejectsBellStatusMalformedFramesAndOverlongPartialLine() {
        for (String input : new String[]{"\u0007", "F01\r", "t7E8900\r", "t7E8200\r", "t7E81GG\r",
                "tFFF100\r", "t7E810012\r", "garbage\r", "t".repeat(65)}) {
            Port port = new Port(); port.offer(input);
            assertThrows(IOException.class, () -> new SlcanTransport(port, 1).receiveCan(), input);
        }
    }

    @Test void busThreeOnlyAcceptsDollarFrames() throws Exception {
        Port port = new Port(); port.offer("t7E8101\r&t7E8102\r$t7E8103\r");
        SlcanTransport t = new SlcanTransport(port, 3);
        assertArrayEquals(bytes(3), t.receiveCan().data);
        assertNull(t.receiveCan());
    }

    @Test void alreadyClosedBellIsAcceptedButBitrateRejectionIsNot() throws Exception {
        Port closed = new Port() {
            public void write(byte[] data) { super.write(data); offer(writes.size() == 1 ? "\u0007" : "\r"); }
        };
        new SlcanTransport(closed, 1).initialize();
        assertEquals(Arrays.asList("C\r", "S6\r", "O\r"), closed.writes);
        Port badSpeed = new Port() {
            public void write(byte[] data) { super.write(data); offer(writes.size() == 1 ? "\r" : "\u0007"); }
        };
        assertThrows(IOException.class, () -> new SlcanTransport(badSpeed, 1).initialize());
        assertEquals(2, badSpeed.writes.size());
    }
}
