package com.rusefi.m749;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.rusefi.m749.M749Identification.bytes;

class M749EcuProbeTest {
    private M749Monitor.Identification inspectOem(String build, boolean rusefi) throws Exception {
        List<Integer> requests = new ArrayList<>();
        M749Monitor.Identification result = M749EcuProbe.inspect(new M749Uploader.Connection() {
            public void pause(long ms) { fail("Identification must not pause for a session change"); }
            public byte[] exchange(byte[] request, byte[] prefix, long timeout) throws IOException {
                assertEquals(3, request.length);
                assertEquals(0x22, request[0], "Identification must only read DIDs");
                int did = (request[1] & 255) << 8 | request[2] & 255;
                requests.add(did);
                if (did == 0xF186) return bytes(0x62, 0xF1, 0x86, 1);
                if (did == 0xF189 && build != null) {
                    byte[] value = build.getBytes(StandardCharsets.US_ASCII);
                    byte[] response = Arrays.copyOf(prefix, 3 + value.length);
                    System.arraycopy(value, 0, response, 3, value.length);
                    return response;
                }
                if (did == 0xF192 && build != null && build.startsWith("I865LB52_w2404b1")) {
                    return bytes(0x62, 0xF1, 0x92, '8', '4', '5', '0', '0', '9', '4', '6', '1', '5');
                }
                if (did == 0xF1A4 && rusefi) return bytes(0x62, 0xF1, 0xA4, 'r', 'E', 'F', 'I');
                throw new UdsClient.NegativeResponse(0x22, 0x31);
            }
        }, s -> {});
        assertEquals(List.of(0xF186, 0xF189, 0xF192, 0xF1A4, 0xF1A0), requests);
        return result;
    }

    @Test void recognizesExactOemBuildsWithOptionalPadding() throws Exception {
        for (String build : new String[]{"I812NA01_w2243v21", "I812TA01_w2243v21", "I832GA01_w2304v2", "I865LB52_w2404b1"}) {
            for (String padding : new String[]{"", "\0\0  "}) {
                assertEquals(M749FirmwareDetection.Result.OEM, inspectOem(build + padding, false).firmware);
            }
        }
    }

    @Test void exposesExactI865IdentityForLocalPairSelection() throws Exception {
        M749Monitor.Identification result = inspectOem("I865LB52_w2404b1\0 ", false);
        assertEquals("I865LB52_w2404b1", result.software);
        assertEquals("8450094615", result.part);
        assertEquals(1, result.session);
    }

    @Test void loaderIdentityRetainsSessionAndPartWhenSoftwareDidIsUnavailable() throws Exception {
        M749Monitor.Identification result = M749EcuProbe.inspect(new M749Uploader.Connection() {
            public void pause(long ms) { fail("Read-only probe must not change sessions"); }
            public byte[] exchange(byte[] request, byte[] prefix, long timeout) throws IOException {
                int did = (request[1] & 255) << 8 | request[2] & 255;
                if (did == 0xF186) return bytes(0x62, 0xF1, 0x86, 2);
                if (did == 0xF192) return bytes(0x62, 0xF1, 0x92, '8', '4', '5', '0', '0', '9', '4', '6', '1', '5');
                throw new UdsClient.NegativeResponse(0x22, 0x31);
            }
        }, s -> {});
        assertEquals(M749FirmwareDetection.Result.OEM_UNKNOWN, result.firmware);
        assertNull(result.software);
        assertEquals("8450094615", result.part);
        assertEquals(2, result.session);
    }

    @Test void unfamiliarOrUnavailableOemBuildIsExplicitlyUnknown() throws Exception {
        for (String build : new String[]{null, " ", "\0\0", "I835LB52_w2404b1", "I832GA02_w2304v2",
                "I832GA01_w9999v1", "I832GA01", "I832GA01_w2304v2_extra", "I832GA01_w2304v2\0extra"}) {
            assertEquals(M749FirmwareDetection.Result.OEM_UNKNOWN, inspectOem(build, false).firmware, build);
        }
        assertEquals(M749FirmwareDetection.Result.RUSEFI, inspectOem("unfamiliar OEM build", true).firmware);
    }

    private static final class View implements M749Monitor.View {
        final List<Boolean> detection = new ArrayList<>();
        final List<String> details = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        M749ConnectionOptions selected;
        M749Monitor.Identification result;
        boolean busy;

        public void detection(boolean detected, String detail) {
            detection.add(detected);
            details.add(detail);
        }
        public void identification(List<String> summary) { }
        public void message(String message) { messages.add(message); }
        public void busy(boolean busy) { this.busy = busy; }
        public void connection(M749ConnectionOptions options, M749Monitor.Identification result) {
            selected = options.copy();
            this.result = result;
        }
    }

    @Test void openedAdapterWithRejectedIdentityRemainsDetectedAndSelectedForRetry() {
        View view = new View();
        boolean[] identified = {false};
        SocketCanTransportTest.Port port = new SocketCanTransportTest.Port() {
            @Override public void send(com.rusefi.io.can.ClassicCanFrame frame) {
                assertEquals(List.of(true), view.detection, "Show adapter detection before querying the ECU");
                super.send(frame);
                assertEquals(0x22, frame.getPayload()[1]);
                if (identified[0] && (frame.getPayload()[3] & 255) == 0xA4) {
                    reply(0x7E8, bytes(7, 0x62, 0xF1, 0xA4, 'r', 'E', 'F', 'I'));
                } else {
                    reply(0x7E8, bytes(3, 0x7F, 0x22, 0x31));
                }
            }
        };
        List<String> opened = new ArrayList<>();
        M749Monitor.Backend backend = M749Monitor.canBackend(action -> action.run(), (options, out) -> {
            opened.add(options.endpoint());
            options.slcan = "COM42";
            port.closed = false;
            return new SocketCanTransport(port);
        });
        M749Monitor monitor = new M749Monitor(backend, view);
        M749ConnectionOptions options = new M749ConnectionOptions();
        options.validate();
        monitor.pollConnection(true, options);
        assertEquals(List.of("auto"), opened);
        assertEquals(5, port.outgoing.size(), "The adapter exchanged all five ECU identity requests");
        assertTrue(port.closed);
        assertEquals(List.of(true, true), view.detection);
        assertTrue(view.details.get(1).contains("SLCAN COM42: adapter opened; ECU query failed"));
        assertTrue(view.details.get(1).contains("ECU identity was not confirmed"));
        assertEquals(view.details.get(1), view.messages.get(view.messages.size() - 1));
        assertEquals("COM42", view.selected.slcan);
        assertEquals(M749FirmwareDetection.Result.UNKNOWN, view.result.firmware);
        assertTrue(view.result.summary.isEmpty());
        assertFalse(view.busy);
        assertEquals("auto", options.slcan, "The original request is not mutated");

        monitor.pollConnection(false, view.selected);
        assertEquals(1, opened.size(), "Resolving auto must not bypass failure retry throttling");
        view.detection.clear();
        identified[0] = true;
        monitor.pollConnection(true, view.selected);
        assertEquals(List.of("auto", "COM42"), opened, "Explicit retry uses the same adapter");
        assertEquals(M749FirmwareDetection.Result.RUSEFI, view.result.firmware);
        assertTrue(view.details.get(view.details.size() - 1).contains("ECU identified"));
        assertTrue(port.closed);
        monitor.pollConnection(false, view.selected);
        assertEquals(2, opened.size(), "A successful retry ends automatic queries");
    }

    @Test void accessAndOpenFailuresRemainUnavailableWithTheirOriginalReason() {
        for (boolean accessFails : new boolean[]{true, false}) {
            View view = new View();
            String reason = accessFails ? "Timed out waiting for adapter discovery to stop" : "Cannot open SLCAN serial port COM42";
            M749Monitor.Backend backend = M749Monitor.canBackend(action -> {
                if (accessFails) { throw new IOException(reason); }
                return action.run();
            }, (options, out) -> {
                assertFalse(accessFails, "Access failure must not open an adapter");
                options.slcan = "COM42";
                throw new IOException(reason);
            });
            M749ConnectionOptions options = new M749ConnectionOptions();
            options.validate();
            new M749Monitor(backend, view).pollConnection(true, options);
            assertEquals(List.of(false), view.detection);
            assertEquals("SLCAN " + (accessFails ? "auto" : "COM42") + ": adapter access failed: " + reason, view.details.get(0));
            assertEquals("auto", view.selected.slcan, "Do not pin an endpoint that never opened");
            assertEquals(M749FirmwareDetection.Result.UNKNOWN, view.result.firmware);
            assertFalse(view.busy);
        }
    }

    @Test void productionBackendIdentifiesAllConnectorsThroughOwnedReadOnlyTransport() throws Exception {
        for (String[] selection : new String[][]{{"--channel", "PCAN_USBBUS2"}, {"--slcan", "COM42"}, {"--socketcan", "can7"}}) {
            M749ConnectionOptions options = new M749ConnectionOptions();
            options.accept(selection[0], selection[1]);
            options.validate();
            SocketCanTransportTest.Port port = new SocketCanTransportTest.Port() {
                @Override public void send(com.rusefi.io.can.ClassicCanFrame frame) {
                    super.send(frame);
                    byte[] q = frame.getPayload();
                    assertEquals(3, q[0]);
                    assertEquals(0x22, q[1], "Automatic identification must not change session or authenticate");
                    if ((q[3] & 255) == 0xA4) reply(0x7E8, bytes(7, 0x62, 0xF1, 0xA4, 'r', 'E', 'F', 'I'));
                    else if ((q[3] & 255) == 0xA0) reply(0x7E8, bytes(7, 0x62, 0xF1, 0xA0, 0x4D, 0x74, 1, 1));
                    else reply(0x7E8, bytes(3, 0x7F, 0x22, 0x31));
                }
            };
            boolean[] owned = {false};
            M749Monitor.Backend backend = M749Monitor.canBackend(action -> {
                owned[0] = true;
                try { return action.run(); }
                finally { owned[0] = false; }
            }, (selected, out) -> {
                assertTrue(owned[0]);
                assertEquals(options.key(), selected.key());
                return new SocketCanTransport(port);
            });
            assertEquals(M749FirmwareDetection.Result.M749_READY, backend.inspect(options, s -> {}).firmware);
            assertEquals(5, port.outgoing.size());
            assertTrue(port.closed);
            assertFalse(owned[0]);
        }
    }

    @Test void confirmsReplacementFirmwareEvenWhenOemDidsTimeOut() throws Exception {
        List<byte[]> sent = new ArrayList<>();
        List<String> log = new ArrayList<>();
        M749EcuProbe.identify(new M749Uploader.Connection() {
            public void pause(long ms) { }
            public byte[] exchange(byte[] request, byte[] prefix, long timeout) throws IOException {
                sent.add(request);
                if ((request[2] & 255) == 0xa4) { return bytes(0x62, 0xf1, 0xa4, 'r', 'E', 'F', 'I'); }
                throw new UdsClient.Timeout(0x22, "awaiting the response");
            }
        }, log::add);
        assertEquals(5, sent.size());
        assertTrue(sent.stream().allMatch(q -> q.length == 3 && q[0] == 0x22));
        assertTrue(log.stream().anyMatch(s -> s.contains("ECU presence confirmed")));
    }

    @Test void negativeResponsesAloneDoNotCertifyIdentity() {
        assertThrows(IOException.class, () -> M749EcuProbe.identify(new M749Uploader.Connection() {
            public void pause(long ms) { }
            public byte[] exchange(byte[] request, byte[] prefix, long timeout) throws IOException {
                throw new UdsClient.NegativeResponse(0x22, 0x31);
            }
        }, s -> {}));
    }
}
