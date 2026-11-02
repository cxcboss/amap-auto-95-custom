// Fixed 9.5 arm64 Luming capsule. Reuse the disabled road-speed color method.
// Keep its original 32-byte frame and saved x19/x20/x29/x30 for ABI/unwinding.
.macro FRAME_CALL high, low
    add x8, x20, #\high, lsl #12
    add x8, x8, #\low
    blr x8
.endm

.section .text.capsule_center,"ax",%progbits
.global capsule_center
capsule_center:
    stp x29, x30, [sp, #-32]!
    stp x20, x19, [sp, #16]
    mov x29, sp
    mov x19, x0

    // Preserve the initializer's original hide(speed, true).
    ldr x0, [x19, #0xaf8]
    mov w1, #1
    bl capsule_hide

    ldr x0, [x19, #0xb00]
    mov w1, #0x11
    bl capsule_text_gravity

    // The existing import resolves to CGTextView::setGravity in fixed GFrame.
    adrp x20, capsule_gravity_slot
    ldr x20, [x20, :lo12:capsule_gravity_slot]
    sub x20, x20, #0x3a7, lsl #12
    sub x20, x20, #0xf94

    // Native getParentNode handles the actual parent type. Center the remaining
    // flex children; text gravity handles both filling and wrapping road labels.
    ldr x0, [x19, #0xb00]
    FRAME_CALL 0x48c, 0xabc
    cbz x0, 1f
    mov w1, #1
    FRAME_CALL 0x6bd, 0xad8
1:
    ldp x20, x19, [sp, #16]
    ldp x29, x30, [sp], #32
    ret
