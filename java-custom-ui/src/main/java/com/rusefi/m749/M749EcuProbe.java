package com.rusefi.m749;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;
import static com.rusefi.m749.M749Identification.bytes;

/** Bounded identity queries without changing diagnostic sessions or security. */
final class M749EcuProbe {
    static void identify(M749Uploader.Connection connection, Consumer<String> out)
            throws IOException, InterruptedException {
        int positive = 0;
        for (int did : new int[]{0xF186, 0xF189, 0xF192, 0xF1A4, 0xF1A0}) {
            byte[] prefix = bytes(0x62, did >>> 8, did);
            try {
                byte[] response = connection.exchange(bytes(0x22, did >>> 8, did), prefix, 2000);
                if (!UdsClient.startsWith(response, prefix) || response.length <= 3) {
                    throw new IOException(String.format("Malformed identity response for DID %04X", did));
                }
                byte[] value = Arrays.copyOfRange(response, 3, response.length);
                out.accept(String.format("ECU present on 7E8: DID %04X | hex=%s | ASCII=%s", did,
                        M749Identification.hex(value), M749Identification.ascii(value)));
                positive++;
            } catch (UdsClient.Timeout | UdsClient.NegativeResponse e) {
                out.accept(String.format("DID %04X unavailable: %s", did, e.getMessage()));
            }
        }
        if (positive == 0) { throw new IOException("ECU identity was not confirmed: no positive identification response on 7E8"); }
        out.accept("ECU presence confirmed; no session change, security access or RAM upload requested");
    }
}
