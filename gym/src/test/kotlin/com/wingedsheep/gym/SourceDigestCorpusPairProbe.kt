package com.wingedsheep.gym

import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.contract.legacySourceDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Test-only pair oracle for every TrainingObservation returned by the B1 harness. */
internal object SourceDigestCorpusPairProbe {
    @Volatile
    private var activeSession: Session? = null

    @Synchronized
    internal fun start(): Session {
        check(activeSession == null) { "Source digest pair probe is already active" }
        return Session().also { activeSession = it }
    }

    @Synchronized
    internal fun stop(session: Session): Snapshot {
        check(activeSession === session) { "Source digest pair probe session is not active" }
        activeSession = null
        return session.snapshot()
    }

    internal fun record(observation: TrainingObservation) {
        activeSession?.record(observation)
    }

    internal class Session internal constructor() {
        private val observations = AtomicLong()
        private val mismatches = AtomicLong()
        private val firstMismatch = AtomicReference<String?>()

        internal fun record(observation: TrainingObservation) {
            val expected = legacySourceDigest(observation)
            val actual = observation.stateDigest
            observations.incrementAndGet()
            if (actual != expected) {
                mismatches.incrementAndGet()
                firstMismatch.compareAndSet(
                    null,
                    "expected=$expected actual=$actual",
                )
            }
        }

        internal fun snapshot(): Snapshot = Snapshot(
            observations = observations.get(),
            mismatches = mismatches.get(),
            firstMismatch = firstMismatch.get(),
        )
    }

    internal data class Snapshot(
        val observations: Long,
        val mismatches: Long,
        val firstMismatch: String?,
    )
}
