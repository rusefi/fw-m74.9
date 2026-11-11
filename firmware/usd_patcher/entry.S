.syntax unified
.cpu cortex-m4
.thumb
.section .entry,"ax",%progbits
.global inspect_entry, apply_entry
.type inspect_entry,%function
inspect_entry:
    b.w inspect_start
.type apply_entry,%function
apply_entry:
    b.w apply_start

.section .text.entry,"ax",%progbits
.thumb_func
inspect_start:
    movs r0, #0
    b common_entry
.thumb_func
apply_start:
    movs r0, #1
common_entry:
    mrs r1, ipsr
    cbnz r1, bad_context
    mrs r1, control
    tst r1, #3
    bne bad_context
    mov r1, sp
    tst r1, #7
    bne bad_context
    ldr r2, =0x2001e440
    cmp r1, r2
    blo bad_context
    ldr r2, =0x20020000
    cmp r1, r2
    bhi bad_context
    /* Preserve original stack and interrupt state for non-destructive returns. */
    push {r3-r11, lr}
    mrs r4, primask
    mrs r5, basepri
    ldr r6, =0xe000ed08
    ldr r7, [r6]
    mov r8, sp
    cpsid i
    ldr sp, =0x2001e400
    bl payload_main
    str r7, [r6]
    dsb
    isb
    mov sp, r8
    msr basepri, r5
    msr primask, r4
    pop {r3-r11, pc}
bad_context:
    movs r0, #0x10
    bx lr
.size inspect_entry, 4
.size apply_entry, 4

.section .text.fault,"ax",%progbits
.global fault_entry
.thumb_func
fault_entry:
    cpsid i
    ldr sp, =0x2001e400
    b payload_fault
