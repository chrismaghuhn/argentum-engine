package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.DamageEdgeDirection
import com.wingedsheep.engine.core.ResolutionTargetKind

/**
 * Validates the semantic assignment plan represented by a combat-resolution
 * graph. This deliberately contains no damage-assignment-order concept: the
 * graph's ordinary blocker and attacker edges are freely splittable, while
 * trample is checked as its own aggregate requirement.
 */
internal object CombatDamageAssignmentPlanValidator {

    fun validate(
        decision: CombatResolutionDecision,
        amounts: Map<String, Int>,
        enforceTrample: Boolean = true,
    ): String? {
        val missingEdge = decision.edges.firstOrNull { it.id !in amounts }
        if (missingEdge != null) {
            return "Missing combat assignment for edge ${missingEdge.id}"
        }

        val attackersById = decision.attackers.associateBy { it.id }
        val blockersById = decision.blockers.associateBy { it.id }
        val defendersById = decision.defenders.associateBy { it.id }
        for (edge in decision.edges) {
            when (edge.direction) {
                DamageEdgeDirection.ATTACKER_TO_BLOCKER -> {
                    val attacker = attackersById[edge.sourceId]
                        ?: return "Edge ${edge.id}: source is not an attacker"
                    if (edge.targetId !in attacker.blockedByIds) {
                        return "Edge ${edge.id}: target is not blocking source ${edge.sourceId}"
                    }
                    if (edge.targetId !in blockersById) {
                        return "Edge ${edge.id}: target is not a blocker node"
                    }
                }

                DamageEdgeDirection.BLOCKER_TO_ATTACKER -> {
                    val blocker = blockersById[edge.sourceId]
                        ?: return "Edge ${edge.id}: source is not a blocker"
                    if (edge.targetId !in blocker.blockedAttackerIds) {
                        return "Edge ${edge.id}: target is not blocked by source ${edge.sourceId}"
                    }
                    if (edge.targetId !in attackersById) {
                        return "Edge ${edge.id}: target is not an attacker node"
                    }
                }

                DamageEdgeDirection.ATTACKER_TO_PLAYER,
                DamageEdgeDirection.ATTACKER_TO_PLANESWALKER,
                DamageEdgeDirection.ATTACKER_TO_BATTLE -> {
                    val attacker = attackersById[edge.sourceId]
                        ?: return "Edge ${edge.id}: source is not an attacker"
                    if (edge.targetId != attacker.attackedDefenderId) {
                        return "Edge ${edge.id}: target is not the attacked defender"
                    }
                    val defender = defendersById[edge.targetId]
                        ?: return "Edge ${edge.id}: target is not a defender node"
                    val expectedDirection = when (defender.kind) {
                        ResolutionTargetKind.PLAYER -> DamageEdgeDirection.ATTACKER_TO_PLAYER
                        ResolutionTargetKind.PLANESWALKER -> DamageEdgeDirection.ATTACKER_TO_PLANESWALKER
                        ResolutionTargetKind.BATTLE -> DamageEdgeDirection.ATTACKER_TO_BATTLE
                    }
                    if (edge.direction != expectedDirection) {
                        return "Edge ${edge.id}: direction does not match defender kind ${defender.kind}"
                    }
                }
            }
        }

        val edgesBySource = decision.edges.groupBy { it.sourceId }
        for ((sourceId, sourceEdges) in edgesBySource) {
            val maximums = sourceEdges.map { it.maximum }.toSet()
            if (maximums.size != 1) {
                return "Source $sourceId: combat assignment edges disagree on the source total"
            }
            val expected = maximums.single()
            val actual = sourceEdges.sumOf { amounts[it.id] ?: 0 }
            if (actual != expected) {
                return "Source $sourceId: combat assignment must total exactly $expected, got $actual"
            }
        }

        if (!enforceTrample) return null

        val damageToBlocker = decision.edges
            .asSequence()
            .filter { it.direction == DamageEdgeDirection.ATTACKER_TO_BLOCKER }
            .groupingBy { it.targetId }
            .fold(0) { total, edge -> total + (amounts[edge.id] ?: 0) }

        for (drain in decision.edges.filter { it.isTrampleDrain && (amounts[it.id] ?: 0) > 0 }) {
            val attacker = attackersById[drain.sourceId]
                ?: return "Trample drain ${drain.id}: attacker is missing from the assignment plan"
            if (!attacker.hasTrample) {
                return "Trample drain ${drain.id}: source does not have trample"
            }
            if (attacker.attackedDefenderId != drain.targetId) {
                return "Trample drain ${drain.id}: target is not the attacked defender"
            }
            val defender = defendersById[drain.targetId]
                ?: return "Trample drain ${drain.id}: defender is missing from the assignment plan"
            val expectedDirection = when (defender.kind) {
                com.wingedsheep.engine.core.ResolutionTargetKind.PLAYER -> DamageEdgeDirection.ATTACKER_TO_PLAYER
                com.wingedsheep.engine.core.ResolutionTargetKind.PLANESWALKER -> DamageEdgeDirection.ATTACKER_TO_PLANESWALKER
                com.wingedsheep.engine.core.ResolutionTargetKind.BATTLE -> DamageEdgeDirection.ATTACKER_TO_BATTLE
            }
            if (drain.direction != expectedDirection) {
                return "Trample drain ${drain.id}: direction does not match defender kind ${defender.kind}"
            }
            val blockerEdges = decision.edges.filter {
                it.sourceId == drain.sourceId &&
                    it.direction == DamageEdgeDirection.ATTACKER_TO_BLOCKER
            }
            val missingBlocker = attacker.blockedByIds.firstOrNull { blockerId ->
                blockerEdges.none { it.targetId == blockerId }
            }
            if (missingBlocker != null) {
                return "Trample drain ${drain.id}: blocker $missingBlocker is missing from the assignment plan"
            }
            for (blockerEdge in blockerEdges) {
                val blocker = blockersById[blockerEdge.targetId]
                    ?: return "Trample drain ${drain.id}: blocker is missing from the assignment plan"
                val markedAndAssigned = blocker.markedDamage +
                    (damageToBlocker[blocker.id] ?: 0)
                val deathtouchDamageAssigned = decision.edges.any { edge ->
                    edge.direction == DamageEdgeDirection.ATTACKER_TO_BLOCKER &&
                        edge.targetId == blocker.id &&
                        (amounts[edge.id] ?: 0) > 0 &&
                        attackersById[edge.sourceId]?.hasDeathtouch == true
                }
                val lethal = markedAndAssigned >= blocker.toughness || deathtouchDamageAssigned
                if (!lethal) {
                    return "Trample drain ${drain.id}: preceding blocker not at lethal"
                }
            }
        }

        return null
    }
}
