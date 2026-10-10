#pragma once
#include <cstdint>

namespace m749 {
// Volatile across power loss. Reserved below loader RAM initialization and away
// from the boot token. Publish magic last so interrupted writes are rejected.
constexpr uint32_t BootDiagnosticAddress = 0x20000040;
constexpr uint32_t BootDiagnosticMagic = 0x3144424D; // MBD1
constexpr uint32_t BootDiagnosticVersionSize = 0x00010040;
enum class BootReason : uint32_t {
    Starting = 1, RamReady = 2, OptionReload = 3, ApplicationReady = 4,
    WrongMcu = 16, RamOption = 17, Protection = 18, Slib = 19,
    ImageChecks = 20, MarkerToken = 21, FlashBusy = 22, OptionUnlock = 23,
    OptionTimeout = 24, OptionStatus = 25, OptionReadback = 26,
    OtherOptionsChanged = 27, Activation = 28
};
struct BootDiagnostic { uint32_t words[16]; };
static_assert(sizeof(BootDiagnostic) == 64);

#define M749_BOOT_INLINE __attribute__((always_inline)) inline
M749_BOOT_INLINE volatile uint32_t* bootDiagnosticWords() {
    return reinterpret_cast<volatile uint32_t*>(BootDiagnosticAddress);
}
M749_BOOT_INLINE uint32_t bootDiagnosticChecksum(const volatile uint32_t* p) {
    uint32_t sum = BootDiagnosticMagic;
    for (unsigned i = 1; i < 15; ++i) {
        sum = ((sum << 5) | (sum >> 27)) ^ p[i];
    }
    return sum;
}
M749_BOOT_INLINE bool bootDiagnosticValid() {
    auto p = bootDiagnosticWords();
    return p[0] == BootDiagnosticMagic && p[1] == BootDiagnosticVersionSize &&
        p[15] == bootDiagnosticChecksum(p);
}
M749_BOOT_INLINE void finishBootDiagnostic(BootReason reason) {
    auto p = bootDiagnosticWords();
    p[2] = uint32_t(reason);
    p[15] = bootDiagnosticChecksum(p);
    asm volatile("dsb" ::: "memory");
    p[0] = BootDiagnosticMagic;
    asm volatile("dsb" ::: "memory");
}
M749_BOOT_INLINE void noteBootReason(BootReason reason) {
    auto p = bootDiagnosticWords();
    p[0] = 0;
    // Refresh controller observations after any option operation.
    p[6] = *reinterpret_cast<volatile uint16_t*>(0x1FFFC010) |
        uint32_t(*reinterpret_cast<volatile uint16_t*>(0x1FFFC000)) << 16;
    p[8] = *reinterpret_cast<volatile uint32_t*>(0x40023C0C);
    finishBootDiagnostic(reason);
}
M749_BOOT_INLINE void beginBootDiagnostic() {
    auto p = bootDiagnosticWords();
    uint32_t sequence = bootDiagnosticValid() ? p[4] + 1 : 1;
    p[0] = 0;
    p[1] = BootDiagnosticVersionSize;
    p[3] = *reinterpret_cast<volatile uint32_t*>(0x080FFFFC);
    p[4] = sequence;
    p[5] = *reinterpret_cast<volatile uint32_t*>(0xE0042000);
    p[7] = *reinterpret_cast<volatile uint32_t*>(0x40023CCC);
    p[9] = *reinterpret_cast<volatile uint32_t*>(0x08200000);
    p[10] = *reinterpret_cast<volatile uint32_t*>(0x20000000);
    for (unsigned i = 11; i < 15; ++i) { p[i] = 0; }
    noteBootReason(BootReason::Starting);
}
template<class Checks>
M749_BOOT_INLINE void noteBootChecks(const Checks& checks) {
    auto p = bootDiagnosticWords();
    p[0] = 0;
    p[11] = checks.software; p[12] = checks.calibration; p[13] = checks.boot;
    p[14] = checks.valid ? 3 : 1; // bit 0: computed; bit 1: valid
    finishBootDiagnostic(BootReason::ImageChecks);
}
#undef M749_BOOT_INLINE
}
