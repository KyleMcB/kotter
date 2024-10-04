package com.varabyte.kotterx.test.foundation

import com.varabyte.kotter.foundation.*
import com.varabyte.kotter.platform.concurrent.locks.*
import com.varabyte.kotter.runtime.*
import com.varabyte.kotter.runtime.terminal.*
import com.varabyte.kotter.runtime.terminal.mock.*

/**
 * A thread safe channel-like class which collects any exceptions thrown by a Kotter section block.
 */
internal class RenderExceptions {
    private val lock = ReentrantLock()
    private val exceptions = mutableListOf<Throwable>()

    fun send(exception: Throwable) {
        lock.withLock { exceptions.add(exception) }
    }

    fun withLock(peekList: (List<Throwable>) -> Unit) = lock.withLock { peekList(exceptions) }
}

/**
 * Like a [session] block but backed by a [MockTerminal], which is provides as a lambda argument.
 *
 * The [MockTerminal] is a useful way to assert that various Kotter commands resulted in expected behavior.
 *
 * @param suppressSectionExceptions If true (the default), then this function will assert that no render exceptions were
 *   thrown during the session.
 */
fun testSession(
    size: TerminalSize = TerminalSize.Default,
    suppressSectionExceptions: Boolean = false,
    block: Session.(MockTerminal) -> Unit
) {
    val terminal = MockTerminal(size)
    val renderExceptions = RenderExceptions()
    session(terminal, sectionExceptionHandler = { renderExceptions.send(it) }) {
        this.block(terminal)
    }

    if (!suppressSectionExceptions) {
        renderExceptions.withLock { exceptions ->
            check(exceptions.isEmpty()) {
                buildString {
                    append("${exceptions.size} exception(s) were thrown in this test's section block. Either fix the code, wrap the offending exception explicitly in a try/catch block, or pass `suppressSectionExceptions = true` to this test's `testSession`.\n\n")
                    exceptions.forEachIndexed { index, throwable ->
                        append("Exception #${index + 1}: $throwable\n\n")
                    }
                }
            }
        }
    }
}
