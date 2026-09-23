package com.rusefi.m749;

import java.io.IOException;
import java.util.Arrays;

import static com.rusefi.m749.M749Identification.bytes;

/** Read-only positive identification; silence is not an OEM identity. */
final class M749FirmwareDetection {
    enum Result {
        UNKNOWN("No rusEFI identity response", false),
        RUSEFI("rusEFI (UDS identity F1A4; M749ACT1 not confirmed)", false),
        M749_READY("rusEFI (M749ACT1; activation ready)", true),
        M749_NOT_READY("rusEFI (M749ACT1; activation NOT ready)", true);

        final String description;
        final boolean m749;

        Result(String description, boolean m749) {
            this.description = description;
            this.m749 = m749;
        }
    }

    static Result detect(UdsClient client) throws IOException, InterruptedException {
        byte[] identity = readOptional(client, 0xF1A4);
        boolean rusefi = Arrays.equals(identity, bytes(0x62, 0xF1, 0xA4, 'r', 'E', 'F', 'I'));
        // Compatibility with installed M74.9 images predating EFI_UDS. This
        // also confirms the board-specific interface independently of identity.
        byte[] activation = readOptional(client, 0xF1A0);
        if (Arrays.equals(activation, bytes(0x62, 0xF1, 0xA0, 0x4D, 0x74, 1, 1))) {
            return Result.M749_READY;
        }
        if (Arrays.equals(activation, bytes(0x62, 0xF1, 0xA0, 0x4D, 0x74, 1, 0))) {
            return Result.M749_NOT_READY;
        }
        return rusefi ? Result.RUSEFI : Result.UNKNOWN;
    }

    private static byte[] readOptional(UdsClient client, int did) throws IOException, InterruptedException {
        try {
            return client.exchange(bytes(0x22, did >>> 8, did), bytes(0x62, did >>> 8, did), 2_000);
        } catch (UdsClient.Timeout | UdsClient.NegativeResponse unavailable) {
            return null;
        }
    }
}
