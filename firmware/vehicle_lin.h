#pragma once

#include <cstddef>
#include <cstdint>

// Protocol engine only. A single owner must serialize all calls and supply
// transport events; this class does not configure pins, a UART or a timer.
namespace m749lin {
constexpr unsigned FrameCount = 11;
constexpr uint32_t BaudRate = 19200;

struct FrameDefinition {
    uint8_t id;
    uint8_t length;
    bool subscribed;
    bool enhanced;
    uint8_t slotMs;
    bool sporadic;
};

extern const FrameDefinition Frames[FrameCount];
uint8_t protectedId(uint8_t id);
// Diagnostic IDs 3C/3D always use classic checksum, even if enhanced is true.
uint8_t checksum(uint8_t id, const uint8_t* data, size_t length, bool enhanced);

struct Transfer {
    uint8_t slot = 0;
    // Send a LIN break (at least 13 dominant bits + delimiter) before bytes.
    // bytes includes sync and PID, plus data/checksum for a publisher.
    uint8_t bytes[11] = {};
    uint8_t length = 0;
};

struct Sample {
    uint8_t bytes[8] = {};
    uint8_t length = 0;
    uint32_t receivedAtMs = 0;
    bool valid = false;
};

struct Statistics {
    uint32_t received = 0;
    uint32_t published = 0;
    uint32_t timeouts = 0;
    uint32_t errors = 0;
};

enum class ReceiveResult { Ignored, InProgress, Received, Published, Error };

class Master {
public:
    // Full payload replacement only. Slot 0 queues a single sporadic transfer;
    // subsequent writes before its slot replace that queued payload.
    bool setPublisher(unsigned slot, const uint8_t* data, size_t length);
    // All three periodic publisher payloads must be supplied before starting.
    bool start(uint32_t nowMs);
    void stop();
    // Call frequently. Returns at most one transfer; missed slots are not burst
    // replayed. The first slot is due 10 ms after start().
    bool poll(uint32_t nowMs, Transfer& out);
    // The transport must deliver the local break, sync, PID and TX echo too.
    // Decode a break as an event, rather than treating every zero as a break.
    void onBreak();
    ReceiveResult onByte(uint8_t byte, uint32_t nowMs);
    void onTransportError();
    bool read(unsigned slot, uint32_t nowMs, uint32_t maxAgeMs, Sample& out) const;
    const Statistics& statistics() const { return stats; }

private:
    bool running = false;
    bool pending = false;
    bool sawBreak = false;
    bool queued = false;
    bool ready[FrameCount] = {};
    uint8_t publishers[4][8] = {};
    Sample samples[FrameCount] = {};
    Transfer current;
    uint8_t response[8] = {};
    unsigned cursor = FrameCount - 1;
    unsigned position = 0;
    uint32_t dueMs = 0;
    Statistics stats;
};
}
