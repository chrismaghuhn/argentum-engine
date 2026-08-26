package com.wingedsheep.engine.multiplayer

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameInitializer
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * The shared active team may submit the combined attack through either teammate's input window.
 * Band ordinals therefore live for the whole combat, not only for one declaration call.
 */
class SharedTeamAttackBandIdentityTest : FunSpec({

    val bandingBear = CardDefinition.creature(
        name = "Shared Team Banding Bear",
        manaCost = ManaCost.ZERO,
        subtypes = setOf(Subtype("Bear")),
        power = 2,
        toughness = 2,
        keywords = setOf(Keyword.BANDING),
    )

    fun registry() = CardRegistry().also { it.register(bandingBear) }

    fun init2hg(): Pair<GameState, List<EntityId>> {
        val deck = Deck(cards = List(40) { bandingBear.name })
        val result = GameInitializer(registry()).initializeGame(
            GameConfig(
                format = Format.TwoHeadedGiant(),
                players = (1..4).map { PlayerConfig("Player $it", deck) },
                teams = listOf(listOf(0, 1), listOf(2, 3)),
                startingPlayerIndex = 0,
                skipMulligans = true,
            ),
        )
        return result.state to result.playerIds
    }

    fun GameState.withBandedBear(owner: EntityId): Pair<GameState, EntityId> {
        val id = EntityId("shared-team-band-${entities.size}")
        val container = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = bandingBear.name,
                name = bandingBear.name,
                manaCost = bandingBear.manaCost,
                typeLine = bandingBear.typeLine,
                baseStats = bandingBear.creatureStats,
                baseKeywords = bandingBear.keywords,
                ownerId = owner,
            ),
            OwnerComponent(owner),
            ControllerComponent(owner),
        )
        val next = withEntity(id, container).addToZone(ZoneKey(owner, Zone.BATTLEFIELD), id)
        return next to id
    }

    test("separate teammate declarations keep distinct combat-local band ordinals") {
        val (base, players) = init2hg()
        val (state1, first) = base.withBandedBear(players[0])
        val (state2, second) = state1.withBandedBear(players[0])
        val (state3, third) = state2.withBandedBear(players[1])
        val (state4, fourth) = state3.withBandedBear(players[1])
        val state = state4.copy(step = Step.DECLARE_ATTACKERS, phase = Phase.COMBAT)
            .withPriority(players[0])
        val processor = ActionProcessor(registry())

        val firstDeclaration = processor.process(
            state,
            DeclareAttackers(
                players[0],
                mapOf(first to players[2], second to players[2]),
                bands = listOf(setOf(first, second)),
            ),
        ).result
        check(firstDeclaration.isSuccess) { "first declaration failed: ${firstDeclaration.error}" }

        val secondDeclaration = processor.process(
            firstDeclaration.newState.withPriority(players[1]),
            DeclareAttackers(
                players[1],
                mapOf(third to players[3], fourth to players[3]),
                bands = listOf(setOf(third, fourth)),
            ),
        ).result
        check(secondDeclaration.isSuccess) { "second declaration failed: ${secondDeclaration.error}" }

        val bandIds = listOf(first, second, third, fourth).map { attacker ->
            secondDeclaration.newState.getEntity(attacker)
                ?.get<AttackingComponent>()?.bandId
        }
        bandIds.filterNotNull().toSet() shouldHaveSize 2
        bandIds.filterNotNull().toSet() shouldBe setOf("combat-band-0", "combat-band-1")
    }

    test("a multi-band ordinal range is rejected atomically when it would overflow") {
        val (base, players) = init2hg()
        val (state1, first) = base.withBandedBear(players[0])
        val (state2, second) = state1.withBandedBear(players[0])
        val (state3, third) = state2.withBandedBear(players[1])
        val (state4, fourth) = state3.withBandedBear(players[1])
        val state = state4.copy(step = Step.DECLARE_ATTACKERS, phase = Phase.COMBAT)
            .withPriority(players[0])
        val processor = ActionProcessor(registry())

        val firstDeclaration = processor.process(
            state,
            DeclareAttackers(
                players[0],
                mapOf(first to players[2], second to players[2]),
                bands = listOf(setOf(first, second)),
            ),
        ).result
        check(firstDeclaration.isSuccess) { "first declaration failed: ${firstDeclaration.error}" }

        val exhaustedBandId = "combat-band-${Long.MAX_VALUE}"
        val exhaustedState = firstDeclaration.newState
            .updateEntity(first) {
                it.with(it.get<AttackingComponent>()!!.copy(bandId = exhaustedBandId))
            }
            .updateEntity(second) {
                it.with(it.get<AttackingComponent>()!!.copy(bandId = exhaustedBandId))
            }
            .withPriority(players[1])
        val before = exhaustedState
        val rejected = processor.process(
            exhaustedState,
            DeclareAttackers(
                players[1],
                mapOf(third to players[3], fourth to players[3]),
                bands = listOf(setOf(third, fourth)),
            ),
        ).result

        rejected.isSuccess shouldBe false
        rejected.newState shouldBe before
    }
})
