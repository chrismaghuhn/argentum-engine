package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.legalactions.BlockerDeclarationDomainSupport
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.RulesBlockRequirement
import com.wingedsheep.engine.legalactions.RulesBlockerDeclarationDomain
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BlockerDeclarationDomainSubmissionTest : FunSpec({
    test("accepts a valid declaration and an explicitly legal empty declaration") {
        val action = legalAction(domain(canDeclareZeroBlockers = true))

        BlockerDeclarationDomainSubmission.requireWithinRegisteredDomain(
            action,
            DeclareBlockers(player, mapOf(blockerA to listOf(attackerA))),
        )
        BlockerDeclarationDomainSubmission.requireWithinRegisteredDomain(
            action,
            DeclareBlockers(player, emptyMap()),
        )
    }

    test("rejects unknown and pairwise-invalid choices before execution") {
        val action = legalAction(domain())
        val cases = listOf(
            unknownBlocker to "UNKNOWN_BLOCKER",
            blockerA to "UNKNOWN_ATTACKER",
            blockerB to "INVALID_ATTACKER_FOR_BLOCKER",
        )

        shouldThrow<IllegalArgumentException> {
            BlockerDeclarationDomainSubmission.requireWithinRegisteredDomain(
                action,
                DeclareBlockers(player, mapOf(cases[0].first to listOf(attackerA))),
            )
        }.message shouldBe "Blocker declaration is outside the registered domain: UNKNOWN_BLOCKER"
        shouldThrow<IllegalArgumentException> {
            BlockerDeclarationDomainSubmission.requireWithinRegisteredDomain(
                action,
                DeclareBlockers(player, mapOf(cases[1].first to listOf(unknownAttacker))),
            )
        }.message shouldBe "Blocker declaration is outside the registered domain: UNKNOWN_ATTACKER"
        shouldThrow<IllegalArgumentException> {
            BlockerDeclarationDomainSubmission.requireWithinRegisteredDomain(
                action,
                DeclareBlockers(player, mapOf(cases[2].first to listOf(attackerA))),
            )
        }.message shouldBe "Blocker declaration is outside the registered domain: INVALID_ATTACKER_FOR_BLOCKER"
    }

    test("rejects malformed assignments, caps, and unmet requirements") {
        val capped = legalAction(
                domain(
                maxAttackersByBlocker = mapOf(blockerA to 1, blockerB to 1),
                requirements = listOf(RulesBlockRequirement.BlockSpecific(blockerA, attackerB)),
                minimumSatisfiedRequirementCount = 1,
                canDeclareZeroBlockers = false,
            ),
        )

        shouldThrow<IllegalArgumentException> {
            BlockerDeclarationDomainSubmission.requireWithinRegisteredDomain(
                capped,
                DeclareBlockers(player, mapOf(blockerA to emptyList())),
            )
        }.message shouldBe "Blocker declaration is outside the registered domain: EMPTY_BLOCKER_ASSIGNMENT"
        shouldThrow<IllegalArgumentException> {
            BlockerDeclarationDomainSubmission.requireWithinRegisteredDomain(
                capped,
                DeclareBlockers(player, mapOf(blockerA to listOf(attackerA, attackerB))),
            )
        }.message shouldBe "Blocker declaration is outside the registered domain: BLOCKER_MAX_EXCEEDED"
        shouldThrow<IllegalArgumentException> {
            BlockerDeclarationDomainSubmission.requireWithinRegisteredDomain(
                capped,
                DeclareBlockers(player, mapOf(blockerA to listOf(attackerA))),
            )
        }.message shouldBe "Blocker declaration is outside the registered domain: REQUIREMENT_THRESHOLD_UNSATISFIED"
    }

    test("rejects actor changes and unsupported or stale certificates") {
        val action = legalAction(domain())
        shouldThrow<IllegalArgumentException> {
            BlockerDeclarationDomainSubmission.requireWithinRegisteredDomain(
                action,
                DeclareBlockers(EntityId("other-player"), mapOf(blockerA to listOf(attackerA))),
            )
        }.message shouldBe "Structured action changed its action actor"

        val unsupported = LegalAction(
            action = DeclareBlockers(player, emptyMap()),
            actionType = "DeclareBlockers",
            description = "block",
        )
        shouldThrow<UnsupportedPathFailure> {
            BlockerDeclarationDomainSubmission.requireSupported(unsupported)
        }.diagnostics.single().semanticCode shouldBe "BLOCKER_DECLARATION_DOMAIN_UNSUPPORTED"
    }
})

private val player = EntityId("player")
private val blockerA = EntityId("blocker-a")
private val blockerB = EntityId("blocker-b")
private val unknownBlocker = EntityId("unknown-blocker")
private val attackerA = EntityId("attacker-a")
private val attackerB = EntityId("attacker-b")
private val unknownAttacker = EntityId("unknown-attacker")

private fun legalAction(domain: RulesBlockerDeclarationDomain): LegalAction = LegalAction(
    action = DeclareBlockers(player, emptyMap()),
    actionType = "DeclareBlockers",
    description = "block",
    blockerDeclarationDomain = domain,
    blockerDeclarationDomainSupport = BlockerDeclarationDomainSupport.SUPPORTED,
)

private fun domain(
    maxAttackersByBlocker: Map<EntityId, Int> = mapOf(blockerA to 2, blockerB to 1),
    requirements: List<RulesBlockRequirement> = emptyList(),
    minimumSatisfiedRequirementCount: Int = 0,
    canDeclareZeroBlockers: Boolean = true,
): RulesBlockerDeclarationDomain = RulesBlockerDeclarationDomain(
    blockerOrder = listOf(blockerA, blockerB),
    attackerOrder = listOf(attackerA, attackerB),
    blockerToAttackers = linkedMapOf(
        blockerA to listOf(attackerA, attackerB),
        blockerB to listOf(attackerB),
    ),
    maxAttackersByBlocker = linkedMapOf(
        blockerA to maxAttackersByBlocker.getValue(blockerA),
        blockerB to maxAttackersByBlocker.getValue(blockerB),
    ),
    minBlockersByAttacker = emptyMap(),
    maxBlockersByAttacker = emptyMap(),
    globalMaxBlockers = null,
    coBlockerRequirements = emptyMap(),
    requirements = requirements,
    minimumSatisfiedRequirementCount = minimumSatisfiedRequirementCount,
    canDeclareZeroBlockers = canDeclareZeroBlockers,
)
