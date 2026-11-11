CROSS_COMPILE ?= arm-none-eabi-
CC := $(CROSS_COMPILE)gcc
OBJCOPY := $(CROSS_COMPILE)objcopy
SIZE := $(CROSS_COMPILE)size
BUILD ?= build
CFLAGS := -mcpu=cortex-m4 -mthumb -mfloat-abi=soft -Os -std=c11 -ffreestanding \
 -fno-builtin -fno-common -fno-unwind-tables -fno-asynchronous-unwind-tables \
 -ffunction-sections -fdata-sections -Wall -Wextra -Werror -g3 $(EXTRA_CFLAGS)
LDFLAGS := -nostdlib -Wl,--gc-sections,--build-id=none,-Map,$(BUILD)/usd_patcher.map -Tlink.ld

.PHONY: all
all: $(BUILD)/usd_patcher.bin

$(BUILD):
	mkdir -p $@
$(BUILD)/usd_patcher.elf: payload.c payload.h entry.S link.ld Makefile | $(BUILD)
	$(CC) $(CFLAGS) $(LDFLAGS) entry.S payload.c -o $@
	$(SIZE) $@
$(BUILD)/usd_patcher.bin: $(BUILD)/usd_patcher.elf
	$(OBJCOPY) -O binary $< $@

.PHONY: check-bundle
check-bundle: $(BUILD)/usd_patcher.bin
	cmp $(BUILD)/usd_patcher.bin ../../java-custom-ui/src/main/resources/usd_patcher_2001ba00.bin
