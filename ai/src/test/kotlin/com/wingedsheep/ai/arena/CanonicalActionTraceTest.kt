package com.wingedsheep.ai.arena

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
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

    test("decision routing ids do not change a semantic response trace") {
        val first = SubmitDecision(player, YesNoResponse("decision-a", choice = true))
        val second = SubmitDecision(player, YesNoResponse("decision-b", choice = true))

        canonicalActionTrace(first) shouldBe canonicalActionTrace(second)
    }
})
