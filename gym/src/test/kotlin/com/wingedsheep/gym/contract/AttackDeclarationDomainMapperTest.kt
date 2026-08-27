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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    test("projects all certificate fields in Rules-owned wire order") {
        val action = action(
            RulesAttackDeclarationDomain(
                attackerOrder = listOf(attackerB, attackerA),
                defenderOrder = listOf(defenderTwo, defenderOne),
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
                        defenderOne to listOf(attackerB, attackerA),
                    ),
                ),
            ),
        )

        val mapped = AttackDeclarationDomainMapper.map(action) { true } as
            AttackDeclarationDomainMapper.Result.Supported
        val domain = mapped.domain
        domain shouldNotBe null
        domain!!.attackerOrder shouldBe listOf(attackerB, attackerA)
        domain.attackerToDefenders.keys.toList() shouldBe listOf(attackerB, attackerA)
        domain.attackerToDefenders[attackerB] shouldBe listOf(defenderTwo, defenderOne)
        domain.mandatoryAttackers shouldBe listOf(attackerB, attackerA)
        domain.coAttackerRequirements.keys.toList() shouldBe listOf(attackerB)
        domain.bandConstraints.nonBandingAttackersByDefender.keys.toList() shouldBe
            listOf(defenderOne)
        domain.bandConstraints.bandingAttackersByDefender.keys.toList() shouldBe listOf(defenderTwo)
    }

    test("mapper preserves the Rules relation and list sequence") {
        val action = action(
            RulesAttackDeclarationDomain(
                attackerOrder = listOf(attackerB, attackerA),
                defenderOrder = listOf(defenderTwo, defenderOne),
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

    test("mapper preserves the certificate defender order when filtered lists hide its prefix") {
        val action = action(
            RulesAttackDeclarationDomain(
                attackerOrder = listOf(attackerA, attackerB),
                defenderOrder = listOf(defenderOne, defenderTwo),
                attackerToDefenders = linkedMapOf(
                    attackerA to listOf(defenderTwo),
                    attackerB to listOf(defenderOne, defenderTwo),
                ),
                mandatoryAttackers = emptyList(),
                canDeclareZeroAttackers = true,
                maxAttackers = 2,
                coAttackerRequirements = emptyMap(),
                bandConstraints = RulesAttackBandConstraints(
                    bandingAttackersByDefender = emptyMap(),
                    nonBandingAttackersByDefender = linkedMapOf(
                        defenderTwo to listOf(attackerA, attackerB),
                        defenderOne to listOf(attackerB),
                    ),
                ),
            ),
        )

        val mapped = AttackDeclarationDomainMapper.map(action) { true } as
            AttackDeclarationDomainMapper.Result.Supported
        val domain = mapped.domain
        domain shouldNotBe null
        domain!!.attackerToDefenders[attackerA] shouldBe listOf(defenderTwo)
        domain.attackerToDefenders[attackerB] shouldBe listOf(defenderOne, defenderTwo)
        domain.bandConstraints.nonBandingAttackersByDefender.keys.toList() shouldBe
            listOf(defenderOne, defenderTwo)
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

    test("rejects unknown future V2 wire versions at DTO construction") {
        shouldThrow<IllegalArgumentException> {
            AttackDeclarationDomainV2(
                version = ATTACK_DECLARATION_DOMAIN_V2_VERSION + 1,
                attackerOrder = emptyList(),
                attackerToDefenders = emptyMap(),
                mandatoryAttackers = emptyList(),
                canDeclareZeroAttackers = true,
                maxAttackers = null,
                coAttackerRequirements = emptyMap(),
                bandConstraints = AttackBandConstraintsV1(emptyMap(), emptyMap()),
            )
        }
    }

    test("round-trips V2 attacker order exactly through its codec") {
        val domain = AttackDeclarationDomainV2(
            attackerOrder = listOf(attackerB, attackerA),
            attackerToDefenders = linkedMapOf(
                attackerB to listOf(defenderTwo, defenderOne),
                attackerA to listOf(defenderOne),
            ),
            mandatoryAttackers = listOf(attackerB, attackerA),
            canDeclareZeroAttackers = false,
            maxAttackers = 2,
            coAttackerRequirements = linkedMapOf(
                attackerB to listOf(AttackCoAttackerRequirementV1(listOf(attackerA))),
            ),
            bandConstraints = AttackBandConstraintsV1(
                bandingAttackersByDefender = linkedMapOf(defenderTwo to listOf(attackerB)),
                nonBandingAttackersByDefender = linkedMapOf(
                    defenderOne to listOf(attackerB, attackerA),
                ),
            ),
        )
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val encoded = json.encodeToString(AttackDeclarationDomainV2.serializer(), domain)

        json.decodeFromString(AttackDeclarationDomainV2.serializer(), encoded) shouldBe domain
        val unknownVersion = encoded.replace("\"version\":2", "\"version\":99")
        shouldThrow<IllegalArgumentException> {
            json.decodeFromString(AttackDeclarationDomainV2.serializer(), unknownVersion)
        }
    }

    test("rejects malformed attacker order before public projection") {
        val duplicate = completeDomain().copy(attackerOrder = listOf(attackerA, attackerA))
        val mismatch = completeDomain().copy(attackerOrder = listOf(attackerA))

        AttackDeclarationDomainMapper.map(action(duplicate)) { true } shouldBe
            AttackDeclarationDomainMapper.Result.Unsupported
        AttackDeclarationDomainMapper.map(action(mismatch)) { true } shouldBe
            AttackDeclarationDomainMapper.Result.Unsupported
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
    attackerOrder = listOf(attackerA, attackerB),
    defenderOrder = listOf(defenderOne, defenderTwo),
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
