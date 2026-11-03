// 9.5.0.600013 arm64 GFragmentCruise::onPause, inlined footprint milestone check.
// Logged-in users already exit at milestone_login_branch. Check that predicate
// before RequestTaskSync fetches track statistics used only by the login prompt.
// Keep the original allocation cleanup, unlogged-in path and all pause work.
.section .text.milestone_entry,"ax",%progbits
    b milestone_guard

// Reuse only the FT end-log block. Both normal and timed-wait returns skip it.
.section .text.milestone_log_skip,"ax",%progbits
    b milestone_log_resume

.section .text.milestone_guard,"ax",%progbits
.global milestone_guard
milestone_guard:
    bl milestone_is_logged_in
    tbnz w0, #0, milestone_login_branch
    bl milestone_get_context
    b milestone_context_resume

.section .text.milestone_wait_log_skip,"ax",%progbits
    b milestone_log_resume
