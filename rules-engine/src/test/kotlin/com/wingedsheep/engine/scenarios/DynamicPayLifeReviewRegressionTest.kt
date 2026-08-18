package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.handlers.CostHandler
import com.wingedsheep.engine.handlers.DynamicAmountEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.continuations.ManaPaymentContinuationResumer
import com.wingedsheep.engine.mechanics.cost.CostAmountResolver
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.ContinuationFrame
import com.wingedsheep.engine.core.ManaSourceOption
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.MayPayManaSelectionContinuation
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.mechanics.mana.ManaPaymentWindow
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.handlers.effects.player.PayOrSufferExecutor
import com.wingedsheep.engine.mechanics.mana.ManaAbilitySideEffectExecutor
import com.wingedsheep.engine.mechanics.mana.ManaProduction
import com.wingedsheep.engine.mechanics.mana.ManaSolution
import com.wingedsheep.engine.mechanics.mana.ManaSource
import com.wingedsheep.engine.mechanics.mana.ManaPool
import com.wingedsheep.engine.handlers.effects.composite.payManaCostFromPool
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import com.wingedsheep.engine.state.components.identity.CommanderRegistryComponent
import com.wingedsheep.mtg.sets.definitions.ons.cards.FutureSight
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.DynamicAmounts
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.SelfAlternativeCost
import com.wingedsheep.sdk.scripting.conditions.Compare
import com.wingedsheep.sdk.scripting.conditions.ComparisonOperator
import com.wingedsheep.sdk.scripting.costs.CostAtom
import com.wingedsheep.sdk.scripting.costs.PayCost
import com.wingedsheep.sdk.scripting.effects.PayOrSufferEffect
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.engine.core.EffectResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Focused review regressions for atomic dynamic life-cost resolution and payment. */
class DynamicPayLifeReviewRegressionTest : FunSpec({

    val commander = card("Review Dynamic Life Commander") {
        manaCost = "{G}"
        colorIdentity = "G"
        typeLine = "Legendary Creature — Human"
        power = 1
        toughness = 1
    }

    val twoColorCommander = card("Review Two-Color Dynamic Life Commander") {
        manaCost = "{W}{U}"
        colorIdentity = "WU"
        typeLine = "Legendary Creature — Human"
        power = 2
        toughness = 2
    }

    val compositeAbility = card("Review Composite Dynamic Life Ability") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Composite(
                Costs.PayLife(DynamicAmounts.commanderColorIdentityCount()),
                Costs.PayLife(DynamicAmounts.commanderColorIdentityCount())
            )
            effect = Effects.DrawCards(1)
        }
    }

    val compositeAdditionalSpell = card("Review Composite Dynamic Life Spell") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        spell { effect = Effects.DrawCards(1) }
        additionalCost(
            Costs.additional.Composite(
                listOf(
                    Costs.additional.PayLife(DynamicAmounts.commanderColorIdentityCount()),
                    Costs.additional.PayLife(DynamicAmounts.commanderColorIdentityCount())
                )
            )
        )
    }

    val combinedAlternativeSpell = card("Review Combined Dynamic Life Spell") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        spell { effect = Effects.DrawCards(1) }
        additionalCost(Costs.additional.PayLife(DynamicAmounts.commanderColorIdentityCount()))
        selfAlternativeCost = SelfAlternativeCost(
            manaCost = ManaCost.parse("{0}"),
            additionalCosts = listOf(
                Costs.additional.PayLife(DynamicAmounts.commanderColorIdentityCount())
            )
        )
    }

    val topOfLibraryDynamicSpell = card("Review Top Dynamic Life Spell") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        spell { effect = Effects.DrawCards(1) }
        additionalCost(
            Costs.additional.Composite(
                listOf(
                    Costs.additional.PayLife(DynamicAmounts.commanderColorIdentityCount()),
                    Costs.additional.PayLife(DynamicAmounts.commanderColorIdentityCount()),
                )
            )
        )
    }

    val commandZoneDynamicSpell = card("Review Command Zone Dynamic Life Spell") {
        manaCost = "{0}"
        typeLine = "Legendary Creature — Human"
        power = 1
        toughness = 1
        spell { effect = Effects.DrawCards(1) }
        additionalCost(
            Costs.additional.Composite(
                listOf(
                    Costs.additional.PayLife(DynamicAmounts.commanderColorIdentityCount()),
                    Costs.additional.PayLife(DynamicAmounts.commanderColorIdentityCount()),
                )
            )
        )
    }

    val payOrSufferSource = card("Review Pay Or Suffer Life Source") {
        typeLine = "Creature — Human"
        power = 1
        toughness = 1
    }

    val autoTapSpell = card("Review Auto Tap Dynamic Life Spell") {
        manaCost = "{1}"
        typeLine = "Sorcery"
        spell { effect = Effects.DrawCards(1) }
    }

    val autoTapAbilityTarget = card("Review Auto Tap Dynamic Life Ability") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Mana("{1}")
            effect = Effects.DrawCards(1)
        }
    }

    val explicitPainfulAbilityTarget = card("Review Explicit Painful Dynamic Life Ability") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Composite(
                Costs.Mana("{1}"),
                Costs.PayLife(DynamicAmount.Fixed(1)),
            )
            effect = Effects.DrawCards(1)
        }
    }

    val dualManaSource = card("Review Dual Dynamic Life Mana Source") {
        typeLine = "Artifact"
        // The more expensive ability is deliberately declared first. Auto-tap must use the
        // ability represented by the solver's selected payment, not the first matching ability.
        activatedAbility {
            cost = Costs.Composite(
                Costs.Tap,
                Costs.PayLife(
                    com.wingedsheep.sdk.scripting.values.DynamicAmount.Add(
                        DynamicAmounts.commanderColorIdentityCount(),
                        com.wingedsheep.sdk.scripting.values.DynamicAmount.Fixed(1)
                    )
                )
            )
            effect = Effects.AddColorlessMana(1)
            manaAbility = true
        }
        activatedAbility {
            cost = Costs.Composite(
                Costs.Tap,
                Costs.PayLife(DynamicAmounts.commanderColorIdentityCount())
            )
            effect = Effects.AddColorlessMana(1)
            manaAbility = true
        }
    }

    val oneLifeManaSource = card("Review One-Life Dynamic Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Composite(
                Costs.Tap,
                Costs.PayLife(DynamicAmount.Fixed(1))
            )
            effect = Effects.AddColorlessMana(1)
            manaAbility = true
        }
    }

    val activationCostPainSource = card("Review Activation Cost Pain Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Composite(
                Costs.Mana("{1}"),
                Costs.Tap,
                Costs.PayLife(DynamicAmount.Fixed(1)),
            )
            effect = Effects.AddMana(Color.GREEN)
            manaAbility = true
        }
    }

    fun createDriver(
        initialLife: Int = 1,
        commanderNames: List<String> = listOf(commander.name, commander.name),
    ): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(
            TestCards.all + listOf(
                commander,
                twoColorCommander,
                compositeAbility,
                compositeAdditionalSpell,
                combinedAlternativeSpell,
                topOfLibraryDynamicSpell,
                commandZoneDynamicSpell,
                FutureSight,
                payOrSufferSource,
                autoTapSpell,
                autoTapAbilityTarget,
                dualManaSource,
                oneLifeManaSource,
                explicitPainfulAbilityTarget,
                activationCostPainSource,
            )
        )
        driver.initMultiplayer(
            decks = listOf(Deck.of("Forest" to 40), Deck.of("Forest" to 40)),
            format = Format.Commander(),
            commanders = commanderNames,
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        driver.setLifeTotal(driver.activePlayer!!, initialLife)
        return driver
    }

    fun activatedAction(driver: GameTestDriver, sourceId: com.wingedsheep.sdk.model.EntityId) =
        driver.legalActions(driver.activePlayer!!).firstOrNull { legalAction ->
            (legalAction.action as? com.wingedsheep.engine.core.ActivateAbility)?.sourceId == sourceId
        }

    fun castAction(driver: GameTestDriver, cardId: com.wingedsheep.sdk.model.EntityId) =
        driver.legalActions(driver.activePlayer!!).firstOrNull { legalAction ->
            (legalAction.action as? com.wingedsheep.engine.core.CastSpell)?.cardId == cardId
        }

    fun alternativeCastAction(driver: GameTestDriver, cardId: com.wingedsheep.sdk.model.EntityId) =
        driver.legalActions(driver.activePlayer!!).firstOrNull { legalAction ->
            (legalAction.action as? com.wingedsheep.engine.core.CastSpell)?.let {
                it.cardId == cardId && it.useAlternativeCost
            } == true
        }

    test("a composite activated cost checks all dynamic life atoms as one total") {
        val driver = createDriver(initialLife = 1)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, compositeAbility.name)
        val handler = CostHandler(driver.cardRegistry)
        val cost = driver.cardRegistry.getCard(compositeAbility.name)!!.activatedAbilities.single().cost

        handler.canPayAbilityCost(
            state = driver.state,
            cost = cost,
            sourceId = sourceId,
            controllerId = player,
            manaPool = ManaPool()
        ) shouldBe false
        handler.payAbilityCost(
            state = driver.state,
            cost = cost,
            sourceId = sourceId,
            controllerId = player,
            manaPool = ManaPool()
        ).success shouldBe false
        driver.getLifeTotal(player) shouldBe 1
        activatedAction(driver, sourceId) shouldBe null
        val abilityId = driver.cardRegistry.getCard(compositeAbility.name)!!
            .activatedAbilities.single().id
        driver.submit(ActivateAbility(player, sourceId, abilityId)).error.shouldNotBeNull()
    }

    test("a payable composite activated cost deducts the dynamic life total once") {
        val driver = createDriver(initialLife = 3)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, compositeAbility.name)
        val cost = driver.cardRegistry.getCard(compositeAbility.name)!!.activatedAbilities.single().cost
        val result = CostHandler(driver.cardRegistry).payAbilityCost(
            state = driver.state,
            cost = cost,
            sourceId = sourceId,
            controllerId = player,
            manaPool = ManaPool(),
        )

        result.success shouldBe true
        driver.getLifeTotal(player) shouldBe 3
        result.newState.shouldNotBeNull().lifeTotal(player) shouldBe 1
    }

    test("a composite spell additional cost checks all dynamic life atoms as one total") {
        val driver = createDriver(initialLife = 1)
        val player = driver.activePlayer!!
        val spellId = driver.putCardInHand(player, compositeAdditionalSpell.name)
        val additionalCost = driver.cardRegistry.getCard(compositeAdditionalSpell.name)!!.script.additionalCosts.single()
        val handler = CostHandler(driver.cardRegistry)

        handler.canPayAdditionalCost(
            state = driver.state,
            cost = additionalCost,
            controllerId = player,
            sourceId = spellId
        ) shouldBe false
        castAction(driver, spellId) shouldBe null
    }

    test("a payable composite spell additional cost deducts the dynamic life total once") {
        val driver = createDriver(initialLife = 3)
        val player = driver.activePlayer!!
        val spellId = driver.putCardInHand(player, compositeAdditionalSpell.name)
        val cast = castAction(driver, spellId).shouldNotBeNull()

        driver.submit(cast.action).error shouldBe null
        driver.getLifeTotal(player) shouldBe 1
    }

    test("a printed and alternative dynamic life cost is one total before offering or executing") {
        val driver = createDriver(initialLife = 1)
        val player = driver.activePlayer!!
        val spellId = driver.putCardInHand(player, combinedAlternativeSpell.name)

        alternativeCastAction(driver, spellId) shouldBe null
        driver.submit(
            com.wingedsheep.engine.core.CastSpell(
                playerId = player,
                cardId = spellId,
                useAlternativeCost = true,
            )
        ).error.shouldNotBeNull()
    }

    test("top-of-library spell enumeration checks the complete dynamic additional-cost total") {
        val driver = createDriver(initialLife = 1)
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, FutureSight.name)
        val spellId = driver.putCardOnTopOfLibrary(player, topOfLibraryDynamicSpell.name)

        castAction(driver, spellId) shouldBe null
    }

    test("command-zone spell enumeration checks the complete dynamic additional-cost total") {
        val driver = createDriver(initialLife = 1)
        val player = driver.activePlayer!!
        val spellId = driver.putCardInCommandZone(player, commandZoneDynamicSpell.name)
        driver.replaceState(
            driver.state.updateEntity(spellId) { it.with(CommanderComponent(player)) }
        )

        castAction(driver, spellId) shouldBe null
    }

    test("PayOrSuffer offers an exact-to-zero life payment") {
        val driver = createDriver(initialLife = 1)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, payOrSufferSource.name)

        val result = PayOrSufferExecutor(driver.cardRegistry).execute(
            state = driver.state,
            effect = PayOrSufferEffect(
                cost = PayCost.Atom(CostAtom.PayLife(1)),
                suffer = Effects.GainLife(1)
            ),
            context = EffectContext(sourceId = sourceId, controllerId = player)
        )

        val decision = result.pendingDecision.shouldNotBeNull()
        driver.getLifeTotal(player) shouldBe 1

        driver.replaceState(result.state)
        driver.submitDecision(player, YesNoResponse(decision.id, choice = true)).error shouldBe null
        driver.getLifeTotal(player) shouldBe 0
    }

    test("the generic dynamic evaluator fails closed instead of throwing for commander count") {
        val driver = createDriver(initialLife = 10)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, compositeAbility.name)
        val evaluator = DynamicAmountEvaluator()

        evaluator.evaluate(
            state = driver.state,
            amount = DynamicAmounts.commanderColorIdentityCount(),
            context = EffectContext(sourceId = sourceId, controllerId = player)
        ) shouldBe 0
    }

    test("auto-tap pays the dynamic life cost of the solver-selected same-color ability") {
        val driver = createDriver(initialLife = 2)
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, dualManaSource.name)
        val spellId = driver.putCardInHand(player, autoTapSpell.name)
        val cast = castAction(driver, spellId).shouldNotBeNull()

        driver.submit(cast.action).error shouldBe null
        driver.getLifeTotal(player) shouldBe 1
    }

    test("activated-ability auto-tap pays the selected dynamic life cost") {
        val driver = createDriver(initialLife = 2)
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, dualManaSource.name)
        val targetId = driver.putPermanentOnBattlefield(player, autoTapAbilityTarget.name)
        val activation = activatedAction(driver, targetId).shouldNotBeNull()

        driver.submit(activation.action).error shouldBe null
        driver.getLifeTotal(player) shouldBe 1
    }

    test("explicit activation executes the exact selected painful mana ability") {
        val driver = createDriver(initialLife = 2)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, oneLifeManaSource.name)
        val targetId = driver.putPermanentOnBattlefield(player, autoTapAbilityTarget.name)
        val abilityId = driver.cardRegistry.getCard(autoTapAbilityTarget.name)!!
            .activatedAbilities.single().id

        val result = driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = targetId,
                abilityId = abilityId,
                paymentStrategy = PaymentStrategy.Explicit(listOf(sourceId)),
            )
        )

        result.error shouldBe null
        driver.getLifeTotal(player) shouldBe 1
        driver.state.getEntity(sourceId)!!
            .has<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe true
    }

    test("explicit activation atomically combines source and ability life costs") {
        val driver = createDriver(initialLife = 1)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, oneLifeManaSource.name)
        val targetId = driver.putPermanentOnBattlefield(player, explicitPainfulAbilityTarget.name)
        val abilityId = driver.cardRegistry.getCard(explicitPainfulAbilityTarget.name)!!
            .activatedAbilities.single().id

        val result = driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = targetId,
                abilityId = abilityId,
                paymentStrategy = PaymentStrategy.Explicit(listOf(sourceId)),
            )
        )

        result.error.shouldNotBeNull()
        driver.getLifeTotal(player) shouldBe 1
        driver.state.getEntity(sourceId)!!
            .has<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe false
        driver.stackSize shouldBe 0
    }

    test("auto-tap rolls back the tap when the selected dynamic life payment fails") {
        val driver = createDriver(initialLife = 0)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, dualManaSource.name)
        val selectedAbility = driver.cardRegistry.getCard(dualManaSource.name)!!
            .activatedAbilities.last()
        val source = ManaSource(
            entityId = sourceId,
            name = dualManaSource.name,
            producesColors = emptySet(),
            producesColorless = true,
            manaAbilityForColorless = selectedAbility,
        )
        val result = ManaAbilitySideEffectExecutor(driver.cardRegistry) { state, _, _ ->
            EffectResult.success(state)
        }.tapSourcesWithSideEffects(
            state = driver.state,
            solution = ManaSolution(
                sources = listOf(source),
                manaProduced = mapOf(
                    sourceId to ManaProduction(colorless = 1, manaAbility = selectedAbility),
                ),
            ),
            controllerId = player,
        )

        result.success shouldBe false
        result.state shouldBe driver.state
        result.events shouldBe emptyList()
        driver.state.getEntity(sourceId)!!.has<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe false
    }

    test("effect-level auto-tap pays the selected dynamic life cost") {
        val driver = createDriver(initialLife = 2)
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, dualManaSource.name)

        val result = payManaCostFromPool(
            state = driver.state,
            player = player,
            cost = ManaCost.parse("{1}"),
            cardRegistry = driver.cardRegistry,
        )

        result.error shouldBe null
        result.state.lifeTotal(player) shouldBe 1
    }

    test("auto-pay pays a production-less activation-cost source's dynamic life") {
        val driver = createDriver(initialLife = 2)
        val player = driver.activePlayer!!
        val filterSourceId = driver.putPermanentOnBattlefield(player, activationCostPainSource.name)
        val activationSourceId = driver.putPermanentOnBattlefield(player, oneLifeManaSource.name)

        val result = payManaCostFromPool(
            state = driver.state,
            player = player,
            cost = ManaCost.parse("{G}"),
            cardRegistry = driver.cardRegistry,
        )

        result.error shouldBe null
        result.state.lifeTotal(player) shouldBe 0
        result.state.getEntity(filterSourceId)!!
            .has<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe true
        result.state.getEntity(activationSourceId)!!
            .has<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe true
    }

    test("manual mana window pays the selected mana ability life cost before floating mana") {
        val driver = createDriver(initialLife = 1)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, oneLifeManaSource.name)
        val decision = ManaPaymentWindow.buildDecision(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{1}"),
            decisionId = "review-manual-window",
            prompt = "Pay {1}",
            context = DecisionContext(sourceId = sourceId, phase = DecisionPhase.RESOLUTION),
            canDecline = false,
            cardRegistry = driver.cardRegistry,
        )

        val option = decision.availableSources.single()
        option.manaAbilityId shouldBe driver.cardRegistry.getCard(oneLifeManaSource.name)!!
            .activatedAbilities.single().id
        val result = ManaPaymentWindow.floatSelectedMana(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{1}"),
            response = ManaSourcesSelectedResponse(
                decisionId = decision.id,
                selectedSources = listOf(sourceId),
            ),
            availableSources = listOf(option),
            services = EngineServices(driver.cardRegistry),
        )

        result.paid shouldBe true
        result.state.lifeTotal(player) shouldBe 0
        result.state.getEntity(sourceId)!!.has<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe true
    }

    test("manual mana window rejects a production-less mana activation cost") {
        val driver = createDriver(initialLife = 2)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, activationCostPainSource.name)
        val abilityId = driver.cardRegistry.getCard(activationCostPainSource.name)!!
            .activatedAbilities.single().id
        val before = driver.state

        val result = ManaPaymentWindow.floatSelectedMana(
            state = before,
            playerId = player,
            cost = ManaCost.parse("{G}"),
            response = ManaSourcesSelectedResponse("review-manual-activation-cost", listOf(sourceId)),
            availableSources = listOf(
                ManaSourceOption(
                    entityId = sourceId,
                    name = activationCostPainSource.name,
                    producesColors = setOf(Color.GREEN),
                    producesColorless = false,
                    manaAbilityId = abilityId,
                )
            ),
            services = EngineServices(driver.cardRegistry),
        )

        result.paid shouldBe false
        result.state shouldBe before
        result.state.lifeTotal(player) shouldBe 2
        result.state.getEntity(sourceId)!!
            .has<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe false
    }

    test("manual mana window fails closed and rolls back when dynamic life is insufficient") {
        val driver = createDriver(initialLife = 1)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, oneLifeManaSource.name)
        val option = ManaSourceOption(
            entityId = sourceId,
            name = oneLifeManaSource.name,
            producesColors = emptySet(),
            producesColorless = true,
            manaAbilityId = driver.cardRegistry.getCard(oneLifeManaSource.name)!!
                .activatedAbilities.single().id,
        )
        driver.setLifeTotal(player, 0)
        val before = driver.state

        val result = ManaPaymentWindow.floatSelectedMana(
            state = before,
            playerId = player,
            cost = ManaCost.parse("{1}"),
            response = ManaSourcesSelectedResponse("review-manual-insufficient", listOf(sourceId)),
            availableSources = listOf(option),
            services = EngineServices(driver.cardRegistry),
        )

        result.paid shouldBe false
        result.state shouldBe before
        result.state.getEntity(sourceId)!!.has<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe false
    }

    test("manual mana continuation pays the selected mana ability life cost before spending mana") {
        val driver = createDriver(initialLife = 1)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, oneLifeManaSource.name)
        val continuation = MayPayManaSelectionContinuation(
            decisionId = "review-manual-resume",
            playerId = player,
            sourceName = oneLifeManaSource.name,
            manaCost = ManaCost.parse("{1}"),
            effect = Effects.DrawCards(1),
            effectContext = EffectContext(sourceId = sourceId, controllerId = player),
            availableSources = listOf(
                ManaSourceOption(
                    entityId = sourceId,
                    name = oneLifeManaSource.name,
                    producesColors = emptySet(),
                    producesColorless = true,
                    manaAbilityId = driver.cardRegistry.getCard(oneLifeManaSource.name)!!
                        .activatedAbilities.single().id,
                )
            ),
            autoPaySuggestion = emptyList(),
        )

        val result = ManaPaymentContinuationResumer(EngineServices(driver.cardRegistry))
            .resumeMayPayManaSelection(
                state = driver.state,
                continuation = continuation,
                response = ManaSourcesSelectedResponse(
                    decisionId = continuation.decisionId,
                    selectedSources = listOf(sourceId),
                ),
                checkForMore = { state, events -> ExecutionResult.success(state, events) },
            )

        result.error shouldBe null
        result.state.lifeTotal(player) shouldBe 0
        result.state.getEntity(sourceId)!!.has<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe true
    }

    test("manual mana continuation rejects a production-less mana activation cost") {
        val driver = createDriver(initialLife = 2)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, activationCostPainSource.name)
        val abilityId = driver.cardRegistry.getCard(activationCostPainSource.name)!!
            .activatedAbilities.single().id
        val continuation = MayPayManaSelectionContinuation(
            decisionId = "review-activation-cost-resume",
            playerId = player,
            sourceName = activationCostPainSource.name,
            manaCost = ManaCost.parse("{G}"),
            effect = Effects.DrawCards(1),
            effectContext = EffectContext(sourceId = sourceId, controllerId = player),
            availableSources = listOf(
                ManaSourceOption(
                    entityId = sourceId,
                    name = activationCostPainSource.name,
                    producesColors = setOf(Color.GREEN),
                    producesColorless = false,
                    manaAbilityId = abilityId,
                )
            ),
            autoPaySuggestion = emptyList(),
        )
        val before = driver.state

        val result = ManaPaymentContinuationResumer(EngineServices(driver.cardRegistry))
            .resumeMayPayManaSelection(
                state = before,
                continuation = continuation,
                response = ManaSourcesSelectedResponse(
                    decisionId = continuation.decisionId,
                    selectedSources = listOf(sourceId),
                ),
                checkForMore = { state, events -> ExecutionResult.success(state, events) },
            )

        result.error.shouldNotBeNull()
        result.state shouldBe before
        result.state.lifeTotal(player) shouldBe 2
        result.state.getEntity(sourceId)!!
            .has<com.wingedsheep.engine.state.components.battlefield.TappedComponent>() shouldBe false
    }

    test("manual mana continuation preserves exact mana ability identity through serialization") {
        val driver = createDriver(initialLife = 1)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, oneLifeManaSource.name)
        val abilityId = driver.cardRegistry.getCard(oneLifeManaSource.name)!!
            .activatedAbilities.single().id
        val original = MayPayManaSelectionContinuation(
            decisionId = "review-manual-serialization",
            playerId = player,
            sourceName = oneLifeManaSource.name,
            manaCost = ManaCost.parse("{1}"),
            effect = Effects.DrawCards(1),
            effectContext = EffectContext(sourceId = sourceId, controllerId = player),
            availableSources = listOf(
                ManaSourceOption(
                    entityId = sourceId,
                    name = oneLifeManaSource.name,
                    producesColors = emptySet(),
                    producesColorless = true,
                    manaAbilityId = abilityId,
                )
            ),
            autoPaySuggestion = emptyList(),
        )
        val json = Json { serializersModule = engineSerializersModule }

        val encoded = json.encodeToString(ContinuationFrame.serializer(), original)
        val decoded = json.decodeFromString(ContinuationFrame.serializer(), encoded)

        decoded shouldBe original
    }

    test("mana solver rejects two one-life sources when their aggregate life cost exceeds life") {
        val driver = createDriver(initialLife = 1)
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, oneLifeManaSource.name)
        driver.putPermanentOnBattlefield(player, oneLifeManaSource.name)

        ManaSolver(driver.cardRegistry).solve(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{2}"),
        ) shouldBe null
    }

    test("nested conditional commander amount resolves through arithmetic") {
        val driver = createDriver(initialLife = 10)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, compositeAbility.name)
        val amount = DynamicAmount.Add(
            DynamicAmount.Conditional(
                condition = Compare(
                    left = DynamicAmounts.commanderColorIdentityCount(),
                    operator = ComparisonOperator.EQ,
                    right = DynamicAmount.Fixed(1),
                ),
                ifTrue = DynamicAmount.Fixed(4),
                ifFalse = DynamicAmount.Fixed(9),
            ),
            DynamicAmount.Fixed(2),
        )

        CostAmountResolver.resolve(
            state = driver.state,
            amount = amount,
            sourceId = sourceId,
            controllerId = player,
            cardRegistry = driver.cardRegistry,
        ) shouldBe 6
    }

    test("nested CountPlayersWith commander amount resolves through arithmetic") {
        val driver = createDriver(initialLife = 10)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, compositeAbility.name)
        val amount = DynamicAmount.Add(
            DynamicAmount.CountPlayersWith(
                scope = Player.EachOpponent,
                condition = Compare(
                    left = DynamicAmounts.commanderColorIdentityCount(),
                    operator = ComparisonOperator.EQ,
                    right = DynamicAmount.Fixed(1),
                ),
            ),
            DynamicAmount.Fixed(1),
        )

        CostAmountResolver.resolve(
            state = driver.state,
            amount = amount,
            sourceId = sourceId,
            controllerId = player,
            cardRegistry = driver.cardRegistry,
        ) shouldBe 2
    }

    test("nested CountPlayersWith resolves commander identity under each candidate controller") {
        val driver = createDriver(
            initialLife = 10,
            commanderNames = listOf(commander.name, twoColorCommander.name),
        )
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, compositeAbility.name)
        val amount = DynamicAmount.Add(
            DynamicAmount.CountPlayersWith(
                scope = Player.EachOpponent,
                condition = Compare(
                    left = DynamicAmounts.commanderColorIdentityCount(),
                    operator = ComparisonOperator.EQ,
                    right = DynamicAmount.Fixed(2),
                ),
            ),
            DynamicAmount.Fixed(1),
        )

        CostAmountResolver.resolve(
            state = driver.state,
            amount = amount,
            sourceId = sourceId,
            controllerId = player,
            cardRegistry = driver.cardRegistry,
        ) shouldBe 2
    }

    test("nested commander amount fails closed when the controller has no commander") {
        val driver = createDriver(initialLife = 10)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, compositeAbility.name)
        val stateWithoutCommander = driver.state.updateEntity(player) {
            it.without<CommanderRegistryComponent>()
        }
        val amount = DynamicAmount.Conditional(
            condition = Compare(
                left = DynamicAmounts.commanderColorIdentityCount(),
                operator = ComparisonOperator.EQ,
                right = DynamicAmount.Fixed(0),
            ),
            ifTrue = DynamicAmount.Fixed(7),
            ifFalse = DynamicAmount.Fixed(3),
        )

        CostAmountResolver.resolve(
            state = stateWithoutCommander,
            amount = amount,
            sourceId = sourceId,
            controllerId = player,
            cardRegistry = driver.cardRegistry,
        ) shouldBe null
    }
})
