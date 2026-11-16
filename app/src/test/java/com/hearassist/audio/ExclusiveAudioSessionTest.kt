package com.hearassist.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests of structured child-job lifetime, ownership and mutex ordering.
 * NonCancellable + virtual delay models an in-flight inference that temporarily
 * ignores cancellation. This is NOT a real JNI/native-thread timing verification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExclusiveAudioSessionTest {

    @Test
    fun cancellationWaitsForInFlightChildBeforeClosingAndAdmittingTheNextSession() =
        runTest(StandardTestDispatcher()) {
            val gate = ExclusiveAudioSession()
            val events = mutableListOf<String>()
            var inferenceInFlight = false
            var nextFactoryCalls = 0
            lateinit var inference: Job
            val firstResources = TrackingResources("first", events) {
                assertFalse("Cannot close while fake inference is in flight", inferenceInFlight)
                assertTrue("The structured child must finish before close", inference.isCompleted)
            }
            val nextResources = TrackingResources("next", events)

            val first = launch {
                gate.run(resources = {
                    events += "first.acquire"
                    firstResources
                }) {
                    // No explicit child join here: the gate must wait via coroutineScope.
                    inference = launch {
                        inferenceInFlight = true
                        try {
                            withContext(NonCancellable) {
                                events += "inference.start"
                                delay(1_000)
                                events += "inference.return"
                            }
                        } finally {
                            inferenceInFlight = false
                            events += "inference.finished"
                        }
                    }
                    awaitCancellation()
                }
            }
            runCurrent()
            assertTrue(inferenceInFlight)
            assertEquals(listOf("first.acquire", "inference.start"), events)

            val next = launch {
                events += "next.wait"
                gate.run(resources = {
                    nextFactoryCalls++
                    assertTrue("Next factory cannot run until the first close returns", firstResources.isClosed)
                    assertEquals(1, firstResources.closeCount)
                    events += "next.acquire"
                    nextResources
                }) {
                    events += "next.block"
                }
            }
            runCurrent()
            assertEquals(0, nextFactoryCalls)

            first.cancel()
            runCurrent()

            assertTrue(first.isCancelled)
            assertTrue(inference.isCancelled)
            assertFalse(first.isCompleted)
            assertFalse(inference.isCompleted)
            assertTrue(inferenceInFlight)
            assertEquals(0, firstResources.closeCount)
            assertEquals(0, nextFactoryCalls)
            assertFalse(next.isCompleted)

            advanceTimeBy(999)
            runCurrent()
            assertTrue(inferenceInFlight)
            assertFalse(first.isCompleted)
            assertEquals(0, firstResources.closeCount)
            assertEquals(0, nextResources.closeCount)
            assertEquals(0, nextFactoryCalls)
            assertEquals(listOf("first.acquire", "inference.start", "next.wait"), events)

            // advanceTimeBy excludes work at the target time; runCurrent executes it.
            advanceTimeBy(1)
            runCurrent()
            first.join()
            next.join()

            assertTrue(first.isCompleted)
            assertTrue(first.isCancelled)
            assertTrue(inference.isCompleted)
            assertFalse(inferenceInFlight)
            assertTrue(next.isCompleted)
            assertFalse(next.isCancelled)
            assertTrue(firstResources.isClosed)
            assertTrue(nextResources.isClosed)
            assertEquals(1, firstResources.closeCount)
            assertEquals(1, nextResources.closeCount)
            assertEquals(1, nextFactoryCalls)
            assertEquals(
                listOf(
                    "first.acquire", "inference.start", "next.wait",
                    "inference.return", "inference.finished",
                    "first.close.begin", "first.close.end",
                    "next.acquire", "next.block", "next.close.begin", "next.close.end",
                ),
                events,
            )
        }

    @Test
    fun successfulRunClosesExactlyOnceAndKeepsTheLockUntilCloseReturns() =
        runTest(StandardTestDispatcher()) {
            val gate = ExclusiveAudioSession()
            val events = mutableListOf<String>()
            var nextFactoryCalls = 0
            lateinit var next: Deferred<String>
            lateinit var firstResources: TrackingResources
            val nextResources = TrackingResources("next", events)
            firstResources = TrackingResources("first", events) {
                assertFalse(firstResources.isClosed)
                // Attempt lock acquisition inside synchronous close(), before it returns.
                next = async(start = CoroutineStart.UNDISPATCHED) {
                    events += "next.wait"
                    gate.run(resources = {
                        nextFactoryCalls++
                        assertTrue(firstResources.isClosed)
                        events += "next.acquire"
                        nextResources
                    }) {
                        events += "next.block"
                        "next-result"
                    }
                }
                assertEquals("Lock must still be held during close", 0, nextFactoryCalls)
                assertFalse(next.isCompleted)
            }

            val result = gate.run(resources = {
                events += "first.acquire"
                firstResources
            }) { owned ->
                assertSame(firstResources, owned)
                events += "first.block"
                "first-result"
            }

            assertEquals("first-result", result)
            assertTrue(firstResources.isClosed)
            assertEquals(1, firstResources.closeCount)
            assertEquals("next-result", next.await())
            assertTrue(nextResources.isClosed)
            assertEquals(1, nextResources.closeCount)
            assertEquals(1, nextFactoryCalls)
            assertEquals(
                listOf(
                    "first.acquire", "first.block", "first.close.begin", "next.wait",
                    "first.close.end", "next.acquire", "next.block",
                    "next.close.begin", "next.close.end",
                ),
                events,
            )
        }

    @Test
    fun resourceFactoryFailureSkipsBlockAndReleasesLockWithFactoryOwnedCleanup() =
        runTest(StandardTestDispatcher()) {
            val gate = ExclusiveAudioSession()
            val events = mutableListOf<String>()
            val partialResources = mutableListOf<TrackingResources>()
            val expected = IllegalStateException("Resource factory loading failed")
            var blockCalled = false
            val failingFactory: () -> TrackingResources = {
                events += "factory.acquire"
                val partial = TrackingResources("partial", events)
                partialResources += partial
                // The gate owns nothing until resources() returns. The factory must
                // release partial allocations itself if construction/loading throws.
                try {
                    partial.load(expected)
                    partial
                } catch (failure: Throwable) {
                    partial.close()
                    throw failure
                }
            }

            assertFailsWithSame(expected) {
                gate.run(resources = failingFactory) {
                    blockCalled = true
                }
            }

            assertFalse(blockCalled)
            assertEquals(1, partialResources.size)
            val partial = partialResources.single()
            assertTrue(partial.isClosed)
            assertEquals(1, partial.closeCount)

            val nextResources = TrackingResources("next", events)
            val result = gate.run(resources = {
                assertTrue(partial.isClosed)
                events += "next.acquire"
                nextResources
            }) {
                events += "next.block"
                "recovered"
            }

            assertEquals("recovered", result)
            assertTrue(nextResources.isClosed)
            assertEquals(1, partial.closeCount)
            assertEquals(1, nextResources.closeCount)
            assertEquals(
                listOf(
                    "factory.acquire", "partial.load", "partial.close.begin", "partial.close.end",
                    "next.acquire", "next.block", "next.close.begin", "next.close.end",
                ),
                events,
            )
        }

    @Test
    fun ownedResourceLoadingAndBlockFailuresWaitForChildrenThenReleaseExactlyOnce() =
        runTest(StandardTestDispatcher()) {
            val gate = ExclusiveAudioSession()

            for (stage in FailureStage.values()) {
                val events = mutableListOf<String>()
                val expected = IllegalStateException("Failure during $stage")
                val failNow = CompletableDeferred<Unit>()
                var inferenceInFlight = false
                var nextFactoryCalls = 0
                lateinit var inference: Job
                val failedResources = TrackingResources("failed", events) {
                    assertFalse(inferenceInFlight)
                    assertTrue(inference.isCompleted)
                }
                val nextResources = TrackingResources("next", events)
                // Catch inside async so the intentional failure cannot cancel runTest.
                val failure = async {
                    try {
                        gate.run(resources = {
                            events += "failed.acquire"
                            failedResources
                        }) { owned ->
                            inference = launch {
                                inferenceInFlight = true
                                try {
                                    withContext(NonCancellable) {
                                        events += "inference.start"
                                        delay(100)
                                        events += "inference.return"
                                    }
                                } finally {
                                    inferenceInFlight = false
                                    events += "inference.finished"
                                }
                            }
                            failNow.await()
                            // Like production: return an owner from the factory, then
                            // load its resources in the block, under the gate's finally.
                            owned.load(if (stage == FailureStage.RESOURCE_LOADING) expected else null)
                            events += "block.failure"
                            throw expected
                        }
                    } catch (actual: Throwable) {
                        actual
                    }
                }
                runCurrent()
                assertTrue(inferenceInFlight)
                failNow.complete(Unit)
                runCurrent()

                assertTrue(inference.isCancelled)
                assertFalse(inference.isCompleted)
                assertFalse(failure.isCompleted)
                assertEquals(0, failedResources.closeCount)

                val next = async {
                    events += "next.wait"
                    gate.run(resources = {
                        nextFactoryCalls++
                        assertTrue(failedResources.isClosed)
                        events += "next.acquire"
                        nextResources
                    }) {
                        events += "next.block"
                        "recovered"
                    }
                }
                runCurrent()
                advanceTimeBy(99)
                runCurrent()

                assertTrue(inferenceInFlight)
                assertFalse(failure.isCompleted)
                assertFalse(next.isCompleted)
                assertEquals(0, failedResources.closeCount)
                assertEquals(0, nextFactoryCalls)
                val beforeInferenceReturns = mutableListOf("failed.acquire", "inference.start", "failed.load")
                if (stage == FailureStage.BLOCK) beforeInferenceReturns += "block.failure"
                beforeInferenceReturns += "next.wait"
                assertEquals(beforeInferenceReturns, events)

                advanceTimeBy(1)
                runCurrent()

                assertSame(expected, failure.await())
                assertEquals("recovered", next.await())
                assertTrue(inference.isCompleted)
                assertFalse(inferenceInFlight)
                assertTrue(failedResources.isClosed)
                assertTrue(nextResources.isClosed)
                assertEquals(1, failedResources.closeCount)
                assertEquals(1, nextResources.closeCount)
                assertEquals(1, nextFactoryCalls)
                assertEquals(
                    beforeInferenceReturns + listOf(
                        "inference.return", "inference.finished", "failed.close.begin", "failed.close.end",
                        "next.acquire", "next.block", "next.close.begin", "next.close.end",
                    ),
                    events,
                )
            }
        }

    private enum class FailureStage { RESOURCE_LOADING, BLOCK }

    private class TrackingResources(
        private val name: String,
        private val events: MutableList<String>,
        private val beforeClose: () -> Unit = {},
    ) : AutoCloseable {
        var closeCount = 0
            private set
        var isClosed = false
            private set

        fun load(failure: Throwable? = null) {
            check(!isClosed)
            events += "$name.load"
            if (failure != null) throw failure
        }

        override fun close() {
            // Deliberately non-idempotent counting catches every duplicate close call.
            closeCount++
            events += "$name.close.begin"
            beforeClose()
            isClosed = true
            events += "$name.close.end"
        }
    }

    private suspend fun <T> assertFailsWithSame(expected: Throwable, action: suspend () -> T) {
        val actual = try {
            action()
            null
        } catch (failure: Throwable) {
            failure
        }
        assertSame("The original failure must propagate", expected, actual)
    }
}
