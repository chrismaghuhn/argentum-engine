package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.legalactions.AttackDeclarationDomainSupport
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.RulesAttackBandConstraints
import com.wingedsheep.engine.legalactions.RulesAttackDeclarationDomain
import com.wingedsheep.engine.legalactions.RulesCoAttackerRequirement
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class AttackDeclarationDomainMapperTest : FunSpec({
    test("noncombat actions carry no attack domain") {
        val result = AttackDeclarationDomainMapper.map(
            LegalAction(PassPriority(player), "PassPriority", "pass"),
            isEntityReferenceAddressable = { true },
        )

        result shouldBe AttackDeclarationDomainMapper.Result.Supported(null)
    }

    test("missing or unsupported attack certificates fail the whole action") {
        val action = LegalAction(
            action = DeclareAttackers(player, emptyMap()),
            actionType = "DeclareAttackers",
            description = "attack",
        )

        AttackDeclarationDomainMapper.map(action) { true } shouldBe
            AttackDeclarationDomainMapper.Result.Unsupported
    }

    test("projects all certificate fields into deterministic canonical wire order") {
        val action = action(
            RulesAttackDeclarationDomain(
                attackerToDefenders = linkedMapOf(
                    attackerB to listOf(defenderTwo, defenderOne),
                    attackerA to listOf(defenderOne),
                ),
                mandatoryAttackers = listOf(attackerB, attackerA),
                canDeclareZeroAttackers = false,
                maxAttackers = 2,
                coAttackerRequirements = linkedMapOf(
                    attackerB to listOf(RulesCoAttackerRequirement(listOf(attackerA))),
                ),
                bandConstraints = RulesAttackBandConstraints(
                    bandingAttackersByDefender = linkedMapOf(defenderTwo to listOf(attackerB)),
                    nonBandingAttackersByDefender = linkedMapOf(
                        defenderOne to listOf(attackerA, attackerB),
                    ),
                ),
            ),
        )

        val mapped = AttackDeclarationDomainMapper.map(action) { true } as
            AttackDeclarationDomainMapper.Result.Supported
        val domain = mapped.domain
        domain shouldNotBe null
        domain!!.attackerToDefenders.keys.map(EntityId::value) shouldBe listOf("attacker-a", "attacker-b")
        domain.attackerToDefenders[attackerB]?.map(EntityId::value) shouldBe
            listOf("defender-1", "defender-2")
        domain.mandatoryAttackers shouldBe listOf(attackerA, attackerB)
        domain.coAttackerRequirements.keys.toList() shouldBe listOf(attackerB)
        domain.bandConstraints.nonBandingAttackersByDefender.keys.toList() shouldBe
            listOf(defenderOne)
        domain.bandConstraints.bandingAttackersByDefender.keys.toList() shouldBe listOf(defenderTwo)
    }

    test("RED: mapper preserves the Rules relation and list sequence") {
        val action = action(
            RulesAttackDeclarationDomain(
                attackerToDefenders = linkedMapOf(
                    attackerB to listOf(defenderTwo, defenderOne),
                    attackerA to listOf(defenderOne),
                ),
                mandatoryAttackers = listOf(attackerB, attackerA),
                canDeclareZeroAttackers = false,
                maxAttackers = 2,
                coAttackerRequirements = emptyMap(),
                bandConstraints = RulesAttackBandConstraints(
                    bandingAttackersByDefender = linkedMapOf(defenderTwo to listOf(attackerB)),
                    nonBandingAttackersByDefender = linkedMapOf(
                        defenderOne to listOf(attackerB, attackerA),
                    ),
                ),
            ),
        )

        val mapped = AttackDeclarationDomainMapper.map(action) { true } as
            AttackDeclarationDomainMapper.Result.Supported
        val domain = mapped.domain
        domain shouldNotBe null
        domain!!.attackerToDefenders.keys.toList() shouldBe listOf(attackerB, attackerA)
        domain.attackerToDefenders.getValue(attackerB) shouldBe listOf(defenderTwo, defenderOne)
        domain.mandatoryAttackers shouldBe listOf(attackerB, attackerA)
    }

    test("rejects every hidden reference family without filtering it") {
        val domain = completeDomain()
        val references = listOf(
            attackerA,
            defenderOne,
            attackerB,
            defenderTwo,
        )

        references.forEach { hidden ->
            val result = AttackDeclarationDomainMapper.map(action(domain)) { it != hidden }
            result shouldBe AttackDeclarationDomainMapper.Result.Unsupported
        }
    }

    test("rejects a malformed certificate instead of publishing a reduced relation") {
        val malformed = completeDomain().copy(
            bandConstraints = RulesAttackBandConstraints(
                bandingAttackersByDefender = emptyMap(),
                nonBandingAttackersByDefender = emptyMap(),
            ),
        )

        AttackDeclarationDomainMapper.map(action(malformed)) { true } shouldBe
            AttackDeclarationDomainMapper.Result.Unsupported
    }

    test("rejects future wire versions at DTO construction") {
        shouldThrow<IllegalArgumentException> {
            AttackDeclarationDomainV1(
                version = 2,
                attackerToDefenders = emptyMap(),
                mandatoryAttackers = emptyList(),
                canDeclareZeroAttackers = true,
                maxAttackers = null,
                coAttackerRequirements = emptyMap(),
                bandConstraints = AttackBandConstraintsV1(emptyMap(), emptyMap()),
            )
        }
    }
})

private val player = EntityId("player")
private val attackerA = EntityId("attacker-a")
private val attackerB = EntityId("attacker-b")
private val defenderOne = EntityId("defender-1")
private val defenderTwo = EntityId("defender-2")

private fun action(domain: RulesAttackDeclarationDomain): LegalAction = LegalAction(
    action = DeclareAttackers(player, emptyMap()),
    actionType = "DeclareAttackers",
    description = "attack",
    attackDeclarationDomain = domain,
    attackDeclarationDomainSupport = AttackDeclarationDomainSupport.SUPPORTED,
)

private fun completeDomain(): RulesAttackDeclarationDomain = RulesAttackDeclarationDomain(
    attackerToDefenders = linkedMapOf(
        attackerA to listOf(defenderOne),
        attackerB to listOf(defenderOne, defenderTwo),
    ),
    mandatoryAttackers = listOf(attackerA),
    canDeclareZeroAttackers = false,
    maxAttackers = 2,
    coAttackerRequirements = mapOf(
        attackerB to listOf(RulesCoAttackerRequirement(listOf(attackerA))),
    ),
    bandConstraints = RulesAttackBandConstraints(
        bandingAttackersByDefender = mapOf(defenderOne to listOf(attackerB)),
        nonBandingAttackersByDefender = mapOf(
            defenderOne to listOf(attackerA),
            defenderTwo to listOf(attackerB),
        ),
    ),
)
