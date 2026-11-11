#include "payload.h"

#define W(a) (*(volatile uint32_t *)(uintptr_t)(a))
#define H(a) (*(volatile uint16_t *)(uintptr_t)(a))
#define FLASH 0x40023c00u
#define STS (FLASH + 0x0cu)
#define CTRL (FLASH + 0x10u)
#define R ((volatile struct report *)REPORT_ADDR)
#define BACKUP ((volatile uint16_t *)SNAPSHOT_ADDR)
#define INVERSE ((volatile uint16_t *)INVERSE_ADDR)

extern void fault_entry(void);
static inline void barriers(void) { __asm volatile("dsb\n isb" ::: "memory"); }
static inline void feed(void) { W(0x40003000u) = 0xaaaau; }

static uint32_t crc_bytes(uint32_t crc, uintptr_t address, unsigned size) {
    volatile const uint8_t *p = (volatile const uint8_t *)address;
    while (size--) {
        crc ^= (uint32_t)*p++ << 24;
        for (unsigned b = 0; b < 8; ++b)
            crc = (crc << 1) ^ ((crc & 0x80000000u) ? 0x04c11db7u : 0);
        feed();
    }
    return crc;
}

static uint32_t complete(uint32_t code) {
    R->status = code;
    R->crc = crc_bytes(0xffffffffu, REPORT_ADDR, 60);
    for (unsigned i = 0; i < 16; ++i)
        W(RETAINED_ADDR + i * 4) = W(REPORT_ADDR + i * 4);
    barriers();
    return code;
}

__attribute__((noreturn, noinline)) static void park(uint32_t code) {
    /* Never reset or return to OEM code after an incomplete erase/restore. */
    complete(code);
    for (;;) feed();
}

__attribute__((noreturn)) void payload_fault(void) {
    /* Do not touch flash control: an interrupted operation may still be busy. */
    park(FAULTED);
}

static int wait_done(void) {
    uint32_t remaining = POLL_LIMIT;
    while (W(STS) & 1u) {
        feed();
        if (--remaining == 0) {
            R->flash_status = W(STS);
            return 0;
        }
    }
    R->flash_status = W(STS);
    return (R->flash_status & 0x35u) == 0x20u;
}

static int encoding_ok(uint16_t value) {
    return value == 0xffffu || (((value >> 8) ^ value) & 0xffu) == 0xffu;
}
static int reserved(unsigned offset) {
    return offset == 0x12 || (offset >= 0x1c && offset < 0x34) ||
           (offset >= 0x44 && offset < 0x4c);
}
static int snapshot_intact(void) {
    for (unsigned i = 0; i < USD_BYTES / 2; ++i)
        if ((uint16_t)(BACKUP[i] ^ INVERSE[i]) != 0xffffu) return 0;
    return crc_bytes(0xffffffffu, SNAPSHOT_ADDR, USD_BYTES) == R->snapshot_crc;
}

uint32_t payload_main(uint32_t apply) {
    for (unsigned i = 0; i < 16; ++i) W(REPORT_ADDR + i * 4) = 0;
    R->magic = REPORT_MAGIC;
    R->version = 1;
    R->stage = PREFLIGHT;
    R->target_option = TARGET_OPTION;
    for (unsigned i = 0; i < 64; ++i) W(VECTOR_ADDR + i * 4) = (uintptr_t)fault_entry;
    W(VECTOR_ADDR) = 0x2001e400u;
    W(0xe000ed08u) = VECTOR_ADDR;
    barriers();

    R->mcu = W(0xe0042000u);
    if (R->mcu != SUPPORTED_MCU) return complete(WRONG_MCU);
    /* Boot-domain identity also rejects unreadable all-FF protected/debug data. */
    R->boot_crc = crc_bytes(0xffffffffu, 0x08000000u, 0x1000);
    R->boot_crc = crc_bytes(R->boot_crc, 0x08201000u, 0x2cffc);
    if (R->boot_crc != SUPPORTED_BOOT_CRC || W(0x0822dffcu) != R->boot_crc)
        return complete(WRONG_BOOT);
    /* The scratch layout is qualified for the exact I862 OEM application only. */
    R->application_crc = crc_bytes(0xffffffffu, 0x08001000u, 0x5f000);
    R->application_crc = crc_bytes(R->application_crc, 0x08080000u, 0x7fffc);
    if (R->application_crc != SUPPORTED_APP_CRC || W(0x080ffffcu) != R->application_crc ||
        W(0x0808005cu) != 0x20019320u) return complete(WRONG_APPLICATION);
    if ((W(FLASH + 0x1cu) & 3u) != 2u || H(USD_BASE) != 0xffffu ||
        (W(0xe000edf0u) & 1u)) return complete(PROTECTION);
    if (W(FLASH + 0xccu) & 8u) return complete(SLIB);
    if ((W(STS) | W(FLASH + 0x4cu)) & 1u) return complete(BUSY);
    R->old_option = H(USD_BASE + 0x10);
    if (R->old_option != 0x00ffu && R->old_option != TARGET_OPTION)
        return complete(BAD_OPTION);

    for (unsigned i = 0; i < USD_BYTES / 2; ++i) {
        uint16_t v = H(USD_BASE + i * 2);
        R->index = i * 2;
        /* Table 5-5 assigns no complement encoding to wholly reserved slots. */
        if (!reserved(i * 2) && !encoding_ok(v)) return complete(BAD_OPTION);
        BACKUP[i] = v;
        INVERSE[i] = (uint16_t)~v;
        feed();
    }
    R->snapshot_crc = crc_bytes(0xffffffffu, SNAPSHOT_ADDR, USD_BYTES);
    if (!snapshot_intact() || crc_bytes(0xffffffffu, USD_BASE, USD_BYTES) != R->snapshot_crc)
        return complete(SNAPSHOT_CHANGED);
    R->stage = SAVED;
    if (!apply) return complete(INSPECTED);

    volatile const struct command *c = (volatile const struct command *)COMMAND_ADDR;
    if (c->magic != COMMAND_MAGIC || c->version != 1 || c->old_option != R->old_option ||
        c->snapshot_crc != R->snapshot_crc || c->target_option != TARGET_OPTION ||
        c->arm != ARM_VALUE || c->arm_inverse != (uint32_t)~ARM_VALUE ||
        c->crc != crc_bytes(0xffffffffu, COMMAND_ADDR, 28)) return complete(BAD_COMMAND);
    if (R->old_option == TARGET_OPTION) return complete(ALREADY_SET);
    if (!snapshot_intact() || crc_bytes(0xffffffffu, USD_BASE, USD_BYTES) != R->snapshot_crc)
        return complete(SNAPSHOT_CHANGED);

    /* Refuse an active/stale mode; do not interrupt another flash operation. */
    if (((W(CTRL) | W(FLASH + 0x50u)) & 0x7fu) ||
        ((W(STS) | W(FLASH + 0x4cu)) & 1u)) return complete(BUSY);
    if (W(CTRL) & 0x80u) {
        W(FLASH + 4) = 0x45670123u;
        W(FLASH + 4) = 0xcdef89abu;
    }
    W(FLASH + 8) = 0x45670123u;
    W(FLASH + 8) = 0xcdef89abu;
    if ((W(CTRL) & 0x280u) != 0x200u) {
        W(CTRL) = 0x80u;
        return complete(UNLOCK_FAILED);
    }
    R->stage = UNLOCKED;
    W(STS) = 0x34u;
    R->destructive = 1;
    R->stage = ERASING;
    complete(ERASE_FAILED); /* Persistent in-progress status before erase starts. */
    W(CTRL) = 0x220u;
    W(CTRL) = 0x260u;
    if (!wait_done()) {
        if (!(W(STS) & 1u)) W(CTRL) = 0x80u;
        park(ERASE_FAILED);
    }
    W(CTRL) = 0x200u;
    for (unsigned i = 0; i < USD_BYTES / 2; ++i) {
        R->index = i * 2;
        /* Reserved readback need not have the normal erased representation.
         * Preserve its snapshot verbatim and require exact final verification. */
        if (!reserved(i * 2) && H(USD_BASE + i * 2) != 0xffffu) {
            W(CTRL) = 0x80u;
            park(ERASE_VERIFY_FAILED);
        }
        feed();
    }
    R->stage = RESTORING;
    /* FAP (offset 0) is never programmed. Erased FFFF remains protected. */
    for (unsigned i = 1; i < USD_BYTES / 2; ++i) {
        R->index = i * 2;
        if ((uint16_t)(BACKUP[i] ^ INVERSE[i]) != 0xffffu) {
            W(CTRL) = 0x80u;
            park(SNAPSHOT_CHANGED);
        }
        uint16_t desired = (i == 8) ? TARGET_OPTION : BACKUP[i];
        int raw = reserved(i * 2);
        if (raw) {
            /* Some reserved fields may remain unchanged by erase. */
            if (H(USD_BASE + i * 2) == desired) continue;
        } else if (desired == 0xffffu) continue;
        W(STS) = 0x34u;
        W(CTRL) = 0x210u;
        /* Do not discard or synthesize bits of a reserved halfword. */
        H(USD_BASE + i * 2) = raw ? desired : (desired & 0xffu);
        if (!wait_done()) {
            if (W(STS) & 1u) park(PROGRAM_FAILED);
            ++R->failures; /* Still attempt remaining options when not busy. */
        }
        W(CTRL) = 0x200u;
        if (H(USD_BASE + i * 2) != desired) ++R->failures;
        feed();
    }
    W(CTRL) = 0x80u;
    R->stage = VERIFYING;
    if (!snapshot_intact()) park(SNAPSHOT_CHANGED);
    for (unsigned i = 0; i < USD_BYTES / 2; ++i) {
        R->index = i * 2;
        uint16_t desired = (i == 8) ? TARGET_OPTION : BACKUP[i];
        if (H(USD_BASE + i * 2) != desired) ++R->failures;
        feed();
    }
    R->after_crc = crc_bytes(0xffffffffu, USD_BASE, USD_BYTES);
    if (R->failures) park(VERIFY_FAILED);
    R->stage = FINISHED;
    complete(APPLIED);
    W(0x20000000u) = 0x4df9123bu; /* Stay in the resident loader for verification. */
    barriers();
    W(0xe000ed0cu) = 0x05fa0004u;
    barriers();
    for (;;) feed();
}
