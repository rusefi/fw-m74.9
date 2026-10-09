package com.rusefi.m749;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Resolve the local paired I865 credential without adding controls to the console tab. */
final class M749AutoCredential {
    private static final String SOFTWARE = "I865LB52_w2404b1";
    private static final String PART = "8450094615";
    private static final String FILE = SOFTWARE + "_" + PART + ".pair";

    static Path path() {
        return Path.of(System.getProperty("user.home"), ".rusefi", "m749", FILE);
    }

    interface LiveReader {
        void read(Path pairFile, Consumer<String> out) throws IOException, InterruptedException;
    }

    static Path select(M749Monitor.Identification identity, LiveReader reader, Consumer<String> out)
            throws IOException, InterruptedException {
        if (identity == null || identity.firmware.m749) return null;
        boolean application = SOFTWARE.equals(identity.software);
        boolean loader = Integer.valueOf(2).equals(identity.session) && PART.equals(identity.part);
        if (!application && !loader) return null;
        if (!PART.equals(identity.part)) {
            throw new IOException("I865 software identified, but part number does not match the local pairing profile");
        }
        Path credential = path();
        int known = 0;
        if (Files.isRegularFile(credential)) {
            try {
                known = M749PairFile.load(credential).knownCount();
            } catch (IOException e) {
                throw new IOException("I865 local .pair cache invalid at " + credential + ": " + e.getMessage(), e);
            }
        }
        if (known == M749PairFile.SIZE) {
            out.accept("I865 .pair cache: reusing complete local file " + credential + " (24/24 bytes); no live read");
            return credential;
        }
        out.accept("I865 .pair cache: " + (Files.isRegularFile(credential) ? "incomplete" : "missing")
                + " at " + credential + " (" + known + "/24 bytes); populating from live OEM via FF01 one-byte checksum reads");
        try {
            reader.read(credential, out);
        } catch (IOException e) {
            throw new IOException("I865 .pair cache " + credential + " remains incomplete; live FF01 read failed: "
                    + e.getMessage(), e);
        }
        int completed;
        try {
            completed = M749PairFile.load(credential).knownCount();
        } catch (IOException e) {
            throw new IOException("I865 .pair cache invalid after live read at " + credential + ": "
                    + e.getMessage(), e);
        }
        if (completed != M749PairFile.SIZE) {
            throw new IOException("I865 .pair cache " + credential + " remains incomplete after live FF01 read ("
                    + completed + "/24 bytes)");
        }
        out.accept("I865 .pair cache: populated local file " + credential
                + " from live OEM FF01 one-byte checksum reads (24/24 bytes)");
        return credential;
    }

    private M749AutoCredential() { }
}
