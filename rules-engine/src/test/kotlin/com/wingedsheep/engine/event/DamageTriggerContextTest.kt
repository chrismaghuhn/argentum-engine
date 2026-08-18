package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.DamageDealtEvent
import com.wingedsheep.engine.core.DamageRecipientKind
import com.wingedsheep.engine.core.DamageRecipientKindSet
import com.wingedsheep.engine.core.ContinuationFrame
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.TriggeredAbilityContinuation
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.core.effectiveRecipientKind
import com.wingedsheep.engine.core.effectiveRecipientKinds
import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.DynamicAmountEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.effects.composite.ReflexiveTriggerEffectExecutor
import com.wingedsheep.engine.handlers.effects.DamageUtils
import com.wingedsheep.engine.handlers.effects.TargetResolutionUtils
import com.wingedsheep.engine.handlers.effects.zones.MoveToZoneEffectExecutor
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.predicates.CardPredicate
import com.wingedsheep.sdk.scripting.events.RecipientFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.values.EntityNumericProperty
import com.wingedsheep.sdk.scripting.values.EntityReference
import com.wingedsheep.sdk.scripting.values.contextScopedReferenceIn
import com.wingedsheep.sdk.scripting.effects.MoveToZoneEffect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class DamageTriggerContextTest : FunSpec({
    val json = Json {
        serializersModule = engineSerializersModule
        encodeDefaults = true
    }

    val sourceId = EntityId("damage-source")
    val recipientId = EntityId("damage-recipient")

    val damageEvent = DamageDealtEvent(
        sourceId = sourceId,
        targetId = recipientId,
        amount = 3,
        isCombatDamage = true,
        recipientKind = DamageRecipientKind.CREATURE,
        damageSourceLastKnownSnapshot = EntitySnapshot(
            entityId = sourceId,
            power = 4,
            toughness = 4,
            subtypes = setOf("Spider"),
            colors = setOf(Color.GREEN.name),
            battlefieldEntryTimestamp = 1L,
        ),
        damageRecipientLastKnownSnapshot = EntitySnapshot(
            entityId = recipientId,
            power = 2,
            toughness = 3,
            subtypes = setOf("Elf"),
            colors = setOf(Color.GREEN.name),
            battlefieldEntryTimestamp = 2L,
        )
    )

    test("damage trigger context preserves source and recipient as separate roles") {
        val context = TriggerContext.fromEvent(damageEvent)

        context.triggeringEntityId shouldBe recipientId
        context.damageSourceEntityId shouldBe sourceId
        context.damageRecipientEntityId shouldBe recipientId
        context.damageRecipientKind shouldBe DamageRecipientKind.CREATURE
    }

    test("damage source and recipient targets resolve independently of triggering entity") {
        val controllerId = EntityId("controller")
        val context = EffectContext(
            sourceId = null,
            controllerId = controllerId,
            triggeringEntityId = recipientId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId,
            damageRecipientKind = DamageRecipientKind.CREATURE,
            damageRecipientKinds = DamageRecipientKindSet.CREATURE,
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot,
            damageRecipientLastKnownSnapshot = damageEvent.damageRecipientLastKnownSnapshot,
        )

        TargetResolutionUtils.resolveTarget(EffectTarget.DamageSource, context) shouldBe null
        TargetResolutionUtils.resolveTarget(EffectTarget.DamageRecipient, context) shouldBe null
        val liveState = GameState(
            zones = mapOf(ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(sourceId, recipientId))
        ).withEntity(
            sourceId,
            ComponentContainer.of(BattlefieldEntryTimestampComponent(1L))
        ).withEntity(
            recipientId,
            ComponentContainer.of(BattlefieldEntryTimestampComponent(2L))
        )
        TargetResolutionUtils.resolveTarget(EffectTarget.DamageSource, context, liveState) shouldBe sourceId
        TargetResolutionUtils.resolveTarget(EffectTarget.DamageRecipient, context, liveState) shouldBe recipientId
    }

    test("source-filtered observers keep source TriggeringEntity without losing the recipient") {
        val context = TriggerContext.fromDamageEvent(
            damageEvent,
            triggeringEntityId = sourceId,
            triggeringPlayerId = EntityId("recipient-player")
        )

        context.triggeringEntityId shouldBe sourceId
        context.triggeringPlayerId shouldBe EntityId("recipient-player")
        context.damageSourceEntityId shouldBe sourceId
        context.damageRecipientEntityId shouldBe recipientId
    }

    test("simultaneous damage events retain each source-recipient pairing") {
        val secondSourceId = EntityId("second-source")
        val secondRecipientId = EntityId("second-recipient")
        val secondEvent = damageEvent.copy(
            sourceId = secondSourceId,
            targetId = secondRecipientId,
            damageSourceLastKnownSnapshot = EntitySnapshot(secondSourceId, power = 5),
            damageRecipientLastKnownSnapshot = EntitySnapshot(secondRecipientId, power = 6)
        )

        val firstContext = TriggerContext.fromEvent(damageEvent)
        val secondContext = TriggerContext.fromEvent(secondEvent)

        firstContext.damageSourceEntityId shouldBe sourceId
        firstContext.damageRecipientEntityId shouldBe recipientId
        secondContext.damageSourceEntityId shouldBe secondSourceId
        secondContext.damageRecipientEntityId shouldBe secondRecipientId
        secondContext.damageSourceLastKnownSnapshot?.entityId shouldBe secondSourceId
        secondContext.damageRecipientLastKnownSnapshot?.entityId shouldBe secondRecipientId
    }

    test("a player damage recipient resolves through the player-target path") {
        val playerId = EntityId("damage-player")
        val playerEvent = damageEvent.copy(
            targetId = playerId,
            targetIsPlayer = true,
            recipientKind = DamageRecipientKind.PLAYER,
            damageRecipientLastKnownSnapshot = null
        )
        val context = EffectContext(
            sourceId = null,
            controllerId = EntityId("controller"),
            triggeringEntityId = playerId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = playerId,
            damageRecipientKind = DamageRecipientKind.PLAYER
        )

        TriggerContext.fromEvent(playerEvent).damageRecipientEntityId shouldBe playerId
        TargetResolutionUtils.resolvePlayerTarget(EffectTarget.DamageRecipient, context) shouldBe playerId
    }

    test("non-player damage recipients are rejected by the player-target path") {
        listOf(
            DamageRecipientKind.CREATURE,
            DamageRecipientKind.PLANESWALKER,
            DamageRecipientKind.BATTLE,
            DamageRecipientKind.OTHER,
            DamageRecipientKind.UNKNOWN
        ).forEach { kind ->
            val context = EffectContext(
                sourceId = null,
                controllerId = EntityId("controller"),
                damageSourceEntityId = sourceId,
                damageRecipientEntityId = recipientId,
                damageRecipientKind = kind
            )

            TargetResolutionUtils.resolvePlayerTarget(EffectTarget.DamageRecipient, context) shouldBe null
        }
    }

    test("damage detector dispatch uses the captured recipient role") {
        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())
        val playerEvent = damageEvent.copy(
            targetId = EntityId("player"),
            targetIsPlayer = true,
            recipientKind = DamageRecipientKind.PLAYER,
            damageRecipientLastKnownSnapshot = null
        )
        val planeswalkerEvent = damageEvent.copy(
            targetId = EntityId("planeswalker"),
            targetIsPlayer = false,
            recipientKind = DamageRecipientKind.PLANESWALKER
        )

        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(recipient = com.wingedsheep.sdk.scripting.events.RecipientFilter.AnyPlayer),
            playerEvent,
            GameState()
        ) shouldBe true
        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(recipient = com.wingedsheep.sdk.scripting.events.RecipientFilter.AnyPlayer),
            damageEvent,
            GameState()
        ) shouldBe false
        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(recipient = com.wingedsheep.sdk.scripting.events.RecipientFilter.AnyPlayerOrPlaneswalker),
            planeswalkerEvent,
            GameState()
        ) shouldBe true
    }

    test("a deathtouch source damaging a planeswalker keeps the planeswalker recipient role") {
        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())
        val event = damageEvent.copy(
            targetId = EntityId("planeswalker"),
            targetIsPlayer = false,
            recipientKind = DamageRecipientKind.PLANESWALKER,
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot?.copy(
                keywords = setOf("DEATHTOUCH")
            )
        )

        event.effectiveRecipientKind shouldBe DamageRecipientKind.PLANESWALKER
        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(recipient = RecipientFilter.AnyPlayerOrPlaneswalker),
            event,
            GameState()
        ) shouldBe true
        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(recipient = RecipientFilter.AnyCreature),
            event,
            GameState()
        ) shouldBe false
    }

    test("damage detector dispatch rejects a non-player recipient even when its id is player-shaped") {
        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())
        val observer = TriggerIndex.IndexedEntity(
            entityId = EntityId("damage-observer"),
            cardComponent = CardComponent(
                cardDefinitionId = "damage-observer",
                name = "Damage Observer",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT))
            ),
            controllerId = EntityId("player"),
            abilities = listOf(
                com.wingedsheep.sdk.scripting.TriggeredAbility(
                    id = AbilityId("damage-observer-ability"),
                    trigger = EventPattern.DealsDamageEvent(recipient = RecipientFilter.You),
                    binding = TriggerBinding.ANY,
                    effect = Effects.DrawCards(1)
                )
            )
        )
        val index = TriggerIndex(
            byCategory = emptyMap(),
            aurasByTarget = emptyMap(),
            grantProviders = emptyList(),
            statics = BattlefieldStaticsIndex.EMPTY,
            damageToYouObservers = listOf(observer),
            subtypeDamageObservers = emptyList(),
            damageObservers = emptyList(),
            creatureDamageDeathTrackers = emptyList()
        )
        val detector = DamageTriggerDetector(
            TriggerAbilityResolver(CardRegistry(), AbilityRegistry()),
            matcher
        )
        val playerId = EntityId("player")
        val state = GameState()
        val playerEvent = damageEvent.copy(
            targetId = playerId,
            targetIsPlayer = true,
            recipientKind = DamageRecipientKind.PLAYER,
            damageRecipientLastKnownSnapshot = null
        )
        val creatureEvent = playerEvent.copy(
            targetIsPlayer = false,
            recipientKind = DamageRecipientKind.CREATURE
        )

        val playerTriggers = mutableListOf<PendingTrigger>()
        detector.detectDamageToControllerTriggers(
            state, playerEvent, playerTriggers, state.projectedState, index
        )
        playerTriggers.size shouldBe 1

        val creatureTriggers = mutableListOf<PendingTrigger>()
        detector.detectDamageToControllerTriggers(
            state, creatureEvent, creatureTriggers, state.projectedState, index
        )
        creatureTriggers shouldBe emptyList()
    }

    test("damage source predicates do not fall back to the recipient") {
        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())
        val candidate = CardComponent(
            cardDefinitionId = "recipient-creature",
            name = "Recipient Creature",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            baseStats = CreatureStats(2, 2)
        )
        val state = GameState().withEntity(recipientId, ComponentContainer.of(candidate))
        val event = damageEvent.copy(
            sourceId = EntityId("missing-source"),
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot
                ?.copy(entityId = sourceId)
        )

        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(
                recipient = RecipientFilter.AnyCreature,
                sourceFilter = GameObjectFilter.Creature
            ),
            event,
            state
        ) shouldBe false
    }

    test("damage source filters use token LKI instead of a same-id replacement") {
        val controllerId = EntityId("source-controller")
        val sourceSnapshot = EntitySnapshot(
            entityId = sourceId,
            controllerId = controllerId,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            wasToken = true,
            battlefieldEntryTimestamp = 9L,
        )
        val replacement = CardComponent(
            cardDefinitionId = "replacement-creature",
            name = "Replacement Creature",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            baseStats = CreatureStats(2, 2),
        )
        val state = GameState(
            zones = mapOf(ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(sourceId))
        ).withEntity(
            sourceId,
            ComponentContainer.of(
                replacement,
                ControllerComponent(controllerId),
                BattlefieldEntryTimestampComponent(10L),
            )
        )
        val event = damageEvent.copy(
            sourceId = sourceId,
            damageSourceLastKnownSnapshot = sourceSnapshot,
        )
        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())

        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(
                recipient = RecipientFilter.AnyCreature,
                sourceFilter = GameObjectFilter.Creature,
            ),
            event,
            state,
            controllerId,
        ) shouldBe true
        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(
                recipient = RecipientFilter.AnyCreature,
                sourceFilter = GameObjectFilter.Creature.nontoken(),
            ),
            event,
            state,
            controllerId,
        ) shouldBe false
    }

    test("damage-role entity properties and predicates read captured LKI") {
        val snapshot = EntitySnapshot(
            entityId = recipientId,
            power = 7,
            toughness = 4,
            subtypes = setOf("Elf"),
            colors = setOf(Color.GREEN.name)
        )
        val context = EffectContext(
            sourceId = null,
            controllerId = EntityId("controller"),
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId,
            damageRecipientKind = DamageRecipientKind.CREATURE,
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot,
            damageRecipientLastKnownSnapshot = snapshot
        )
        val amountEvaluator = DynamicAmountEvaluator()

        amountEvaluator.evaluate(
            GameState(),
            DynamicAmount.EntityProperty(EntityReference.DamageRecipient, EntityNumericProperty.Power),
            context
        ) shouldBe 7
        amountEvaluator.evaluate(
            GameState(),
            DynamicAmount.EntityProperty(EntityReference.DamageRecipient, EntityNumericProperty.SubtypeCount),
            context
        ) shouldBe 1
        amountEvaluator.evaluate(
            GameState(),
            DynamicAmount.EntityProperty(EntityReference.DamageRecipient, EntityNumericProperty.ColorCount),
            context
        ) shouldBe 1
        amountEvaluator.evaluate(
            GameState(),
            DynamicAmount.EntityProperty(EntityReference.DamageSource, EntityNumericProperty.Power),
            context
        ) shouldBe 4

        val candidateId = EntityId("candidate")
        val candidate = CardComponent(
            cardDefinitionId = "test-candidate",
            name = "Test Candidate",
            manaCost = ManaCost(emptyList()),
            typeLine = TypeLine(
                cardTypes = setOf(CardType.CREATURE),
                subtypes = setOf(Subtype("Elf"))
            ),
            baseStats = CreatureStats(6, 6),
            colors = setOf(Color.GREEN)
        )
        val state = GameState().withEntity(candidateId, ComponentContainer.of(candidate))
        val predicateContext = PredicateContext(
            controllerId = EntityId("controller"),
            damageSourceId = sourceId,
            damageRecipientId = recipientId,
            damageRecipientKind = DamageRecipientKind.CREATURE,
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot,
            damageRecipientLastKnownSnapshot = snapshot
        )
        val predicateEvaluator = PredicateEvaluator()

        predicateEvaluator.matchesCardPredicate(
            state,
            state.projectedState,
            candidateId,
            CardPredicate.PowerAtMostEntity(EntityReference.DamageRecipient),
            predicateContext
        ) shouldBe true
        predicateEvaluator.matchesCardPredicate(
            state,
            state.projectedState,
            candidateId,
            CardPredicate.SharesCreatureTypeWith(EntityReference.DamageRecipient),
            predicateContext
        ) shouldBe true
        predicateEvaluator.matchesCardPredicate(
            state,
            state.projectedState,
            candidateId,
            CardPredicate.SharesColorWith(EntityReference.DamageRecipient),
            predicateContext
        ) shouldBe true

        contextScopedReferenceIn(
            DynamicAmount.EntityProperty(EntityReference.DamageSource, EntityNumericProperty.Power)
        ) shouldBe "DamageSource"
        contextScopedReferenceIn(
            DynamicAmount.EntityProperty(EntityReference.DamageRecipient, EntityNumericProperty.Power)
        ) shouldBe "DamageRecipient"
    }

    test("live damage-role predicates prefer current projection over stale LKI") {
        val controllerId = EntityId("live-controller")
        val liveRecipient = CardComponent(
            cardDefinitionId = "live-recipient",
            name = "Live Recipient",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(
                cardTypes = setOf(CardType.CREATURE),
                subtypes = setOf(Subtype("Goblin"))
            ),
            baseStats = CreatureStats(2, 2),
            colors = setOf(Color.RED)
        )
        val liveState = GameState(
            zones = mapOf(ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(recipientId))
        ).withEntity(
            recipientId,
            ComponentContainer.of(
                liveRecipient,
                ControllerComponent(controllerId),
                BattlefieldEntryTimestampComponent(30L),
            )
        )
        val candidateId = EntityId("live-candidate")
        val candidate = CardComponent(
            cardDefinitionId = "live-candidate",
            name = "Live Candidate",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(
                cardTypes = setOf(CardType.CREATURE),
                subtypes = setOf(Subtype("Elf"))
            ),
            baseStats = CreatureStats(6, 6),
            colors = setOf(Color.GREEN)
        )
        val stateWithCandidate = liveState.withEntity(
            candidateId,
            ComponentContainer.of(candidate)
        )
        val context = PredicateContext(
            controllerId = controllerId,
            damageRecipientId = recipientId,
            damageRecipientKind = DamageRecipientKind.CREATURE,
            damageRecipientLastKnownSnapshot = EntitySnapshot(
                entityId = recipientId,
                power = 7,
                subtypes = setOf("Elf"),
                colors = setOf(Color.GREEN.name),
                battlefieldEntryTimestamp = 30L,
            )
        )
        val evaluator = PredicateEvaluator()

        evaluator.matchesCardPredicate(
            stateWithCandidate,
            stateWithCandidate.projectedState,
            candidateId,
            CardPredicate.PowerAtMostEntity(EntityReference.DamageRecipient),
            context
        ) shouldBe false
        evaluator.matchesCardPredicate(
            stateWithCandidate,
            stateWithCandidate.projectedState,
            candidateId,
            CardPredicate.SharesCreatureTypeWith(EntityReference.DamageRecipient),
            context
        ) shouldBe false
        evaluator.matchesCardPredicate(
            stateWithCandidate,
            stateWithCandidate.projectedState,
            candidateId,
            CardPredicate.SharesColorWith(EntityReference.DamageRecipient),
            context
        ) shouldBe false
    }

    test("damage source properties read the captured incarnation after same-id reuse") {
        val controllerId = EntityId("source-reuse-controller")
        val sourceSnapshot = EntitySnapshot(
            entityId = sourceId,
            power = 8,
            toughness = 8,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            battlefieldEntryTimestamp = 20L,
        )
        val replacement = CardComponent(
            cardDefinitionId = "new-source",
            name = "New Source",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            baseStats = CreatureStats(1, 1),
        )
        val state = GameState(
            zones = mapOf(ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(sourceId))
        ).withEntity(
            sourceId,
            ComponentContainer.of(
                replacement,
                ControllerComponent(controllerId),
                BattlefieldEntryTimestampComponent(21L),
            )
        )
        val context = EffectContext(
            sourceId = null,
            controllerId = controllerId,
            damageSourceEntityId = sourceId,
            damageSourceLastKnownSnapshot = sourceSnapshot,
        )

        TargetResolutionUtils.resolveEntityReference(
            EntityReference.DamageSource,
            context,
            state,
        ) shouldBe null

        DynamicAmountEvaluator().evaluate(
            state,
            DynamicAmount.EntityProperty(EntityReference.DamageSource, EntityNumericProperty.Power),
            context,
        ) shouldBe 8
    }

    test("damage role predicates read captured LKI after same-id reuse and reject unsupported live reads") {
        val controllerId = EntityId("predicate-reuse-controller")
        val sourceSnapshot = EntitySnapshot(
            entityId = sourceId,
            power = 8,
            toughness = 8,
            subtypes = setOf("Elf"),
            colors = setOf(Color.GREEN.name),
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE), subtypes = setOf(Subtype("Elf"))),
            battlefieldEntryTimestamp = 20L,
        )
        val replacement = CardComponent(
            cardDefinitionId = "replacement-source",
            name = "Replacement Source",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE), subtypes = setOf(Subtype("Goblin"))),
            baseStats = CreatureStats(1, 1),
            colors = setOf(Color.RED),
        )
        val candidateId = EntityId("predicate-reuse-candidate")
        val candidate = CardComponent(
            cardDefinitionId = "predicate-candidate",
            name = "Predicate Candidate",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE), subtypes = setOf(Subtype("Elf"))),
            baseStats = CreatureStats(6, 6),
            colors = setOf(Color.GREEN),
        )
        val state = GameState(
            zones = mapOf(ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(sourceId, candidateId))
        ).withEntity(
            sourceId,
            ComponentContainer.of(
                replacement,
                ControllerComponent(controllerId),
                BattlefieldEntryTimestampComponent(21L),
            )
        ).withEntity(candidateId, ComponentContainer.of(candidate))
        val context = PredicateContext(
            controllerId = controllerId,
            damageSourceId = sourceId,
            damageSourceLastKnownSnapshot = sourceSnapshot,
        )
        val evaluator = PredicateEvaluator()

        evaluator.matchesCardPredicate(
            state,
            state.projectedState,
            candidateId,
            CardPredicate.PowerAtMostEntity(EntityReference.DamageSource),
            context,
        ) shouldBe true
        evaluator.matchesCardPredicate(
            state,
            state.projectedState,
            candidateId,
            CardPredicate.SharesCreatureTypeWith(EntityReference.DamageSource),
            context,
        ) shouldBe true
        evaluator.matchesCardPredicate(
            state,
            state.projectedState,
            candidateId,
            CardPredicate.SharesColorWith(EntityReference.DamageSource),
            context,
        ) shouldBe true
        evaluator.matchesSnapshot(
            state,
            sourceSnapshot,
            GameObjectFilter(cardPredicates = listOf(CardPredicate.HasColor(Color.GREEN))),
            context,
        ) shouldBe true
        // The snapshot does not carry cast history. Do not fall through to the replacement's
        // mana value just because it reused the old entity id.
        evaluator.matchesCardPredicate(
            state,
            state.projectedState,
            candidateId,
            CardPredicate.ManaValueAtMostEntity(EntityReference.DamageSource),
            context,
        ) shouldBe false
    }

    test("reflexive trigger context preserves both damage roles") {
        val context = EffectContext(
            sourceId = sourceId,
            controllerId = EntityId("controller"),
            triggeringEntityId = sourceId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId,
            damageRecipientKinds = DamageRecipientKindSet.of(
                DamageRecipientKind.CREATURE,
                DamageRecipientKind.PLANESWALKER,
            ),
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot,
            damageRecipientLastKnownSnapshot = damageEvent.damageRecipientLastKnownSnapshot,
        )

        val event = ReflexiveTriggerEffectExecutor.buildReflexiveTriggeredEvent(
            state = GameState(),
            reflexiveEffect = Effects.DrawCards(1),
            reflexiveTargetRequirements = emptyList(),
            descriptionOverride = null,
            effectContext = context
        )

        event.carriedTriggerContext.damageSourceEntityId shouldBe sourceId
        event.carriedTriggerContext.damageRecipientEntityId shouldBe recipientId
        event.carriedTriggerContext.effectiveDamageRecipientKinds shouldBe
            DamageRecipientKindSet.of(DamageRecipientKind.CREATURE, DamageRecipientKind.PLANESWALKER)
        event.carriedTriggerContext.damageSourceLastKnownSnapshot shouldBe
            damageEvent.damageSourceLastKnownSnapshot
        event.carriedTriggerContext.damageRecipientLastKnownSnapshot shouldBe
            damageEvent.damageRecipientLastKnownSnapshot
    }

    test("damage roles survive triggered-ability continuation serialization") {
        val continuation: ContinuationFrame = TriggeredAbilityContinuation(
            decisionId = "damage-decision",
            sourceId = sourceId,
            sourceName = "damage-observer",
            controllerId = EntityId("controller"),
            effect = Effects.DrawCards(1),
            description = "draw",
            triggeringEntityId = sourceId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId,
            damageRecipientKind = DamageRecipientKind.PLANESWALKER,
            damageRecipientKinds = DamageRecipientKindSet.of(
                DamageRecipientKind.CREATURE,
                DamageRecipientKind.PLANESWALKER,
            ),
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot,
            damageRecipientLastKnownSnapshot = damageEvent.damageRecipientLastKnownSnapshot
        )

        val encoded = json.encodeToString(ContinuationFrame.serializer(), continuation)
        json.decodeFromString(ContinuationFrame.serializer(), encoded) shouldBe continuation
    }

    test("damage role targets retain IDs when source and recipient leave before resolution") {
        val context = EffectContext(
            sourceId = null,
            controllerId = EntityId("controller"),
            triggeringEntityId = recipientId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId
        )

        TargetResolutionUtils.resolveTarget(EffectTarget.DamageSource, context) shouldBe null
        TargetResolutionUtils.resolveTarget(EffectTarget.DamageRecipient, context) shouldBe null
        TargetResolutionUtils.resolveTarget(EffectTarget.DamageSource, context, GameState()) shouldBe null
        TargetResolutionUtils.resolveTarget(EffectTarget.DamageRecipient, context, GameState()) shouldBe null
    }

    test("damage source and recipient roles survive event and trigger-context serialization") {
        val eventRoundTrip = json.decodeFromString<GameEvent>(
            json.encodeToString(GameEvent.serializer(), damageEvent)
        )
        eventRoundTrip shouldBe damageEvent

        val context = TriggerContext.fromEvent(damageEvent)
        val encoded = json.encodeToString(TriggerContext.serializer(), context)
        val decoded = json.decodeFromString(TriggerContext.serializer(), encoded)

        decoded shouldBe context
        json.encodeToString(TriggerContext.serializer(), decoded) shouldBe encoded

        val damageSourceReference = json.encodeToString(
            EntityReference.serializer(), EntityReference.DamageSource
        )
        val damageRecipientReference = json.encodeToString(
            EntityReference.serializer(), EntityReference.DamageRecipient
        )
        json.decodeFromString(EntityReference.serializer(), damageSourceReference) shouldBe
            EntityReference.DamageSource
        json.decodeFromString(EntityReference.serializer(), damageRecipientReference) shouldBe
            EntityReference.DamageRecipient
    }

    test("damage roles survive serialization of the triggered ability stack component") {
        val ability = TriggeredAbilityOnStackComponent(
            sourceId = sourceId,
            sourceName = "damage-observer",
            controllerId = EntityId("controller"),
            effect = Effects.DrawCards(1),
            description = "draw",
            triggeringEntityId = recipientId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId
        )

        val encoded = json.encodeToString(TriggeredAbilityOnStackComponent.serializer(), ability)
        val decoded = json.decodeFromString(TriggeredAbilityOnStackComponent.serializer(), encoded)

        decoded shouldBe ability
    }

    test("damage roles survive a GameState replay serialization round trip") {
        val stackId = EntityId("damage-stack")
        val ability = TriggeredAbilityOnStackComponent(
            sourceId = sourceId,
            sourceName = "damage-observer",
            controllerId = EntityId("controller"),
            effect = Effects.DrawCards(1),
            description = "draw",
            triggeringEntityId = sourceId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId,
            damageRecipientKind = DamageRecipientKind.PLANESWALKER,
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot,
            damageRecipientLastKnownSnapshot = damageEvent.damageRecipientLastKnownSnapshot
        )
        val state = GameState(
            entities = mapOf(stackId to ComponentContainer.of(ability)),
            stack = listOf(stackId)
        )

        val replayJson = Json {
            serializersModule = engineSerializersModule
            allowStructuredMapKeys = true
        }
        val encoded = replayJson.encodeToString(GameState.serializer(), state)
        replayJson.decodeFromString(GameState.serializer(), encoded) shouldBe state
    }

    test("damage recipient matching uses recipient LKI after a same-id reuse and Destroy does not hit the reuse") {
        val controllerId = EntityId("controller")
        val oldSnapshot = EntitySnapshot(
            entityId = recipientId,
            power = 2,
            toughness = 2,
            controllerId = controllerId,
            typeLine = TypeLine(cardTypes = setOf(CardType.PLANESWALKER)),
            battlefieldEntryTimestamp = 7L,
        )
        val event = damageEvent.copy(
            targetId = recipientId,
            targetIsPlayer = false,
            recipientKind = DamageRecipientKind.PLANESWALKER,
            recipientKinds = DamageRecipientKindSet.of(DamageRecipientKind.PLANESWALKER),
            damageRecipientLastKnownSnapshot = oldSnapshot,
        )
        val reusedState = GameState(
            zones = mapOf(ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(recipientId))
        ).withEntity(
            recipientId,
            ComponentContainer.of(
                CardComponent(
                    cardDefinitionId = "new-creature",
                    name = "New Creature",
                    manaCost = ManaCost.ZERO,
                    typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                    baseStats = CreatureStats(2, 2),
                ),
                ControllerComponent(controllerId),
                BattlefieldEntryTimestampComponent(8L),
            )
        )
        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())

        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(
                recipient = RecipientFilter.Matching(GameObjectFilter.Planeswalker)
            ),
            event,
            reusedState,
            controllerId,
        ) shouldBe true

        val context = EffectContext(
            sourceId = null,
            controllerId = controllerId,
            damageRecipientEntityId = recipientId,
            damageRecipientKind = DamageRecipientKind.PLANESWALKER,
            damageRecipientKinds = DamageRecipientKindSet.of(DamageRecipientKind.PLANESWALKER),
            damageRecipientLastKnownSnapshot = oldSnapshot,
        )
        TargetResolutionUtils.resolveTarget(EffectTarget.DamageRecipient, context, reusedState) shouldBe null
        val destroy = Effects.Destroy(EffectTarget.DamageRecipient) as MoveToZoneEffect
        val result = MoveToZoneEffectExecutor(CardRegistry()).execute(reusedState, destroy, context)
        result.state.getBattlefield() shouldBe setOf(recipientId)
    }

    test("damage recipient matching uses live projected state for the original incarnation") {
        val controllerId = EntityId("live-recipient-controller")
        val state = GameState(
            zones = mapOf(ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(recipientId))
        ).withEntity(
            recipientId,
            ComponentContainer.of(
                CardComponent(
                    cardDefinitionId = "animated-recipient",
                    name = "Animated Recipient",
                    manaCost = ManaCost.ZERO,
                    typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                    baseStats = CreatureStats(2, 2),
                ),
                ControllerComponent(controllerId),
                BattlefieldEntryTimestampComponent(30L),
            )
        )
        val event = damageEvent.copy(
            targetId = recipientId,
            recipientKind = DamageRecipientKind.PLANESWALKER,
            recipientKinds = DamageRecipientKindSet.PLANESWALKER,
            damageRecipientLastKnownSnapshot = EntitySnapshot(
                entityId = recipientId,
                typeLine = TypeLine(cardTypes = setOf(CardType.PLANESWALKER)),
                battlefieldEntryTimestamp = 30L,
            ),
        )

        TriggerMatcher(PredicateEvaluator(), ConditionEvaluator()).matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(
                recipient = RecipientFilter.Matching(GameObjectFilter.Creature),
            ),
            event,
            state,
            controllerId,
        ) shouldBe true
    }

    test("damage recipient kind set retains multiple simultaneous card types") {
        val kinds = DamageRecipientKindSet.of(
            DamageRecipientKind.CREATURE,
            DamageRecipientKind.PLANESWALKER,
        )
        val event = damageEvent.copy(
            recipientKind = DamageRecipientKind.UNKNOWN,
            recipientKinds = kinds,
        )

        event.effectiveRecipientKinds shouldBe kinds
        event.effectiveRecipientKind shouldBe DamageRecipientKind.UNKNOWN

        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())
        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(recipient = RecipientFilter.AnyCreature),
            event,
            GameState(),
        ) shouldBe true
        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(recipient = RecipientFilter.AnyPlayerOrPlaneswalker),
            event,
            GameState(),
        ) shouldBe true

        val mixedRoleContext = EffectContext(
            sourceId = null,
            controllerId = EntityId("mixed-role-controller"),
            damageRecipientEntityId = recipientId,
            damageRecipientKinds = DamageRecipientKindSet.of(
                DamageRecipientKind.PLAYER,
                DamageRecipientKind.PLANESWALKER,
            ),
        )
        TargetResolutionUtils.resolvePlayerTarget(
            EffectTarget.DamageRecipient,
            mixedRoleContext,
        ) shouldBe null
    }

    test("damage recipient role capture retains every projected card type") {
        val controllerId = EntityId("multi-role-controller")
        val multiRoleId = EntityId("multi-role-recipient")
        val state = GameState(
            zones = mapOf(ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(multiRoleId))
        ).withEntity(
            multiRoleId,
            ComponentContainer.of(
                CardComponent(
                    cardDefinitionId = "multi-role-permanent",
                    name = "Multi-role Permanent",
                    manaCost = ManaCost.ZERO,
                    typeLine = TypeLine(
                        cardTypes = setOf(CardType.CREATURE, CardType.PLANESWALKER),
                    ),
                    baseStats = CreatureStats(2, 2),
                ),
                ControllerComponent(controllerId),
                BattlefieldEntryTimestampComponent(40L),
            )
        )

        DamageUtils.damageRecipientKinds(state, multiRoleId, targetIsPlayer = false) shouldBe
            DamageRecipientKindSet.of(
                DamageRecipientKind.CREATURE,
                DamageRecipientKind.PLANESWALKER,
            )
    }

    test("damage LKI preserves token status for nontoken filters") {
        val controllerId = EntityId("token-controller")
        val tokenId = EntityId("token-source")
        val tokenCard = CardComponent(
            cardDefinitionId = "token-creature",
            name = "Token Creature",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            baseStats = CreatureStats(1, 1),
        )
        val state = GameState(
            zones = mapOf(ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(tokenId))
        ).withEntity(
            tokenId,
            ComponentContainer.of(
                tokenCard,
                ControllerComponent(controllerId),
                TokenComponent,
                BattlefieldEntryTimestampComponent(12L),
            )
        )

        val tokenSnapshot = DamageUtils.captureDamageEntitySnapshot(state, tokenId)
        tokenSnapshot?.wasToken shouldBe true

        val reusedState = state.withEntity(
            tokenId,
            ComponentContainer.of(
                tokenCard,
                ControllerComponent(controllerId),
                BattlefieldEntryTimestampComponent(13L),
            )
        )
        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())
        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(
                recipient = RecipientFilter.AnyCreature,
                sourceFilter = GameObjectFilter.Creature.nontoken(),
            ),
            damageEvent.copy(
                sourceId = tokenId,
                damageSourceLastKnownSnapshot = tokenSnapshot,
            ),
            reusedState,
            controllerId,
        ) shouldBe false
    }

    test("unknown damage recipient role never becomes a player from a reused id") {
        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())
        val event = damageEvent.copy(
            targetId = EntityId("player-shaped-id"),
            targetIsPlayer = false,
            targetWasCreature = false,
            recipientKind = DamageRecipientKind.UNKNOWN,
            recipientKinds = DamageRecipientKindSet.UNKNOWN,
            damageRecipientLastKnownSnapshot = null,
        )

        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(recipient = RecipientFilter.AnyPlayer),
            event,
            GameState(),
        ) shouldBe false
    }
})
