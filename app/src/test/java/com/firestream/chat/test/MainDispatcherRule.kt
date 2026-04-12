package com.firestream.chat.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit 4 rule that installs a [TestDispatcher] as `Dispatchers.Main` for the
 * duration of a test and resets it afterwards.
 *
 * Defaults to [UnconfinedTestDispatcher] — eager execution, no manual
 * `runCurrent()` needed. Pass a [kotlinx.coroutines.test.StandardTestDispatcher]
 * when the test needs fine-grained control over time (e.g. for verifying a
 * `delay(...)` window).
 *
 * Pattern from the Now in Android sample.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
