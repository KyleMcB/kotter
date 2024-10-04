package com.varabyte.kotter.runtime

import com.varabyte.kotter.foundation.*
import com.varabyte.kotter.foundation.render.*
import com.varabyte.kotter.foundation.text.*
import com.varabyte.kotter.runtime.internal.ansi.Ansi.Csi.Codes
import com.varabyte.kotter.runtime.terminal.mock.*
import com.varabyte.kotterx.test.foundation.*
import com.varabyte.kotterx.test.terminal.*
import com.varabyte.truthish.assertThat
import com.varabyte.truthish.assertThrows
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test

class SectionTest {
    @Test
    fun `section always ends with a reset code and newline`() = testSession { terminal ->
        assertThat(terminal.buffer.toString()).isEmpty()
        section {}.run()

        assertThat(terminal.buffer.toString()).isEqualTo("${Codes.Sgr.RESET}\n")
    }

    @Test
    fun `if a section does not get run the session will throw an exception`() {
        assertThrows<IllegalStateException> {
            testSession {
                section {} // Whoops, forgot to add ".run()"! I hope I get warned about my mistake....
            }
        }

        assertThrows<IllegalStateException> {
            testSession {
                section {} // Whoops, forgot to add ".run()"! I hope I get warned about my mistake....
                section {}.run()
            }
        }
    }

    @Test
    fun `a section can be run only once`() = testSession {
        val s = section {
            textLine("Please don't try this at home")
        }

        s.run() // This first run consumes the section
        assertThrows<IllegalStateException> {
            s.run()
        }
    }

    @Test
    fun `exceptions in run blocks are thrown`() = testSession {
        assertThrows<RuntimeException> {
            section {}.run {
                throw RuntimeException("Exception in run")
            }
        }.also {
            assertThat(it.message).isEqualTo("Exception in run")
        }
    }

    @Test
    fun `cancellations in run blocks are ignored`() = testSession {
        var runWasCalled = false
        section {}.run {
            runWasCalled = true
            throw CancellationException("CancellationException in run")
        }

        assertThat(runWasCalled).isTrue()
    }

    @Test
    fun `exceptions in section blocks are swallowed`() = testSession(suppressSectionExceptions = true) {
        var sectionWasCalled = false
        section {
            sectionWasCalled = true
            throw RuntimeException("Exception in section")
        }.run()

        assertThat(sectionWasCalled).isTrue()
    }

    @Test
    fun `multiple sections all append to the same buffer`() = testSession { terminal ->
        assertThat(terminal.buffer.toString()).isEmpty()
        section {}.run()
        section {}.run()
        section {}.run()

        assertThat(terminal.buffer.toString()).isEqualTo(
            "${Codes.Sgr.RESET}\n".repeat(3)
        )
    }

    @Test
    fun `single line sections get repainted in place`() = testSession { terminal ->
        var count by liveVarOf(0)

        val renderedSignal = Channel<Unit>(capacity = 1)
        section {
            text(count.toString())
        }.onRendered {
            runBlocking { renderedSignal.send(Unit) }
        }.run {
            renderedSignal.receive()
            count = 1
            renderedSignal.receive()
            count = 2
            renderedSignal.receive()
        }

        assertThat(terminal.lines()).containsExactly(
            "0${Codes.Sgr.RESET}",
            "\r${Codes.Erase.CURSOR_TO_LINE_END}${Codes.Cursor.MOVE_TO_PREV_LINE}" // Clear the auto-appended newline
                    + "\r${Codes.Erase.CURSOR_TO_LINE_END}1${Codes.Sgr.RESET}",
            "\r${Codes.Erase.CURSOR_TO_LINE_END}${Codes.Cursor.MOVE_TO_PREV_LINE}" // Clear the auto-appended newline
                    + "\r${Codes.Erase.CURSOR_TO_LINE_END}2${Codes.Sgr.RESET}",
            "", // Newline added at the end of the section
        ).inOrder()

        // Also, make sure the resolved view looks right
        terminal.assertMatches {
            text("2")
        }
    }

    @Test
    fun `multiline sections get repainted in place`() = testSession { terminal ->
        var count by liveVarOf(1)

        val renderedSignal = Channel<Unit>(capacity = 1)

        section {
            textLine("Multiple lines")
            text("Run #$count")
        }.onRendered {
            runBlocking { renderedSignal.send(Unit) }
        }.run {
            renderedSignal.receive()
            count++
            renderedSignal.receive()
            count++
            renderedSignal.receive()
        }

        assertThat(terminal.lines()).containsExactly(
            "Multiple lines",
            "Run #1${Codes.Sgr.RESET}",
            "\r${Codes.Erase.CURSOR_TO_LINE_END}${Codes.Cursor.MOVE_TO_PREV_LINE}" // Clear the auto-appended newline
                    + "\r${Codes.Erase.CURSOR_TO_LINE_END}${Codes.Cursor.MOVE_TO_PREV_LINE}"
                    + "\r${Codes.Erase.CURSOR_TO_LINE_END}"
                    + "Multiple lines",
            "Run #2${Codes.Sgr.RESET}",
            "\r${Codes.Erase.CURSOR_TO_LINE_END}${Codes.Cursor.MOVE_TO_PREV_LINE}" // Clear the auto-appended newline
                    + "\r${Codes.Erase.CURSOR_TO_LINE_END}${Codes.Cursor.MOVE_TO_PREV_LINE}"
                    + "\r${Codes.Erase.CURSOR_TO_LINE_END}"
                    + "Multiple lines",
            "Run #3${Codes.Sgr.RESET}",
            "", // Newline added at the end of the section
        ).inOrder()

        // Also, make sure the resolved view looks right
        terminal.assertMatches {
            textLine("Multiple lines")
            text("Run #3")
        }
    }

    @Test
    fun `any extra asides always flush`() = testSession { terminal ->
        val renderedSignal = CompletableDeferred<Unit>()

        section {
            textLine()
            text("Section text")
        }.onRendered {
            renderedSignal.complete(Unit)
        }.run {
            renderedSignal.await()
            for (i in 1..5) {
                aside { text("Aside #$i") }
            }
        }

        // Thread timing can result in different intermediate buffer states. However, the final render, after all
        // rerenders are resolved, will be identical for all cases.
        assertThat(terminal.resolveRerenders()).containsExactly(
            "Aside #1${Codes.Sgr.RESET}",
            "Aside #2${Codes.Sgr.RESET}",
            "Aside #3${Codes.Sgr.RESET}",
            "Aside #4${Codes.Sgr.RESET}",
            "Aside #5${Codes.Sgr.RESET}",
            "",
            "Section text${Codes.Sgr.RESET}",
            "", // Newline added at the end of the section
        ).inOrder()
    }

    @Test
    fun `section is repainted if a LiveVar value changes`() = testSession { terminal ->
        var value by liveVarOf(0)
        section {
            text(value.toString())
        }.run {
            value = 42
        }

        // There's no harm in setting this outside of the section block, but it doesn't cause a rerender at this point.
        value = 123

        assertThat(terminal.lines()).containsExactly(
            "0${Codes.Sgr.RESET}",
            "\r${Codes.Erase.CURSOR_TO_LINE_END}${Codes.Cursor.MOVE_TO_PREV_LINE}" // Clear the auto-appended newline
                    + "\r${Codes.Erase.CURSOR_TO_LINE_END}"
                    + "42${Codes.Sgr.RESET}",
            "", // Newline added at the end of the section
        ).inOrder()
    }


    @Test
    fun `runUntilSignal exits after the signal is reached`() = testSession {
        section {}.runUntilSignal {
            signal()
        }
    }
}
