.syntax unified
.cpu cortex-m4
.thumb
.section .m749_startup,"ax",%progbits
.global Reset_Handler
.type Reset_Handler,%function
.thumb_func
Reset_Handler:
    cpsid i
    // Use only the top of the OEM's 128 KiB SRAM until the RAM option is ready.
    movs r0, #0
    msr control, r0
    isb
    ldr sp, =0x20020000
    ldr r0, =0x08001000
    ldr r1, =0xE000ED08
    str r0, [r1]
    dsb
    isb
    bl m749PrepareRam
    b _crt0_entry
.size Reset_Handler, .-Reset_Handler
