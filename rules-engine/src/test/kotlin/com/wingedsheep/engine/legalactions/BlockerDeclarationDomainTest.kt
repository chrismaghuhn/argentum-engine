package com.wingedsheep.engine.legalactions

import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BlockerDeclarationDomainTest : FunSpec({
    test("publishes and validates the complete blocker-to-attacker relation") {
        val domain = domain(
            blockers = listOf(blockerA),
            attackers = listOf(attackerA, attackerB),
            relation = mapOf(blockerA to listOf(attackerA, attackerB)),
        )

        BlockerDeclarationDomainValidator.validate(
            domain,
            declaration(blockerA to listOf(attackerB)),
        ) shouldBe BlockerDeclarationValidationResult.Accepted

        BlockerDeclarationDomainValidator.validate(
            domain,
            declaration(blockerA to listOf(unknownAttacker)),
        ) shouldBe rejected(BlockerDeclarationRejection.UNKNOWN_ATTACKER)
    }

    test("preserves a blocker max and rejects excess attacker assignments") {
        val domain = domain(
            blockers = listOf(blockerA),
            attackers = listOf(attackerA, attackerB),
            relation = mapOf(blockerA to listOf(attackerA, attackerB)),
            maxAttackersByBlocker = mapOf(blockerA to 1),
        )

        BlockerDeclarationDomainValidator.validate(
            domain,
            declaration(blockerA to listOf(attackerA, attackerB)),
        ) shouldBe rejected(BlockerDeclarationRejection.BLOCKER_MAX_EXCEEDED)
    }

    test("retains duplicate requirement instances and computes their exact threshold") {
        val domain = domain(
            blockers = listOf(blockerA, blockerB),
            attackers = listOf(attackerA, attackerB, attackerC),
            relation = mapOf(
                blockerA to listOf(attackerA, attackerB, attackerC),
                blockerB to listOf(attackerA, attackerB, attackerC),
            ),
            maxAttackersByBlocker = mapOf(blockerA to 1, blockerB to 1),
            requirements = listOf(
                RulesBlockRequirement.BlockSpecific(blockerA, attackerA),
                RulesBlockRequirement.BlockSpecific(blockerA, attackerA),
                RulesBlockRequirement.BlockSpecific(blockerB, attackerB),
                RulesBlockRequirement.BlockSpecific(blockerB, attackerC),
            ),
            minimumSatisfiedRequirementCount = 3,
            canDeclareZeroBlockers = false,
        )

        BlockerDeclarationDomainValidator.maximumSatisfiedRequirementCount(domain) shouldBe 3
        BlockerDeclarationDomainValidator.validate(
            domain,
            declaration(blockerA to listOf(attackerB), blockerB to listOf(attackerC)),
        ) shouldBe rejected(BlockerDeclarationRejection.REQUIREMENT_THRESHOLD_UNSATISFIED)
        BlockerDeclarationDomainValidator.validate(
            domain,
            declaration(blockerA to listOf(attackerA), blockerB to listOf(attackerB)),
        ) shouldBe BlockerDeclarationValidationResult.Accepted
    }

    test("specific Provoke requirements compete rather than pinning a blocker") {
        val domain = domain(
            blockers = listOf(blockerA),
            attackers = listOf(attackerA, attackerB),
            relation = mapOf(blockerA to listOf(attackerA, attackerB)),
            requirements = listOf(
                RulesBlockRequirement.BlockSpecific(blockerA, attackerA),
                RulesBlockRequirement.AttackerMustBeBlockedIfAble(attackerB),
            ),
            minimumSatisfiedRequirementCount = 1,
            canDeclareZeroBlockers = false,
        )

        BlockerDeclarationDomainValidator.validate(
            domain,
            declaration(blockerA to listOf(attackerB)),
        ) shouldBe BlockerDeclarationValidationResult.Accepted
    }

    test("a blocking-cost edge does not satisfy the 509.1c threshold") {
        val domain = domain(
            blockers = listOf(blockerA),
            attackers = listOf(attackerA),
            relation = mapOf(blockerA to listOf(attackerA)),
            requirementRelation = mapOf(blockerA to emptyList()),
            requirements = listOf(
                RulesBlockRequirement.BlockSpecific(blockerA, attackerA),
            ),
            minimumSatisfiedRequirementCount = 0,
            canDeclareZeroBlockers = true,
        )

        BlockerDeclarationDomainValidator.maximumSatisfiedRequirementCount(domain) shouldBe 0
        BlockerDeclarationDomainValidator.validate(
            domain,
            declaration(),
        ) shouldBe BlockerDeclarationValidationResult.Accepted
        // The same relation remains a voluntary declaration and is accepted before the separate
        // blocking-cost continuation is entered.
        BlockerDeclarationDomainValidator.validate(
            domain,
            declaration(blockerA to listOf(attackerA)),
        ) shouldBe BlockerDeclarationValidationResult.Accepted
    }

    test("enforces attacker bounds, global caps, and co-blocker restrictions") {
        val domain = domain(
            blockers = listOf(blockerA, blockerB),
            attackers = listOf(attackerA),
            relation = mapOf(
                blockerA to listOf(attackerA),
                blockerB to listOf(attackerA),
            ),
            minBlockersByAttacker = mapOf(attackerA to 1),
            maxBlockersByAttacker = mapOf(attackerA to 1),
            globalMaxBlockers = 2,
            coBlockerRequirements = mapOf(
                blockerA to listOf(RulesCoBlockerRequirement(listOf(blockerB))),
            ),
            canDeclareZeroBlockers = true,
        )

        BlockerDeclarationDomainValidator.validate(
            domain,
            declaration(blockerA to listOf(attackerA)),
        ) shouldBe rejected(BlockerDeclarationRejection.CO_BLOCKER_REQUIREMENT_UNSATISFIED)
        BlockerDeclarationDomainValidator.validate(
            domain,
            declaration(blockerA to listOf(attackerA), blockerB to listOf(attackerA)),
        ) shouldBe rejected(BlockerDeclarationRejection.MAX_BLOCKERS_EXCEEDED)
    }

    test("represents legal and illegal empty declarations explicitly") {
        val legalEmpty = domain(
            blockers = listOf(blockerA),
            attackers = listOf(attackerA),
            relation = mapOf(blockerA to listOf(attackerA)),
            canDeclareZeroBlockers = true,
        )
        val illegalEmpty = legalEmpty.copy(
            requirements = listOf(RulesBlockRequirement.BlockSpecific(blockerA, attackerA)),
            minimumSatisfiedRequirementCount = 1,
            canDeclareZeroBlockers = false,
        )

        BlockerDeclarationDomainValidator.validate(legalEmpty, declaration()) shouldBe
            BlockerDeclarationValidationResult.Accepted
        BlockerDeclarationDomainValidator.validate(illegalEmpty, declaration()) shouldBe
            rejected(BlockerDeclarationRejection.ZERO_BLOCKERS_FORBIDDEN)
    }

    test("attacker minimum is conditional on choosing to block that attacker") {
        val menaceDomain = domain(
            blockers = listOf(blockerA),
            attackers = listOf(attackerA),
            relation = mapOf(blockerA to listOf(attackerA)),
            minBlockersByAttacker = mapOf(attackerA to 2),
            canDeclareZeroBlockers = true,
        )

        BlockerDeclarationDomainValidator.validate(
            menaceDomain,
            declaration(),
        ) shouldBe BlockerDeclarationValidationResult.Accepted
        BlockerDeclarationDomainValidator.validate(
            menaceDomain,
            declaration(blockerA to listOf(attackerA)),
        ) shouldBe rejected(BlockerDeclarationRejection.MIN_BLOCKERS_UNSATISFIED)
    }

    test("must-be-blocked-if-able contributes no requirement when no blocker is able") {
        val domain = domain(
            blockers = emptyList(),
            attackers = listOf(attackerA),
            relation = emptyMap(),
            requirements = listOf(
                RulesBlockRequirement.AttackerMustBeBlockedIfAble(attackerA),
            ),
            minimumSatisfiedRequirementCount = 0,
            canDeclareZeroBlockers = true,
        )

        BlockerDeclarationDomainValidator.maximumSatisfiedRequirementCount(domain) shouldBe 0
        BlockerDeclarationDomainValidator.validate(domain, declaration()) shouldBe
            BlockerDeclarationValidationResult.Accepted
    }
})

private val player = EntityId("defender")
private val blockerA = EntityId("blocker-a")
private val blockerB = EntityId("blocker-b")
private val attackerA = EntityId("attacker-a")
private val attackerB = EntityId("attacker-b")
private val attackerC = EntityId("attacker-c")
private val unknownAttacker = EntityId("unknown-attacker")

private fun declaration(vararg assignments: Pair<EntityId, List<EntityId>>): DeclareBlockers =
    DeclareBlockers(player, linkedMapOf(*assignments))

private fun domain(
    blockers: List<EntityId>,
    attackers: List<EntityId>,
    relation: Map<EntityId, List<EntityId>>,
    maxAttackersByBlocker: Map<EntityId, Int> = blockers.associateWith { 1 },
    minBlockersByAttacker: Map<EntityId, Int> = emptyMap(),
    maxBlockersByAttacker: Map<EntityId, Int> = emptyMap(),
    globalMaxBlockers: Int? = null,
    coBlockerRequirements: Map<EntityId, List<RulesCoBlockerRequirement>> = emptyMap(),
    requirements: List<RulesBlockRequirement> = emptyList(),
    requirementRelation: Map<EntityId, List<EntityId>> = relation,
    minimumSatisfiedRequirementCount: Int = 0,
    canDeclareZeroBlockers: Boolean = true,
): RulesBlockerDeclarationDomain = RulesBlockerDeclarationDomain(
    blockerOrder = blockers,
    attackerOrder = attackers,
    blockerToAttackers = blockers.associateWith { relation.getValue(it) },
    maxAttackersByBlocker = blockers.associateWith { maxAttackersByBlocker.getValue(it) },
    minBlockersByAttacker = minBlockersByAttacker,
    maxBlockersByAttacker = maxBlockersByAttacker,
    globalMaxBlockers = globalMaxBlockers,
    coBlockerRequirements = coBlockerRequirements,
    requirements = requirements,
    minimumSatisfiedRequirementCount = minimumSatisfiedRequirementCount,
    canDeclareZeroBlockers = canDeclareZeroBlockers,
    requirementBlockerToAttackers = blockers.associateWith { requirementRelation.getValue(it) },
)

private fun rejected(reason: BlockerDeclarationRejection): BlockerDeclarationValidationResult =
    BlockerDeclarationValidationResult.Rejected(reason)
