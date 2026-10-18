#include "vehicle_lin.h"

#include <cstring>

namespace m749lin {
const FrameDefinition Frames[FrameCount] = {
    {0x3b, 8, false, false, 20, true},
    {0x29, 4, false, true,  10, false},
    {0x0f, 2, false, true,  10, false},
    {0x20, 4, false, true,  10, false},
    {0x30, 8, true,  true,  10, false},
    {0x12, 2, true,  true,  10, false},
    {0x15, 4, true,  true,  10, false},
    {0x18, 2, true,  true,  10, false},
    {0x26, 2, true,  true,  10, false},
    {0x05, 2, true,  true,  10, false},
    {0x10, 2, true,  true,  10, false},
};

uint8_t protectedId(uint8_t id) {
    id &= 0x3f;
    const auto p0 = (id ^ (id >> 1) ^ (id >> 2) ^ (id >> 4)) & 1;
    const auto p1 = ~( (id >> 1) ^ (id >> 3) ^ (id >> 4) ^ (id >> 5)) & 1;
    return id | (p0 << 6) | (p1 << 7);
}

uint8_t checksum(uint8_t id, const uint8_t* data, size_t length, bool enhanced) {
    id &= 0x3f;
    unsigned sum = enhanced && id != 0x3c && id != 0x3d ? protectedId(id) : 0;
    for (size_t i = 0; i < length; i++) {
        sum += data[i];
        if (sum > 255) {
            sum -= 255;
        }
    }
    return static_cast<uint8_t>(~sum);
}

bool Master::setPublisher(unsigned slot, const uint8_t* data, size_t length) {
    if (slot >= 4 || data == nullptr || length != Frames[slot].length) {
        return false;
    }
    std::memcpy(publishers[slot], data, length);
    ready[slot] = true;
    if (slot == 0) {
        queued = true;
    }
    return true;
}

bool Master::start(uint32_t nowMs) {
    if (running || !ready[1] || !ready[2] || !ready[3]) {
        return false;
    }
    running = true;
    cursor = FrameCount - 1;
    dueMs = nowMs + 10;
    return true;
}

void Master::stop() {
    running = pending = sawBreak = queued = false;
    for (auto& sample : samples) {
        sample.valid = false;
    }
}

bool Master::poll(uint32_t nowMs, Transfer& out) {
    if (!running || static_cast<int32_t>(nowMs - dueMs) < 0) {
        return false;
    }
    if (pending) {
        stats.timeouts++;
    }
    cursor = (cursor + 1) % FrameCount;
    if (cursor == 0 && !queued) {
        cursor = 1;
    }
    const auto& frame = Frames[cursor];
    dueMs = nowMs + frame.slotMs;
    current = {};
    current.slot = cursor;
    current.bytes[0] = 0x55;
    current.bytes[1] = protectedId(frame.id);
    current.length = 2;
    if (!frame.subscribed) {
        std::memcpy(current.bytes + 2, publishers[cursor], frame.length);
        current.bytes[2 + frame.length] = checksum(frame.id, current.bytes + 2, frame.length, frame.enhanced);
        current.length += frame.length + 1;
    }
    if (cursor == 0) {
        queued = false;
    }
    pending = true;
    sawBreak = false;
    position = 0;
    out = current;
    return true;
}

void Master::onBreak() {
    if (pending) {
        if (sawBreak) {
            onTransportError();
            return;
        }
        position = 0;
        sawBreak = true;
    }
}

void Master::onTransportError() {
    if (pending) {
        stats.errors++;
        pending = sawBreak = false;
    }
}

ReceiveResult Master::onByte(uint8_t byte, uint32_t nowMs) {
    if (!pending || !sawBreak) {
        return ReceiveResult::Ignored;
    }
    // Reject late bytes even if the caller has not polled the next slot yet.
    if (static_cast<int32_t>(nowMs - dueMs) >= 0) {
        pending = sawBreak = false;
        stats.timeouts++;
        return ReceiveResult::Error;
    }
    const auto& frame = Frames[current.slot];
    if (position < 2 || !frame.subscribed) {
        if (byte != current.bytes[position]) {
            onTransportError();
            return ReceiveResult::Error;
        }
    } else if (position < 2U + frame.length) {
        response[position - 2] = byte;
    } else if (byte != checksum(frame.id, response, frame.length, frame.enhanced)) {
        onTransportError();
        return ReceiveResult::Error;
    }
    position++;
    if (position != 3U + frame.length) {
        return ReceiveResult::InProgress;
    }
    pending = sawBreak = false;
    if (frame.subscribed) {
        auto& sample = samples[current.slot];
        std::memcpy(sample.bytes, response, frame.length);
        sample.length = frame.length;
        sample.receivedAtMs = nowMs;
        sample.valid = true;
        stats.received++;
        return ReceiveResult::Received;
    }
    stats.published++;
    return ReceiveResult::Published;
}

bool Master::read(unsigned slot, uint32_t nowMs, uint32_t maxAgeMs, Sample& out) const {
    if (slot >= FrameCount || !Frames[slot].subscribed || !samples[slot].valid ||
        nowMs - samples[slot].receivedAtMs > maxAgeMs) {
        return false;
    }
    out = samples[slot];
    return true;
}
}
