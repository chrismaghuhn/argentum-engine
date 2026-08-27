package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.legalactions.BlockerDeclarationDomainSupport
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.RulesBlockRequirement
import com.wingedsheep.engine.legalactions.RulesBlockerDeclarationDomain
import com.wingedsheep.engine.legalactions.RulesCoBlockerRequirement
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class BlockerDeclarationDomainMapperTest : FunSpec({
    test("noncombat actions carry no blocker domain") {
        BlockerDeclarationDomainMapper.map(
            LegalAction(PassPriority(player), "PassPriority", "pass"),
        ) { true } shouldBe BlockerDeclarationDomainMapper.Result.Supported(null)
    }

    test("missing or unsupported blocker certificates fail closed") {
        val action = LegalAction(
            action = DeclareBlockers(player, emptyMap()),
            actionType = "DeclareBlockers",
            description = "block",
        )

        BlockerDeclarationDomainMapper.map(action) { true } shouldBe
            BlockerDeclarationDomainMapper.Result.Unsupported
    }

    test("projects all certificate fields and preserves requirement multiplicity") {
        val duplicate = RulesBlockRequirement.BlockSpecific(blockerA, attackerA)
        val action = legalAction(
            RulesBlockerDeclarationDomain(
                blockerOrder = listOf(blockerA, blockerB),
                attackerOrder = listOf(attackerA, attackerB),
                blockerToAttackers = linkedMapOf(
                    blockerA to listOf(attackerA, attackerB),
                    blockerB to listOf(attackerB),
                ),
                maxAttackersByBlocker = linkedMapOf(blockerA to 2, blockerB to 1),
                minBlockersByAttacker = linkedMapOf(attackerA to 1),
                maxBlockersByAttacker = linkedMapOf(attackerB to 1),
                globalMaxBlockers = 2,
                coBlockerRequirements = linkedMapOf(
                    blockerA to listOf(RulesCoBlockerRequirement(listOf(blockerB))),
                ),
                requirements = listOf(
                    duplicate,
                    duplicate,
                    RulesBlockRequirement.BlockOneOf(blockerB, listOf(attackerB)),
                ),
                minimumSatisfiedRequirementCount = 2,
                canDeclareZeroBlockers = false,
            ),
        )

        val mapped = BlockerDeclarationDomainMapper.map(action) { true }
            as BlockerDeclarationDomainMapper.Result.Supported
        val domain = mapped.domain
        domain shouldNotBe null
        domain!!.blockerOrder shouldBe listOf(blockerA, blockerB)
        domain.attackerOrder shouldBe listOf(attackerA, attackerB)
        domain.blockerToAttackers.keys.toList() shouldBe listOf(blockerA, blockerB)
        domain.blockerToAttackers[blockerA] shouldBe listOf(attackerA, attackerB)
        domain.maxAttackersByBlocker[blockerA] shouldBe 2
        domain.minBlockersByAttacker[attackerA] shouldBe 1
        domain.maxBlockersByAttacker[attackerB] shouldBe 1
        domain.globalMaxBlockers shouldBe 2
        domain.coBlockerRequirements.keys.toList() shouldBe listOf(blockerA)
        domain.requirements shouldHaveSize 3
        domain.requirements[0] shouldBe domain.requirements[1]
        domain.minimumSatisfiedRequirementCount shouldBe 2
        domain.canDeclareZeroBlockers shouldBe false
    }

    test("rejects an unaddressable reference without filtering the domain") {
        val domain = completeDomain()
        BlockerDeclarationDomainMapper.map(legalAction(domain)) { it != attackerB } shouldBe
            BlockerDeclarationDomainMapper.Result.Unsupported
    }

    test("rejects a malformed producer order instead of inventing one") {
        val malformed = completeDomain().copy(
            blockerToAttackers = linkedMapOf(
                blockerA to listOf(attackerB, attackerA),
                blockerB to listOf(attackerB),
            ),
        )

        BlockerDeclarationDomainMapper.map(legalAction(malformed)) { true } shouldBe
            BlockerDeclarationDomainMapper.Result.Unsupported
    }

    test("rejects an empty co-blocker group instead of publishing an impossible any-of") {
        val malformed = completeDomain().copy(
            coBlockerRequirements = linkedMapOf(
                blockerA to listOf(RulesCoBlockerRequirement(emptyList())),
            ),
        )

        BlockerDeclarationDomainMapper.map(legalAction(malformed)) { true } shouldBe
            BlockerDeclarationDomainMapper.Result.Unsupported
    }

    test("rejects future wire versions at DTO construction") {
        shouldThrow<IllegalArgumentException> {
            BlockerDeclarationDomainV1(
                version = 2,
                blockerOrder = emptyList(),
                attackerOrder = emptyList(),
                blockerToAttackers = emptyMap(),
                maxAttackersByBlocker = emptyMap(),
                minBlockersByAttacker = emptyMap(),
                maxBlockersByAttacker = emptyMap(),
                globalMaxBlockers = null,
                coBlockerRequirements = emptyMap(),
                requirements = emptyList(),
                minimumSatisfiedRequirementCount = 0,
                canDeclareZeroBlockers = true,
            )
        }
    }
})

private val player = EntityId("player")
private val blockerA = EntityId("blocker-a")
private val blockerB = EntityId("blocker-b")
private val attackerA = EntityId("attacker-a")
private val attackerB = EntityId("attacker-b")

private fun legalAction(domain: RulesBlockerDeclarationDomain): LegalAction = LegalAction(
    action = DeclareBlockers(player, emptyMap()),
    actionType = "DeclareBlockers",
    description = "block",
    blockerDeclarationDomain = domain,
    blockerDeclarationDomainSupport = BlockerDeclarationDomainSupport.SUPPORTED,
)

private fun completeDomain(): RulesBlockerDeclarationDomain = RulesBlockerDeclarationDomain(
    blockerOrder = listOf(blockerA, blockerB),
    attackerOrder = listOf(attackerA, attackerB),
    blockerToAttackers = linkedMapOf(
        blockerA to listOf(attackerA),
        blockerB to listOf(attackerB),
    ),
    maxAttackersByBlocker = linkedMapOf(blockerA to 1, blockerB to 1),
    minBlockersByAttacker = emptyMap(),
    maxBlockersByAttacker = emptyMap(),
    globalMaxBlockers = null,
    coBlockerRequirements = emptyMap(),
    requirements = emptyList(),
    minimumSatisfiedRequirementCount = 0,
    canDeclareZeroBlockers = true,
)
