package com.rusefi.m749;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static com.rusefi.m749.M749Identification.bytes;
import static org.junit.jupiter.api.Assertions.*;

class M749UsdPatcherTest {
    @TempDir Path directory;

    static byte[] snapshot() {
        byte[] data = new byte[4096]; Arrays.fill(data, (byte) 255);
        data[17] = 0; data[19] = 0; // Programmed FF, including a reserved slot.
        for (int offset = 0x4C; offset < data.length; offset += 2) {
            data[offset] = (byte) (offset / 2); data[offset + 1] = (byte) ~data[offset];
        }
        return data;
    }
    static byte[] report(byte[] snapshot, boolean applied) {
        byte[] after = snapshot.clone(); after[16] = (byte) 0xFA; after[17] = 5;
        ByteBuffer b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : new int[]{0x55445352, 1, applied ? 1 : 0, applied ? 6 : 1, 4094, 0,
                M749UsdPatcher.crc(snapshot), applied ? M749UsdPatcher.crc(after) : 0,
                (snapshot[16] & 255) | (snapshot[17] & 255) << 8, 0x05FA,
                M749UsdPatcher.MCU, M749UsdPatcher.BOOT_CRC, applied ? 1 : 0, 0, M749UsdPatcher.APP_CRC}) b.putInt(v);
        b.putInt(M749UsdPatcher.crc(Arrays.copyOf(b.array(), 60)));
        return b.array();
    }
    static void setWord(byte[] data, int index, int value) {
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(index * 4, value);
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(60, M749UsdPatcher.crc(Arrays.copyOf(data, 60)));
    }
    Path inspection(String name) throws Exception {
        Path dir = Files.createDirectory(directory.resolve(name));
        Files.write(dir.resolve(M749UsdPatcher.SNAPSHOT_FILE), snapshot());
        Files.write(dir.resolve(M749UsdPatcher.REPORT_FILE), report(snapshot(), false));
        return dir;
    }

    static class Ecu extends M749RamHelperTest.Ecu {
        final Map<Integer, Byte> memory = new HashMap<>();
        byte[] original = snapshot(), options = original.clone();
        boolean payload, loseApply = true, rejectInspect, stuckMatch, unstable;
        int session = 1, faultWrite = -1, writes, resetCount, recordReads;
        String identity = "I862BA02_w2309v4";
        Path output;
        Ecu() throws IOException {
            for (M749TargetProfile p : M749TargetProfile.values()) if (p == M749TargetProfile.I865) {
                put(0x0822DFFC, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(p.bootCrc).array());
                for (int n = 0; n < p.addresses.length; n++) {
                    String hex = p.sentinels[n]; byte[] data = new byte[hex.length()/2];
                    for (int i = 0; i < data.length; i++) data[i] = (byte) Integer.parseInt(hex.substring(i*2, i*2+2), 16);
                    put(p.addresses[n], data);
                }
            }
        }
        void put(int address, byte[] data) { for (int i = 0; i < data.length; i++) memory.put(address + i, data[i]); }
        public byte[] exchange(byte[] q, byte[] prefix, long timeout) throws IOException {
            int sid = q[0] & 255;
            if (sid == 0x22) {
                requests.add(q.clone());
                if ((q[1] & 255) == 0xF1 && (q[2] & 255) == 0x86) return bytes(0x62, 0xF1, 0x86, session);
                assertArrayEquals(bytes(0x22, 0xF1, 0x89), q);
                byte[] name = (identity + "\0\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                byte[] r = Arrays.copyOf(bytes(0x62, 0xF1, 0x89), 3 + name.length);
                System.arraycopy(name, 0, r, 3, name.length); return r;
            }
            if (sid == 0x3D) {
                requests.add(q.clone());
                if (++writes == faultWrite) throw new IOException("Write acknowledgement lost");
                int address = integer(q, 2), count = (q[6] & 255) * 256 + (q[7] & 255);
                assertEquals(q.length - 8, count); assertEquals(0x24, q[1]);
                if (address == 0x2001BA00) payload = q[8] == M749UsdPatcher.load()[0] && q[9] == M749UsdPatcher.load()[1];
                put(address, Arrays.copyOfRange(q, 8, q.length));
                byte[] r = Arrays.copyOf(q, 8); r[0] = 0x7D; return r;
            }
            if (sid == 0x31 && (q[2] & 255) == 0xF0) {
                requests.add(q.clone());
                if (!payload) return bytes(0x71, 1, 0xF0, 0, 0x7C, 0, 0);
                int entry = integer(q, 4);
                if (entry == 0x2001BA00) {
                    byte[] r = report(original, false);
                    if (rejectInspect) setWord(r, 2, 0x20);
                    put(M749UsdPatcher.REPORT, r); put(M749UsdPatcher.SNAPSHOT, original);
                    return bytes(0x71, 1, 0xF0, 0);
                }
                assertEquals(0x2001BA04, entry);
                if (output != null) assertTrue(Files.exists(output.resolve(M749UsdPatcher.ATTEMPT_FILE)));
                put(M749BootDiagnostic.ADDRESS, report(original, true));
                options[16] = (byte) 0xFA; options[17] = 5; session = 2;
                if (loseApply) throw new UdsClient.Timeout(0x31, "awaiting the response");
                byte[] r = report(original, false); setWord(r, 2, 0x18); put(M749UsdPatcher.REPORT, r);
                return bytes(0x71, 1, 0xF0, 0);
            }
            if (sid == 0x31 && (q[2] & 255) == 0xFF) {
                requests.add(q.clone());
                assertEquals(1, integer(q, 9));
                int address = integer(q, 5), candidate = (q[13] & 255) * 256 + (q[14] & 255);
                int value = address >= M749UsdReader.BASE && address < M749UsdReader.BASE + 4096 ?
                        options[address - M749UsdReader.BASE] & 255 : memory.getOrDefault(address, (byte) 255) & 255;
                return bytes(0x71, 1, 0xFF, 1, stuckMatch || candidate == value ? 0 : 1);
            }
            if (sid == 0x23) {
                int address = integer(q, 1), count = (q[5] & 255) * 256 + (q[6] & 255);
                if (address >= M749UsdPatcher.SNAPSHOT && address < M749UsdPatcher.REPORT + 64) {
                    requests.add(q.clone());
                    byte[] r = Arrays.copyOf(q, count + 5); r[0] = 0x63;
                    for (int i = 0; i < count; i++) r[i + 5] = memory.getOrDefault(address + i, (byte) 0);
                    if (unstable && ++recordReads == 2) r[5] ^= 1;
                    return r;
                }
            }
            if (sid == 0x11) {
                resetCount++;
                if (output != null) {
                    assertTrue(Files.exists(output.resolve(M749UsdPatcher.SNAPSHOT_FILE)));
                    assertTrue(Files.exists(output.resolve(M749UsdPatcher.REPORT_FILE)));
                }
                session = 1;
            }
            return super.exchange(q, prefix, timeout);
        }
    }

    @Test void commandMatchesIndependentKnownCrcAndExactAbi() throws Exception {
        assertEquals(0x0376E6E7, M749UsdPatcher.crc("123456789".getBytes()));
        byte[] cmd = M749UsdPatcher.command(snapshot());
        assertEquals(32, cmd.length);
        assertArrayEquals(new int[]{0x55445341, 1, M749UsdPatcher.crc(snapshot()), 255, 0x05FA, 0xE7419B2C, 0x18BE64D3},
                java.util.stream.IntStream.range(0, 7).map(i -> M749UsdPatcher.word(cmd, i)).toArray());
        assertEquals(M749UsdPatcher.crc(Arrays.copyOf(cmd, 28)), M749UsdPatcher.word(cmd, 7));
        assertEquals(1804, M749UsdPatcher.load().length);
    }

    @Test void rejectsPartialUnprotectedMalformedDefinedAndUnsupportedRamOptions() throws Exception {
        for (int length : new int[]{32, 4095, 4097}) assertThrows(IOException.class, () -> M749UsdPatcher.command(new byte[length]));
        for (int[] mutation : new int[][]{{0, 0x5AA5}, {16, 0xFFFF}, {16, 0x06F9}, {80, 0x0000}}) {
            byte[] data = snapshot(); ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putShort(mutation[0], (short) mutation[1]);
            assertThrows(IOException.class, () -> M749UsdPatcher.command(data));
        }
        byte[] good = snapshot(); good[16] = (byte) 0xFA; good[17] = 5;
        assertEquals(0x05FA, M749UsdPatcher.word(M749UsdPatcher.command(good), 3));
    }

    @Test void onlyDocumentedReservedHalfwordsBypassComplementValidation() throws Exception {
        int[] reserved = {0x12,0x1c,0x1e,0x20,0x22,0x24,0x26,0x28,0x2a,0x2c,0x2e,0x30,0x32,0x44,0x46,0x48,0x4a};
        for (int offset : reserved) {
            for (int value : new int[]{0,0x1234,0xabcd,0xffff}) {
                byte[] raw = snapshot();
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, (short) value);
                M749UsdPatcher.inspected(raw, report(raw, false));
                assertEquals(M749UsdPatcher.crc(raw), M749UsdPatcher.word(M749UsdPatcher.command(raw), 2));
            }
        }
        for (int offset : new int[]{0,0x10,0x14,0x18,0x1a,0x34,0x42,0x4c,0xffe}) {
            byte[] raw = snapshot(); ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putShort(offset,(short)0x1234);
            assertThrows(IOException.class, () -> M749UsdPatcher.command(raw), "Defined offset " + offset);
        }
    }

    @Test void inspectionRequiresEverySafetyFieldEvenWithCorrectRecordCrc() throws Exception {
        byte[] snapshot = snapshot(), record = report(snapshot, false);
        M749UsdPatcher.inspected(snapshot, record);
        for (int i : new int[]{0, 1, 2, 3, 6, 8, 9, 10, 11, 12, 13, 14}) {
            byte[] bad = record.clone(); setWord(bad, i, M749UsdPatcher.word(bad, i) ^ 1);
            assertThrows(IOException.class, () -> M749UsdPatcher.inspected(snapshot, bad), "field " + i);
        }
        record[63] ^= 1;
        assertThrows(IOException.class, () -> M749UsdPatcher.inspected(snapshot, record));
    }

    @Test void inspectCollectsBothRecordsBeforeResetAndApplyLaunchIsNeverReplayed() throws Exception {
        Path dir = Files.createDirectory(directory.resolve("inspection"));
        Ecu ecu = new Ecu(); ecu.output = dir;
        M749UsdPatcher patcher = new M749UsdPatcher(ecu, 1, s -> {});
        patcher.inspect(dir, M749UsdPatcher.load());
        assertArrayEquals(ecu.original, M749UsdPatcher.readInspection(dir));
        assertEquals(1, ecu.resetCount);
        assertEquals(1, ecu.requests.stream().filter(q -> q[0] == 0x10).count());
        assertTrue(ecu.requests.stream().filter(q -> q[0] == 0x3D).anyMatch(q -> q.length == 276), "Final 268-byte payload block");
        ecu.requests.clear();
        assertEquals(3, patcher.apply(dir, ecu.original, M749UsdPatcher.load()));
        assertEquals(1, ecu.requests.stream().filter(q -> q[0] == 0x31).count());
        assertFalse(ecu.requests.stream().anyMatch(q -> q[0] == 0x11));
        int count = ecu.requests.size();
        assertThrows(IOException.class, () -> patcher.apply(dir, ecu.original, M749UsdPatcher.load()));
        assertEquals(count, ecu.requests.size());
        patcher.verify(dir, ecu.original);
        assertTrue(Files.exists(dir.resolve("verification.bin")));
        assertEquals(1, ecu.resetCount);
    }

    @Test void inspectRefusalUnstableDataAndStagingFailureNeverResetOrPublish() throws Exception {
        for (int fault = 0; fault < 3; fault++) {
            Ecu ecu = new Ecu(); ecu.rejectInspect = fault == 0; ecu.unstable = fault == 1; ecu.faultWrite = fault == 2 ? 4 : -1;
            Path dir = Files.createDirectory(directory.resolve("failure" + fault));
            assertThrows(IOException.class, () -> new M749UsdPatcher(ecu, 1, s -> {}).inspect(dir, M749UsdPatcher.load()));
            assertFalse(Files.exists(dir.resolve(M749UsdPatcher.REPORT_FILE)));
            assertEquals(0, ecu.resetCount);
        }
    }

    @Test void reservedZeroHalfwordsAreAcceptedAndUnrelatedRejectionsStillSaveDiagnosticRecord() throws Exception {
        byte[] raw = snapshot();
        Arrays.fill(raw, 0x48, 0x4C, (byte) 0);
        byte[] cmd = M749UsdPatcher.command(raw);
        assertEquals(M749UsdPatcher.crc(raw), M749UsdPatcher.word(cmd, 2));
        M749UsdPatcher.inspected(raw, report(raw, false));
        Ecu ecu = new Ecu() {
            @Override public byte[] exchange(byte[] q, byte[] prefix, long timeout) throws IOException {
                byte[] reply = super.exchange(q, prefix, timeout);
                if (q[0] == 0x31 && (q[2] & 255) == 0xF0 && payload) {
                    byte[] record = report(original, false);
                    setWord(record, 2, 0x16); setWord(record, 3, 0); setWord(record, 4, 0x48);
                    put(M749UsdPatcher.REPORT, record);
                }
                return reply;
            }
        };
        Path dir = Files.createDirectory(directory.resolve("reserved-zero"));
        IOException failure = assertThrows(IOException.class, () ->
                new M749UsdPatcher(ecu, 1, s -> {}).inspect(dir, M749UsdPatcher.load()));
        assertTrue(failure.getMessage().contains("offset 048"));
        assertTrue(failure.getMessage().contains("destructive=0"));
        byte[] record = Files.readAllBytes(dir.resolve("inspection-rejected.bin"));
        M749UsdPatcher.report(record);
        assertEquals(0x16, M749UsdPatcher.word(record, 2));
        assertEquals(0x48, M749UsdPatcher.word(record, 4));
        assertFalse(Files.exists(dir.resolve(M749UsdPatcher.REPORT_FILE)));
        assertFalse(Files.exists(dir.resolve(M749UsdPatcher.SNAPSHOT_FILE)));
        assertEquals(0, ecu.resetCount);
    }

    @Test void wrongApplicationAndResidentLoaderStopBeforeRamUpload() throws Exception {
        for (boolean loader : new boolean[]{false, true}) {
            Ecu ecu = new Ecu(); if (loader) ecu.session = 2; else ecu.identity = "I865LB52";
            assertThrows(IOException.class, () -> new M749UsdPatcher(ecu, 1, s -> {}).inspect(directory, M749UsdPatcher.load()));
            assertTrue(ecu.requests.stream().allMatch(q -> q[0] == 0x22));
        }
    }

    @Test void verificationRejectsChangedUsdAndWrongCandidateResponderWithoutMutations() throws Exception {
        for (int fault = 0; fault < 3; fault++) {
            Ecu ecu = new Ecu(); ecu.session = 2;
            ecu.put(M749BootDiagnostic.ADDRESS, report(ecu.original, true));
            ecu.options[16] = (byte) 0xFA; ecu.options[17] = 5;
            if (fault == 0) ecu.options[4095] ^= 1;
            if (fault == 1) ecu.stuckMatch = true;
            if (fault == 2) {
                byte[] bad = report(ecu.original, true); setWord(bad, 6, 123); ecu.put(M749BootDiagnostic.ADDRESS, bad);
            }
            assertThrows(IOException.class, () -> new M749UsdPatcher(ecu, 1, s -> {}).verify(directory, ecu.original));
            assertFalse(Files.exists(directory.resolve("verification.bin")));
            assertTrue(ecu.requests.stream().allMatch(q -> q[0] == 0x22 || q[0] == 0x27 || q[0] == 0x31 && (q[2] & 255) == 0xFF));
        }
    }

    @Test void offlineInputsAndExplicitConfirmationAreCheckedBeforeOpeningAdapter() throws Exception {
        M749UsdPatchCli.TransportFactory noAdapter = o -> { throw new AssertionError("Adapter opened"); };
        Path dir = inspection("offline");
        assertEquals(0, M749UsdPatchCli.execute(new String[]{"--patch-usd", dir.toString(), "--dry-run"}, noAdapter, s -> {}));
        for (String[] args : new String[][]{
                {"--patch-usd", dir.toString()}, {"--patch-usd", dir.toString(), "--dry-run", "--confirm-usd-erase"},
                {"--inspect-usd", "x", "--confirm-usd-erase"}, {"--verify-usd-patch", "x", "--dry-run"},
                {"--patch-usd", "x", "--dry-run", "--socketcan", "can0"},
                {"--inspect-usd", "x", "--write-flash", "test.bin"}, {"--inspect-usd", "x", "--inspect-usd", "y"}})
            assertEquals(2, M749UsdPatchCli.execute(args, noAdapter, s -> {}));
        Files.write(dir.resolve(M749UsdPatcher.ATTEMPT_FILE), bytes(1));
        assertThrows(IOException.class, () -> M749UsdPatchCli.execute(new String[]{"--patch-usd", dir.toString(), "--confirm-usd-erase"}, noAdapter, s -> {}));
        assertThrows(IOException.class, () -> M749UsdPatchCli.execute(new String[]{"--inspect-usd", dir.toString()}, noAdapter, s -> {}));
        Files.write(dir.resolve(M749UsdPatcher.SNAPSHOT_FILE), new byte[32]);
        assertThrows(IOException.class, () -> M749UsdPatchCli.execute(new String[]{"--patch-usd", dir.toString(), "--dry-run"}, noAdapter, s -> {}));
    }

    @Test void cliInspectionRoutesThroughWireTransportAndNeverApplies() throws Exception {
        Ecu ecu = new Ecu(); Path dir = directory.resolve("wire"); ecu.output = dir;
        assertEquals(0, M749UsdPatchCli.execute(new String[]{"--inspect-usd", dir.toString(), "--socketcan", "can0", "--tx-gap", "0"},
                o -> new M749ReadFlashCliTest.WireEcu(ecu), s -> {}));
        assertArrayEquals(ecu.original, M749UsdPatcher.readInspection(dir));
        assertFalse(Files.exists(dir.resolve(M749UsdPatcher.ATTEMPT_FILE)));
    }

    @Test void cliExplainsDirectoryFailuresBeforeOpeningAdapter() throws Exception {
        M749UsdPatchCli.TransportFactory noAdapter = o -> { throw new AssertionError("Adapter opened"); };
        Path existing = directory.resolve("keep.bin");
        Files.write(existing, bytes(1, 2, 3));
        for (Path path : new Path[]{directory, existing}) {
            IOException failure = assertThrows(IOException.class, () -> M749UsdPatchCli.execute(
                    new String[]{"--inspect-usd", path.toString()}, noAdapter, s -> {}));
            assertTrue(failure.getMessage().contains("already exists"));
            assertTrue(failure.getMessage().contains("Choose a new directory name"));
            assertTrue(failure.getMessage().contains("no adapter was opened"));
        }
        IOException failure = assertThrows(IOException.class, () -> M749UsdPatchCli.execute(
                new String[]{"--inspect-usd", directory.resolve("missing/child").toString()}, noAdapter, s -> {}));
        assertTrue(failure.getMessage().contains("existing writable folder"));
        assertArrayEquals(bytes(1, 2, 3), Files.readAllBytes(existing));
    }

    @Test void patcherRangesDoNotExposeArbitraryMemory() throws Exception {
        Ecu ecu = new Ecu(); M749RamHelper helper = new M749RamHelper(ecu);
        for (int address : new int[]{0x08000000, 0x40023C00, M749UsdPatcher.SNAPSHOT, 0x2001BA01})
            assertThrows(IllegalArgumentException.class, () -> helper.writeRam(address, new byte[32]));
        for (int address : new int[]{0x40023C00, 0x20000000, M749UsdPatcher.SNAPSHOT - 1, M749UsdPatcher.SNAPSHOT + 4095})
            assertThrows(IllegalArgumentException.class, () -> helper.readPatchRecord(address, 2));
        assertThrows(IllegalArgumentException.class, () -> new M749ChecksumReader(ecu).usdPatchByteMatches(0x40023C00, 0));
        assertTrue(ecu.requests.isEmpty());
    }
}
