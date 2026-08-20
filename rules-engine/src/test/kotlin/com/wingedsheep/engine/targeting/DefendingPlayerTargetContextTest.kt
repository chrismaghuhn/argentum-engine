package com.wingedsheep.engine.targeting

import com.wingedsheep.engine.core.AttackersDeclaredEvent
import com.wingedsheep.engine.core.DeclaredAttack
import com.wingedsheep.engine.event.TriggerContext
import com.wingedsheep.engine.event.TriggerDetector
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.legalactions.utils.TargetEnumerationUtils
import com.wingedsheep.engine.mechanics.combat.CombatDefenders
import com.wingedsheep.engine.state.components.battlefield.ProtectorComponent
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * The defending-player relation for an attack-trigger target filter is a declaration-time fact.
 * These tests intentionally exercise the generic filter/target paths without adding a card.
 */
class DefendingPlayerTargetContextTest : FunSpec({

    val artifact = CardDefinition.artifact(
        name = "Defending Context Artifact",
        manaCost = ManaCost.parse("{2}"),
    )

    val enchantment = card("Defending Context Enchantment") {
        manaCost = "{2}"
        typeLine = "Enchantment"
    }

    val walker = card("Defending Context Walker") {
        manaCost = "{2}{U}{U}"
        typeLine = "Planeswalker — Test"
        startingLoyalty = 4
    }

    val siege = card("Defending Context Siege") {
        manaCost = "{2}{B}{B}"
        typeLine = "Battle — Siege"
        startingDefense = 5
    }

    val watcher = card("Defending Context Watcher") {
        manaCost = "{2}{W}"
        typeLine = "Creature — Human Soldier"
        power = 2
        toughness = 2
        triggeredAbility {
            trigger = Triggers.Attacks
            val target = target(
                "target artifact or enchantment defending player controls",
                TargetObject(
                    filter = TargetFilter(
                        GameObjectFilter.ArtifactOrEnchantment.targetPlayerControls(
                            EffectTarget.PlayerRef(Player.DefendingPlayer)
                        )
                    )
                )
            )
            effect = Effects.Destroy(target)
        }
    }

    fun driver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + artifact + enchantment + walker + siege + watcher)
        driver.initMultiplayer(
            decks = listOf(
                Deck.of("Forest" to 40),
                Deck.of("Forest" to 40),
                Deck.of("Forest" to 40),
                Deck.of("Forest" to 40),
            ),
            skipMulligans = true,
            startingPlayer = 0,
        )
        return driver
    }

    fun requirement(): TargetObject = TargetObject(
        filter = TargetFilter(
            GameObjectFilter.ArtifactOrEnchantment.targetPlayerControls(
                EffectTarget.PlayerRef(Player.DefendingPlayer)
            )
        )
    )

    test("normal attack context scopes TargetFinder to the declared defending player") {
        val driver = driver()
        val attacker = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val defendingArtifact = driver.putPermanentOnBattlefield(driver.player2, artifact.name)
        driver.replaceState(driver.state.updateEntity(attacker) {
            it.with(AttackingComponent(driver.player2))
        })

        val context = PredicateContext(
            controllerId = driver.player1,
            sourceId = attacker,
            triggeringEntityId = attacker,
            defendingPlayerId = driver.player2,
        )

        TargetFinder().findLegalTargets(
            state = driver.state,
            requirement = requirement(),
            controllerId = driver.player1,
            sourceId = attacker,
            triggeringEntityId = attacker,
            pipelineContext = context,
        ) shouldBe listOf(defendingArtifact)

        TargetEnumerationUtils(PredicateEvaluator()).findValidTargets(
            state = driver.state,
            playerId = driver.player1,
            requirement = requirement(),
            sourceId = attacker,
            predicateContext = context,
        ) shouldBe listOf(defendingArtifact)
    }

    test("generic attack detection carries the declared defender into the trigger context") {
        val driver = driver()
        val watcherId = driver.putCreatureOnBattlefield(driver.player1, watcher.name)
        val event = AttackersDeclaredEvent(
            attackers = listOf(watcherId),
            attackingPlayerId = driver.player1,
            declaredAttacks = listOf(DeclaredAttack(watcherId, driver.player2, driver.player2)),
        )

        val triggers = TriggerDetector(driver.cardRegistry)
            .detectTriggers(driver.state, listOf(event))
            .filter { it.sourceId == watcherId }

        triggers shouldHaveSize 1
        triggers.single().triggerContext.triggeringEntityId shouldBe watcherId
        triggers.single().triggerContext.triggeringPlayerId shouldBe driver.player1
        triggers.single().triggerContext.defendingPlayerId shouldBe driver.player2
    }

    test("planeswalker and battle declarations use controller and protector respectively") {
        val driver = driver()
        val players = driver.state.turnOrder
        val planeswalker = driver.putPermanentOnBattlefield(driver.player2, walker.name)
        val battle = driver.putPermanentOnBattlefield(driver.player2, siege.name)
        driver.replaceState(driver.state.updateEntity(battle) {
            it.with(ProtectorComponent(players[2]))
        })

        CombatDefenders.defendingPlayerOf(driver.state, planeswalker) shouldBe driver.player2
        CombatDefenders.defendingPlayerOf(driver.state, battle) shouldBe players[2]
    }

    test("declared attack snapshots survive attacker removal and preserve multiplayer identity") {
        val driver = driver()
        val attacker = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val playerC = driver.state.turnOrder[2]
        val event = AttackersDeclaredEvent(
            attackers = listOf(attacker),
            attackingPlayerId = driver.player1,
            declaredAttacks = listOf(DeclaredAttack(attacker, EntityId.of("planeswalker"), playerC)),
        )
        driver.replaceState(driver.state.removeEntity(attacker))

        val context = TriggerContext.forDeclaredAttack(event, attacker)
        context.defendingPlayerId shouldBe playerC
        driver.state.hasEntity(attacker) shouldBe false
    }

    test("unknown historical attack context fails closed and serializes deterministically") {
        val attacker = EntityId.of("attacker")
        val event = AttackersDeclaredEvent(
            attackers = listOf(attacker),
            attackingPlayerId = EntityId.of("player-a"),
        )

        TriggerContext.forDeclaredAttack(event, attacker).defendingPlayerId shouldBe null

        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(AttackersDeclaredEvent.serializer(), event)
        json.decodeFromString(AttackersDeclaredEvent.serializer(), encoded) shouldBe event
    }
})
