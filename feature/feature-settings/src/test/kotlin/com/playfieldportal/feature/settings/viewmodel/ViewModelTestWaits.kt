package com.playfieldportal.feature.settings.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext

/**
 * The wait idiom the Display settings ViewModel tests share.
 *
 * These ViewModels launch on the test dispatcher while their side effects finish on REAL threads
 * (DataStore writes, `Dispatchers.IO` file work), so neither `advanceUntilIdle()` nor a plain
 * sleep is enough on its own. This alternates the two, and sleeps on an IO thread rather than the
 * scheduler thread — blocking that one would deadlock the very coroutines being waited on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.eventually(
    reason: String,
    timeoutMillis: Long = 10_000,
    condition: suspend () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) {
            throw AssertionError("condition not met within ${timeoutMillis}ms: $reason")
        }
        advanceUntilIdle()
        withContext(Dispatchers.IO) { Thread.sleep(25) }
    }
    advanceUntilIdle()
}

/**
 * Subscribes to [state] for the rest of the test and waits for its first real value. Call this
 * before touching anything the state derives from.
 *
 * Use this ONLY for a test that reads the state through `.value`, and be deliberate about it,
 * because a state update reaches a test by one of two mutually exclusive routes:
 *
 *  - **Notification** — a standing subscriber is handed DataStore's update. This function.
 *  - **Restart** — with no standing subscriber, every `uiState.first()` drops the subscription
 *    count to zero and back, so `WhileSubscribed` restarts the upstream and the store is re-read
 *    from scratch. A lost notification then costs nothing; the next poll picks the value up.
 *
 * Adding a subscriber to a test that polls with `first()` disables the restart route without
 * putting anything in its place, which is a regression, not a hardening.
 *
 * Either route can be dropped during a test class's first test, where Robolectric spends seconds
 * loading every class the ViewModel's combine touches while DataStore hands out updates through
 * a drop-oldest buffer. Only first tests have ever been seen to fail this way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun TestScope.observeUntilSettled(state: StateFlow<*>) {
    var settled = false
    backgroundScope.launch { state.collect { settled = true } }
    eventually("state pipeline settled") { settled }
}
