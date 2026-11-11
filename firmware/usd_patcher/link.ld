ENTRY(inspect_entry)
MEMORY { CODE (rx) : ORIGIN = 0x2001ba00, LENGTH = 0x1a00 }
SECTIONS {
    .text : { KEEP(*(.entry)) *(.text*) *(.rodata*) } > CODE
    .data : { *(.data*) } > CODE
    .bss (NOLOAD) : { *(.bss*) *(COMMON) } > CODE
    /DISCARD/ : { *(.ARM.exidx*) *(.ARM.extab*) *(.comment*) }
    ASSERT(SIZEOF(.data) == 0, "Payload must not require initialized globals")
    ASSERT(SIZEOF(.bss) == 0, "Payload must not require BSS initialization")
    ASSERT(apply_entry == 0x2001ba04, "Apply entry ABI changed")
}
