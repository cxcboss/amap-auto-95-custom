// Bundled GFrame 9.5.0.600013 / arm64 only. The original prologue has already
// saved x19-x26 and the return address. Replace only its transition log block.
// Build one alpha animation in the engine's own named enter/exit map, then
// resume the original transition, visibility and lifecycle code.
.macro PAGE_FADE outgoing, map_offset, resume, info
    ldr w8, [x19, #0x4c8]
.if \outgoing
    cmp w8, #2
    b.lt \resume
.else
    cmp w8, #2
    b.eq \resume
    cmp w8, #4
    b.gt \resume
.endif
    ldr x8, [x19, #0x3b0]
    cbz x8, \resume
    ldr x23, [x8, #0x5c0]
    ldrb w8, [x23, #0x14]
    cbnz w8, \resume

    // Use the original 80-byte scratch area; keep SP and unwind metadata intact.
    // The converter reads this constant record and copies its values.
    adr x9, \info
    add x10, x9, #40
    stp x9, x10, [sp]
    str x10, [sp, #16]
    mov x0, sp
    mov w1, #1
    mov w2, #0
    // STGAnimateDescription is a non-trivial 32-byte return, passed in x8.
    add x8, sp, #24
    bl gframe_animator_description

    mov x0, x23
    bl gframe_ensure_appearance
    ldr x0, [x23, #0x40]
    mov x1, x20
    add x2, sp, #24
    add x3, x0, #\map_offset
    bl gframe_set_named_animation

    // Same vector cleanup used by CGFragment::setStart/EndAnimations.
    add x8, sp, #32
    str x8, [sp, #56]
    add x0, sp, #56
    bl gframe_description_vector_cleanup
    b \resume

    // STGAnimatorBaseInfo_: property, from, to, duration, order, index,
    // interpolator, padding, nullable RefCountPtr (40 bytes).
    // Property 0 maps to MatrixData alpha (0), clamped to [0, 1] by the engine.
    .balign 8
\info:
.if \outgoing
    .word 0, 0x3f800000, 0, 120
.else
    .word 0, 0, 0x3f800000, 120
.endif
    .word 0, 0, 0, 0
    .xword 0
.endm

// Legacy resource animations use the same description, but only when the
// caller supplied a resource ID. ID -1 keeps the original clear/no-animation
// path. The original function owns the descriptor and performs its cleanup.
.macro RESOURCE_FADE info, resume
    adr x9, \info
    add x10, x9, #40
    stp x9, x10, [sp]
    str x10, [sp, #16]
    mov x0, sp
    mov w1, #1
    mov w2, #0
    add x8, sp, #48
    bl gframe_animator_description
    // Restore the original empty name, temporarily used for the vector view.
    stp xzr, xzr, [sp, #16]
    str xzr, [sp, #32]
    b \resume
.endm

.section .text.fade_enter,"ax",%progbits
.global gframe_fade_enter
gframe_fade_enter:
    PAGE_FADE 0, 0x1d0, gframe_enter_resume, gframe_fade_enter_info
.global gframe_inflate_enter_override
gframe_inflate_enter_override:
    RESOURCE_FADE gframe_fade_enter_info, gframe_inflate_enter_resume

.section .text.fade_exit,"ax",%progbits
.global gframe_fade_exit
gframe_fade_exit:
    PAGE_FADE 1, 0x1e8, gframe_exit_resume, gframe_fade_exit_info
.global gframe_inflate_exit_override
gframe_inflate_exit_override:
    RESOURCE_FADE gframe_fade_exit_info, gframe_inflate_exit_resume

.section .text.inflate_enter_branch,"ax",%progbits
    b.ne gframe_inflate_enter_override
.section .text.inflate_exit_branch,"ax",%progbits
    b.ne gframe_inflate_exit_override
