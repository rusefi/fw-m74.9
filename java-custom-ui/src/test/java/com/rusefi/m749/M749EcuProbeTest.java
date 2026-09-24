package com.rusefi.m749;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.rusefi.m749.M749Identification.bytes;

class M749EcuProbeTest {
    @Test void confirmsReplacementFirmwareEvenWhenOemDidsTimeOut() throws Exception {
        List<byte[]> sent = new ArrayList<>();
        List<String> log = new ArrayList<>();
        M749EcuProbe.identify(new M749Uploader.Connection() {
            public void pause(long ms) { }
            public byte[] exchange(byte[] request, byte[] prefix, long timeout) throws IOException {
                sent.add(request);
                if ((request[2] & 255) == 0xa4) { return bytes(0x62, 0xf1, 0xa4, 'r', 'E', 'F', 'I'); }
                throw new UdsClient.Timeout();
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
