package com.rusefi.m749;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static com.rusefi.m749.M749Identification.bytes;
import static org.junit.jupiter.api.Assertions.*;

class M749RamPreflightTest {
    private static M749Image software() throws IOException {
        return M749Image.validate(M749ImageTest.records(M749Image.Domain.SOFTWARE), M749Image.Domain.SOFTWARE);
    }

    private static void unchanged(M749UploaderTest.Ecu ecu, byte[] before) {
        assertArrayEquals(before, ecu.flash);
        assertTrue(ecu.erases.isEmpty());
        assertEquals(0, ecu.writes);
        assertEquals(0, ecu.resets);
        for (byte[] q : ecu.requests) {
            int sid = q[0] & 255;
            assertTrue(sid == 0x10 || sid == 0x27 || sid == 0x22 ||
                    (sid == 0x31 && q[3] == 1), "Unexpected mutating request");
        }
    }

    @Test void unsupportedRamSettingsStopUploadAndCheckTargetBeforeAnyMutation() throws Exception {
        for (int option : new int[]{0x00FF, 0x01FE, 0x02FD, 0x03FC, 0x04FB, 0x00FA, 0x0000}) {
            for (boolean checkOnly : new boolean[]{false, true}) {
                M749UploaderTest.Ecu ecu = new M749UploaderTest.Ecu();
                ecu.putRamPreflight(0x1FFFC010, option, 2);
                byte[] before = ecu.flash.clone();
                M749Uploader uploader = new M749Uploader(ecu, ecu.messages::add);
                IOException error = assertThrows(IOException.class, () -> {
                    if (checkOnly) uploader.checkTarget(software());
                    else uploader.upload(software(), false);
                });
                assertTrue(error.getMessage().contains(String.format("EOPB0=%04X access=FFFF", option)));
                assertTrue(error.getMessage().contains("non-erased"));
                assertTrue(error.getMessage().contains("before application erase"));
                assertTrue(error.getMessage().contains("No flash erase or programming requests were sent"));
                assertFalse(error.getMessage().contains("may be incomplete"));
                unchanged(ecu, before);
            }
        }
    }

    @Test void sufficientRamPassesWithProtectionAndSlibEnabled() throws Exception {
        for (int option : new int[]{0x05FA, 0x06F9, 0x07F8, 0xFD02, 0xFE01, 0xFF00}) {
            M749UploaderTest.Ecu ecu = new M749UploaderTest.Ecu();
            ecu.putRamPreflight(0x1FFFC010, option, 2);
            ecu.putRamPreflight(0x40023CCC, 8, 4);
            byte[] before = ecu.flash.clone();
            new M749Uploader(ecu, ecu.messages::add).checkTarget(software());
            assertTrue(ecu.messages.stream().anyMatch(m -> m.contains("existing RAM setting is sufficient")));
            unchanged(ecu, before);
        }
    }

    @Test void erasedUnprotectedOptionsPassWithoutChangingOptions() throws Exception {
        M749UploaderTest.Ecu ecu = new M749UploaderTest.Ecu();
        ecu.putRamPreflight(0x1FFFC010, 0xFFFF, 2);
        ecu.putRamPreflight(0x1FFFC000, 0x5AA5, 2);
        byte[] before = ecu.flash.clone();
        new M749Uploader(ecu, ecu.messages::add).checkTarget(software());
        assertTrue(ecu.messages.stream().anyMatch(m -> m.contains("eligible for first-boot setup to 384 KiB")));
        unchanged(ecu, before);
    }

    @Test void erasedOptionsRejectProtectionSlibAndWrongMcuBeforeErase() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            M749UploaderTest.Ecu ecu = new M749UploaderTest.Ecu();
            ecu.putRamPreflight(0x1FFFC010, 0xFFFF, 2);
            ecu.putRamPreflight(0x1FFFC000, kind == 0 ? 0xFFFF : 0x5AA5, 2);
            if (kind == 1) ecu.putRamPreflight(0x40023CCC, 8, 4);
            if (kind == 2) ecu.putRamPreflight(0xE0042000, 0x70084541, 4);
            byte[] before = ecu.flash.clone();
            IOException error = assertThrows(IOException.class, () -> ecu.run(M749Image.Domain.SOFTWARE));
            assertTrue(error.getMessage().contains(new String[]{"access protection", "SLIB restriction", "unsupported MCU"}[kind]));
            unchanged(ecu, before);
        }
    }

    @Test void sufficientRamStillRejectsWrongMcu() throws Exception {
        M749UploaderTest.Ecu ecu = new M749UploaderTest.Ecu();
        ecu.putRamPreflight(0xE0042000, 0x12345678, 4);
        byte[] before = ecu.flash.clone();
        IOException error = assertThrows(IOException.class, () -> ecu.run(M749Image.Domain.SOFTWARE));
        assertTrue(error.getMessage().contains("unsupported MCU"));
        unchanged(ecu, before);
    }

    @Test void unreadableMalformedOrUntrustworthyOptionReadsNeverReachErase() throws Exception {
        for (int kind = 0; kind < 5; kind++) {
            final int mode = kind;
            int[] attempts = {0};
            M749UploaderTest.Ecu ecu = new M749UploaderTest.Ecu() {
                @Override public byte[] exchange(byte[] q, byte[] prefix, long timeout) throws IOException {
                    if (q[0] == 0x31 && q[3] == 1 && big(q, 5) == 0x1FFFC010) {
                        requests.add(q.clone());
                        attempts[0]++;
                        if (mode == 0) throw new UdsClient.Timeout(0x31, "awaiting response");
                        if (mode == 1) return bytes(0x71, 1, 0xFF, 1, 2);
                        if (mode == 2) return bytes(0x71, 1, 0xFF, 1, 0);
                        if (mode == 3) return bytes(0x71, 1, 0xFF, 1, 1);
                        throw new UdsClient.NegativeResponse(0x31, 0x31);
                    }
                    return super.exchange(q, prefix, timeout);
                }
            };
            byte[] before = ecu.flash.clone();
            assertThrows(IOException.class, () -> ecu.run(M749Image.Domain.SOFTWARE));
            assertEquals(new int[]{1, 1, 2, 256, 1}[kind], attempts[0]);
            unchanged(ecu, before);
        }
    }

    @Test void readPathOnlyAcceptsTheTwelveWhitelistedBytes() throws Exception {
        M749ChecksumReaderTest.Ecu ecu = new M749ChecksumReaderTest.Ecu();
        M749ChecksumReader reader = new M749ChecksumReader(ecu);
        for (int address : new int[]{0x1FFFC000, 0x1FFFC001, 0x1FFFC010, 0x1FFFC011,
                0xE0042000, 0xE0042001, 0xE0042002, 0xE0042003,
                0x40023CCC, 0x40023CCD, 0x40023CCE, 0x40023CCF}) {
            assertTrue(reader.ramPreflightByteMatches(address, 0));
            assertThrows(IllegalArgumentException.class, () -> reader.readByte(address));
        }
        int sent = ecu.requests.size();
        for (int address : new int[]{0x1FFFBFFF, 0x1FFFC002, 0x1FFFC00F, 0x1FFFC012,
                0xE0041FFF, 0xE0042004, 0x40023CCB, 0x40023CD0, 0x08000000, 0x20000040}) {
            assertThrows(IllegalArgumentException.class, () -> reader.ramPreflightByteMatches(address, 0));
        }
        for (int candidate : new int[]{-1, 256}) {
            assertThrows(IllegalArgumentException.class, () -> reader.ramPreflightByteMatches(0x1FFFC010, candidate));
        }
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, () -> reader.ramPreflightByteMatches(0x1FFFC010, 0)); }
        finally { Thread.interrupted(); }
        assertEquals(sent, ecu.requests.size());
    }

    @Test void calibrationUpdateDoesNotRequireReplacementRamSetup() throws Exception {
        M749UploaderTest.Ecu ecu = new M749UploaderTest.Ecu();
        ecu.putRamPreflight(0x1FFFC010, 0x00FF, 2);
        ecu.run(M749Image.Domain.CALIBRATION);
        assertFalse(ecu.erases.isEmpty());
        assertFalse(ecu.messages.stream().anyMatch(m -> m.startsWith("RAM preflight")));
    }
}
