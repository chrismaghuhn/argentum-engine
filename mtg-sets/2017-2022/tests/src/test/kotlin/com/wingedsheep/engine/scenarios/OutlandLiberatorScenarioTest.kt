package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.mechanics.daynight.DayNightService
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.battlefield.DamageComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.DoubleFacedComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.DayNight
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.daybound
import com.wingedsheep.sdk.dsl.nightbound
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetObject
import com.wingedsheep.sdk.scripting.targets.TargetPermanent
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * RED acceptance matrix for Outland Liberator // Frenzied Trapbreaker (MID #190).
 *
 * The local definition is deliberately test-only: the production card is not present yet. This
 * keeps the RED gate executable while asserting the exact pair's Oracle shape, day/night
 * thresholds, face identity, and transform persistence. The final test is intentionally expected
 * to expose any missing generic defending-player target context; the card code must not weaken that
 * target domain to make the test pass.
 */
class OutlandLiberatorScenarioTest : ScenarioTestBase() {

    private val outlandLiberatorFront = card("Outland Liberator") {
        manaCost = "{1}{G}"
        colorIdentity = "G"
        typeLine = "Creature — Human Werewolf"
        power = 2
        toughness = 2
        oracleText = "{1}, Sacrifice this creature: Destroy target artifact or enchantment.\n" +
            "Daybound (If a player casts no spells during their own turn, it becomes night next turn.)"

        daybound()
        activatedAbility {
            cost = Costs.Composite(Costs.Mana("{1}"), Costs.SacrificeSelf)
            val target = target(
                "target artifact or enchantment",
                TargetPermanent(filter = TargetFilter.ArtifactOrEnchantment),
            )
            effect = Effects.Destroy(target)
        }
    }

    private val frenziedTrapbreaker = card("Frenzied Trapbreaker") {
        manaCost = ""
        colorIdentity = "G"
        colorIndicator = "G"
        typeLine = "Creature — Werewolf"
        power = 3
        toughness = 3
        oracleText = "{1}, Sacrifice this creature: Destroy target artifact or enchantment.\n" +
            "Whenever this creature attacks, destroy target artifact or enchantment defending player controls.\n" +
            "Nightbound (If a player casts at least two spells during their own turn, it becomes day next turn.)"

        nightbound()
        activatedAbility {
            cost = Costs.Composite(Costs.Mana("{1}"), Costs.SacrificeSelf)
            val target = target(
                "target artifact or enchantment",
                TargetPermanent(filter = TargetFilter.ArtifactOrEnchantment),
            )
            effect = Effects.Destroy(target)
        }
        triggeredAbility {
            trigger = Triggers.Attacks
            val target = target(
                "target artifact or enchantment defending player controls",
                TargetObject(
                    filter = TargetFilter(
                        baseFilter = GameObjectFilter.ArtifactOrEnchantment
                            .targetPlayerControls(EffectTarget.PlayerRef(Player.DefendingPlayer)),
                    ),
                ),
            )
            effect = Effects.Destroy(target)
        }
    }

    private val outlandLiberator: CardDefinition = CardDefinition.doubleFacedCreature(
        frontFace = outlandLiberatorFront,
        backFace = frenziedTrapbreaker,
    )

    init {
        cardRegistry.register(outlandLiberator)

        test("RED-01: front face has the exact 2/2 daybound Oracle shape") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Outland Liberator")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val liberator = game.findPermanent("Outland Liberator").shouldNotBeNull()
            val definition = cardRegistry.getCard("Outland Liberator").shouldNotBeNull()

            definition.typeLine shouldBe "Creature — Human Werewolf"
            definition.creatureStats?.power shouldBe 2
            definition.creatureStats?.toughness shouldBe 2
            definition.oracleText shouldBe outlandLiberatorFront.oracleText
            definition.keywords shouldBe outlandLiberatorFront.keywords
            game.state.getEntity(liberator)?.get<CardComponent>()?.name shouldBe "Outland Liberator"
            game.state.getEntity(liberator)?.get<DoubleFacedComponent>()?.currentFace shouldBe
                DoubleFacedComponent.Face.FRONT
            game.state.projectedState.getPower(liberator) shouldBe 2
            game.state.projectedState.getToughness(liberator) shouldBe 2
        }

        test("RED-02: daybound front becomes the 3/3 back when day changes to night") {
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

        test("RED-03: nightbound back needs two spells before becoming day and front") {
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

        test("RED-04: day/night transform preserves counters, marked damage, and object identity") {
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

        test("RED-05: back attack trigger offers only the defending player's artifact or enchantment") {
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

            game.passUntilPhase(Phase.PRECOMBAT_MAIN, Step.DECLARE_ATTACKERS)
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
