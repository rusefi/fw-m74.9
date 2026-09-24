package com.rusefi.m749;

import com.rusefi.libopenblt.file.SrecParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Immutable, complete CRC domain. Validation finishes before any device access. */
final class M749Image {
    enum Domain { SOFTWARE, CALIBRATION }
    static final int START = 0x08001000, CAL = 0x08060000, SECOND = 0x08080000, END = 0x08100000;
    static final int PAGE = 4096;
    static final int ACTIVATION_ADDRESS = 0x0805FFE0;
    static final byte[] ACTIVATION_ABI = new byte[]{0x4D, 0x37, 0x34, 0x39, 0x41, 0x43, 0x54, 0x31,
            (byte) 0x94, (byte) 0xB8, (byte) 0xB6, (byte) 0xD7, 1, 0, 0, 0};
    static final byte[] ACTIVATION_ABI_V2 = new byte[]{0x4D, 0x37, 0x34, 0x39, 0x41, 0x43, 0x54, 0x32,
            2, 0, 0, 0, 1, 0, 0, 0,
            (byte) 0x94, (byte) 0xB8, (byte) 0xB6, (byte) 0xD7, 0, 0, 6, 8,
            (byte) 0xD9, 0x6C, 0x25, 0x4F, 0, (byte) 0x90, 6, 8};
    static final class Range {
        final int address;
        private final byte[] data;
        Range(int address, byte[] data) { this.address = address; this.data = data.clone(); }
        int length() { return data.length; }
        byte[] bytes() { return data.clone(); }
    }
    final Domain domain;
    final List<Range> ranges;
    final int crc;

    private M749Image(Domain domain, List<Range> ranges, int crc) {
        this.domain = domain;
        this.ranges = Collections.unmodifiableList(ranges);
        this.crc = crc;
    }

    static M749Image load(Path file, Domain domain) throws IOException {
        if (Files.size(file) > 8 * 1024 * 1024) {
            throw new IOException("Image text exceeds 8 MiB");
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        List<SrecParser.SRecord> records;
        if (name.endsWith(".srec") || name.endsWith(".s19") || name.endsWith(".s28") || name.endsWith(".s37")) {
            records = SRecordReader.read(file);
        } else if (name.endsWith(".hex")) {
            records = IntelHexReader.read(file);
        } else {
            throw new IOException("Expected addressed .hex or .srec input; raw BIN and ELF are not upload payloads");
        }
        return validate(records, domain);
    }

    static M749Image validate(List<SrecParser.SRecord> input, Domain domain) throws IOException {
        List<SrecParser.SRecord> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingLong(r -> Integer.toUnsignedLong(r.address)));
        List<Range> ranges = new ArrayList<>();
        ByteArrayOutputStream run = new ByteArrayOutputStream();
        int start = 0;
        long end = -1;
        for (SrecParser.SRecord record : sorted) {
            long address = Integer.toUnsignedLong(record.address);
            long last = address + record.data.length;
            if (record.data.length == 0 || address < START || last > END ||
                    (domain == Domain.CALIBRATION ? address < CAL || last > SECOND :
                            !(last <= CAL || address >= SECOND))) {
                throw new IOException(String.format("Forbidden or mixed-domain range at 0x%08X", address));
            }
            if (address < end) {
                throw new IOException("Overlapping image records");
            }
            if (address != end) {
                if (run.size() > 0) { ranges.add(new Range(start, run.toByteArray())); }
                run.reset();
                start = record.address;
            }
            run.write(record.data, 0, record.data.length);
            end = last;
        }
        if (run.size() > 0) { ranges.add(new Range(start, run.toByteArray())); }
        int[][] expected = domain == Domain.SOFTWARE ?
                new int[][]{{START, CAL - START}, {SECOND, END - SECOND}} :
                new int[][]{{CAL, SECOND - CAL}};
        if (ranges.size() != expected.length) { throw new IOException("Incomplete CRC domain: missing ranges or bytes"); }
        for (int i = 0; i < expected.length; i++) {
            Range range = ranges.get(i);
            if (range.address != expected[i][0] || range.length() != expected[i][1]) {
                throw new IOException("Incomplete CRC domain: every declared page byte, including padding, is required");
            }
        }
        if (domain == Domain.SOFTWARE && (littleEndian(ranges.get(0).data, 0) != 0x20020000 ||
                littleEndian(ranges.get(0).data, 4) != SECOND + 1)) {
            throw new IOException("Invalid M74.9 initial stack/reset vectors");
        }
        int crc = -1;
        for (int i = 0; i < ranges.size(); i++) {
            byte[] data = ranges.get(i).data;
            crc = crc32(data, i == ranges.size() - 1 ? data.length - 4 : data.length, crc);
        }
        byte[] tail = ranges.get(ranges.size() - 1).data;
        if (crc != littleEndian(tail, tail.length - 4)) {
            throw new IOException("CRC-32/MPEG-2 mismatch (including erased padding)");
        }
        return new M749Image(domain, ranges, crc);
    }

    static int crc32(byte[] data, int length, int crc) {
        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 255) << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc << 1) ^ (crc < 0 ? 0x04C11DB7 : 0);
            }
        }
        return crc;
    }

    void requireActivationSupport() throws IOException {
        if (domain == Domain.SOFTWARE) {
            byte[] first = ranges.get(0).data;
            int offset = ACTIVATION_ADDRESS - START;
            if (!descriptorMatches(first, offset, ACTIVATION_ABI) && !descriptorMatches(first, offset, ACTIVATION_ABI_V2)) {
                throw new IOException("Software lacks a supported M749ACT1/M749ACT2 persistent-activation ABI; rebuild first");
            }
        }
    }

    private static boolean descriptorMatches(byte[] data, int offset, byte[] descriptor) {
        return Arrays.equals(descriptor, Arrays.copyOfRange(data, offset, offset + descriptor.length));
    }

    void requireTarget(M749TargetProfile profile) throws IOException {
        requireActivationSupport();
        if (domain == Domain.CALIBRATION && profile.calibrationStart != CAL) {
            throw new IOException("This calibration payload uses the I865 layout; I812 software updates preserve its calibration");
        }
        if (domain == Domain.SOFTWARE && profile == M749TargetProfile.I812 &&
                !descriptorMatches(ranges.get(0).data, ACTIVATION_ADDRESS - START, ACTIVATION_ABI_V2)) {
            throw new IOException("I812 requires M749ACT2 software; the M749ACT1 image supports only I865");
        }
    }

    private static int littleEndian(byte[] data, int i) {
        return (data[i] & 255) | (data[i + 1] & 255) << 8 | (data[i + 2] & 255) << 16 | data[i + 3] << 24;
    }

    void describe(Consumer<String> out) {
        out.accept(String.format("Validated %s CRC domain: CRC32/MPEG-2 %08X", domain, crc));
        for (Range range : ranges) {
            out.accept(String.format("0x%08X-0x%08X: %d bytes, %d pages, %d blocks at maximum 2048 bytes",
                    range.address, range.address + range.length() - 1, range.length(),
                    range.length() / PAGE, (range.length() + 2047) / 2048));
        }
        out.accept("Plan: programming session -> loader security -> erase each range -> 34/36/37 -> verify -> activation.");
        out.accept("Activation: preserve loader metadata -> reset -> application CRC/marker checks -> reset and recheck.");
    }
}
