package com.rusefi.m749;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static com.rusefi.m749.M749Identification.bytes;
import static org.junit.jupiter.api.Assertions.*;

class M749UsdReaderTest {
    @TempDir Path directory;

    static class Ecu extends M749RamHelperTest.Ecu {
        final byte[] usd = new byte[4096];
        final Map<Integer, Integer> counts = new HashMap<>();
        int corruptAddress = -1, corruptRead = 2, failAddress = -1;
        Ecu() throws IOException {
            // Include all byte values, erased halfwords, complements and arbitrary user data.
            for (int i = 0; i < usd.length; i++) usd[i] = (byte) (i < 512 ? i : (i % 3 == 0 ? 0 : 255));
        }
        public byte[] exchange(byte[] q, byte[] prefix, long timeout) throws IOException {
            if (q[0] == 0x23 && integer(q, 1) >= M749UsdReader.BASE && integer(q, 1) < M749UsdReader.BASE + 4096) {
                requests.add(q.clone());
                int address = integer(q, 1), count = (q[5] & 255) * 256 + (q[6] & 255);
                assertTrue(count > 0 && count <= 4080 && address + count <= M749UsdReader.BASE + 4096);
                if (address == failAddress) throw new IOException("Lost USD reply");
                int number = counts.merge(address, 1, Integer::sum);
                byte[] reply = Arrays.copyOf(q, count + 5); reply[0] = 0x63;
                System.arraycopy(usd, address - M749UsdReader.BASE, reply, 5, count);
                if (address == corruptAddress && number == corruptRead) reply[5] ^= 1;
                if (badAddress) reply[4] ^= 1;
                return shortResponse ? Arrays.copyOf(reply, reply.length - 1) : reply;
            }
            // Parent fixture rejects resident-loader services, including FF01 and session 02.
            return super.exchange(q, prefix, timeout);
        }
    }

    @Test void limitsUsdReadsAndRejectsWrongAddressShortReplyLossAndCancellation() throws Exception {
        Ecu ecu = new Ecu(); M749RamHelper helper = new M749RamHelper(ecu);
        for (int[] range : new int[][]{{M749UsdReader.BASE - 1, 1}, {M749UsdReader.BASE + 4096, 1},
                {M749UsdReader.BASE + 4095, 2}, {M749UsdReader.BASE, 4081}, {M749UsdReader.BASE, 0},
                {M749UsdReader.BASE, -1}, {0x40000000, 1}, {Integer.MAX_VALUE, 2}}) {
            assertThrows(IllegalArgumentException.class, () -> helper.readUsd(range[0], range[1]));
        }
        assertThrows(IllegalArgumentException.class, () -> helper.read(M749UsdReader.BASE, 1));
        assertTrue(ecu.requests.isEmpty());
        ecu.badAddress = true;
        assertThrows(IOException.class, () -> helper.readUsd(M749UsdReader.BASE, 32));
        ecu.badAddress = false; ecu.shortResponse = true;
        assertThrows(IOException.class, () -> helper.readUsd(M749UsdReader.BASE, 32));
        ecu.shortResponse = false; ecu.failAddress = M749UsdReader.BASE;
        int before = ecu.requests.size();
        assertThrows(IOException.class, () -> helper.readUsd(M749UsdReader.BASE, 32));
        assertEquals(before + 1, ecu.requests.size(), "No automatic retry after lost reply");
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedException.class, () -> helper.readUsd(M749UsdReader.BASE, 32)); }
        finally { Thread.interrupted(); }
        assertEquals(before + 1, ecu.requests.size());
    }

    @Test void splitsCompleteRawImageAtHelperMaximumAndChecksBothReads() throws Exception {
        Ecu ecu = new Ecu(); Path path = directory.resolve("raw.usd.bin");
        try (FlashReadFile file = new FlashReadFile(path, M749UsdReader.BASE, 4096, false, true)) {
            assertEquals(FlashReadFile.hash(ecu.usd), M749UsdReader.read(new M749RamHelper(ecu), file, 4080, s -> {}));
        }
        assertArrayEquals(ecu.usd, Files.readAllBytes(path));
        assertEquals(4, ecu.requests.size());
        assertEquals(2, ecu.counts.get(M749UsdReader.BASE));
        assertEquals(2, ecu.counts.get(M749UsdReader.BASE + 4080));
        assertTrue(ecu.requests.stream().allMatch(q -> q[0] == 0x23));
        assertThrows(IOException.class, () -> new FlashReadFile(path, M749UsdReader.BASE, 4096, true, true));
    }

    @Test void mismatchedBlockIsNotSavedAndResumeRechecksEverySavedByte() throws Exception {
        Ecu ecu = new Ecu(); Path path = directory.resolve("partial.usd.bin");
        M749RamHelper helper = new M749RamHelper(ecu);
        ecu.corruptAddress = M749UsdReader.BASE + 1024;
        try (FlashReadFile file = new FlashReadFile(path, M749UsdReader.BASE, 4096, false, true)) {
            assertThrows(IOException.class, () -> M749UsdReader.read(helper, file, 1024, s -> {}));
            assertEquals(1024, file.completed());
        }
        assertFalse(Files.exists(path));
        ecu.corruptAddress = -1; ecu.usd[1023] ^= 1;
        try (FlashReadFile file = new FlashReadFile(path, M749UsdReader.BASE, 4096, true, true)) {
            assertThrows(IOException.class, () -> M749UsdReader.read(helper, file, 256, s -> {}));
            assertEquals(1024, file.completed());
        }
        ecu.usd[1023] ^= 1;
        try (FlashReadFile file = new FlashReadFile(path, M749UsdReader.BASE, 4096, true, true)) {
            assertEquals(FlashReadFile.hash(ecu.usd), M749UsdReader.read(helper, file, 1024, s -> {}));
        }
        assertArrayEquals(ecu.usd, Files.readAllBytes(path));
    }

    @Test void combinedBackupUsesOneHelperSessionAndResetsOnlyAfterBothImages() throws Exception {
        Path path = directory.resolve("both.bin"); Path usd = M749UsdReader.outputFor(path);
        Ecu ecu = new Ecu() {
            public byte[] exchange(byte[] q, byte[] prefix, long timeout) throws IOException {
                if (q[0] == 0x11) {
                    assertTrue(Files.exists(path)); assertTrue(Files.exists(M749UsdReader.outputFor(path)));
                }
                return super.exchange(q, prefix, timeout);
            }
        };
        List<String> log = new ArrayList<>();
        assertEquals(0, M749ReadFlashCli.execute(new String[]{"--read-flash", path.toString(),
                "--length", "128", "--socketcan", "can0", "--tx-gap", "0", "--with-usd", "--reset-after"},
                o -> new M749ReadFlashCliTest.WireEcu(ecu), log::add));
        assertArrayEquals(M749RamHelperTest.Ecu.content(M749RamHelper.BASE, 128), Files.readAllBytes(path));
        assertArrayEquals(ecu.usd, Files.readAllBytes(usd));
        assertEquals(1, ecu.requests.stream().filter(q -> q[0] == 0x10).count());
        assertEquals(13, ecu.requests.stream().filter(q -> q[0] == 0x3D).count());
        assertEquals(1, ecu.requests.stream().filter(q -> q[0] == 0x31).count()); // F000 only
        assertEquals(1, ecu.requests.stream().filter(q -> q[0] == 0x11).count());
        assertEquals(0x11, ecu.requests.get(ecu.requests.size() - 1)[0]);
        assertTrue(log.stream().anyMatch(s -> s.startsWith("USD backup complete:")));
    }

    @Test void usdFailureKeepsMainImageAndResumeUsesAlreadyRunningHelper() throws Exception {
        Path path = directory.resolve("failed.bin");
        Ecu ecu = new Ecu(); ecu.failAddress = M749UsdReader.BASE + 1024;
        List<String> log = new ArrayList<>();
        assertThrows(IOException.class, () -> M749ReadFlashCli.execute(new String[]{"--read-flash", path.toString(),
                "--length", "128", "--socketcan", "can0", "--tx-gap", "0", "--with-usd", "--reset-after"},
                o -> new M749ReadFlashCliTest.WireEcu(ecu), log::add));
        assertEquals(128, Files.size(path));
        Path usd = M749UsdReader.outputFor(path);
        assertFalse(Files.exists(usd));
        assertEquals(1024, Files.size(Path.of(usd + ".part")));
        assertFalse(ecu.requests.stream().anyMatch(q -> q[0] == 0x11));
        assertTrue(log.stream().anyMatch(s -> s.contains("--read-usd")));
        ecu.failAddress = -1; ecu.requests.clear();
        assertEquals(0, M749ReadFlashCli.execute(new String[]{"--read-usd", usd.toString(),
                "--socketcan", "can0", "--tx-gap", "0", "--resume", "--helper-running"},
                o -> new M749ReadFlashCliTest.WireEcu(ecu), s -> {}));
        assertArrayEquals(ecu.usd, Files.readAllBytes(usd));
        assertTrue(ecu.requests.stream().allMatch(q -> q[0] == 0x23 || q[0] == 0x3E || q[0] == 0x1A));
    }

    @Test void standaloneReadBootstrapsHelperAndChecksFlashProbeBeforeUsd() throws Exception {
        Ecu ecu = new Ecu(); Path path = directory.resolve("standalone.bin");
        assertEquals(0, M749ReadFlashCli.execute(new String[]{"--read-usd", path.toString(),
                "--socketcan", "can0", "--chunk-size", "4080", "--tx-gap", "0"},
                o -> new M749ReadFlashCliTest.WireEcu(ecu), s -> {}));
        assertArrayEquals(ecu.usd, Files.readAllBytes(path));
        assertEquals(13, ecu.requests.stream().filter(q -> q[0] == 0x3D).count());
        assertFalse(ecu.requests.stream().anyMatch(q -> q[0] == 0x11));
        Ecu blocked = new Ecu(); blocked.uniform = true;
        Path fail = directory.resolve("blocked.bin");
        assertThrows(IOException.class, () -> M749ReadFlashCli.execute(new String[]{"--read-usd", fail.toString(),
                "--socketcan", "can0", "--tx-gap", "0"}, o -> new M749ReadFlashCliTest.WireEcu(blocked), s -> {}));
        assertTrue(blocked.counts.isEmpty());
        assertFalse(Files.exists(fail));
    }

    @Test void invalidArgumentsExistingOutputAndOldFf01CheckpointStopBeforeAdapter() throws Exception {
        M749ReadFlashCli.TransportFactory noAdapter = o -> { throw new AssertionError("Adapter opened"); };
        for (String flag : new String[]{"--with-usd", "--read-flash"}) {
            assertEquals(2, M749ReadFlashCli.execute(new String[]{"--read-usd", "unused", flag}, noAdapter, s -> {}));
        }
        for (String flag : new String[]{"--start", "--length"}) {
            assertEquals(2, M749ReadFlashCli.execute(new String[]{"--read-usd", "unused", flag, "1"}, noAdapter, s -> {}));
        }
        assertEquals(2, M749ReadFlashCli.execute(new String[]{"--read-usd", "unused", "--chunk-size", "4081"}, noAdapter, s -> {}));
        Path flash = directory.resolve("exists.bin");
        Path usd = M749UsdReader.outputFor(flash); Files.write(usd, new byte[]{42});
        assertThrows(IOException.class, () -> M749ReadFlashCli.execute(new String[]{"--read-flash", flash.toString(),
                "--with-usd"}, noAdapter, s -> {}));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(usd));
        Path old = directory.resolve("old.bin");
        try (FlashReadFile file = new FlashReadFile(old, M749UsdReader.BASE, 4096, false, true)) { }
        Path state = Path.of(old + ".properties");
        Files.writeString(state, Files.readString(state).replace("M749USD2", "M749USD1"));
        assertThrows(IOException.class, () -> M749ReadFlashCli.execute(new String[]{"--read-usd", old.toString(),
                "--resume"}, noAdapter, s -> {}));
    }
}
