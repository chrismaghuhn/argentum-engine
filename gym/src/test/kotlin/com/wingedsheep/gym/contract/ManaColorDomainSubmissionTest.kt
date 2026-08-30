package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ManaColorDomainSubmissionTest : FunSpec({
    test("accepts a color from the registered domain") {
        ManaColorDomainSubmission.requireWithinRegisteredDomain(
            legalAction(),
            submitted(Color.WHITE),
        )
    }

    test("rejects a color outside the registered domain") {
        val failure = shouldThrow<IllegalArgumentException> {
            ManaColorDomainSubmission.requireWithinRegisteredDomain(legalAction(), submitted(Color.BLUE))
        }

        failure.message shouldBe "Mana-color choice BLUE is outside the registered domain: [WHITE, RED]"
    }

    test("rejects a missing choice for a required domain") {
        shouldThrow<IllegalArgumentException> {
            ManaColorDomainSubmission.requireWithinRegisteredDomain(legalAction(), submitted(null))
        }
    }

    test("rejects an injected choice when the action does not require one") {
        shouldThrow<IllegalArgumentException> {
            ManaColorDomainSubmission.requireWithinRegisteredDomain(
                legalAction(requiresChoice = false, colors = null),
                submitted(Color.WHITE),
            )
        }
    }

    test("rejects duplicate registered colors") {
        shouldThrow<IllegalArgumentException> {
            ManaColorDomainSubmission.requireSupported(
                legalAction(colors = listOf(Color.WHITE, Color.WHITE)),
            )
        }
    }

    test("rejects a registered domain that drifted from the current candidate") {
        shouldThrow<IllegalArgumentException> {
            ManaColorDomainSubmission.requireCurrentDomain(
                legalAction(),
                legalAction(colors = listOf(Color.WHITE)),
            )
        }
    }
})

private val player = EntityId("player-0")
private val source = EntityId("source-0")

private fun legalAction(
    requiresChoice: Boolean = true,
    colors: List<Color>? = listOf(Color.WHITE, Color.RED),
): LegalAction = LegalAction(
    action = ActivateAbility(player, source, AbilityId("mana-choice")),
    actionType = "ActivateAbility",
    description = "Choose a mana color",
    requiresManaColorChoice = requiresChoice,
    availableManaColors = colors,
)

private fun submitted(color: Color?): ActivateAbility = ActivateAbility(
    playerId = player,
    sourceId = source,
    abilityId = AbilityId("mana-choice"),
    manaColorChoice = color,
)
