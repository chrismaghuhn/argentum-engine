package com.wingedsheep.gym

import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.components.identity.EmblemActivatedAbilityComponent
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder

/**
 * Test-only coarse counters for the current emblem discovery expression.
 *
 * This deliberately records only collection traversal and descriptor counts. It does not time
 * production code, retain GameState instances, or change the legal-action result.
 */
internal object B1EmblemGrantScanProbe {
    private val activeSession = AtomicReference<Session?>()

    internal data class Snapshot(
        val enumerationInvocations: Long,
        val scanInvocations: Long,
        val entityEntriesVisited: Long,
        val descriptorCountTotal: Long,
        val scansWithDescriptors: Long,
        val maxDescriptorsPerScan: Long,
    )

    internal class Session internal constructor() {
        private val enumerationInvocations = LongAdder()
        private val scanInvocations = LongAdder()
        private val entityEntriesVisited = LongAdder()
        private val descriptorCountTotal = LongAdder()
        private val scansWithDescriptors = LongAdder()
        private val maxDescriptorsPerScan = AtomicLong()

        internal fun recordEnumerationInvocation() {
            enumerationInvocations.increment()
        }

        internal fun recordEntityMap(value: Any?) {
            val entities = value as? Map<*, *> ?: return
            val descriptors = entities.values.count { container ->
                (container as? ComponentContainer)?.get<EmblemActivatedAbilityComponent>() != null
            }.toLong()
            scanInvocations.increment()
            entityEntriesVisited.add(entities.size.toLong())
            descriptorCountTotal.add(descriptors)
            if (descriptors > 0L) scansWithDescriptors.increment()
            maxDescriptorsPerScan.accumulateAndGet(descriptors, ::maxOf)
        }

        internal fun snapshot(): Snapshot = Snapshot(
            enumerationInvocations = enumerationInvocations.sum(),
            scanInvocations = scanInvocations.sum(),
            entityEntriesVisited = entityEntriesVisited.sum(),
            descriptorCountTotal = descriptorCountTotal.sum(),
            scansWithDescriptors = scansWithDescriptors.sum(),
            maxDescriptorsPerScan = maxDescriptorsPerScan.get(),
        )

        internal fun reset() {
            check(activeSession.get() === this) { "Cannot reset an inactive emblem scan probe" }
            enumerationInvocations.reset()
            scanInvocations.reset()
            entityEntriesVisited.reset()
            descriptorCountTotal.reset()
            scansWithDescriptors.reset()
            maxDescriptorsPerScan.set(0L)
        }
    }

    internal fun start(): Session {
        val session = Session()
        check(activeSession.compareAndSet(null, session)) { "Emblem scan probe already active" }
        return session
    }

    internal fun stop(session: Session): Snapshot {
        check(activeSession.compareAndSet(session, null)) { "Emblem scan probe session mismatch" }
        return session.snapshot()
    }

    @JvmStatic
    fun recordEnumerationInvocation() {
        activeSession.get()?.recordEnumerationInvocation()
    }

    @JvmStatic
    fun recordEntityMap(value: Any?) {
        activeSession.get()?.recordEntityMap(value)
    }
}
