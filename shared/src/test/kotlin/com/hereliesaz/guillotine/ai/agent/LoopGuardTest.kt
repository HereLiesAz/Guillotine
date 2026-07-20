package com.hereliesaz.guillotine.ai.agent

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for the agent stall guard: it must catch genuine cycles but never fire on productive work. */
class LoopGuardTest {

    /** Feed a sequence of (name,args) calls (none erroring); return the first stop reason, or null. */
    private fun run(calls: List<Pair<String, String>>): String? {
        val guard = LoopGuard()
        for ((name, args) in calls) {
            guard.check(name, args, isError = false)?.let { return it }
        }
        return null
    }

    @Test fun trips_on_the_same_call_spun_on() {
        val stop = run(List(3) { "analyze_clip" to "{\"id\":1}" })
        assertNotNull("three identical calls in a row should trip", stop)
    }

    @Test fun trips_on_ab_ab_ab_oscillation() {
        val stop = run(
            listOf(
                "seek" to "{\"t\":1}", "look_at_frame" to "{}",
                "seek" to "{\"t\":1}", "look_at_frame" to "{}",
                "seek" to "{\"t\":1}", "look_at_frame" to "{}",
            ),
        )
        assertNotNull("a two-step cycle repeated three times should trip", stop)
    }

    @Test fun does_not_trip_on_read_interleaved_with_distinct_edits() {
        // get_timeline recurs, but each edit is distinct — productive interleaving, not a stall.
        val stop = run(
            listOf(
                "get_timeline" to "{}", "edit_clip" to "{\"id\":1}",
                "get_timeline" to "{}", "edit_clip" to "{\"id\":2}",
                "get_timeline" to "{}", "edit_clip" to "{\"id\":3}",
            ),
        )
        assertNull("a read-only call between distinct edits must not trip", stop)
    }

    @Test fun does_not_trip_on_varied_productive_calls() {
        val stop = run(
            listOf(
                "get_timeline" to "{}",
                "split_clip" to "{\"id\":1}",
                "delete_clip" to "{\"id\":2}",
                "add_text" to "{\"s\":\"hi\"}",
                "seek" to "{\"t\":5}",
                "trim_clip" to "{\"id\":3}",
            ),
        )
        assertNull("six distinct calls must not trip", stop)
    }

    @Test fun same_name_with_different_args_is_not_a_cycle() {
        // Same tool, genuinely different targets each time — not spinning.
        val stop = run(
            listOf(
                "analyze_clip" to "{\"id\":1}",
                "analyze_clip" to "{\"id\":2}",
                "analyze_clip" to "{\"id\":3}",
            ),
        )
        assertNull("same tool with distinct args is progress, not a cycle", stop)
    }

    @Test fun trips_after_four_consecutive_errors() {
        val guard = LoopGuard()
        var stop: String? = null
        // Distinct calls so only the error streak — not a cycle — can trip it.
        for (i in 1..4) {
            stop = guard.check("analyze_clip", "{\"id\":$i}", isError = true)
        }
        assertNotNull("four failing calls in a row should trip the error streak", stop)
    }

    @Test fun error_streak_resets_on_a_success() {
        val guard = LoopGuard()
        guard.check("a", "{\"id\":1}", isError = true)
        guard.check("b", "{\"id\":2}", isError = true)
        guard.check("c", "{\"id\":3}", isError = false) // resets the streak
        val stop = guard.check("d", "{\"id\":4}", isError = true)
        assertNull("a success in the middle should reset the error streak", stop)
    }
}
