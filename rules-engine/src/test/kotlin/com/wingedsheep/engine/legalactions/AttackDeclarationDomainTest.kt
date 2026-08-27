package com.wingedsheep.engine.legalactions

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AttackDeclarationDomainTest : FunSpec({
    test("accepts an asymmetric declaration with a valid band") {
        val domain = domain(
            relation = linkedMapOf(
                attackerA to listOf(defenderOne, defenderTwo),
                attackerB to listOf(defenderOne),
            ),
            banding = mapOf(defenderOne to listOf(attackerA, attackerB)),
        )

        AttackDeclarationDomainValidator.validate(
            domain,
            declaration(attackerA to defenderOne, attackerB to defenderOne, bands = listOf(setOf(attackerA, attackerB))),
        ) shouldBe AttackDeclarationValidationResult.Accepted
    }

    test("accepts relation map insertion order different from attacker order") {
        val domain = domain(
            relation = linkedMapOf(
                attackerB to listOf(defenderOne),
                attackerA to listOf(defenderOne),
            ),
            attackerOrder = listOf(attackerA, attackerB),
        )

        AttackDeclarationDomainValidator.validate(domain, declaration(attackerA to defenderOne)) shouldBe
            AttackDeclarationValidationResult.Accepted
    }

    test("rejects duplicate attacker order elements") {
        val domain = domain(
            relation = linkedMapOf(
                attackerA to listOf(defenderOne),
                attackerB to listOf(defenderOne),
            ),
            attackerOrder = listOf(attackerA, attackerA),
        )

        AttackDeclarationDomainValidator.validate(domain, declaration(attackerA to defenderOne)) shouldBe
            rejected(AttackDeclarationRejection.MALFORMED_CERTIFICATE)
    }

    test("rejects attacker order and relation membership mismatch") {
        val domain = domain(
            relation = linkedMapOf(attackerA to listOf(defenderOne)),
            attackerOrder = listOf(attackerB),
        )

        AttackDeclarationDomainValidator.validate(domain, declaration(attackerA to defenderOne)) shouldBe
            rejected(AttackDeclarationRejection.MALFORMED_CERTIFICATE)
    }

    test("preserves asymmetric attacker-to-defender relations") {
        val domain = domain(
            relation = linkedMapOf(
                attackerA to listOf(defenderOne, defenderTwo),
                attackerB to listOf(defenderOne),
            ),
        )

        AttackDeclarationDomainValidator.validate(
            domain,
            declaration(attackerB to defenderTwo),
        ) shouldBe rejected(AttackDeclarationRejection.INVALID_DEFENDER)
    }

    test("rejects unknown attackers") {
        val domain = domain(
            relation = linkedMapOf(attackerA to listOf(defenderOne)),
        )

        AttackDeclarationDomainValidator.validate(
            domain,
            declaration(unknownAttacker to defenderOne),
        ) shouldBe rejected(AttackDeclarationRejection.UNKNOWN_ATTACKER)
    }

    test("enforces explicit zero-attacker legality") {
        val domain = domain(
            relation = linkedMapOf(attackerA to listOf(defenderOne)),
            canDeclareZeroAttackers = false,
        )

        AttackDeclarationDomainValidator.validate(domain, declaration()) shouldBe
            rejected(AttackDeclarationRejection.ZERO_ATTACKERS_FORBIDDEN)
    }

    test("enforces mandatory attackers") {
        val domain = domain(
            relation = linkedMapOf(
                attackerA to listOf(defenderOne),
                attackerB to listOf(defenderOne),
            ),
            mandatoryAttackers = listOf(attackerA),
        )

        AttackDeclarationDomainValidator.validate(
            domain,
            declaration(attackerB to defenderOne),
        ) shouldBe rejected(AttackDeclarationRejection.MANDATORY_ATTACKER_MISSING)
    }

    test("enforces the global attacker cap") {
        val domain = domain(
            relation = linkedMapOf(
                attackerA to listOf(defenderOne),
                attackerB to listOf(defenderOne),
            ),
            maxAttackers = 1,
        )

        AttackDeclarationDomainValidator.validate(
            domain,
            declaration(attackerA to defenderOne, attackerB to defenderOne),
        ) shouldBe rejected(AttackDeclarationRejection.ATTACKER_CAP_EXCEEDED)
    }

    test("enforces concrete all-of co-attacker requirements") {
        val domain = domain(
            relation = linkedMapOf(
                attackerA to listOf(defenderOne),
                attackerB to listOf(defenderOne),
            ),
            coAttackerRequirements = mapOf(
                attackerA to listOf(RulesCoAttackerRequirement(anyOf = listOf(attackerB))),
            ),
        )

        AttackDeclarationDomainValidator.validate(
            domain,
            declaration(attackerA to defenderOne),
        ) shouldBe rejected(AttackDeclarationRejection.CO_ATTACKER_REQUIREMENT_UNSATISFIED)

        AttackDeclarationDomainValidator.validate(
            domain,
            declaration(attackerA to defenderOne, attackerB to defenderOne),
        ) shouldBe AttackDeclarationValidationResult.Accepted
    }

    test("canonicalizes co-attacker requirement instances by attacker ranks without deduplication") {
        val requirements = listOf(
            RulesCoAttackerRequirement(anyOf = listOf(attackerB)),
            RulesCoAttackerRequirement(anyOf = listOf(attackerB)),
            RulesCoAttackerRequirement(anyOf = listOf(attackerC)),
        )
        val domain = domain(
            relation = linkedMapOf(
                attackerA to listOf(defenderOne),
                attackerB to listOf(defenderOne),
                attackerC to listOf(defenderOne),
            ),
            attackerOrder = listOf(attackerA, attackerB, attackerC),
            coAttackerRequirements = mapOf(attackerA to requirements),
        )

        AttackDeclarationDomainValidator.isStructurallyValid(domain) shouldBe true
        AttackDeclarationDomainValidator.isStructurallyValid(
            domain.copy(
                coAttackerRequirements = mapOf(attackerA to requirements.reversed()),
            ),
        ) shouldBe false
        domain.coAttackerRequirements.getValue(attackerA).count { it.anyOf == listOf(attackerB) } shouldBe 2
    }

    test("rejects duplicate band membership") {
        val domain = domain(
            relation = linkedMapOf(
                attackerA to listOf(defenderOne),
                attackerB to listOf(defenderOne),
                attackerC to listOf(defenderOne),
            ),
            banding = mapOf(defenderOne to listOf(attackerA, attackerB, attackerC)),
        )

        AttackDeclarationDomainValidator.validate(
            domain,
            declaration(
                attackerA to defenderOne,
                attackerB to defenderOne,
                attackerC to defenderOne,
                bands = listOf(setOf(attackerA, attackerB), setOf(attackerB, attackerC)),
            ),
        ) shouldBe rejected(AttackDeclarationRejection.DUPLICATE_BAND_MEMBER)
    }

    test("requires every band to use one defender") {
        val domain = domain(
            relation = linkedMapOf(
                attackerA to listOf(defenderOne),
                attackerB to listOf(defenderTwo),
            ),
            banding = mapOf(
                defenderOne to listOf(attackerA),
                defenderTwo to listOf(attackerB),
            ),
        )

        AttackDeclarationDomainValidator.validate(
            domain,
            declaration(
                attackerA to defenderOne,
                attackerB to defenderTwo,
                bands = listOf(setOf(attackerA, attackerB)),
            ),
        ) shouldBe rejected(AttackDeclarationRejection.MALFORMED_BAND)
    }

    test("enforces at most one non-banding member per band") {
        val domain = domain(
            relation = linkedMapOf(
                attackerA to listOf(defenderOne),
                attackerB to listOf(defenderOne),
            ),
            nonBanding = mapOf(defenderOne to listOf(attackerA, attackerB)),
        )

        AttackDeclarationDomainValidator.validate(
            domain,
            declaration(
                attackerA to defenderOne,
                attackerB to defenderOne,
                bands = listOf(setOf(attackerA, attackerB)),
            ),
        ) shouldBe rejected(AttackDeclarationRejection.MALFORMED_BAND)
    }

    test("accepts a band of certified banding attackers") {
        val domain = domain(
            relation = linkedMapOf(
                attackerA to listOf(defenderOne),
                attackerB to listOf(defenderOne),
            ),
            banding = mapOf(defenderOne to listOf(attackerA, attackerB)),
        )

        AttackDeclarationDomainValidator.validate(
            domain,
            declaration(
                attackerA to defenderOne,
                attackerB to defenderOne,
                bands = listOf(setOf(attackerA, attackerB)),
            ),
        ) shouldBe AttackDeclarationValidationResult.Accepted
    }

    test("marks a DeclareAttackers action without a certificate unsupported by default") {
        LegalAction(
            action = DeclareAttackers(player, emptyMap()),
            actionType = "DeclareAttackers",
            description = "Declare attackers",
        ).attackDeclarationDomainSupport shouldBe AttackDeclarationDomainSupport.UNSUPPORTED(
            AttackDeclarationDomainUnsupportedReason.CERTIFICATE_MISSING,
        )

        LegalAction(
            action = PassPriority(player),
            actionType = "PassPriority",
            description = "Pass priority",
        ).attackDeclarationDomainSupport shouldBe AttackDeclarationDomainSupport.SUPPORTED
    }
})

private val player = EntityId("player")
private val attackerA = EntityId("attacker-a")
private val attackerB = EntityId("attacker-b")
private val attackerC = EntityId("attacker-c")
private val unknownAttacker = EntityId("unknown-attacker")
private val defenderOne = EntityId("defender-1")
private val defenderTwo = EntityId("defender-2")

private fun declaration(
    vararg assignments: Pair<EntityId, EntityId>,
    bands: List<Set<EntityId>> = emptyList(),
): DeclareAttackers = DeclareAttackers(
    playerId = player,
    attackers = linkedMapOf(*assignments),
    bands = bands,
)

private fun domain(
    relation: Map<EntityId, List<EntityId>>,
    attackerOrder: List<EntityId> = relation.keys.toList(),
    mandatoryAttackers: List<EntityId> = emptyList(),
    canDeclareZeroAttackers: Boolean = true,
    maxAttackers: Int? = null,
    coAttackerRequirements: Map<EntityId, List<RulesCoAttackerRequirement>> = emptyMap(),
    banding: Map<EntityId, List<EntityId>> = emptyMap(),
    nonBanding: Map<EntityId, List<EntityId>> = emptyMap(),
): RulesAttackDeclarationDomain = RulesAttackDeclarationDomain(
    attackerOrder = attackerOrder,
    attackerToDefenders = relation,
    mandatoryAttackers = mandatoryAttackers,
        canDeclareZeroAttackers = canDeclareZeroAttackers,
        maxAttackers = maxAttackers,
        coAttackerRequirements = coAttackerRequirements,
        bandConstraints = RulesAttackBandConstraints(
            bandingAttackersByDefender = banding.toCanonicalMap(attackerOrder),
            nonBandingAttackersByDefender = nonBanding
                .completeFor(relation, banding, attackerOrder)
                .toCanonicalMap(attackerOrder),
        ),
    )

private fun rejected(reason: AttackDeclarationRejection): AttackDeclarationValidationResult =
    AttackDeclarationValidationResult.Rejected(reason)

private fun Map<EntityId, List<EntityId>>.completeFor(
    relation: Map<EntityId, List<EntityId>>,
    banding: Map<EntityId, List<EntityId>>,
    attackerOrder: List<EntityId>,
): Map<EntityId, List<EntityId>> {
    val result = linkedMapOf<EntityId, MutableSet<EntityId>>()
    entries.forEach { (defender, attackers) ->
        result.getOrPut(defender) { linkedSetOf() }.addAll(attackers)
    }
    attackerOrder.forEach { attacker ->
        val defenders = relation[attacker].orEmpty()
        defenders.forEach { defender ->
            if (attacker !in banding[defender].orEmpty() && attacker !in this[defender].orEmpty()) {
                result.getOrPut(defender) { linkedSetOf() }.add(attacker)
            }
        }
    }
    return result.mapValues { (_, attackers) -> attackerOrder.filter(attackers::contains) }
}

private fun Map<EntityId, List<EntityId>>.toCanonicalMap(
    attackerOrder: List<EntityId>,
): Map<EntityId, List<EntityId>> =
    entries.associate { (defender, attackers) ->
        defender to attackerOrder.filter(attackers::contains)
    }
