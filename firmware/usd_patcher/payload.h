#pragma once
#include <stdint.h>

#define USD_BASE 0x1fffc000u
#define USD_BYTES 4096u
#define SNAPSHOT_ADDR 0x20019400u
#define INVERSE_ADDR 0x2001a400u
#define COMMAND_ADDR 0x2001b400u
#define REPORT_ADDR 0x2001b440u
#define VECTOR_ADDR 0x2001b600u
#define RETAINED_ADDR 0x20000040u
#define COMMAND_MAGIC 0x55445341u
#define REPORT_MAGIC 0x55445352u
#define ARM_VALUE 0xe7419b2cu
#define TARGET_OPTION 0x05fau
#define SUPPORTED_MCU 0x70084540u
#define SUPPORTED_BOOT_CRC 0xd7b6b894u
#define SUPPORTED_APP_CRC 0xb167c266u
#ifndef POLL_LIMIT
#define POLL_LIMIT 0x01000000u
#endif

/* All words are little endian. CRC-32/MPEG-2 over the first seven words. */
struct command {
    uint32_t magic, version, snapshot_crc, old_option;
    uint32_t target_option, arm, arm_inverse, crc;
};

enum status {
    INSPECTED = 0, APPLIED = 1, ALREADY_SET = 2,
    BAD_CONTEXT = 0x10, WRONG_MCU, WRONG_BOOT, PROTECTION, SLIB,
    BUSY, BAD_OPTION, BAD_RESERVED, BAD_COMMAND, SNAPSHOT_CHANGED,
    UNLOCK_FAILED, ERASE_FAILED, ERASE_VERIFY_FAILED, PROGRAM_FAILED,
    VERIFY_FAILED, FAULTED, WRONG_APPLICATION
};
enum stage { PREFLIGHT, SAVED, UNLOCKED, ERASING, RESTORING, VERIFYING, FINISHED };
struct report {
    uint32_t magic, version, status, stage, index, flash_status;
    uint32_t snapshot_crc, after_crc, old_option, target_option;
    uint32_t mcu, boot_crc, destructive, failures, application_crc, crc;
};
_Static_assert(sizeof(struct report) == 64, "report ABI");
_Static_assert(sizeof(struct command) == 32, "command ABI");
