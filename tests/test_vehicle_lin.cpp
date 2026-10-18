#include "pch.h"
#include "vehicle_lin.h"

using namespace m749lin;

namespace {
void configure(Master& master) {
    const uint8_t four[] = {0x88, 0x88, 0, 3};
    const uint8_t two[] = {0x78, 0xff};
    ASSERT_TRUE(master.setPublisher(1, four, sizeof(four)));
    ASSERT_TRUE(master.setPublisher(2, two, sizeof(two)));
    ASSERT_TRUE(master.setPublisher(3, four, sizeof(four)));
}

ReceiveResult echo(Master& master, const Transfer& transfer, uint32_t now) {
    master.onBreak();
    ReceiveResult result = ReceiveResult::Ignored;
    for (unsigned i = 0; i < transfer.length; i++) {
        result = master.onByte(transfer.bytes[i], now);
    }
    return result;
}

ReceiveResult response(Master& master, const Transfer& transfer, uint32_t now, bool corrupt = false) {
    const auto& frame = Frames[transfer.slot];
    EXPECT_EQ(ReceiveResult::InProgress, echo(master, transfer, now));
    uint8_t bytes[8] = {};
    // Includes zero data: it must not be mistaken for a break.
    for (unsigned i = 0; i < frame.length; i++) {
        bytes[i] = i;
        EXPECT_EQ(ReceiveResult::InProgress, master.onByte(bytes[i], now));
    }
    return master.onByte(checksum(frame.id, bytes, frame.length, frame.enhanced) ^ (corrupt ? 1 : 0), now);
}
}

TEST(M749Lin, ParityAndCarryChecksum) {
    const uint8_t ids[] = {0x3b, 0x29, 0x0f, 0x20, 0x30, 0x12, 0x15, 0x18, 0x26, 0x05, 0x10};
    const uint8_t pids[] = {0xfb, 0xe9, 0xcf, 0x20, 0xf0, 0x92, 0x55, 0xd8, 0xa6, 0x85, 0x50};
    for (unsigned i = 0; i < FrameCount; i++) {
        EXPECT_EQ(pids[i], protectedId(ids[i]));
        EXPECT_EQ(pids[i], protectedId(ids[i] | 0xc0));
        EXPECT_EQ(ids[i], Frames[i].id);
    }
    const uint8_t classic[] = {0x4a, 0x55, 0x93};
    EXPECT_EQ(0xcc, checksum(0, classic, sizeof(classic), false));
    const uint8_t carry[] = {0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff};
    EXPECT_EQ(0, checksum(0, carry, sizeof(carry), false));
    EXPECT_EQ(0x16, checksum(0x29, carry, sizeof(carry), true));
    EXPECT_EQ(0xcc, checksum(0x3c, classic, sizeof(classic), true));
    EXPECT_EQ(0xcc, checksum(0x3d, classic, sizeof(classic), true));
}

TEST(M749Lin, RequiresExplicitPublisherPayloads) {
    Master master;
    Transfer transfer;
    EXPECT_FALSE(master.start(0));
    EXPECT_FALSE(master.poll(100, transfer));
    const uint8_t bytes[8] = {};
    EXPECT_FALSE(master.setPublisher(11, bytes, 8));
    EXPECT_FALSE(master.setPublisher(4, bytes, 8));
    EXPECT_FALSE(master.setPublisher(1, nullptr, 4));
    EXPECT_FALSE(master.setPublisher(1, bytes, 3));
    EXPECT_FALSE(master.setPublisher(1, bytes, 5));
    configure(master);
    EXPECT_TRUE(master.start(0));
    EXPECT_FALSE(master.start(0));
    EXPECT_FALSE(master.poll(9, transfer));
    EXPECT_TRUE(master.poll(10, transfer));
    EXPECT_EQ(1, transfer.slot);
    const uint8_t expected[] = {0x55, 0xe9, 0x88, 0x88, 0, 3, 2};
    EXPECT_EQ(sizeof(expected), transfer.length);
    EXPECT_EQ(0, memcmp(expected, transfer.bytes, sizeof(expected)));
}

TEST(M749Lin, ScheduleAndAllResponseLengths) {
    Master master;
    configure(master);
    ASSERT_TRUE(master.start(0));
    Sample sample;
    EXPECT_FALSE(master.read(4, 0, 100, sample));
    for (unsigned step = 0; step < 20; step++) {
        const uint32_t now = 10 + step * 10;
        Transfer transfer;
        ASSERT_TRUE(master.poll(now, transfer));
        EXPECT_EQ(1 + step % 10, transfer.slot);
        EXPECT_FALSE(master.poll(now, transfer));
        const auto& frame = Frames[transfer.slot];
        if (frame.subscribed) {
            EXPECT_EQ(2, transfer.length);
            EXPECT_EQ(ReceiveResult::Received, response(master, transfer, now + 1));
            ASSERT_TRUE(master.read(transfer.slot, now + 1, 0, sample));
            EXPECT_EQ(frame.length, sample.length);
            for (unsigned i = 0; i < frame.length; i++) EXPECT_EQ(i, sample.bytes[i]);
            EXPECT_TRUE(master.read(transfer.slot, now + 11, 10, sample));
            EXPECT_FALSE(master.read(transfer.slot, now + 12, 10, sample));
        } else {
            EXPECT_EQ(ReceiveResult::Published, echo(master, transfer, now + 1));
            EXPECT_FALSE(master.read(transfer.slot, now + 1, 100, sample));
        }
    }
    EXPECT_EQ(14U, master.statistics().received);
    EXPECT_EQ(6U, master.statistics().published);
    EXPECT_EQ(0U, master.statistics().timeouts);
}

TEST(M749Lin, SporadicPublisherIsSentOnceAndUsesTwentyMs) {
    Master master;
    configure(master);
    const uint8_t bytes[] = {0, 1, 2, 3, 4, 5, 6, 7};
    ASSERT_TRUE(master.setPublisher(0, bytes, sizeof(bytes)));
    ASSERT_TRUE(master.start(0));
    Transfer transfer;
    ASSERT_TRUE(master.poll(10, transfer));
    ASSERT_EQ(0, transfer.slot);
    EXPECT_EQ(11, transfer.length);
    EXPECT_EQ(0xfb, transfer.bytes[1]);
    EXPECT_EQ(0xe3, transfer.bytes[10]);
    EXPECT_EQ(ReceiveResult::Published, echo(master, transfer, 11));
    EXPECT_FALSE(master.poll(29, transfer));
    for (unsigned step = 0; step < 11; step++) {
        ASSERT_TRUE(master.poll(30 + step * 10, transfer));
        EXPECT_EQ(1 + step % 10, transfer.slot);
    }
}

TEST(M749Lin, EchoErrorsAndImmutableInFlightPayload) {
    Master master;
    configure(master);
    ASSERT_TRUE(master.start(0));
    Transfer transfer;
    ASSERT_TRUE(master.poll(10, transfer));
    const uint8_t replacement[4] = {};
    ASSERT_TRUE(master.setPublisher(1, replacement, sizeof(replacement)));
    EXPECT_EQ(ReceiveResult::Published, echo(master, transfer, 11));
    ASSERT_TRUE(master.poll(20, transfer));
    EXPECT_EQ(ReceiveResult::Ignored, master.onByte(0x55, 21));
    master.onBreak();
    EXPECT_EQ(ReceiveResult::Error, master.onByte(0, 21));
    EXPECT_EQ(ReceiveResult::Ignored, master.onByte(0x55, 21));
    ASSERT_TRUE(master.poll(30, transfer));
    master.onBreak();
    EXPECT_EQ(ReceiveResult::InProgress, master.onByte(0x55, 31));
    EXPECT_EQ(ReceiveResult::Error, master.onByte(transfer.bytes[1] ^ 0x40, 31));
    ASSERT_TRUE(master.poll(40, transfer));
    master.onBreak();
    master.onBreak();
    EXPECT_EQ(ReceiveResult::Ignored, master.onByte(0x55, 41));
    EXPECT_EQ(3U, master.statistics().errors);
}

TEST(M749Lin, PartialAndBadResponsesDoNotPublishSamples) {
    Master master;
    configure(master);
    ASSERT_TRUE(master.start(0));
    Transfer transfer;
    for (unsigned now = 10; now <= 40; now += 10) {
        ASSERT_TRUE(master.poll(now, transfer));
        if (now < 40) {
            EXPECT_EQ(ReceiveResult::Published, echo(master, transfer, now + 1));
        }
    }
    EXPECT_EQ(ReceiveResult::Error, response(master, transfer, 41, true));
    Sample sample;
    EXPECT_FALSE(master.read(4, 41, 100, sample));
    ASSERT_TRUE(master.poll(50, transfer));
    EXPECT_EQ(ReceiveResult::InProgress, echo(master, transfer, 51));
    EXPECT_EQ(ReceiveResult::InProgress, master.onByte(0x12, 51));
    EXPECT_EQ(ReceiveResult::Error, master.onByte(0x34, 60));
    EXPECT_FALSE(master.read(5, 60, 100, sample));
    ASSERT_TRUE(master.poll(60, transfer));
    EXPECT_EQ(1U, master.statistics().timeouts);
    EXPECT_EQ(1U, master.statistics().errors);
}

TEST(M749Lin, TransportFailureStopAndRestart) {
    Master master;
    configure(master);
    ASSERT_TRUE(master.start(0));
    Transfer transfer;
    for (unsigned now = 10; now <= 40; now += 10) {
        ASSERT_TRUE(master.poll(now, transfer));
        if (now < 40) {
            EXPECT_EQ(ReceiveResult::Published, echo(master, transfer, now + 1));
        }
    }
    EXPECT_EQ(ReceiveResult::Received, response(master, transfer, 41));
    Sample sample;
    EXPECT_TRUE(master.read(4, 41, 100, sample));
    ASSERT_TRUE(master.poll(50, transfer));
    master.onTransportError();
    EXPECT_EQ(ReceiveResult::Ignored, master.onByte(0x55, 51));
    master.stop();
    EXPECT_FALSE(master.read(4, 51, 100, sample));
    EXPECT_FALSE(master.poll(1000, transfer));
    EXPECT_TRUE(master.start(1000));
    EXPECT_TRUE(master.poll(1010, transfer));
    EXPECT_EQ(1, transfer.slot);
}

TEST(M749Lin, MissedSlotsAndClockWrap) {
    Master master;
    configure(master);
    ASSERT_TRUE(master.start(0xfffffff0));
    Transfer transfer;
    EXPECT_FALSE(master.poll(0xfffffff9, transfer));
    ASSERT_TRUE(master.poll(0xfffffffa, transfer));
    EXPECT_EQ(ReceiveResult::Published, echo(master, transfer, 0xfffffffb));
    EXPECT_FALSE(master.poll(3, transfer));
    ASSERT_TRUE(master.poll(4, transfer));
    EXPECT_EQ(2, transfer.slot);
    ASSERT_TRUE(master.poll(100, transfer));
    EXPECT_EQ(3, transfer.slot);
    EXPECT_FALSE(master.poll(100, transfer));
    EXPECT_EQ(1U, master.statistics().timeouts);
    EXPECT_EQ(ReceiveResult::Published, echo(master, transfer, 101));
}
