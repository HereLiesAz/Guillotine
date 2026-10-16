package com.hereliesaz.guillotine.operation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OperationControllerTest {

    @After
    fun tearDown() {
        OperationController.cancel()
        waitIdle()
    }

    private fun waitIdle() {
        val deadline = System.currentTimeMillis() + 5_000
        while (OperationController.state.value != null && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    private fun start(
        pausable: Boolean,
        events: MutableList<String>,
        done: CountDownLatch,
        block: suspend (OperationController.Sink) -> Unit,
    ): Boolean = OperationController.startWith(
        startForeground = {},
        callbackDispatcher = Dispatchers.Unconfined,
        kind = OperationKind.EXPORT, label = "test", pausable = pausable,
        onError = { events += "error:${it.message}" },
        onCancel = { events += "cancel" },
        onFinished = { events += "finished"; done.countDown() },
        onComplete = { events += "complete" },
        block = block,
    )

    @Test
    fun cancelNotifiesOnCancelThenOnFinished() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        val running = CompletableDeferred<Unit>()
        assertTrue(start(pausable = false, events, done) { running.complete(Unit); awaitCancellation() })
        kotlinx.coroutines.runBlocking { running.await() }
        OperationController.cancel()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("cancel", "finished"), events.toList())
        waitIdle()
        assertNull(OperationController.state.value)
    }

    @Test
    fun checkpointCancellationAlsoReportsCancel() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        assertTrue(start(pausable = true, events, done) { sink ->
            OperationController.cancel()
            sink.checkpointBlocking()
        })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("cancel", "finished"), events.toList())
    }

    @Test
    fun completionAndErrorPaths() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        var done = CountDownLatch(1)
        assertTrue(start(pausable = false, events, done) { })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        waitIdle()
        done = CountDownLatch(1)
        assertTrue(start(pausable = false, events, done) { error("boom") })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("complete", "finished", "error:boom", "finished"), events.toList())
    }

    @Test
    fun pauseIgnoredForNonPausableOperation() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()
        val passed = CompletableDeferred<Unit>()
        assertTrue(start(pausable = false, events, done) { sink ->
            gate.await()
            sink.checkpoint() // would park forever if pause() had set the global flag
            passed.complete(Unit)
        })
        OperationController.pause()
        assertFalse(OperationController.state.value!!.paused)
        gate.complete(Unit)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(passed.isCompleted)
    }

    @Test
    fun secondStartRejectedWhileBusy() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        assertTrue(start(pausable = false, events, done) { awaitCancellation() })
        assertFalse(start(pausable = false, mutableListOf(), CountDownLatch(1)) { })
    }
}
