#include "../firmware/bootloader_handoff.h"
#include <array>
#include <cassert>
#include <algorithm>

int main() {
    std::array<uint8_t, 8> response{};
    int sent = 0;
    int resets = 0;
    bool completed = false;
    auto transmit = [&](const uint8_t* bytes) {
        sent++;
        std::copy(bytes, bytes + 8, response.begin());
        assert(resets == 0);
        return completed;
    };
    auto reset = [&](uint32_t token) {
        assert(completed && sent > 0 && response[1] == 0x50);
        assert(token == 0x4DF9123B);
        resets++;
    };
    uint8_t request[] = {2, 0x10, 2, 0, 0, 0, 0, 0};
    auto handle = [&](size_t bus, uint32_t id, bool ext, bool rtr, size_t len, bool stopped) {
        m749::handleRequest(bus, id, ext, rtr, request, len, stopped, transmit, reset);
    };
    handle(1, 0x7E0, false, false, 8, true);
    handle(0, 0x7DF, false, false, 8, true);
    handle(0, 0x7E0, true, false, 8, true);
    handle(0, 0x7E0, false, true, 8, true);
    handle(0, 0x7E0, false, false, 1, true);
    assert(sent == 0 && resets == 0);
    handle(0, 0x7E0, false, false, 2, true);
    assert(response[1] == 0x7F && response[3] == 0x13 && resets == 0);
    request[2] = 0x82;
    handle(0, 0x7E0, false, false, 8, true);
    assert(response[3] == 0x12 && resets == 0);
    request[2] = 2;
    handle(0, 0x7E0, false, false, 8, false);
    assert(response[3] == 0x22 && resets == 0);
    handle(0, 0x7E0, false, false, 8, true);
    assert(response == (std::array<uint8_t, 8>{6, 0x50, 2, 0, 50, 1, 0xF4, 0}));
    assert(resets == 0); // Queue acceptance/timeout/error must never cause reset.
    completed = true;
    handle(0, 0x7E0, false, false, 3, true);
    assert(resets == 1);
    static_assert(m749::ReturnToken == 0xF9C74A52);
}
