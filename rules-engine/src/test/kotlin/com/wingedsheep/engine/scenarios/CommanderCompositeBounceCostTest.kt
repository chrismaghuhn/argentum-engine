package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Regression coverage for pre-resolved Commander bounces inside composite costs. */
class CommanderCompositeBounceCostTest : ScenarioTestBase() {

    private val compositeBounceHost = card("Composite Bounce Host") {
        manaCost = "{0}"
        typeLine = "Creature — Test"
        power = 2
        toughness = 2
        oracleText = "Return two permanents to their owners' hands: You gain 1 life."

        activatedAbility {
            cost = Costs.Composite(
                Costs.ReturnToHand(GameObjectFilter.Any),
                Costs.ReturnToHand(GameObjectFilter.Any),
            )
            effect = Effects.GainLife(1)
        }
    }

    private val compositeCommander = card("Composite Commander") {
        manaCost = "{0}"
        typeLine = "Creature — Test"
        power = 2
        toughness = 2
    }

    init {
        cardRegistry.register(listOf(compositeBounceHost, compositeCommander))
    }

    init {
        test("each composite ReturnToHand child consumes only its own pre-resolved bounce") {
            val game = scenario()
                .withPlayers("Caster", "Opponent")
                .withFormat(Format.Commander())
                .withCardOnBattlefield(1, compositeBounceHost.name)
                .withCardOnBattlefield(1, compositeCommander.name)
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withActivePlayer(1)
                .build()

            val sourceId = game.findPermanent(compositeBounceHost.name)!!
            val commanderId = game.findPermanent(compositeCommander.name)!!
            val normalId = game.findPermanent("Grizzly Bears")!!
            game.state = game.state.updateEntity(commanderId) {
                it.with(CommanderComponent(ownerId = game.player1Id))
            }

            val abilityId = cardRegistry.getCard(compositeBounceHost.name)!!
                .script.activatedAbilities.single().id
            val initial = game.execute(
                ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = sourceId,
                    abilityId = abilityId,
                    costPayment = AdditionalCostPayment(
                        bouncedPermanents = listOf(commanderId, normalId),
                    ),
                )
            )

            initial.pendingDecision.shouldBeInstanceOf<YesNoDecision>().playerId shouldBe game.player1Id
            game.state.getZone(ZoneKey(game.player1Id, Zone.BATTLEFIELD)) shouldBe
                listOf(sourceId, commanderId, normalId)

            val resumed = game.answerYesNo(choice = false)
            resumed.error shouldBe null
            game.state.getZone(ZoneKey(game.player1Id, Zone.HAND)).toSet() shouldBe
                setOf(commanderId, normalId)
            game.state.getZone(ZoneKey(game.player1Id, Zone.BATTLEFIELD)) shouldBe listOf(sourceId)
        }
    }
}
