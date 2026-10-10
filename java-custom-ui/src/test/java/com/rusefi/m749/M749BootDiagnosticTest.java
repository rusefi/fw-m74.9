package com.rusefi.m749;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Arrays;
import static com.rusefi.m749.M749Identification.bytes;
import static org.junit.jupiter.api.Assertions.*;

class M749BootDiagnosticTest {
    static int[] record() {
        int[] w = new int[16];
        w[0] = M749BootDiagnostic.MAGIC; w[1] = M749BootDiagnostic.VERSION_SIZE;
        w[2] = 18; w[3] = 0xA8656315; w[4] = 7; w[5] = 0x70084540; w[6] = -1;
        seal(w); return w;
    }
    static void seal(int[] w) {
        w[15] = w[0];
        for (int i = 1; i < 15; i++) w[15] = Integer.rotateLeft(w[15], 5) ^ w[i];
    }
    static class Ecu extends M749UploaderTest.Ecu {
        int[] diagnostic = record();
        int diagnosticRequests;
        boolean stuck;
        @Override public byte[] exchange(byte[] q, byte[] prefix, long timeout) throws IOException {
            if (q[0] == 0x31 && q[3] == 1 && big(q, 5) >= M749BootDiagnostic.ADDRESS) {
                int offset = big(q, 5) - M749BootDiagnostic.ADDRESS;
                assertTrue(offset >= 0 && offset < 64); assertEquals(1, big(q, 9));
                assertTrue(timeout <= 5000);
                diagnosticRequests++;
                int actual = diagnostic[offset / 4] >>> (8 * (offset % 4)) & 255;
                return bytes(0x71, 1, 0xFF, 1, stuck || (q[14] & 255) == actual ? 0 : 1);
            }
            return super.exchange(q, prefix, timeout);
        }
    }

    @Test void readsQualifiedRecordUsingIndividualBytesAndWrongCandidateControls() throws Exception {
        Ecu ecu = new Ecu();
        assertEquals(7, new M749BootDiagnostic(ecu).sequence());
        String result = new M749BootDiagnostic(ecu).describe(M749TargetProfile.I865, 0xA8656315, 6);
        assertTrue(result.contains("blocked by access protection"));
        assertTrue(result.contains("(new boot)"));
        assertTrue(result.contains("EOPB0=FFFF access=FFFF"));
        assertTrue(ecu.diagnosticRequests > 64);
        assertTrue(ecu.erases.isEmpty()); assertEquals(0, ecu.resets);
    }
    @Test void rejectsTornWrongImageStaleAndStuckComparisons() throws Exception {
        int[] w = record(); w[8] ^= 1;
        assertThrows(IOException.class, () -> M749BootDiagnostic.decode(w, 0xA8656315, 6));
        assertTrue(M749BootDiagnostic.decode(record(), 0, 6).contains("different software CRC"));
        assertTrue(M749BootDiagnostic.decode(record(), 0xA8656315, 7).contains("stale record ignored"));
        assertTrue(M749BootDiagnostic.decode(record(), 0xA8656315, null).contains("freshness unverified"));
        Ecu ecu = new Ecu(); ecu.stuck = true;
        assertThrows(IOException.class, () -> new M749BootDiagnostic(ecu).sequence());
    }
    @Test void oldFirmwareAndDifferentProfileAreNotDiagnosed() throws Exception {
        Ecu ecu = new Ecu(); Arrays.fill(ecu.diagnostic, 0);
        assertNull(new M749BootDiagnostic(ecu).sequence());
        assertTrue(new M749BootDiagnostic(ecu).describe(M749TargetProfile.I865, 0, null).contains("not present"));
        int before = ecu.diagnosticRequests;
        assertThrows(IOException.class, () -> new M749BootDiagnostic(ecu).describe(M749TargetProfile.I832, 0, null));
        assertEquals(before, ecu.diagnosticRequests);
    }
    @Test void diagnosticDeadlineBoundsRequestsWithoutResetOrRetry() {
        long[] clock = {0}; Ecu ecu = new Ecu();
        M749BootDiagnostic diagnostic = new M749BootDiagnostic(ecu, () -> {
            long value = clock[0]; clock[0] += 8_000_000_000L; return value;
        });
        IOException failure = assertThrows(IOException.class, diagnostic::sequence);
        assertTrue(failure.getMessage().contains("budget exhausted"));
        assertEquals(1, ecu.diagnosticRequests); assertEquals(0, ecu.resets);
    }

    @Test void flashReadAddressGuardStaysClosedAndDiagnosticRangeCannotExpand() {
        Ecu ecu = new Ecu(); M749ChecksumReader reader = new M749ChecksumReader(ecu);
        assertThrows(IllegalArgumentException.class, () -> reader.readWord(M749BootDiagnostic.ADDRESS));
        for (int a : new int[]{0x20000000, 0x2000003F, 0x20000080, 0x1FFFC000}) {
            assertThrows(IllegalArgumentException.class, () -> reader.diagnosticByteMatches(a, 0));
        }
        assertEquals(0, ecu.diagnosticRequests);
    }
}
