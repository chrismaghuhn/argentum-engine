package com.wingedsheep.ai.arena

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.core.DamageAssignmentResponse
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class CanonicalActionTraceTest : FunSpec({
    val player = EntityId("player")
    val card = EntityId("card")
    val target = EntityId("target")

    val cast = CastSpell(
        playerId = player,
        cardId = card,
        targets = listOf(ChosenTarget.Permanent(target)),
        xValue = 3,
        chosenModes = listOf(1),
        modeTargetsOrdered = listOf(listOf(ChosenTarget.Permanent(target))),
    )

    test("internal resume metadata does not change a semantic CastSpell trace") {
        val resumed = cast.copy(
            preResolvedZoneChangeIds = listOf(EntityId("already-moved")),
            preResolvedSneakAttackDefenderId = EntityId("defender"),
            preResolvedWebSlingReturnedManaValue = 5,
        )

        canonicalActionTrace(cast) shouldBe canonicalActionTrace(resumed)
    }

    test("internal resume metadata does not change a semantic ActivateAbility trace") {
        val ability = ActivateAbility(
            playerId = player,
            sourceId = card,
            abilityId = AbilityId("ability"),
        )
        val resumed = ability.copy(preResolvedZoneChangeIds = listOf(EntityId("already-moved")))

        canonicalActionTrace(ability) shouldBe canonicalActionTrace(resumed)
    }

    test("semantic cast choices change the trace") {
        canonicalActionTrace(cast.copy(xValue = 4)) shouldNotBe canonicalActionTrace(cast)
        canonicalActionTrace(
            cast.copy(targets = listOf(ChosenTarget.Permanent(EntityId("other-target"))))
        ) shouldNotBe canonicalActionTrace(cast)
        canonicalActionTrace(cast.copy(chosenModes = listOf(2))) shouldNotBe canonicalActionTrace(cast)
    }

    test("effective Crew identity changes the semantic trace") {
        val first = CrewVehicle(
            playerId = player,
            vehicleId = card,
            crewCreatures = listOf(target),
            crewAbilityKey = "crew-1"
        )
        val second = first.copy(crewAbilityKey = "crew-3")

        canonicalActionTrace(first) shouldNotBe canonicalActionTrace(second)
    }

    test("internal opponent target resume metadata does not change an ability trace") {
        val ability = ActivateAbility(
            playerId = player,
            sourceId = card,
            abilityId = AbilityId("ability"),
        )

        canonicalActionTrace(ability) shouldBe canonicalActionTrace(
            ability.copy(opponentTargetsChosen = true)
        )
    }

    test("map-valued semantic choices are independent of insertion order") {
        val firstTarget = EntityId("first-target")
        val secondTarget = EntityId("second-target")
        val first = cast.copy(
            damageDistribution = linkedMapOf(firstTarget to 1, secondTarget to 2),
        )
        val second = cast.copy(
            damageDistribution = linkedMapOf(secondTarget to 2, firstTarget to 1),
        )

        canonicalActionTrace(first) shouldBe canonicalActionTrace(second)

        val firstMode = cast.copy(
            modeDamageDistribution = linkedMapOf(
                2 to linkedMapOf(secondTarget to 2, firstTarget to 1),
                1 to linkedMapOf(firstTarget to 3, secondTarget to 4),
            ),
        )
        val secondMode = cast.copy(
            modeDamageDistribution = linkedMapOf(
                1 to linkedMapOf(secondTarget to 4, firstTarget to 3),
                2 to linkedMapOf(firstTarget to 1, secondTarget to 2),
            ),
        )
        canonicalActionTrace(firstMode) shouldBe canonicalActionTrace(secondMode)

        val firstResponse = DistributionResponse(
            decisionId = "decision-a",
            distribution = linkedMapOf(firstTarget to 1, secondTarget to 2),
        )
        val secondResponse = DistributionResponse(
            decisionId = "decision-b",
            distribution = linkedMapOf(secondTarget to 2, firstTarget to 1),
        )
        canonicalDecisionResponse(firstResponse) shouldBe canonicalDecisionResponse(secondResponse)

        val firstDamageResponse = DamageAssignmentResponse(
            decisionId = "decision-a",
            assignments = linkedMapOf(firstTarget to 1, secondTarget to 2),
        )
        val secondDamageResponse = DamageAssignmentResponse(
            decisionId = "decision-b",
            assignments = linkedMapOf(secondTarget to 2, firstTarget to 1),
        )
        canonicalDecisionResponse(firstDamageResponse) shouldBe canonicalDecisionResponse(secondDamageResponse)
    }

    test("decision routing ids do not change a semantic response trace") {
        val first = SubmitDecision(player, YesNoResponse("decision-a", choice = true))
        val second = SubmitDecision(player, YesNoResponse("decision-b", choice = true))

        canonicalActionTrace(first) shouldBe canonicalActionTrace(second)
    }
})
