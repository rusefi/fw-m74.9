package com.rusefi.m749;

import com.rusefi.libopenblt.file.SrecParser.SRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class M749CliUploadTest {
    @TempDir Path directory;
    private final M749Monitor.Backend noDevices = new M749Monitor.Backend() {
        public List<PcanDevice.Channel> scan() { throw new AssertionError("Native access before preflight"); }
        public List<String> identify(PcanDevice.Channel c, Consumer<String> out) { throw new AssertionError("Unexpected identify"); }
    };
    private final M749Cli.UploadAction noUpload = (c, i, v, a, o) -> { throw new AssertionError("Unexpected upload"); };

    @Test void malformedOptionsAndInputsNeverAccessHardware() throws Exception {
        for (String[] args : new String[][]{{"--upload"}, {"--upload", "missing.hex"},
                {"--verify-bytes"}, {"--calibration"}, {"--list", "--upload", "x.hex"}, {"--channel"},
                {"--immo-backup"}, {"--immo-backup", "backup.bin"}}) {
            assertEquals(2, M749Cli.execute(args, noDevices, noUpload, s -> {}));
        }
        Path file = directory.resolve("broken.hex");
        Files.writeString(file, ":00000001FF\n");
        assertThrows(IOException.class, () -> M749Cli.execute(new String[]{"--upload", file.toString(), "--channel", "PCAN_USBBUS1"},
                noDevices, noUpload, s -> {}));
        assertEquals(0, M749Cli.execute(new String[]{"--help"}, noDevices, noUpload, s -> {}));
    }

    @Test void dryRunHandlesSpacesAndLiveRequestUsesExactValidatedImageAndExplicitChannel() throws Exception {
        Path file = directory.resolve("calibration with spaces.srec");
        StringBuilder text = new StringBuilder();
        SRecord record = M749ImageTest.records(M749Image.Domain.CALIBRATION).get(0);
        final char[] hex = "0123456789ABCDEF".toCharArray();
        for (int i = 0; i < record.data.length; i += 32) {
            byte[] row = new byte[38];
            row[0] = 37;
            int address = record.address + i;
            for (int n = 0; n < 4; n++) { row[1 + n] = (byte) (address >>> (24 - n * 8)); }
            System.arraycopy(record.data, i, row, 5, 32);
            int sum = 0;
            for (int n = 0; n < row.length - 1; n++) { sum += row[n] & 255; }
            row[37] = (byte) ~sum;
            text.append("S3");
            for (byte value : row) { text.append(hex[(value & 255) >>> 4]).append(hex[value & 15]); }
            text.append('\n');
        }
        Files.writeString(file, text + "S70500000000FA\n");
        Path invalidBackup = directory.resolve("wrong backup.bin");
        Files.write(invalidBackup, new byte[0x3F0000]);
        assertThrows(IOException.class, () -> M749Cli.execute(new String[]{"--upload", file.toString(),
                "--calibration", "--dry-run", "--immo-backup", invalidBackup.toString()},
                noDevices, noUpload, s -> {}));
        assertEquals(0, M749Cli.execute(new String[]{"--upload", file.toString(), "--calibration", "--dry-run"},
                noDevices, noUpload, s -> {}));
        boolean[] called = {false};
        assertEquals(0, M749Cli.execute(new String[]{"--upload", file.toString(), "--calibration", "--channel", "PCAN_USBBUS2", "--verify-bytes"},
                noDevices, (channel, image, verify, immo, out) -> {
                    called[0] = true;
                    assertEquals("PCAN_USBBUS2", channel);
                    assertEquals(M749Image.Domain.CALIBRATION, image.domain);
                    assertArrayEquals(record.data, image.ranges.get(0).bytes());
                    assertTrue(verify);
                    assertNull(immo);
                }, s -> {}));
        assertTrue(called[0]);
    }
}
