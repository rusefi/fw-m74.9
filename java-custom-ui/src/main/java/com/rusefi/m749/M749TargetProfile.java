package com.rusefi.m749;

import com.devexperts.logging.Logging;
import java.io.IOException;

/**
 * Validated resident-loader contracts, not OEM application names. Multiple OEM
 * builds can share one loader profile. A current M749ACT3 software HEX/SREC is
 * common to these profiles; the uploader preserves 0x08060000..0x0807FFFF,
 * while the application uses the matched loader CRC to choose the retained
 * calibration CRC start. For another OEM 707 ECU, use that same software SREC
 * only after its live I832 loader passes detection. The 707-based full BIN
 * contains reference-ECU data and is not the CAN software update payload.
 * Unknown loader contracts must fail before erase.
 */
enum M749TargetProfile {
    // OEM software labels grouped by their resident-loader contract. Application
    // CAN behavior, session admission and credentials require separate checks.
    // I812: I812NA01, I812TA01
    // I832: I815NB02, I832GA01, I872GA02
    // I865: I862BA02, I865LB52
    I865(0xD7B6B894, 0x08060000,
            new int[]{0x08201E2C, 0x08201D84, 0x08204B7C},
            new String[]{"2de9f04184b004460d4617461e4601f0", "70b506460d46144601f024fd012801d0",
                    "08b50a4b1b68fff7e7ff012807d0fff7"}),
    I812(0x4F256CD9, 0x08069000,
            new int[]{0x08201DE8, 0x08201D40, 0x08204CC4},
            new String[]{"2de9f04184b004460d4617461e46", "70b506460d46144601f0eafd0128",
                    "08b50a4b1b68fff7e7ff012807d0"}),
    // The OEM I832GA01 / 8450110707 reference has this loader contract.
    I832(0xE3186D26, 0x08060000,
            new int[]{0x08201E2C, 0x08201D84, 0x08204B7C},
            new String[]{"2de9f04184b004460d4617461e46", "70b506460d46144601f024fd0128",
                    "08b50a4b1b68fff7e7ff012807d0"});

    private static final Logging logger = Logging.getLogging(M749TargetProfile.class);

    // Stored loader CRC at 0x0822DFFC and start of its OEM calibration CRC domain.
    final int bootCrc, calibrationStart;
    // Short loader-code signatures supplement the CRC word; neither checks the
    // MCU option bytes or proves that the entire resident loader is intact.
    final int[] addresses;
    final String[] sentinels;

    M749TargetProfile(int bootCrc, int calibrationStart, int[] addresses, String[] sentinels) {
        this.bootCrc = bootCrc;
        this.calibrationStart = calibrationStart;
        this.addresses = addresses;
        this.sentinels = sentinels;
    }

    static M749TargetProfile detect(M749ChecksumReader reader) throws IOException, InterruptedException {
        // FF01 is only an additive checksum, so compare one byte at a time.
        for (M749TargetProfile profile : values()) {
            boolean matches = true;
            for (int i = 0; i < 4; i++) {
                if (!reader.matches(0x0822DFFC + i, 1, (profile.bootCrc >>> (i * 8)) & 255)) {
                    matches = false;
                    break;
                }
            }
            if (!matches) { continue; }
            // Wrong-candidate controls reject a stuck "match" responder.
            for (int i = 0; i < 4; i++) {
                reader.verifyByte(0x0822DFFC + i, (profile.bootCrc >>> (i * 8)) & 255);
            }
            for (int n = 0; n < profile.addresses.length; n++) {
                String value = profile.sentinels[n];
                for (int i = 0; i < value.length(); i += 2) {
                    reader.verifyByte(profile.addresses[n] + i / 2, Integer.parseInt(value.substring(i, i + 2), 16));
                }
            }
            logger.info(String.format(
                    "Matched resident-loader profile %s: boot CRC 0x%08X, calibration start 0x%08X; " +
                            "CRC bytes and %d loader-code signatures verified",
                    profile, profile.bootCrc, profile.calibrationStart, profile.addresses.length));
            return profile;
        }
        throw new IOException("Unsupported resident loader: I812/I832/I865 compatibility check failed");
    }
}
