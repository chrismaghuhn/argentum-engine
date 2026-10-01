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
import com.wingedsheep.engine.core.Outcome
import io.kotest.matchers.shouldNotBe

/**
 * The shared active team may submit the combined attack through either teammate's input window.
 * Band ordinals therefore live for the whole combat, not only for one declaration call.
 *
 * The team has ONE combined attack (CR 805.10b): the declaration is recorded on both heads, so the
 * teammate can't add a second wave. Both heads' bands are therefore declared together, and the
 * combat-wide ordinal guard is exercised against an already-attacking band.
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

    test("a combined teammate declaration keeps distinct combat-local band ordinals") {
        val (base, players) = init2hg()
        val (state1, first) = base.withBandedBear(players[0])
        val (state2, second) = state1.withBandedBear(players[0])
        val (state3, third) = state2.withBandedBear(players[1])
        val (state4, fourth) = state3.withBandedBear(players[1])
        val state = state4.copy(step = Step.DECLARE_ATTACKERS, phase = Phase.COMBAT)
            .withPriority(players[1])
        val processor = ActionProcessor(registry())

        // The second head submits the team's one combined attack: a band of each head's creatures.
        val declaration = processor.process(
            state,
            DeclareAttackers(
                players[1],
                mapOf(
                    first to players[2], second to players[2],
                    third to players[3], fourth to players[3],
                ),
                bands = listOf(setOf(first, second), setOf(third, fourth)),
            ),
        ).result
        check((declaration.outcome is Outcome.Done)) { "combined declaration failed: ${declaration.error}" }

        val bandIds = listOf(first, second, third, fourth).map { attacker ->
            declaration.newState.getEntity(attacker)
                ?.get<AttackingComponent>()?.bandId
        }
        bandIds.filterNotNull().toSet() shouldHaveSize 2
        bandIds.filterNotNull().toSet() shouldBe setOf("combat-band-0", "combat-band-1")
        bandIds[0] shouldBe bandIds[1]
        bandIds[2] shouldBe bandIds[3]

        // No second declaration can open a fresh ordinal range this combat: the combined attack is
        // recorded on both heads (CR 805.10b).
        val secondWave = processor.process(
            declaration.newState.withPriority(players[0]),
            DeclareAttackers(players[0], emptyMap()),
        ).result
        secondWave.outcome shouldNotBe Outcome.Done
    }

    test("a multi-band ordinal range is rejected atomically when it would overflow") {
        val (base, players) = init2hg()
        val (state1, first) = base.withBandedBear(players[0])
        val (state2, second) = state1.withBandedBear(players[0])
        val (state3, third) = state2.withBandedBear(players[1])
        val (state4, fourth) = state3.withBandedBear(players[1])
        val processor = ActionProcessor(registry())

        // A band already in combat holding the last ordinal. It is placed directly: a second
        // declaration after the team's combined attack is refused outright (CR 805.10b), so that
        // refusal must not be what this test observes.
        val exhaustedBandId = "combat-band-${Long.MAX_VALUE}"
        val exhaustedState = state4
            .updateEntity(first) {
                it.with(AttackingComponent(defenderId = players[2], bandId = exhaustedBandId))
            }
            .updateEntity(second) {
                it.with(AttackingComponent(defenderId = players[2], bandId = exhaustedBandId))
            }
            .copy(step = Step.DECLARE_ATTACKERS, phase = Phase.COMBAT)
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

        rejected.outcome shouldNotBe Outcome.Done
        rejected.error shouldBe "Attack band ordinal space is exhausted"
        rejected.newState shouldBe before
    }
})
