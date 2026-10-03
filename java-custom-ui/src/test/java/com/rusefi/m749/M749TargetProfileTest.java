package com.rusefi.m749;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class M749TargetProfileTest {
    private M749UploaderTest.Ecu target(M749TargetProfile profile) {
        return target(profile, false);
    }

    private M749UploaderTest.Ecu target(M749TargetProfile profile, boolean preflightOnly) {
        M749UploaderTest.Ecu ecu = new M749UploaderTest.Ecu() {
            public byte[] exchange(byte[] request, byte[] prefix, long timeout) throws IOException {
                if (preflightOnly) {
                    int sid = request[0] & 255;
                    assertTrue(sid == 0x10 || sid == 0x27 || sid == 0x22 ||
                            (sid == 0x31 && request[3] == 1), "Unexpected preflight service");
                }
                return super.exchange(request, prefix, timeout);
            }
        };
        M749ImageTest.word(ecu.flash, 0x22DFFC, profile.bootCrc);
        for (int n = 0; n < profile.addresses.length; n++) { ecu.putHex(profile.addresses[n], profile.sentinels[n]); }
        return ecu;
    }

    private M749Image image() throws IOException {
        return image(M749Image.ACTIVATION_ABI_V3);
    }

    private M749Image image(byte[] descriptor) throws IOException {
        return M749Image.validate(M749ImageTest.records(M749Image.Domain.SOFTWARE, descriptor),
                M749Image.Domain.SOFTWARE);
    }

    @Test void onePayloadUploadsToAllProfilesAndPreservesAllRetainedBytes() throws Exception {
        M749Image image = image();
        for (M749TargetProfile profile : M749TargetProfile.values()) {
            M749UploaderTest.Ecu ecu = target(profile);
            byte[] before = ecu.flash.clone();
            new M749Uploader(ecu, ecu.messages::add).upload(image, false);
            assertEquals(223, ecu.erases.size());
            assertEquals(2, ecu.resets);
            assertArrayEquals(Arrays.copyOfRange(before, 0x60000, 0x80000), Arrays.copyOfRange(ecu.flash, 0x60000, 0x80000));
            assertArrayEquals(Arrays.copyOf(before, 0x1000), Arrays.copyOf(ecu.flash, 0x1000));
            assertArrayEquals(Arrays.copyOfRange(before, 0x100000, before.length), Arrays.copyOfRange(ecu.flash, 0x100000, ecu.flash.length));
        }
    }

    @Test void preflightNeverSendsEraseDownloadMetadataOrReset() throws Exception {
        for (M749TargetProfile profile : M749TargetProfile.values()) {
            M749UploaderTest.Ecu ecu = target(profile, true);
            byte[] before = ecu.flash.clone();
            new M749Uploader(ecu, s -> { }).checkTarget(image());
            assertArrayEquals(before, ecu.flash);
            assertTrue(ecu.erases.isEmpty());
            assertEquals(0, ecu.writes);
            assertEquals(0, ecu.resets);
        }
    }

    @Test void detectsI832Ga01AndRejectsCorruptCompatibilityBytes() throws Exception {
        M749UploaderTest.Ecu ecu = new M749UploaderTest.Ecu();
        ecu.putHex(0x0822DFFC, "266d18e3");
        ecu.putHex(0x08201E2C, "2de9f04184b004460d4617461e46");
        ecu.putHex(0x08201D84, "70b506460d46144601f024fd0128");
        ecu.putHex(0x08204B7C, "08b50a4b1b68fff7e7ff012807d0");
        assertEquals(M749TargetProfile.I832, new M749ChecksumReader(ecu).checkProfile());
        for (int offset : new int[]{0x22DFFC, 0x201E2C, 0x201D84, 0x204B7C}) {
            ecu.flash[offset] ^= 1;
            assertThrows(IOException.class, () -> new M749ChecksumReader(ecu).checkProfile());
            ecu.flash[offset] ^= 1;
        }
    }

    @Test void i832RejectsExistingReplacementContractsBeforeErase() throws Exception {
        M749Image[] images = {
                image(M749Image.ACTIVATION_ABI_V2),
                M749Image.validate(M749ImageTest.records(M749Image.Domain.SOFTWARE), M749Image.Domain.SOFTWARE),
                M749Image.validate(M749ImageTest.records(M749Image.Domain.CALIBRATION), M749Image.Domain.CALIBRATION)
        };
        for (M749Image image : images) {
            M749UploaderTest.Ecu ecu = target(M749TargetProfile.I832);
            IOException error = assertThrows(IOException.class, () -> new M749Uploader(ecu, s -> {}).upload(image, false));
            assertTrue(error.getMessage().contains(image.domain == M749Image.Domain.SOFTWARE ?
                    "I832 requires M749ACT3" : "Calibration-only uploads are I865-only"));
            assertTrue(ecu.erases.isEmpty());
            assertEquals(0, ecu.writes);
            assertEquals(0, ecu.resets);
        }
    }

    @Test void legacySoftwareAndI865CalibrationCannotEraseI812() {
        for (M749Image.Domain domain : M749Image.Domain.values()) {
            M749UploaderTest.Ecu ecu = target(M749TargetProfile.I812);
            assertThrows(IOException.class, () -> ecu.run(domain));
            assertTrue(ecu.erases.isEmpty());
            assertEquals(0, ecu.resets);
        }
    }

    @Test void unknownCorruptAndAlwaysMatchingProfilesFailBeforeErase() throws Exception {
        for (int fault = 0; fault < 3; fault++) {
            M749UploaderTest.Ecu ecu = target(M749TargetProfile.I812);
            if (fault == 0) { ecu.flash[0x22DFFC] ^= 1; }
            if (fault == 1) { ecu.flash[0x201DE8] ^= 1; }
            if (fault == 2) {
                M749ChecksumReaderTest.Ecu stuck = new M749ChecksumReaderTest.Ecu();
                stuck.alwaysMatch = true;
                assertThrows(IOException.class, () -> new M749ChecksumReader(stuck).checkProfile());
                continue;
            }
            assertThrows(IOException.class, () -> new M749Uploader(ecu, s -> { }).upload(image(), false));
            assertTrue(ecu.erases.isEmpty());
        }
    }

    @Test void v2StillSupportsI812AndI865() throws Exception {
        for (M749TargetProfile profile : new M749TargetProfile[]{M749TargetProfile.I812, M749TargetProfile.I865}) {
            new M749Uploader(target(profile), s -> {}).upload(image(M749Image.ACTIVATION_ABI_V2), false);
        }
    }

    @Test void corruptV3DescriptorFailsBeforeErase() throws Exception {
        byte[] corrupt = M749Image.ACTIVATION_ABI_V3.clone();
        corrupt[24] ^= 1; // Correct image CRC cannot authorize a different loader list.
        M749UploaderTest.Ecu ecu = target(M749TargetProfile.I832);
        assertThrows(IOException.class, () -> new M749Uploader(ecu, s -> {}).upload(image(corrupt), false));
        assertTrue(ecu.erases.isEmpty());
        assertEquals(0, ecu.writes);
        assertEquals(0, ecu.resets);
    }

    @Test void calibrationRecognizesInstalledV2AndV3OnI865() throws Exception {
        for (byte[] descriptor : new byte[][]{M749Image.ACTIVATION_ABI_V2, M749Image.ACTIVATION_ABI_V3}) {
            M749UploaderTest.Ecu ecu = target(M749TargetProfile.I865);
            System.arraycopy(descriptor, 0, ecu.flash, 0x5FFE0, descriptor.length);
            ecu.run(M749Image.Domain.CALIBRATION);
            assertEquals(32, ecu.erases.size());
        }
    }
}
