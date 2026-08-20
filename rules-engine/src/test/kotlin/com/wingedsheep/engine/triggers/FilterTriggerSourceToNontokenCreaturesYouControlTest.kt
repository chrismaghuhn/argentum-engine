package com.wingedsheep.engine.triggers

import com.wingedsheep.engine.core.DamageDealtEvent
import com.wingedsheep.engine.core.DamageRecipientKind
import com.wingedsheep.engine.event.TriggerDetector
import com.wingedsheep.engine.handlers.effects.DamageUtils
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.EventPattern.OneOrMoreDealCombatDamageToPlayerEvent
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggerSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Batch combat-damage trigger scoped to "nontoken creatures you control" must exclude
 * token creatures from its source predicate even when they simultaneously deal combat damage.
 *
 * The regression guard also verifies that the batch context retains the matching source and its
 * paired player recipient rather than conflating either role with the observer.
 */
class FilterTriggerSourceToNontokenCreaturesYouControlTest : FunSpec({

    // Observer: "Whenever one or more nontoken creatures you control deal combat damage to a player"
    val observer = card("Nontoken Combat Damage Observer") {
        manaCost = "{0}"
        typeLine = "Creature — Human"
        power = 0
        toughness = 1
        triggeredAbility {
            trigger = TriggerSpec(
                OneOrMoreDealCombatDamageToPlayerEvent(
                    sourceFilter = GameObjectFilter.Creature.nontoken()
                ),
                TriggerBinding.ANY
            )
            effect = Effects.DrawCards(1)
        }
    }

    // Two attackers with identical characteristics; one will be marked as a token.
    val attacker = CardDefinition.creature(
        name = "Test Attacker",
        manaCost = ManaCost.parse("{G}"),
        subtypes = emptySet(),
        power = 2,
        toughness = 2
    )

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(observer, attacker))
        driver.initMirrorMatch(deck = Deck.of("Forest" to 20, "Mountain" to 20))
        return driver
    }

    fun detectorFor(driver: GameTestDriver): TriggerDetector =
        TriggerDetector(driver.cardRegistry)

    context("'nontoken creature you control' source predicate in combat-damage batch trigger") {

        /**
         * GIVEN player 1 controls an observer with a 'nontoken creature you control' batch trigger,
         *   AND player 1 controls a nontoken creature and a token creature with identical stats,
         * WHEN both creatures deal combat damage to the opposing player simultaneously,
         * THEN the trigger fires exactly once
         *  AND the nontoken creature is recorded as the triggering damage source
         *  AND no trigger instance is produced for the token creature.
         */
        test("fires exactly once with the nontoken creature recorded as the damage source when both nontoken and token deal combat damage to a player simultaneously") {
            val driver = createDriver()
            driver.putCreatureOnBattlefield(driver.player1, "Nontoken Combat Damage Observer")
            val nontokenId = driver.putCreatureOnBattlefield(driver.player1, "Test Attacker")
            val tokenId    = driver.putCreatureOnBattlefield(driver.player1, "Test Attacker")

            // Mark the second attacker as a token (same stats, same card, but flagged as token)
            driver.replaceState(
                driver.state.updateEntity(tokenId) {
                    it.with(TokenComponent)
                }
            )
            val nontokenSnapshot = DamageUtils.captureDamageEntitySnapshot(driver.state, nontokenId)
            val tokenSnapshot = DamageUtils.captureDamageEntitySnapshot(driver.state, tokenId)

            // Both attackers deal combat damage to the opposing player in the same damage step
            val events = listOf(
                DamageDealtEvent(
                    sourceId       = nontokenId,
                    targetId       = driver.player2,
                    amount         = 2,
                    isCombatDamage = true,
                    targetIsPlayer = true,
                    recipientKind  = DamageRecipientKind.PLAYER,
                    damageSourceLastKnownSnapshot = nontokenSnapshot,
                ),
                DamageDealtEvent(
                    sourceId       = tokenId,
                    targetId       = driver.player2,
                    amount         = 2,
                    isCombatDamage = true,
                    targetIsPlayer = true,
                    recipientKind  = DamageRecipientKind.PLAYER,
                    damageSourceLastKnownSnapshot = tokenSnapshot,
                )
            )

            val triggers = detectorFor(driver).detectTriggers(driver.state, events)

            val batchTriggers = triggers.filter {
                it.ability.trigger is OneOrMoreDealCombatDamageToPlayerEvent
            }

            // Batch collapses all sources into one trigger instance
            batchTriggers shouldHaveSize 1

            // The nontoken creature — not the token — must be recorded as the damage source,
            // while the recipient remains the player from that same damage event.
            val batchTrigger = batchTriggers.first()
            batchTrigger.triggerContext.triggeringEntityId shouldBe nontokenId
            batchTrigger.triggerContext.damageSourceEntityId shouldBe nontokenId
            batchTrigger.triggerContext.damageRecipientEntityId shouldBe driver.player2
            batchTrigger.triggerContext.damageRecipientKind shouldBe DamageRecipientKind.PLAYER
        }

        test("uses event-time source LKI for departed and same-id-reused sources in one batch") {
            val driver = createDriver()
            driver.putCreatureOnBattlefield(driver.player1, "Nontoken Combat Damage Observer")
            val reusedSourceId = driver.putCreatureOnBattlefield(driver.player1, "Test Attacker")
            driver.replaceState(
                driver.state.updateEntity(reusedSourceId) {
                    it.with(ControllerComponent(driver.player2))
                }
            )
            val currentStamp = driver.state.getEntity(reusedSourceId)
                ?.get<BattlefieldEntryTimestampComponent>()?.timestamp
                ?: error("test attacker did not receive an incarnation stamp")
            val departedSourceId = EntityId("departed-source")
            val creatureTypeLine = TypeLine(cardTypes = setOf(CardType.CREATURE))

            val events = listOf(
                DamageDealtEvent(
                    sourceId = reusedSourceId,
                    targetId = driver.player2,
                    amount = 2,
                    isCombatDamage = true,
                    targetIsPlayer = true,
                    recipientKind = DamageRecipientKind.PLAYER,
                    damageSourceLastKnownSnapshot = EntitySnapshot(
                        entityId = reusedSourceId,
                        controllerId = driver.player1,
                        typeLine = creatureTypeLine,
                        wasToken = true,
                        battlefieldEntryTimestamp = currentStamp + 1,
                    ),
                ),
                DamageDealtEvent(
                    sourceId = departedSourceId,
                    targetId = driver.player2,
                    amount = 2,
                    isCombatDamage = true,
                    targetIsPlayer = true,
                    recipientKind = DamageRecipientKind.PLAYER,
                    damageSourceLastKnownSnapshot = EntitySnapshot(
                        entityId = departedSourceId,
                        controllerId = driver.player1,
                        typeLine = creatureTypeLine,
                        wasToken = false,
                        battlefieldEntryTimestamp = 77L,
                    ),
                ),
            )

            val batchTriggers = detectorFor(driver).detectTriggers(driver.state, events).filter {
                it.ability.trigger is OneOrMoreDealCombatDamageToPlayerEvent
            }

            batchTriggers shouldHaveSize 1
            val batchTrigger = batchTriggers.single()
            batchTrigger.triggerContext.triggeringEntityId shouldBe departedSourceId
            batchTrigger.triggerContext.damageSourceEntityId shouldBe departedSourceId
            batchTrigger.triggerContext.damageRecipientEntityId shouldBe driver.player2
        }

        test("does not classify an unstamped source from the current same-id entity") {
            val driver = createDriver()
            driver.putCreatureOnBattlefield(driver.player1, "Nontoken Combat Damage Observer")
            val currentSourceId = driver.putCreatureOnBattlefield(driver.player1, "Test Attacker")

            val event = DamageDealtEvent(
                sourceId = currentSourceId,
                targetId = driver.player2,
                amount = 2,
                isCombatDamage = true,
                targetIsPlayer = true,
                recipientKind = DamageRecipientKind.PLAYER,
            )

            detectorFor(driver).detectTriggers(driver.state, listOf(event)).filter {
                it.ability.trigger is OneOrMoreDealCombatDamageToPlayerEvent
            } shouldHaveSize 0
        }
    }
})
