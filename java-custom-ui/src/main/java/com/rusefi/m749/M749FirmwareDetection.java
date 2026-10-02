package com.rusefi.m749;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

import static com.rusefi.m749.M749Identification.bytes;

/** Read-only positive identification; silence is not an OEM identity. */
final class M749FirmwareDetection {
    // Known software identities, not proof of resident-loader compatibility.
    private static final Set<String> KNOWN_OEM_BUILDS = Set.of(
            "I812NA01_w2243v21", "I812TA01_w2243v21", "I832GA01_w2304v2", "I865LB52_w2404b1");

    enum Result {
        UNKNOWN("No rusEFI identity response", false),
        OEM("OEM firmware detected", false),
        OEM_UNKNOWN("Unknown OEM build", false),
        RUSEFI("rusEFI (UDS identity F1A4; M74.9 activation interface not confirmed)", false),
        M749_READY("rusEFI (M74.9; activation ready)", true),
        M749_NOT_READY("rusEFI (M74.9; activation NOT ready)", true);

        final String description;
        final boolean m749;

        Result(String description, boolean m749) {
            this.description = description;
            this.m749 = m749;
        }
    }

    static Result detect(UdsClient client) throws IOException, InterruptedException {
        byte[] identity = readOptional(client, 0xF1A4);
        return classify(identity, readOptional(client, 0xF1A0));
    }

    static Result classifyOem(byte[] software) {
        if (software == null) return Result.OEM_UNKNOWN;
        int length = software.length;
        while (length > 0 && (software[length - 1] == 0 || software[length - 1] == ' ')) length--;
        String build = new String(software, 0, length, StandardCharsets.US_ASCII);
        return KNOWN_OEM_BUILDS.contains(build) ? Result.OEM : Result.OEM_UNKNOWN;
    }

    static Result classify(byte[] identity, byte[] activation) {
        boolean rusefi = Arrays.equals(identity, bytes(0x62, 0xF1, 0xA4, 'r', 'E', 'F', 'I'));
        // Compatibility with installed M74.9 images predating EFI_UDS. This
        // also confirms the board-specific interface independently of identity.
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
