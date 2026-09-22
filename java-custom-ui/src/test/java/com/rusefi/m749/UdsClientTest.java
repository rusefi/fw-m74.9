package com.rusefi.m749;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import static com.rusefi.m749.M749Identification.bytes;
import static org.junit.jupiter.api.Assertions.*;

class UdsClientTest {
    static class Bus implements DiagnosticTransport, M749Identification.Timing {
        Queue<byte[]> rx = new ArrayDeque<>();
        List<byte[]> tx = new ArrayList<>();
        List<Long> pauses = new ArrayList<>();
        Consumer<byte[]> respond = frame -> {};
        long now;
        public void send(byte[] frame) { tx.add(frame.clone()); respond.accept(frame); }
        public byte[] receive() { now++; return rx.poll(); }
        public void close() { }
        public long now() { return now; }
        public void pause(long ms) { now += ms; pauses.add(ms); }
        UdsClient client() { return new UdsClient(this, this); }
    }

    @Test void honorsFlowControlBlocksStminAndSequenceWrap() throws Exception {
        Bus bus = new Bus();
        byte[] payload = new byte[180];
        payload[0] = 0x36;
        for (int i = 2; i < payload.length; i++) { payload[i] = (byte) i; }
        List<Byte> received = new ArrayList<>();
        int[] frames = {0};
        bus.respond = frame -> {
            int first = frame[0] & 255;
            int start = first >= 0x20 ? 1 : 2;
            for (int i = start; i < 8 && received.size() < payload.length; i++) { received.add(frame[i]); }
            if (first < 0x20) {
                bus.rx.add(bytes(0x31, 0, 0));
                bus.rx.add(bytes(0x30, 4, 0xF1));
            } else {
                assertEquals(0x20 | ((++frames[0]) & 15), first);
                if (received.size() == payload.length) { bus.rx.add(bytes(2, 0x76, 0)); }
                else if (frames[0] % 4 == 0) { bus.rx.add(bytes(0x30, 4, 3)); }
            }
        };
        assertArrayEquals(bytes(0x76, 0), bus.client().exchange(payload, bytes(0x76, 0), 10_000));
        for (int i = 0; i < payload.length; i++) { assertEquals(payload[i], received.get(i)); }
        assertTrue(bus.pauses.contains(1L));
        assertTrue(bus.pauses.contains(3L));
        assertTrue(frames[0] > 16);
    }

    @Test void assemblesMultiframeResponseAndSendsFlowControl() throws Exception {
        Bus bus = new Bus();
        bus.rx.add(bytes(0x10, 10, 0x62, 0xF1, 0x98, 1, 2, 3));
        bus.rx.add(bytes(0x21, 4, 5, 6, 7));
        assertArrayEquals(bytes(0x62, 0xF1, 0x98, 1, 2, 3, 4, 5, 6, 7),
                bus.client().exchange(bytes(0x22, 0xF1, 0x98), bytes(0x62, 0xF1, 0x98), 10_000));
        assertEquals(0x30, bus.tx.get(1)[0]);
    }

    @Test void pendingAndUnrelatedMessagesCannotExtendOverallDeadline() {
        Bus bus = new Bus();
        for (int i = 0; i < 100; i++) { bus.rx.add(bytes(3, 0x7F, 0x31, 0x78)); }
        assertThrows(IOException.class, () -> bus.client().exchange(bytes(0x31, 1), bytes(0x71, 1), 20));
        assertEquals(1, bus.tx.size());
        assertTrue(bus.now <= 21);
    }

    @Test void rejectsOverflowWaitFloodReservedStminAndMissingFlowControlWithoutRetry() {
        for (byte[] fc : new byte[][]{bytes(0x32, 0, 0), bytes(0x30, 0, 0x80), bytes(0x30), bytes(2, 0x76, 0)}) {
            Bus bus = new Bus();
            bus.rx.add(fc);
            assertThrows(IOException.class, () -> bus.client().exchange(new byte[20], bytes(0x76, 0), 500));
            assertEquals(1, bus.tx.size());
        }
        Bus waits = new Bus();
        for (int i = 0; i < 4; i++) { waits.rx.add(bytes(0x31, 0, 0)); }
        assertThrows(IOException.class, () -> waits.client().exchange(new byte[20], bytes(0x76, 0), 500));
        assertEquals(1, waits.tx.size());
        Bus silent = new Bus();
        assertThrows(IOException.class, () -> silent.client().exchange(new byte[20], bytes(0x76, 0), 500));
        assertEquals(1, silent.tx.size());
    }

    @Test void rejectsOutOfOrderResponsesAndNegativeReply() {
        Bus bus = new Bus();
        bus.rx.add(bytes(0x10, 10, 0x62, 0xF1, 0x98, 1, 2, 3));
        bus.rx.add(bytes(0x22, 4, 5, 6, 7));
        assertThrows(IOException.class, () -> bus.client().exchange(bytes(0x22, 0xF1, 0x98), bytes(0x62, 0xF1, 0x98), 500));
        Bus negative = new Bus();
        negative.rx.add(bytes(3, 0x7F, 0x36, 0x72));
        UdsClient.NegativeResponse error = assertThrows(UdsClient.NegativeResponse.class,
                () -> negative.client().exchange(bytes(0x36, 0, 1), bytes(0x76, 0), 500));
        assertEquals(0x72, error.code);
    }
}
