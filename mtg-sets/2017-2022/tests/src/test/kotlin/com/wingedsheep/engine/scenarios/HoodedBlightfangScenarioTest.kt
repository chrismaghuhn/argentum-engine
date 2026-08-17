package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.events.DamageType
import com.wingedsheep.sdk.scripting.events.RecipientFilter
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Hooded Blightfang — M21 #104, {2}{B}, 1/4.
 *
 * Current Scryfall Oracle:
 * - Deathtouch.
 * - Whenever a creature you control with deathtouch attacks, each opponent loses 1 life and you
 *   gain 1 life.
 * - Whenever a creature you control with deathtouch deals damage to a planeswalker, destroy that
 *   planeswalker.
 *
 * This is intentionally a RED characterization while the production card is still absent. The
 * second trigger uses only existing DSL vocabulary. Its source filter matches the damage source,
 * but the effect must resolve the damage recipient (the planeswalker), not the source creature.
 * Current DamageTriggerDetector source-filtered observer context exposes the former as
 * EffectTarget.TriggeringEntity, so the final assertion should fail until a reusable recipient
 * reference is available. No card-specific workaround belongs here.
 */
class HoodedBlightfangScenarioTest : FunSpec({

    val hoodedBlightfang = card("Hooded Blightfang") {
        manaCost = "{2}{B}"
        colorIdentity = "B"
        typeLine = "Creature — Snake"
        power = 1
        toughness = 4
        oracleText = "Deathtouch\n" +
            "Whenever a creature you control with deathtouch attacks, each opponent loses 1 life " +
            "and you gain 1 life.\n" +
            "Whenever a creature you control with deathtouch deals damage to a planeswalker, " +
            "destroy that planeswalker."

        keywords(Keyword.DEATHTOUCH)

        triggeredAbility {
            trigger = Triggers.attacks(
                filter = GameObjectFilter.Creature.youControl().withKeyword(Keyword.DEATHTOUCH),
                binding = TriggerBinding.ANY,
            )
            effect = Effects.Composite(
                Effects.LoseLife(1, EffectTarget.PlayerRef(Player.EachOpponent)),
                Effects.GainLife(1),
            )
        }

        triggeredAbility {
            trigger = Triggers.dealsDamage(
                damageType = DamageType.Any,
                recipient = RecipientFilter.Matching(GameObjectFilter.Planeswalker),
                sourceFilter = GameObjectFilter.Creature.youControl().withKeyword(Keyword.DEATHTOUCH),
                binding = TriggerBinding.ANY,
            )
            effect = Effects.Destroy(EffectTarget.TriggeringEntity)
        }
    }

    val deathtouchAttacker = card("Hooded Deathtouch Attacker") {
        manaCost = "{0}"
        typeLine = "Creature — Snake"
        power = 0
        toughness = 1
        oracleText = "Deathtouch"
        keywords(Keyword.DEATHTOUCH)
    }

    val damagingDeathtouchAttacker = card("Hooded Damaging Deathtouch Attacker") {
        manaCost = "{0}"
        typeLine = "Creature — Snake"
        power = 1
        toughness = 1
        oracleText = "Deathtouch"
        keywords(Keyword.DEATHTOUCH)
    }

    val plainAttacker = card("Hooded Plain Attacker") {
        manaCost = "{0}"
        typeLine = "Creature — Bear"
        power = 1
        toughness = 1
    }

    val testWalker = card("Hooded Test Walker") {
        manaCost = "{0}"
        typeLine = "Legendary Planeswalker — Tester"
        startingLoyalty = 5
        loyaltyAbility(1) { effect = Effects.GainLife(1) }
    }

    fun driver(): GameTestDriver = GameTestDriver().apply {
        registerCards(
            TestCards.all + listOf(
                hoodedBlightfang,
                deathtouchAttacker,
                damagingDeathtouchAttacker,
                plainAttacker,
                testWalker,
            )
        )
    }

    fun resolvePending(d: GameTestDriver, passes: Int = 16) {
        repeat(passes) {
            if (d.pendingDecision != null) d.autoResolveDecision() else d.bothPass()
        }
    }

    test("deathtouch creatures attacking drain every opponent and gain their controller life") {
        val d = driver()
        val players = d.initMultiplayer(
            decks = listOf(Deck.of("Forest" to 40), Deck.of("Forest" to 40), Deck.of("Forest" to 40)),
            skipMulligans = true,
            startingPlayer = 0,
        )
        val active = players[0]
        d.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val hooded = d.putCreatureOnBattlefield(active, "Hooded Blightfang")
        val attacker = d.putCreatureOnBattlefield(active, "Hooded Deathtouch Attacker")
        d.removeSummoningSickness(attacker)
        d.state.projectedState.hasKeyword(hooded, Keyword.DEATHTOUCH) shouldBe true

        val activeLife = d.getLifeTotal(active)
        val opponentOneLife = d.getLifeTotal(players[1])
        val opponentTwoLife = d.getLifeTotal(players[2])

        d.passPriorityUntil(Step.DECLARE_ATTACKERS)
        d.declareAttackers(active, listOf(attacker), players[1]).error shouldBe null
        resolvePending(d)

        d.getLifeTotal(active) shouldBe activeLife + 1
        d.getLifeTotal(players[1]) shouldBe opponentOneLife - 1
        d.getLifeTotal(players[2]) shouldBe opponentTwoLife - 1
    }

    test("a non-deathtouch attacker does not satisfy either deathtouch source filter") {
        val d = driver()
        d.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        val active = d.activePlayer!!
        val opponent = d.getOpponent(active)
        d.passPriorityUntil(Step.PRECOMBAT_MAIN)

        d.putCreatureOnBattlefield(active, "Hooded Blightfang")
        val attacker = d.putCreatureOnBattlefield(active, "Hooded Plain Attacker")
        d.removeSummoningSickness(attacker)
        val activeLife = d.getLifeTotal(active)
        val opponentLife = d.getLifeTotal(opponent)

        d.passPriorityUntil(Step.DECLARE_ATTACKERS)
        d.declareAttackers(active, listOf(attacker), opponent).error shouldBe null
        resolvePending(d)

        d.getLifeTotal(active) shouldBe activeLife
        d.getLifeTotal(opponent) shouldBe opponentLife - 1
    }

    test("deathtouch damage to a planeswalker destroys the recipient and leaves the source alive") {
        val d = driver()
        d.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        val active = d.activePlayer!!
        val opponent = d.getOpponent(active)
        d.passPriorityUntil(Step.PRECOMBAT_MAIN)

        d.putCreatureOnBattlefield(active, "Hooded Blightfang")
        val attacker = d.putCreatureOnBattlefield(active, "Hooded Damaging Deathtouch Attacker")
        val walker = d.putPermanentOnBattlefield(opponent, "Hooded Test Walker")
        d.replaceState(
            d.state.updateEntity(walker) { c ->
                c.with((c.get<CountersComponent>() ?: CountersComponent()).withAdded(CounterType.LOYALTY, 5))
            }
        )
        d.removeSummoningSickness(attacker)

        d.passPriorityUntil(Step.DECLARE_ATTACKERS)
        d.declareAttackers(active, mapOf(attacker to walker)).error shouldBe null
        resolvePending(d)

        d.state.getBattlefield().contains(attacker) shouldBe true
        d.state.getBattlefield().contains(walker) shouldBe false
    }

    test("damage from a non-deathtouch creature does not destroy a planeswalker") {
        val d = driver()
        d.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        val active = d.activePlayer!!
        val opponent = d.getOpponent(active)
        d.passPriorityUntil(Step.PRECOMBAT_MAIN)

        d.putCreatureOnBattlefield(active, "Hooded Blightfang")
        val attacker = d.putCreatureOnBattlefield(active, "Hooded Plain Attacker")
        val walker = d.putPermanentOnBattlefield(opponent, "Hooded Test Walker")
        d.replaceState(
            d.state.updateEntity(walker) { c ->
                c.with((c.get<CountersComponent>() ?: CountersComponent()).withAdded(CounterType.LOYALTY, 5))
            }
        )
        d.removeSummoningSickness(attacker)

        d.passPriorityUntil(Step.DECLARE_ATTACKERS)
        d.declareAttackers(active, mapOf(attacker to walker)).error shouldBe null
        resolvePending(d)

        d.state.getBattlefield().contains(walker) shouldBe true
        d.state.getBattlefield().contains(attacker) shouldBe true
    }
})
