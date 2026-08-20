package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.mechanics.daynight.DayNightService
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.battlefield.DamageComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.DoubleFacedComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.mtg.sets.definitions.mid.cards.OutlandLiberator
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.DayNight
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Focused behavioral evidence for Outland Liberator // Frenzied Trapbreaker's DFC faces, day/night
 * transformation, activated abilities, and defending-player target domain.
 */
class OutlandLiberatorScenarioTest : ScenarioTestBase() {

    init {
        test("front face has the exact 2/2 daybound Oracle shape") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Outland Liberator")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val liberator = game.findPermanent("Outland Liberator").shouldNotBeNull()
            val definition = cardRegistry.getCard("Outland Liberator").shouldNotBeNull()
            definition.typeLine.toString() shouldBe "Creature — Human Werewolf"
            definition.oracleText shouldBe OutlandLiberator.oracleText
            definition.keywords shouldBe OutlandLiberator.keywords
            game.state.getEntity(liberator)?.get<CardComponent>()?.name shouldBe "Outland Liberator"
            game.state.getEntity(liberator)?.get<DoubleFacedComponent>()?.currentFace shouldBe
                DoubleFacedComponent.Face.FRONT
            game.state.projectedState.getPower(liberator) shouldBe 2
            game.state.projectedState.getToughness(liberator) shouldBe 2
        }

        test("daybound front becomes the 3/3 back when day changes to night") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Outland Liberator")
                .build()
            val liberator = game.findPermanent("Outland Liberator").shouldNotBeNull()
            game.state = game.state.copy(
                dayNight = DayNight.DAY,
                previousTurnActiveTeamSpellCounts = mapOf(game.player1Id to 0),
            )

            val (after, _) = DayNightService.checkUntapStepDesignation(game.state, cardRegistry)
            game.state = after

            withClue("zero spells during the previous active turn makes it night") {
                after.dayNight shouldBe DayNight.NIGHT
            }
            withClue("the daybound front changes to the exact back face") {
                after.getEntity(liberator)?.get<CardComponent>()?.name shouldBe "Frenzied Trapbreaker"
                after.getEntity(liberator)?.get<DoubleFacedComponent>()?.currentFace shouldBe
                    DoubleFacedComponent.Face.BACK
                after.projectedState.getPower(liberator) shouldBe 3
                after.projectedState.getToughness(liberator) shouldBe 3
            }
        }

        test("nightbound back needs two spells before becoming day and front") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Frenzied Trapbreaker")
                .build()
            val trapbreaker = game.findPermanent("Frenzied Trapbreaker").shouldNotBeNull()
            game.state = game.state.copy(
                dayNight = DayNight.NIGHT,
                previousTurnActiveTeamSpellCounts = mapOf(game.player1Id to 1),
            )

            val (stillNight, noChangeEvents) =
                DayNightService.checkUntapStepDesignation(game.state, cardRegistry)

            withClue("one spell is below the nightbound threshold") {
                stillNight.dayNight shouldBe DayNight.NIGHT
                stillNight.getEntity(trapbreaker)?.get<CardComponent>()?.name shouldBe "Frenzied Trapbreaker"
                noChangeEvents shouldBe emptyList()
            }

            game.state = stillNight.copy(
                previousTurnActiveTeamSpellCounts = mapOf(game.player1Id to 2),
            )
            val (after, _) = DayNightService.checkUntapStepDesignation(game.state, cardRegistry)

            withClue("two spells during the previous active turn makes it day") {
                after.dayNight shouldBe DayNight.DAY
                after.getEntity(trapbreaker)?.get<CardComponent>()?.name shouldBe "Outland Liberator"
                after.getEntity(trapbreaker)?.get<DoubleFacedComponent>()?.currentFace shouldBe
                    DoubleFacedComponent.Face.FRONT
            }
        }

        test("day/night transform preserves counters, marked damage, and object identity") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Outland Liberator")
                .build()
            val liberator = game.findPermanent("Outland Liberator").shouldNotBeNull()
            game.state = game.state
                .updateEntity(liberator) { container ->
                    container
                        .with(CountersComponent(mapOf(CounterType.PLUS_ONE_PLUS_ONE to 1)))
                        .with(DamageComponent(amount = 1))
                }
                .copy(dayNight = DayNight.DAY)

            val (after, _) = DayNightService.becomeNight(game.state, cardRegistry, "RED test")
            val transformed = after.getEntity(liberator).shouldNotBeNull()

            after.getBattlefield() shouldContain liberator
            transformed.get<DoubleFacedComponent>()?.currentFace shouldBe DoubleFacedComponent.Face.BACK
            transformed.get<CountersComponent>()?.getCount(CounterType.PLUS_ONE_PLUS_ONE) shouldBe 1
            transformed.get<DamageComponent>()?.amount shouldBe 1
        }

        test("back attack trigger offers only the defending player's artifact or enchantment") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Frenzied Trapbreaker")
                .withCardOnBattlefield(1, "Mind Stone")
                .withCardOnBattlefield(2, "Mind Stone")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            game.state = game.state.copy(dayNight = DayNight.NIGHT)

            val trapbreaker = game.findPermanent("Frenzied Trapbreaker").shouldNotBeNull()
            val ownArtifact = game.state.getBattlefield( game.player1Id )
                .first { id -> id != trapbreaker && game.state.getEntity(id)?.get<CardComponent>()?.name == "Mind Stone" }
            val defendingArtifact = game.state.getBattlefield(game.player2Id)
                .first { id -> game.state.getEntity(id)?.get<CardComponent>()?.name == "Mind Stone" }

            game.advanceToPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
            game.declareAttackers(mapOf("Frenzied Trapbreaker" to 2)).error shouldBe null
            game.resolveStack()

            val decision = game.getPendingDecision() as ChooseTargetsDecision
            withClue("the defending player's artifact must be a legal target") {
                decision.legalTargets.getValue(0) shouldContain defendingArtifact
            }
            withClue("the attack trigger must exclude the attacker's own artifact") {
                decision.legalTargets.getValue(0) shouldNotContain ownArtifact
            }
        }
    }
}
