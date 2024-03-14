package com.varabyte.kotter.foundation.timer

import com.varabyte.kotter.platform.concurrent.locks.*
import com.varabyte.kotter.runtime.*
import com.varabyte.kotter.runtime.concurrent.*
import kotlin.time.Duration

// Note: Class needs to be internal because TimerManager is internal
internal class TestTimerManager(lock: ReentrantReadWriteLock) : TimerManager(lock) {
    var currentTime: Long = 0
        private set

    fun fastForward(duration: Duration) {
        if (!duration.isPositive()) return

        currentTime += duration.inWholeMilliseconds
        triggerTimers()
    }

    override fun produceCurrentTime() = currentTime
}

/** Provides a simpler API to the internal [TestTimerManager] class. */
class TestTimer internal constructor(private val timerManager: TestTimerManager) {
    val currentTime get() = timerManager.currentTime

    fun fastForward(duration: Duration) {
        timerManager.fastForward(duration)
    }
}

/**
 * Create a fake timer that can control the time used by Kotter's [addTimer] functionality.
 *
 * This function MUST be declared in a run block and will fail if not set up before a real timer already ran once.
 *
 * Note that the section block will run immediately, so if it references any feature that uses timers under the hood
 * (like inputs, which have a blinking cursor, or animations), that can be a problem. Therefore, when using test timers,
 * it is generally recommended to follow a pattern where the section is early-aborted before the test timer is ready:
 *
 * ```
 * var testTimerReady by liveVarOf(false)
 * section {
 *   if (!testTimerReady) return@section
 *   // ... `section` code here ...
 * }.run {
 *   val timer = data.useTestTimer()
 *   testTimerReady = true
 *   // ... `run` code here ...
 * }
 * ```
 */
fun ConcurrentScopedData.useTestTimer(): TestTimer {
    require(isActive(RunScope.Lifecycle)) {
        "This method can only be called inside a `run` block."
    }

    val testTimerManager = TestTimerManager(lock)
    if (!tryPut(
            TimerManager.Key,
            provideInitialValue = { testTimerManager },
            dispose = { testTimerManager.dispose() })
    ) {
        error("Attempted to initialize this test with a test timer after a different timer was already created.")
    }

    return TestTimer(testTimerManager)
}
