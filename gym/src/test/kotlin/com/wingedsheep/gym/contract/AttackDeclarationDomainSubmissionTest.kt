package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.legalactions.AttackDeclarationDomainSupport
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.RulesAttackBandConstraints
import com.wingedsheep.engine.legalactions.RulesAttackDeclarationDomain
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AttackDeclarationDomainSubmissionTest : FunSpec({
    test("accepts a declaration that satisfies the registered certificate snapshot") {
        val action = legalAction()

        AttackDeclarationDomainSubmission.requireWithinRegisteredDomain(
            action,
            DeclareAttackers(player, mapOf(attacker to defender)),
        )
    }

    test("rejects a declaration outside the registered certificate before execution") {
        val action = legalAction()

        val failure = shouldThrow<IllegalArgumentException> {
            AttackDeclarationDomainSubmission.requireWithinRegisteredDomain(
                action,
                DeclareAttackers(player, mapOf(attacker to EntityId("unpublished-defender"))),
            )
        }

        failure.message shouldBe
            "Attack declaration is outside the registered domain: INVALID_DEFENDER"
    }

    test("rejects an actor mismatch as input before validating declaration choices") {
        val action = legalAction()

        val failure = shouldThrow<IllegalArgumentException> {
            AttackDeclarationDomainSubmission.requireWithinRegisteredDomain(
                action,
                DeclareAttackers(
                    EntityId("other-player"),
                    mapOf(attacker to EntityId("unpublished-defender")),
                ),
            )
        }

        failure.message shouldBe "Structured action changed its action actor"
    }

    test("unsupported registered certificates fail closed") {
        val action = LegalAction(
            action = DeclareAttackers(player, emptyMap()),
            actionType = "DeclareAttackers",
            description = "attack",
        )

        val failure = shouldThrow<UnsupportedPathFailure> {
            AttackDeclarationDomainSubmission.requireSupported(action)
        }

        failure.diagnostics.single().semanticCode shouldBe "ATTACK_DECLARATION_DOMAIN_UNSUPPORTED"
    }
})

private val player = EntityId("player")
private val attacker = EntityId("attacker")
private val defender = EntityId("defender")

private fun legalAction(): LegalAction = LegalAction(
    action = DeclareAttackers(player, emptyMap()),
    actionType = "DeclareAttackers",
    description = "attack",
    attackDeclarationDomain = RulesAttackDeclarationDomain(
        attackerOrder = listOf(attacker),
        defenderOrder = listOf(defender),
        attackerToDefenders = mapOf(attacker to listOf(defender)),
        mandatoryAttackers = emptyList(),
        canDeclareZeroAttackers = true,
        maxAttackers = 1,
        coAttackerRequirements = emptyMap(),
        bandConstraints = RulesAttackBandConstraints(
            bandingAttackersByDefender = emptyMap(),
            nonBandingAttackersByDefender = mapOf(defender to listOf(attacker)),
        ),
    ),
    attackDeclarationDomainSupport = AttackDeclarationDomainSupport.SUPPORTED,
)
