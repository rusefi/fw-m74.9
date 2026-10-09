package com.rusefi.m749;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class M749AutoCredentialTest {
    @TempDir Path directory;

    @Test void matchesI865AndPopulatesSparseCacheOrReusesCompleteFile() throws Exception {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", directory.toString());
            M749Monitor.Identification i865 = identity(M749FirmwareDetection.Result.OEM,
                    "I865LB52_w2404b1", "8450094615");
            M749AutoCredential.LiveReader unused = (path, out) -> fail("No live read for other identities");
            assertNull(M749AutoCredential.select(null, unused, s -> {}));
            assertNull(M749AutoCredential.select(identity(M749FirmwareDetection.Result.OEM,
                    "I832GA01_w2304v2", "8450110707"), unused, s -> {}));
            assertNull(M749AutoCredential.select(identity(M749FirmwareDetection.Result.M749_READY,
                    "I865LB52_w2404b1", "8450094615"), unused, s -> {}));
            assertThrows(IOException.class, () -> M749AutoCredential.select(identity(
                    M749FirmwareDetection.Result.OEM, "I865LB52_w2404b1", "other"), unused, s -> {}));
            Path file = M749AutoCredential.path();
            Files.createDirectories(file.getParent());
            List<String> log = new ArrayList<>();
            assertTrue(assertThrows(IOException.class, () -> M749AutoCredential.select(i865,
                    (path, out) -> { throw new IOException("programming session 02 rejected"); }, log::add))
                    .getMessage().contains("live FF01 read failed"));
            assertTrue(log.get(0).contains(file.toString()));
            assertTrue(log.get(0).contains("missing"));
            assertFalse(Files.exists(file));

            M749PairFile partial = new M749PairFile();
            partial.put(0, 0);
            partial.put(23, 23);
            partial.save(file);
            log.clear();
            assertEquals(file, M749AutoCredential.select(i865, (path, out) -> {
                assertEquals(file, path);
                M749PairFile saved = M749PairFile.load(path);
                assertEquals(2, saved.knownCount());
                for (int i = 0; i < M749PairFile.SIZE; i++) saved.put(i, i);
                saved.save(path);
                out.accept("Pair file complete; no erase/download requests sent");
            }, log::add));
            assertEquals(24, M749PairFile.load(file).knownCount());
            assertTrue(log.get(0).contains("incomplete"));
            assertTrue(log.get(log.size() - 1).contains("populated local file " + file));

            log.clear();
            assertEquals(file, M749AutoCredential.select(i865, unused, log::add));
            assertTrue(log.get(0).contains("reusing complete local file " + file));
            M749Monitor.Identification loader = new M749Monitor.Identification(
                    M749FirmwareDetection.Result.OEM_UNKNOWN, List.of(), null, "8450094615", 2);
            log.clear();
            assertEquals(file, M749AutoCredential.select(loader, unused, log::add));
            assertTrue(log.get(0).contains("reusing complete local file " + file));
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    private static M749Monitor.Identification identity(M749FirmwareDetection.Result firmware,
                                                        String software, String part) {
        return new M749Monitor.Identification(firmware, List.of(), software, part);
    }
}
