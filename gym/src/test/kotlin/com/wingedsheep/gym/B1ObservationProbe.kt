package com.wingedsheep.gym

import kotlinx.serialization.Serializable
import java.util.HashMap
import kotlin.jvm.JvmName

/**
 * Test-only scalar counters for the post-replay B1 ObservationBuilder characterization.
 *
 * The bytecode adapter enables calls into this object only for an explicitly requested
 * characterization run. The probe retains no GameState, TrainingObservation, LegalAction, JSON,
 * payment-domain, or card graph. Action correlation uses identity-hash integers in the current
 * build only; the run records whether distinct action-view indexes shared an identity hash.
 */
internal object B1ObservationProbe {
    private val activeSession = ThreadLocal<Session?>()

    @Serializable
    internal data class BuildSnapshot(
        val legalActionCandidates: Int,
        val actionViews: Int,
        val actionCallCounts: Map<String, Long>,
        val sameActionDuplicateCalls: Map<String, Long>,
        val actionsWithSameActionDuplicates: Map<String, Long>,
        val maxCallsForOneAction: Map<String, Long>,
    )

    @Serializable
    internal data class Snapshot(
        val observationBuilderCalls: Long,
        val gameEnvironmentLegalActions: Long,
        val legalActionEnumeratorCalls: Long,
        val legalActionCandidates: Long,
        val maxLegalActionCandidates: Int,
        val actionViewConstructions: Long,
        val decisionOptionViewConstructions: Long,
        val targetDomainDerivations: Long,
        val attackDomainDerivations: Long,
        val blockerDomainDerivations: Long,
        val paymentQualificationCalls: Long,
        val targetPaymentDomainCalls: Long,
        val paymentDomainV5Calls: Long,
        val paymentDomainBuilderV5Attempts: Long,
        val manaSourceDiscoveryCalls: Long,
        val manaSourceDiscoveryApiCalls: Long,
        val resolveActivatedAbilityCalls: Long,
        val stableAbilityKeyCalls: Long,
        val stableAbilityOrdinalCalls: Long,
        val structuralAbilitySignatureCalls: Long,
        val structuralAbilityJsonCalls: Long,
        val actionSemanticCalls: Long,
        val requiredPayloadFieldsCalls: Long,
        val paymentDomainRequestCalls: Long,
        val targetCostDependencyCalls: Long,
        val outOfBuildCalls: Map<String, Long>,
        val sameActionDuplicateCalls: Map<String, Long>,
        val actionsWithSameActionDuplicates: Map<String, Long>,
        val maxCallsForOneAction: Map<String, Long>,
        val identityHashCollisionDetected: Boolean,
        val largestBuild: BuildSnapshot?,
    )

    internal class Session internal constructor() {
        private var currentBuild: Build? = null

        var observationBuilderCalls: Long = 0
            private set
        var legalActionCandidates: Long = 0
            private set
        var maxLegalActionCandidates: Int = 0
            private set
        var actionViewConstructions: Long = 0
            private set
        var decisionOptionViewConstructions: Long = 0
            private set
        var identityHashCollisionDetected: Boolean = false
            private set

        private val totals = HashMap<String, Long>()
        private val outOfBuildCalls = HashMap<String, Long>()
        private val sameActionDuplicateCalls = HashMap<String, Long>()
        private val actionsWithSameActionDuplicates = HashMap<String, Long>()
        private val maxCallsForOneAction = HashMap<String, Long>()
        private var largestBuild: BuildSnapshot? = null

        fun beginBuild(candidateCount: Int) {
            finishBuild()
            observationBuilderCalls++
            legalActionCandidates += candidateCount.toLong()
            maxLegalActionCandidates = maxOf(maxLegalActionCandidates, candidateCount)
            currentBuild = Build(candidateCount)
        }

        fun endBuild() {
            finishBuild()
        }

        fun recordAction(family: String, action: Any?) {
            increment(family)
            val build = currentBuild
            if (build == null) {
                increment(outOfBuildCalls, family)
            } else {
                build.recordAction(family, action)
            }
        }

        fun recordActionView(index: Int, action: Any?) {
            increment("actionView")
            actionViewConstructions++
            val build = currentBuild
            if (build == null) {
                increment(outOfBuildCalls, "actionView")
            } else {
                build.recordActionView(index, action)
            }
        }

        fun recordDecisionOptionViews(count: Int) {
            increment("decisionOptionView")
            decisionOptionViewConstructions += count.toLong()
        }

        fun recordScalar(family: String) {
            increment(family)
            if (currentBuild == null) increment(outOfBuildCalls, family)
        }

        fun snapshot(): Snapshot {
            finishBuild()
            return Snapshot(
                observationBuilderCalls = observationBuilderCalls,
                gameEnvironmentLegalActions = totals["gameEnvironmentLegalActions"] ?: 0,
                legalActionEnumeratorCalls = totals["legalActionEnumerator"] ?: 0,
                legalActionCandidates = legalActionCandidates,
                maxLegalActionCandidates = maxLegalActionCandidates,
                actionViewConstructions = actionViewConstructions,
                decisionOptionViewConstructions = decisionOptionViewConstructions,
                targetDomainDerivations = totals["targetDomain"] ?: 0,
                attackDomainDerivations = totals["attackDomain"] ?: 0,
                blockerDomainDerivations = totals["blockerDomain"] ?: 0,
                paymentQualificationCalls = totals["paymentQualification"] ?: 0,
                targetPaymentDomainCalls = totals["targetPaymentDomain"] ?: 0,
                paymentDomainV5Calls = totals["paymentDomainV5"] ?: 0,
                paymentDomainBuilderV5Attempts = totals["paymentDomainBuilderV5"] ?: 0,
                manaSourceDiscoveryCalls = totals["manaSourceDiscovery"] ?: 0,
                manaSourceDiscoveryApiCalls = totals["manaSourceDiscoveryApi"] ?: 0,
                resolveActivatedAbilityCalls = totals["resolveActivatedAbility"] ?: 0,
                stableAbilityKeyCalls = totals["stableAbilityKey"] ?: 0,
                stableAbilityOrdinalCalls = totals["stableAbilityOrdinal"] ?: 0,
                structuralAbilitySignatureCalls = totals["structuralAbilitySignature"] ?: 0,
                structuralAbilityJsonCalls = totals["structuralAbilityJson"] ?: 0,
                actionSemanticCalls = totals["actionSemantic"] ?: 0,
                requiredPayloadFieldsCalls = totals["requiredPayloadFields"] ?: 0,
                paymentDomainRequestCalls = totals["paymentDomainRequest"] ?: 0,
                targetCostDependencyCalls = totals["targetCostDependency"] ?: 0,
                outOfBuildCalls = outOfBuildCalls.toSortedMap(),
                sameActionDuplicateCalls = sameActionDuplicateCalls.toSortedMap(),
                actionsWithSameActionDuplicates = actionsWithSameActionDuplicates.toSortedMap(),
                maxCallsForOneAction = maxCallsForOneAction.toSortedMap(),
                identityHashCollisionDetected = identityHashCollisionDetected,
                largestBuild = largestBuild,
            )
        }

        private fun finishBuild() {
            val build = currentBuild ?: return
            val completed = build.snapshot()
            completed.sameActionDuplicateCalls.forEach { (family, count) ->
                if (count > 0) increment(sameActionDuplicateCalls, family, count)
            }
            completed.actionsWithSameActionDuplicates.forEach { (family, count) ->
                if (count > 0) increment(actionsWithSameActionDuplicates, family, count)
            }
            completed.maxCallsForOneAction.forEach { (family, count) ->
                maxCallsForOneAction[family] = maxOf(maxCallsForOneAction[family] ?: 0, count)
            }
            if (largestBuild == null || completed.legalActionCandidates > largestBuild!!.legalActionCandidates) {
                largestBuild = completed
            }
            currentBuild = null
        }

        private fun increment(family: String) = increment(totals, family)

        private fun increment(target: MutableMap<String, Long>, family: String, amount: Long = 1) {
            target[family] = (target[family] ?: 0) + amount
        }

        private class Build(
            val legalActionCandidates: Int,
        ) {
            var actionViews: Int = 0
                private set
            private val callsByAction = HashMap<Int, HashMap<String, Long>>()
            private val actionViewIndexes = HashMap<Int, Int>()

            fun recordAction(family: String, action: Any?) {
                record(family, action)
            }

            fun recordActionView(index: Int, action: Any?) {
                actionViews++
                val identity = identityOf(action)
                val previousIndex = actionViewIndexes.putIfAbsent(identity, index)
                if (previousIndex != null && previousIndex != index) {
                    // The caller reports this as a measurement-quality issue; it is never used
                    // to alter a candidate, domain, or action decision.
                    B1ObservationProbe.activeSession.get()?.identityHashCollisionDetected = true
                }
                record("actionView", action)
            }

            fun snapshot(): BuildSnapshot {
                val actionCallCounts = HashMap<String, Long>()
                val duplicateCalls = HashMap<String, Long>()
                val maxCalls = HashMap<String, Long>()
                callsByAction.values.forEach { calls ->
                    calls.forEach { (family, count) ->
                        actionCallCounts[family] = (actionCallCounts[family] ?: 0) + count
                        duplicateCalls[family] = (duplicateCalls[family] ?: 0) + maxOf(0, count - 1)
                        maxCalls[family] = maxOf(maxCalls[family] ?: 0, count)
                    }
                }
                return BuildSnapshot(
                    legalActionCandidates = legalActionCandidates,
                    actionViews = actionViews,
                    actionCallCounts = actionCallCounts.toSortedMap(),
                    sameActionDuplicateCalls = duplicateCalls.toSortedMap(),
                    actionsWithSameActionDuplicates = callsByAction.values
                        .flatMap { calls ->
                            calls.filterValues { it > 1 }.keys
                        }
                        .groupingBy { it }
                        .eachCount()
                        .mapValues { (_, count) -> count.toLong() }
                        .toSortedMap(),
                    maxCallsForOneAction = maxCalls.toSortedMap(),
                )
            }

            private fun record(family: String, action: Any?) {
                val identity = identityOf(action)
                val calls = callsByAction.getOrPut(identity) { HashMap() }
                calls[family] = (calls[family] ?: 0) + 1
            }

            private fun identityOf(action: Any?): Int = System.identityHashCode(action)
        }
    }

    @JvmStatic
    @JvmName("start")
    internal fun start(): Session {
        check(activeSession.get() == null) { "B1 observation probe session is already active" }
        return Session().also(activeSession::set)
    }

    @JvmStatic
    @JvmName("stop")
    internal fun stop(session: Session): Snapshot {
        check(activeSession.get() === session) { "B1 observation probe session is not active" }
        val snapshot = session.snapshot()
        activeSession.remove()
        return snapshot
    }

    @JvmStatic
    @JvmName("beginBuild")
    internal fun beginBuild(candidateCount: Int) {
        activeSession.get()?.beginBuild(candidateCount)
    }

    @JvmStatic
    @JvmName("endBuild")
    internal fun endBuild() {
        activeSession.get()?.endBuild()
    }

    @JvmStatic
    @JvmName("recordAction")
    internal fun recordAction(family: String, action: Any?) {
        activeSession.get()?.recordAction(family, action)
    }

    @JvmStatic
    @JvmName("recordActionView")
    internal fun recordActionView(index: Int, action: Any?) {
        activeSession.get()?.recordActionView(index, action)
    }

    @JvmStatic
    @JvmName("recordDecisionOptionViews")
    internal fun recordDecisionOptionViews(count: Int) {
        activeSession.get()?.recordDecisionOptionViews(count)
    }

    @JvmStatic
    @JvmName("recordScalar")
    internal fun recordScalar(family: String) {
        activeSession.get()?.recordScalar(family)
    }
}
