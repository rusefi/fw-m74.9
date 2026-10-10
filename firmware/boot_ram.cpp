// Prepare enough SRAM for rusEFI after installation through the OEM CAN loader.
// Before C startup, validate the image and change an erased RAM option to 384 KiB.
// Program from low SRAM, preserve other options, then reset through the loader.
#include "boot_activation.h"
#include "boot_diagnostic.h"

__attribute__((section(".boot_diagnostic"), used))
volatile m749::BootDiagnostic m749BootDiagnostic;

// The FPU has not been enabled yet. Keep this translation unit integer-only,
// including compiler-generated copies of the image-check result.
#pragma GCC target("general-regs-only")

namespace {
constexpr uint32_t Usd = 0x1FFFC000;
constexpr uint32_t Eopb = Usd + 0x10;
constexpr uint32_t Flash = 0x40023C00;
constexpr uint32_t ProgrammingToken = 0x4DF9123B;
constexpr uint32_t ReturnToken = 0xF9C74A52;

__attribute__((always_inline)) inline volatile uint32_t& word(uint32_t address) {
    return *reinterpret_cast<volatile uint32_t*>(address);
}

__attribute__((always_inline)) inline uint16_t halfword(uint32_t address) {
    return *reinterpret_cast<volatile uint16_t*>(address);
}

__attribute__((always_inline)) inline void heartbeat() {
    word(0x40003000) = 0xAAAA; // Reload an already running independent watchdog.
}

__attribute__((always_inline, noreturn)) inline void reset(uint32_t token) {
    word(0x20000000) = token;
    asm volatile("dsb" ::: "memory");
    word(0xE000ED0C) = 0x05FA0004; // AIRCR SYSRESETREQ
    asm volatile("dsb" ::: "memory");
    for (;;) { }
}

// Compare all other USD bytes before/after the one halfword write. This uses
// no backup buffer and never erases or restores the USD page.
__attribute__((always_inline)) inline uint32_t otherOptionsCrc() {
    uint32_t crc = 0xFFFFFFFF;
    for (uint32_t offset = 0; offset < 4096; offset++) {
        if (offset == 0x10 || offset == 0x11) {
            continue;
        }
        crc ^= uint32_t(*reinterpret_cast<volatile uint8_t*>(Usd + offset)) << 24;
        for (unsigned bit = 0; bit < 8; bit++) {
            crc = (crc << 1) ^ ((crc & 0x80000000) ? 0x04C11DB7 : 0);
        }
        heartbeat();
    }
    return crc;
}
}

// Copied into low SRAM before use. All helpers must inline: neither instruction
// fetches nor constants may depend on flash while USD programming is busy.
extern "C" __attribute__((section(".m749_option_ram"), noinline, noclone, noreturn))
void m749ProgramErasedRamOption(uint32_t savedOptionsCrc) {
    using m749::BootReason;
    if (halfword(Eopb) != 0xFFFF) {
        m749::noteBootReason(BootReason::RamOption); reset(ProgrammingToken);
    }
    if (halfword(Usd) != 0x5AA5) {
        m749::noteBootReason(BootReason::Protection); reset(ProgrammingToken);
    }
    if (word(Flash + 0xCC) & 8) {
        m749::noteBootReason(BootReason::Slib); reset(ProgrammingToken);
    }
    if (word(Flash + 0x0C) & 1) {
        m749::noteBootReason(BootReason::FlashBusy); reset(ProgrammingToken);
    }
    word(Flash + 4) = 0x45670123;
    word(Flash + 4) = 0xCDEF89AB;
    word(Flash + 8) = 0x45670123;
    word(Flash + 8) = 0xCDEF89AB;
    if ((word(Flash + 0x10) & 0x280) != 0x200) {
        word(Flash + 0x10) = 0x80;
        m749::noteBootReason(BootReason::OptionUnlock);
        reset(ProgrammingToken);
    }
    word(Flash + 0x0C) = 0x34; // Clear done/program/protection flags.
    word(Flash + 0x10) = 0x210; // USD unlocked + programming; never erase.
    // Artery SDK writes a byte through a halfword store. Hardware adds ~FA.
    *reinterpret_cast<volatile uint16_t*>(Eopb) = 0x00FA;
    uint32_t remaining = 1000000;
    while ((word(Flash + 0x0C) & 1) && --remaining) {
        heartbeat();
    }
    const uint32_t status = word(Flash + 0x0C);
    word(Flash + 0x10) = 0x80; // Disable programming and lock, including errors.
    if (!remaining) {
        m749::noteBootReason(BootReason::OptionTimeout); reset(ProgrammingToken);
    }
    if (status & 0x15) {
        m749::noteBootReason(BootReason::OptionStatus); reset(ProgrammingToken);
    }
    if (halfword(Eopb) != 0x05FA) {
        m749::noteBootReason(BootReason::OptionReadback); reset(ProgrammingToken);
    }
    if (otherOptionsCrc() != savedOptionsCrc) {
        m749::noteBootReason(BootReason::OtherOptionsChanged); reset(ProgrammingToken);
    }
    // Reload the option before any access to application RAM. The resident
    // loader consumes this token and re-enters the same validated application.
    m749::noteBootReason(BootReason::OptionReload);
    reset(ReturnToken);
}

extern "C" uint32_t __m749_option_load__, __m749_option_start__, __m749_option_end__;

extern "C" void m749PrepareRam() {
    using m749::BootReason;
    m749::beginBootDiagnostic();
    const uint16_t option = halfword(Eopb);
    const uint8_t data = option;
    const uint8_t complement = option >> 8;
    // Only the supported 4032 KiB AT32F435ZMT7 memory mapping is covered here.
    if (word(0xE0042000) != 0x70084540) {
        m749::noteBootReason(BootReason::WrongMcu);
        reset(ProgrammingToken);
    }
    if (uint8_t(data ^ complement) == 0xFF && (data & 7) <= 2) {
        m749::noteBootReason(BootReason::RamReady);
        return; // Already 384/448/512 KiB; leave every option byte untouched.
    }
    if (option != 0xFFFF) {
        m749::noteBootReason(BootReason::RamOption); reset(ProgrammingToken);
    }
    if (halfword(Usd) != 0x5AA5) {
        m749::noteBootReason(BootReason::Protection); reset(ProgrammingToken);
    }
    if (word(Flash + 0xCC) & 8) {
        m749::noteBootReason(BootReason::Slib); reset(ProgrammingToken);
    }
    const auto checks = m749::checkImages([](uint32_t address) {
        return *reinterpret_cast<const volatile uint8_t*>(address);
    }, heartbeat);
    const auto marker = word(m749::MarkerAddress);
    m749::noteBootChecks(checks);
    if (!checks.valid) { reset(ProgrammingToken); }
    if (marker != m749::NormalMarker &&
        (word(0x20000000) != ReturnToken ||
         (marker != m749::ProgrammingMarker && marker != 0xFFFFFFFF))) {
        m749::noteBootReason(BootReason::MarkerToken);
        reset(ProgrammingToken);
    }
    auto source = &__m749_option_load__;
    for (auto target = &__m749_option_start__; target < &__m749_option_end__;) {
        *target++ = *source++;
    }
    asm volatile("dsb\n isb" ::: "memory");
    m749ProgramErasedRamOption(otherOptionsCrc());
}
