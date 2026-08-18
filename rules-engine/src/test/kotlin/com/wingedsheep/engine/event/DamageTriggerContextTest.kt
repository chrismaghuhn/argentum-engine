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
import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.mechanics.layers.ProjectedValues
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.identity.RingBearerComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Supertype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.predicates.CardPredicate
import com.wingedsheep.sdk.scripting.events.DamageType
import com.wingedsheep.sdk.scripting.events.RecipientFilter
import com.wingedsheep.sdk.scripting.events.SourceFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetObject
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.values.EntityNumericProperty
import com.wingedsheep.sdk.scripting.values.EntityReference
import com.wingedsheep.sdk.scripting.values.contextScopedReferenceIn
import com.wingedsheep.sdk.scripting.effects.MoveToZoneEffect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
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
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            subtypes = setOf("Spider"),
            colors = setOf(Color.GREEN.name),
            battlefieldEntryTimestamp = 1L,
        ),
        damageRecipientLastKnownSnapshot = EntitySnapshot(
            entityId = recipientId,
            power = 2,
            toughness = 3,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            subtypes = setOf("Elf"),
            colors = setOf(Color.GREEN.name),
            battlefieldEntryTimestamp = 2L,
        )
    )

    fun directDamageReceivedFixture(
        sourceTypeLine: TypeLine,
    ): Pair<GameState, AbilityRegistry> {
        val controllerId = EntityId("direct-damage-received-controller")
        val ability = com.wingedsheep.sdk.scripting.TriggeredAbility(
            id = AbilityId("direct-damage-received-creature-source"),
            trigger = EventPattern.DamageReceivedEvent(source = SourceFilter.Creature),
            binding = TriggerBinding.SELF,
            effect = Effects.DrawCards(1),
        )
        val abilityRegistry = AbilityRegistry().apply {
            register("direct-damage-received-recipient", listOf(ability))
        }
        val state = GameState(
            zones = mapOf(
                ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(sourceId, recipientId),
            ),
            turnOrder = listOf(controllerId),
        )
            .withEntity(
                sourceId,
                ComponentContainer.of(
                    CardComponent(
                        cardDefinitionId = "direct-damage-received-source",
                        name = "Printed Source",
                        manaCost = ManaCost.ZERO,
                        typeLine = sourceTypeLine,
                        baseStats = if (sourceTypeLine.isCreature) CreatureStats(2, 2) else null,
                    ),
                    ControllerComponent(controllerId),
                    BattlefieldEntryTimestampComponent(1L),
                ),
            )
            .withEntity(
                recipientId,
                ComponentContainer.of(
                    CardComponent(
                        cardDefinitionId = "direct-damage-received-recipient",
                        name = "Damage Recipient",
                        manaCost = ManaCost.ZERO,
                        typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                        baseStats = CreatureStats(2, 2),
                    ),
                    ControllerComponent(controllerId),
                    BattlefieldEntryTimestampComponent(2L),
                ),
            )
        return state to abilityRegistry
    }

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

    test("damage source SELF discovery uses deleted token event LKI") {
        val controllerId = EntityId("deleted-token-controller")
        val ability = com.wingedsheep.sdk.scripting.TriggeredAbility(
            id = AbilityId("deleted-token-source-damage"),
            trigger = EventPattern.DealsDamageEvent(
                recipient = RecipientFilter.AnyCreature,
            ),
            binding = TriggerBinding.SELF,
            effect = Effects.DrawCards(1),
        )
        val abilityRegistry = AbilityRegistry().apply {
            register("deleted-token-source", listOf(ability))
        }
        val sourceSnapshot = EntitySnapshot(
            entityId = sourceId,
            name = "Deleted Token Source",
            cardDefinitionId = "deleted-token-source",
            controllerId = controllerId,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            wasToken = true,
            battlefieldEntryTimestamp = 100L,
        )
        val event = damageEvent.copy(
            sourceId = sourceId,
            damageSourceLastKnownSnapshot = sourceSnapshot,
            recipientKind = DamageRecipientKind.CREATURE,
            recipientKinds = DamageRecipientKindSet.CREATURE,
        )
        val triggers = mutableListOf<PendingTrigger>()
        val detector = DamageTriggerDetector(
            TriggerAbilityResolver(CardRegistry(), abilityRegistry),
            TriggerMatcher(PredicateEvaluator(), ConditionEvaluator()),
        )

        detector.detectDamageSourceTriggers(
            state = GameState(),
            statics = BattlefieldStaticsIndex.EMPTY,
            event = event,
            triggers = triggers,
            projected = GameState().projectedState,
        )

        triggers shouldHaveSize 1
        triggers.single().ability shouldBe ability
        triggers.single().sourceName shouldBe "Deleted Token Source"
        triggers.single().controllerId shouldBe controllerId
    }

    test("direct damage-received source dispatch reads the captured projected type line") {
        val (state, abilityRegistry) = directDamageReceivedFixture(
            TypeLine(cardTypes = setOf(CardType.LAND)),
        )
        val event = damageEvent.copy(
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot?.copy(
                typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                battlefieldEntryTimestamp = 1L,
            ),
        )
        val triggers = mutableListOf<PendingTrigger>()
        DamageTriggerDetector(
            TriggerAbilityResolver(CardRegistry(), abilityRegistry),
            TriggerMatcher(PredicateEvaluator(), ConditionEvaluator()),
        ).detectDamagedBySourceTriggers(
            state = state,
            statics = BattlefieldStaticsIndex.EMPTY,
            event = event,
            triggers = triggers,
        )

        triggers shouldHaveSize 1
        triggers.single().triggerContext.damageSourceEntityId shouldBe sourceId
        triggers.single().triggerContext.damageRecipientEntityId shouldBe recipientId
    }

    test("direct damage-received source dispatch fails closed without a stamped source snapshot") {
        val (state, abilityRegistry) = directDamageReceivedFixture(
            TypeLine(cardTypes = setOf(CardType.CREATURE)),
        )
        val detector = DamageTriggerDetector(
            TriggerAbilityResolver(CardRegistry(), abilityRegistry),
            TriggerMatcher(PredicateEvaluator(), ConditionEvaluator()),
        )

        val missingSnapshotTriggers = mutableListOf<PendingTrigger>()
        detector.detectDamagedBySourceTriggers(
            state = state,
            statics = BattlefieldStaticsIndex.EMPTY,
            event = damageEvent.copy(damageSourceLastKnownSnapshot = null),
            triggers = missingSnapshotTriggers,
        )
        missingSnapshotTriggers shouldHaveSize 0

        val unstampedSnapshotTriggers = mutableListOf<PendingTrigger>()
        detector.detectDamagedBySourceTriggers(
            state = state,
            statics = BattlefieldStaticsIndex.EMPTY,
            event = damageEvent.copy(
                damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot?.copy(
                    battlefieldEntryTimestamp = null,
                ),
            ),
            triggers = unstampedSnapshotTriggers,
        )
        unstampedSnapshotTriggers shouldHaveSize 0
    }

    test("top-level detector discovers the old source after same-id replacement") {
        val oldControllerId = EntityId("detector-old-source-controller")
        val replacementControllerId = EntityId("detector-replacement-source-controller")
        val oldAbility = com.wingedsheep.sdk.scripting.TriggeredAbility(
            id = AbilityId("detector-old-source-damage"),
            trigger = EventPattern.DealsDamageEvent(
                recipient = RecipientFilter.AnyCreature,
            ),
            binding = TriggerBinding.SELF,
            effect = Effects.DrawCards(1),
        )
        val replacementAbility = oldAbility.copy(id = AbilityId("detector-replacement-source-damage"))
        val abilityRegistry = AbilityRegistry().apply {
            register("detector-old-source", listOf(oldAbility))
            register("detector-replacement-source", listOf(replacementAbility))
        }
        val oldSnapshot = EntitySnapshot(
            entityId = sourceId,
            name = "Detector Old Source",
            cardDefinitionId = "detector-old-source",
            controllerId = oldControllerId,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            battlefieldEntryTimestamp = 220L,
        )
        val replacement = CardComponent(
            cardDefinitionId = "detector-replacement-source",
            name = "Detector Replacement Source",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            baseStats = CreatureStats(2, 2),
        )
        val state = GameState(
            zones = mapOf(
                ZoneKey(replacementControllerId, Zone.BATTLEFIELD) to listOf(sourceId),
            ),
            turnOrder = listOf(oldControllerId, replacementControllerId),
        ).withEntity(
            sourceId,
            ComponentContainer.of(
                replacement,
                ControllerComponent(replacementControllerId),
                BattlefieldEntryTimestampComponent(221L),
            ),
        )
        val event = damageEvent.copy(
            sourceId = sourceId,
            recipientKind = DamageRecipientKind.CREATURE,
            recipientKinds = DamageRecipientKindSet.CREATURE,
            damageSourceLastKnownSnapshot = oldSnapshot,
        )

        val triggers = TriggerDetector(CardRegistry(), abilityRegistry)
            .detectTriggers(state, listOf(event))

        triggers shouldHaveSize 1
        triggers.single().ability shouldBe oldAbility
        triggers.single().sourceName shouldBe "Detector Old Source"
        triggers.single().controllerId shouldBe oldControllerId
    }

    test("damage received SELF discovery uses the old definition after same-id replacement") {
        val oldControllerId = EntityId("old-recipient-controller")
        val replacementControllerId = EntityId("replacement-recipient-controller")
        val ability = com.wingedsheep.sdk.scripting.TriggeredAbility(
            id = AbilityId("old-recipient-damage"),
            trigger = EventPattern.DamageReceivedEvent(),
            binding = TriggerBinding.SELF,
            effect = Effects.DrawCards(1),
        )
        val abilityRegistry = AbilityRegistry().apply {
            register("old-recipient", listOf(ability))
        }
        val oldSnapshot = EntitySnapshot(
            entityId = recipientId,
            name = "Old Recipient",
            cardDefinitionId = "old-recipient",
            controllerId = oldControllerId,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            battlefieldEntryTimestamp = 200L,
        )
        val replacement = CardComponent(
            cardDefinitionId = "replacement-recipient",
            name = "Replacement Recipient",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            baseStats = CreatureStats(2, 2),
        )
        val state = GameState(
            zones = mapOf(
                ZoneKey(replacementControllerId, Zone.BATTLEFIELD) to listOf(recipientId),
            ),
        ).withEntity(
            recipientId,
            ComponentContainer.of(
                replacement,
                ControllerComponent(replacementControllerId),
                BattlefieldEntryTimestampComponent(201L),
            ),
        )
        val event = damageEvent.copy(
            targetId = recipientId,
            recipientKind = DamageRecipientKind.CREATURE,
            recipientKinds = DamageRecipientKindSet.CREATURE,
            damageRecipientLastKnownSnapshot = oldSnapshot,
        )
        val triggers = mutableListOf<PendingTrigger>()
        val detector = DamageTriggerDetector(
            TriggerAbilityResolver(CardRegistry(), abilityRegistry),
            TriggerMatcher(PredicateEvaluator(), ConditionEvaluator()),
        )

        detector.detectDamageReceivedTriggers(
            state = state,
            statics = BattlefieldStaticsIndex.EMPTY,
            event = event,
            triggers = triggers,
        )

        triggers shouldHaveSize 1
        triggers.single().ability shouldBe ability
        triggers.single().sourceName shouldBe "Old Recipient"
        triggers.single().controllerId shouldBe oldControllerId
    }

    test("top-level detector discovers the old recipient after same-id replacement") {
        val oldControllerId = EntityId("detector-old-recipient-controller")
        val replacementControllerId = EntityId("detector-replacement-recipient-controller")
        val ability = com.wingedsheep.sdk.scripting.TriggeredAbility(
            id = AbilityId("detector-old-recipient-damage"),
            trigger = EventPattern.DamageReceivedEvent(),
            binding = TriggerBinding.SELF,
            effect = Effects.DrawCards(1),
        )
        val abilityRegistry = AbilityRegistry().apply {
            register("detector-old-recipient", listOf(ability))
        }
        val oldSnapshot = EntitySnapshot(
            entityId = recipientId,
            name = "Detector Old Recipient",
            cardDefinitionId = "detector-old-recipient",
            controllerId = oldControllerId,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            battlefieldEntryTimestamp = 210L,
        )
        val replacement = CardComponent(
            cardDefinitionId = "detector-replacement-recipient",
            name = "Detector Replacement Recipient",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            baseStats = CreatureStats(2, 2),
        )
        val state = GameState(
            zones = mapOf(
                ZoneKey(replacementControllerId, Zone.BATTLEFIELD) to listOf(recipientId),
            ),
            turnOrder = listOf(oldControllerId, replacementControllerId),
        ).withEntity(
            recipientId,
            ComponentContainer.of(
                replacement,
                ControllerComponent(replacementControllerId),
                BattlefieldEntryTimestampComponent(211L),
            ),
        )
        val event = damageEvent.copy(
            targetId = recipientId,
            recipientKind = DamageRecipientKind.CREATURE,
            recipientKinds = DamageRecipientKindSet.CREATURE,
            damageRecipientLastKnownSnapshot = oldSnapshot,
        )

        val triggers = TriggerDetector(CardRegistry(), abilityRegistry)
            .detectTriggers(state, listOf(event))

        triggers shouldHaveSize 1
        triggers.single().ability shouldBe ability
        triggers.single().sourceName shouldBe "Detector Old Recipient"
        triggers.single().controllerId shouldBe oldControllerId
    }

    test("source-filtered damage fails closed when the event source is unknown") {
        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())
        val sourceUnknownEvent = damageEvent.copy(
            sourceId = null,
            damageSourceLastKnownSnapshot = null,
        )

        TriggerContext.fromSourceFilteredDamageEvent(sourceUnknownEvent) shouldBe null

        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(
                recipient = RecipientFilter.AnyCreature,
                sourceFilter = GameObjectFilter.Creature,
            ),
            sourceUnknownEvent,
            GameState(),
            EntityId("observer-controller"),
        ) shouldBe false
        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(recipient = RecipientFilter.AnyCreature),
            sourceUnknownEvent,
            GameState(),
            EntityId("observer-controller"),
        ) shouldBe true
    }

    test("attached source-filtered damage keeps the source as TriggeringEntity") {
        val attachmentId = EntityId("damage-attached-observer")
        val ability = com.wingedsheep.sdk.scripting.TriggeredAbility(
            id = AbilityId("attached-source-filtered-damage"),
            trigger = EventPattern.DealsDamageEvent(
                recipient = RecipientFilter.AnyCreature,
                sourceFilter = GameObjectFilter.Creature,
            ),
            binding = TriggerBinding.ATTACHED,
            effect = Effects.DrawCards(1),
        )
        val attachment = TriggerIndex.IndexedEntity(
            entityId = attachmentId,
            cardComponent = CardComponent(
                cardDefinitionId = "damage-attached-observer-card",
                name = "Damage Attached Observer",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT)),
            ),
            controllerId = EntityId("attachment-controller"),
            abilities = listOf(ability),
        )
        val index = TriggerIndex(
            byCategory = emptyMap(),
            aurasByTarget = mapOf(sourceId to listOf(attachment)),
            grantProviders = emptyList(),
            statics = BattlefieldStaticsIndex.EMPTY,
            damageToYouObservers = emptyList(),
            subtypeDamageObservers = emptyList(),
            damageObservers = emptyList(),
            creatureDamageDeathTrackers = emptyList(),
        )
        val detector = AttachmentTriggerDetector(
            TriggerAbilityResolver(CardRegistry(), AbilityRegistry()),
            TriggerMatcher(PredicateEvaluator(), ConditionEvaluator()),
        )
        val triggers = mutableListOf<PendingTrigger>()

        detector.detectAttachmentTriggers(GameState(), damageEvent, triggers, index)

        triggers.single().triggerContext.triggeringEntityId shouldBe sourceId
        triggers.single().triggerContext.damageRecipientEntityId shouldBe recipientId
    }

    test("stack target revalidation preserves damage LKI for predicates and dynamic properties") {
        val controllerId = EntityId("damage-context-controller")
        val candidateId = EntityId("damage-context-candidate")
        val battlefield = ZoneKey(controllerId, Zone.BATTLEFIELD)
        val candidate = CardComponent(
            cardDefinitionId = "damage-context-candidate-card",
            name = "Candidate",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            baseStats = CreatureStats(3, 3),
        )
        val sourceSnapshot = EntitySnapshot(
            entityId = sourceId,
            power = 4,
            toughness = 4,
            controllerId = controllerId,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            battlefieldEntryTimestamp = 11L,
        )
        val recipientSnapshot = EntitySnapshot(
            entityId = recipientId,
            power = 5,
            toughness = 5,
            controllerId = controllerId,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            battlefieldEntryTimestamp = 12L,
        )
        val initialState = GameState(
            zones = mapOf(battlefield to listOf(sourceId, recipientId, candidateId)),
            turnOrder = listOf(controllerId),
        )
            .withEntity(
                controllerId,
                ComponentContainer.of(PlayerComponent("Controller"), LifeTotalComponent(20)),
            )
            .withEntity(
                sourceId,
                ComponentContainer.of(
                    CardComponent(
                        cardDefinitionId = "damage-context-source-card",
                        name = "Source",
                        manaCost = ManaCost.ZERO,
                        typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                        baseStats = CreatureStats(4, 4),
                    ),
                    ControllerComponent(controllerId),
                    BattlefieldEntryTimestampComponent(11L),
                ),
            )
            .withEntity(
                recipientId,
                ComponentContainer.of(
                    CardComponent(
                        cardDefinitionId = "damage-context-recipient-card",
                        name = "Recipient",
                        manaCost = ManaCost.ZERO,
                        typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                        baseStats = CreatureStats(5, 5),
                    ),
                    ControllerComponent(controllerId),
                    BattlefieldEntryTimestampComponent(12L),
                ),
            )
            .withEntity(
                candidateId,
                ComponentContainer.of(
                    candidate,
                    ControllerComponent(controllerId),
                    BattlefieldEntryTimestampComponent(13L),
                ),
            )

        val damageRelativeFilter = GameObjectFilter.Creature
            .withCardPredicate(CardPredicate.PowerAtMostEntity(EntityReference.DamageSource))
            .withCardPredicate(CardPredicate.PowerAtMostEntity(EntityReference.DamageRecipient))
        val targetRequirement = TargetObject(filter = TargetFilter(damageRelativeFilter))
        val ability = TriggeredAbilityOnStackComponent(
            sourceId = EntityId("damage-context-observer"),
            sourceName = "Damage context observer",
            controllerId = controllerId,
            effect = Effects.GainLife(
                DynamicAmount.EntityProperty(EntityReference.DamageRecipient, EntityNumericProperty.Power)
            ),
            description = "Candidate gains context life",
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId,
            damageRecipientKind = DamageRecipientKind.CREATURE,
            damageRecipientKinds = DamageRecipientKindSet.CREATURE,
            damageSourceLastKnownSnapshot = sourceSnapshot,
            damageRecipientLastKnownSnapshot = recipientSnapshot,
        )
        val resolver = StackResolver(CardRegistry())
        val putResult = resolver.putTriggeredAbility(
            state = initialState,
            ability = ability,
            targets = listOf(ChosenTarget.Permanent(candidateId)),
            targetRequirements = listOf(targetRequirement),
        )
        putResult.error shouldBe null

        val resolutionState = putResult.state
            .removeEntity(sourceId)
            .removeEntity(recipientId)
        val resolved = resolver.resolveTop(resolutionState)

        resolved.error shouldBe null
        resolved.state.getEntity(controllerId)?.get<LifeTotalComponent>()?.life shouldBe 25
    }

    test("triggered target revalidation keeps nested target iteration aligned after compaction") {
        val controllerId = EntityId("partial-target-controller")
        val removedTargetId = EntityId("partial-target-removed")
        val legalTargetId = EntityId("partial-target-legal")
        val battlefield = ZoneKey(controllerId, Zone.BATTLEFIELD)
        val creature = { name: String ->
            ComponentContainer.of(
                CardComponent(
                    cardDefinitionId = name,
                    name = name,
                    manaCost = ManaCost.ZERO,
                    typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                    baseStats = CreatureStats(2, 2),
                ),
                ControllerComponent(controllerId),
                BattlefieldEntryTimestampComponent(
                    if (name == "removed-target") 501L else 502L,
                ),
            )
        }
        val initialState = GameState(
            zones = mapOf(battlefield to listOf(removedTargetId, legalTargetId)),
            turnOrder = listOf(controllerId),
        )
            .withEntity(
                controllerId,
                ComponentContainer.of(PlayerComponent("Partial Target Controller"), LifeTotalComponent(20)),
            )
            .withEntity(removedTargetId, creature("removed-target"))
            .withEntity(legalTargetId, creature("legal-target"))
        val ability = TriggeredAbilityOnStackComponent(
            sourceId = EntityId("partial-target-source"),
            sourceName = "Partial target source",
            controllerId = controllerId,
            effect = Effects.TapEachTarget(),
            description = "Tap the selected target",
        )
        val resolver = StackResolver(CardRegistry())
        val putResult = resolver.putTriggeredAbility(
            state = initialState,
            ability = ability,
            targets = listOf(
                ChosenTarget.Permanent(removedTargetId),
                ChosenTarget.Permanent(legalTargetId),
            ),
        )
        putResult.error shouldBe null

        val resolved = resolver.resolveTop(putResult.state.removeEntity(removedTargetId))

        resolved.error shouldBe null
        resolved.events.any { it is com.wingedsheep.engine.core.AbilityFizzledEvent } shouldBe false
        resolved.state.getBattlefield() shouldBe setOf(legalTargetId)
        resolved.state.getEntity(legalTargetId)?.has<TappedComponent>() shouldBe true
    }

    test("missing target entry stamps fail closed during identity revalidation") {
        val controllerId = EntityId("unstamped-target-controller")
        val targetId = EntityId("unstamped-target")
        val state = GameState(
            zones = mapOf(ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(targetId)),
        ).withEntity(
            targetId,
            ComponentContainer.of(
                CardComponent(
                    cardDefinitionId = "unstamped-target-card",
                    name = "Unstamped Target",
                    manaCost = ManaCost.ZERO,
                    typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                    baseStats = CreatureStats(2, 2),
                ),
                ControllerComponent(controllerId),
                BattlefieldEntryTimestampComponent(300L),
            ),
        )

        TargetsComponent.isDifferentObject(state, targetId, emptyMap()) shouldBe true
    }

    test("heterogeneous damage batches keep the matching source-recipient pair") {
        val controllerId = EntityId("batch-observer-controller")
        val secondSourceId = EntityId("batch-second-source")
        val firstRecipientId = EntityId("batch-first-recipient")
        val secondRecipientId = EntityId("batch-second-recipient")
        val sourceCard = CardComponent(
            cardDefinitionId = "batch-source-card",
            name = "Batch Source",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            baseStats = CreatureStats(2, 2),
        )
        val state = GameState(
            zones = mapOf(
                ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(secondSourceId),
            ),
        ).withEntity(
            secondSourceId,
            ComponentContainer.of(
                sourceCard,
                ControllerComponent(controllerId),
                BattlefieldEntryTimestampComponent(22L),
            ),
        )
        val observer = TriggerIndex.IndexedEntity(
            entityId = EntityId("batch-observer"),
            cardComponent = CardComponent(
                cardDefinitionId = "batch-observer-card",
                name = "Batch Observer",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT)),
            ),
            controllerId = controllerId,
            abilities = listOf(
                com.wingedsheep.sdk.scripting.TriggeredAbility(
                    id = AbilityId("batch-observer-ability"),
                    trigger = EventPattern.DealsDamageEvent(
                        recipient = RecipientFilter.AnyCreature,
                        sourceFilter = GameObjectFilter.Creature,
                        batch = true,
                    ),
                    binding = TriggerBinding.ANY,
                    effect = Effects.DrawCards(1),
                ),
            ),
        )
        val index = TriggerIndex(
            byCategory = emptyMap(),
            aurasByTarget = emptyMap(),
            grantProviders = emptyList(),
            statics = BattlefieldStaticsIndex.EMPTY,
            damageToYouObservers = emptyList(),
            subtypeDamageObservers = emptyList(),
            damageObservers = listOf(observer),
            creatureDamageDeathTrackers = emptyList(),
        )
        val detector = DamageTriggerDetector(
            TriggerAbilityResolver(CardRegistry(), AbilityRegistry()),
            TriggerMatcher(PredicateEvaluator(), ConditionEvaluator()),
        )
        val unknownSourceEvent = damageEvent.copy(
            sourceId = null,
            targetId = firstRecipientId,
            damageSourceLastKnownSnapshot = null,
            damageRecipientLastKnownSnapshot = EntitySnapshot(firstRecipientId, power = 2),
        )
        val secondEvent = damageEvent.copy(
            sourceId = secondSourceId,
            targetId = secondRecipientId,
            damageSourceLastKnownSnapshot = EntitySnapshot(
                secondSourceId,
                power = 2,
                battlefieldEntryTimestamp = 22L,
            ),
            damageRecipientLastKnownSnapshot = EntitySnapshot(secondRecipientId, power = 3),
        )
        val triggers = mutableListOf<PendingTrigger>()

        detector.detectDamageObserverBatchTriggers(
            state = state,
            events = listOf(unknownSourceEvent, secondEvent),
            triggers = triggers,
            index = index,
        )

        triggers.size shouldBe 1
        val context = triggers.single().triggerContext
        context.triggeringEntityId shouldBe secondSourceId
        context.damageSourceEntityId shouldBe secondSourceId
        context.damageRecipientEntityId shouldBe secondRecipientId
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

    test("source-blind damage-to-you observers accept an unknown source") {
        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())
        val observer = TriggerIndex.IndexedEntity(
            entityId = EntityId("source-blind-observer"),
            cardComponent = CardComponent(
                cardDefinitionId = "source-blind-observer",
                name = "Source-Blind Observer",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT))
            ),
            controllerId = EntityId("player"),
            abilities = listOf(
                com.wingedsheep.sdk.scripting.TriggeredAbility(
                    id = AbilityId("source-blind-observer-ability"),
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
        val event = damageEvent.copy(
            sourceId = null,
            targetId = EntityId("player"),
            targetIsPlayer = true,
            recipientKind = DamageRecipientKind.PLAYER,
            recipientKinds = DamageRecipientKindSet.PLAYER,
            damageSourceLastKnownSnapshot = null,
        )
        val triggers = mutableListOf<PendingTrigger>()

        DamageTriggerDetector(
            TriggerAbilityResolver(CardRegistry(), AbilityRegistry()),
            matcher,
        ).detectDamageToControllerTriggers(
            GameState(), event, triggers, GameState().projectedState, index
        )

        triggers shouldHaveSize 1
        triggers.single().triggerContext.triggeringEntityId shouldBe event.targetId
        triggers.single().triggerContext.damageSourceEntityId shouldBe null
        triggers.single().triggerContext.damageRecipientEntityId shouldBe event.targetId
    }

    test("subtype damage observers use source LKI after same-id reuse") {
        val controllerId = EntityId("subtype-observer-controller")
        val sourceId = EntityId("reused-subtype-source")
        val observer = TriggerIndex.IndexedEntity(
            entityId = EntityId("subtype-observer"),
            cardComponent = CardComponent(
                cardDefinitionId = "subtype-observer",
                name = "Subtype Observer",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT)),
            ),
            controllerId = controllerId,
            abilities = listOf(
                com.wingedsheep.sdk.scripting.TriggeredAbility(
                    id = AbilityId("subtype-observer-ability"),
                    trigger = EventPattern.DealsDamageEvent(
                        damageType = DamageType.Combat,
                        recipient = RecipientFilter.AnyPlayer,
                        sourceFilter = GameObjectFilter.Creature.withSubtype("Goblin"),
                    ),
                    binding = TriggerBinding.ANY,
                    effect = Effects.DrawCards(1),
                )
            ),
        )
        val index = TriggerIndex(
            byCategory = emptyMap(),
            aurasByTarget = emptyMap(),
            grantProviders = emptyList(),
            statics = BattlefieldStaticsIndex.EMPTY,
            damageToYouObservers = emptyList(),
            subtypeDamageObservers = listOf(observer),
            damageObservers = emptyList(),
            creatureDamageDeathTrackers = emptyList(),
        )
        val state = GameState(
            zones = mapOf(ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(sourceId)),
        ).withEntity(
            sourceId,
            ComponentContainer.of(
                CardComponent(
                    cardDefinitionId = "replacement-elf",
                    name = "Replacement Elf",
                    manaCost = ManaCost.ZERO,
                    typeLine = TypeLine(
                        cardTypes = setOf(CardType.CREATURE),
                        subtypes = setOf(Subtype("Elf")),
                    ),
                    baseStats = CreatureStats(2, 2),
                ),
                ControllerComponent(controllerId),
                BattlefieldEntryTimestampComponent(102L),
            )
        )
        val event = damageEvent.copy(
            sourceId = sourceId,
            targetId = EntityId("damaged-player"),
            targetIsPlayer = true,
            recipientKind = DamageRecipientKind.PLAYER,
            recipientKinds = DamageRecipientKindSet.PLAYER,
            damageSourceLastKnownSnapshot = EntitySnapshot(
                entityId = sourceId,
                controllerId = controllerId,
                typeLine = TypeLine(
                    cardTypes = setOf(CardType.CREATURE),
                    subtypes = setOf(Subtype("Goblin")),
                ),
                battlefieldEntryTimestamp = 101L,
            ),
        )
        val triggers = mutableListOf<PendingTrigger>()

        DamageTriggerDetector(
            TriggerAbilityResolver(CardRegistry(), AbilityRegistry()),
            TriggerMatcher(PredicateEvaluator(), ConditionEvaluator()),
        ).detectSubtypeDamageToPlayerTriggers(
            state, event, triggers, state.projectedState, index
        )

        triggers shouldHaveSize 1
        triggers.single().triggerContext.damageSourceEntityId shouldBe sourceId
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

    test("damage LKI source filters preserve projected supertypes") {
        val controllerId = EntityId("legendary-source-controller")
        val legendaryId = EntityId("legendary-source")
        val legendaryCard = CardComponent(
            cardDefinitionId = "legendary-source-card",
            name = "Legendary Source",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(
                cardTypes = setOf(CardType.CREATURE),
            ),
            baseStats = CreatureStats(2, 2),
        )
        val state = GameState(
            zones = mapOf(ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(legendaryId))
        ).withEntity(
            legendaryId,
            ComponentContainer.of(
                legendaryCard,
                ControllerComponent(controllerId),
                RingBearerComponent(ownerId = controllerId),
                BattlefieldEntryTimestampComponent(91L),
            )
        )
        state.projectedState.isLegendary(legendaryId) shouldBe true
        val snapshot = DamageUtils.captureDamageEntitySnapshot(state, legendaryId)

        snapshot?.typeLine?.supertypes shouldBe setOf(Supertype.LEGENDARY)
        TriggerMatcher(PredicateEvaluator(), ConditionEvaluator()).matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(
                recipient = RecipientFilter.AnyCreature,
                sourceFilter = GameObjectFilter(
                    cardPredicates = listOf(CardPredicate.IsLegendary),
                ),
            ),
            damageEvent.copy(
                sourceId = legendaryId,
                damageSourceLastKnownSnapshot = snapshot,
            ),
            GameState(),
            controllerId,
        ) shouldBe true
        TriggerMatcher(PredicateEvaluator(), ConditionEvaluator()).matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(
                recipient = RecipientFilter.AnyCreature,
                sourceFilter = GameObjectFilter(
                    cardPredicates = listOf(CardPredicate.IsNonlegendary),
                ),
            ),
            damageEvent.copy(
                sourceId = legendaryId,
                damageSourceLastKnownSnapshot = snapshot,
            ),
            GameState(),
            controllerId,
        ) shouldBe false
    }

    test("damage LKI basic-land predicates read projected supertypes") {
        val snapshot = EntitySnapshot(
            entityId = sourceId,
            typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
            supertypes = setOf(Supertype.BASIC.name),
            battlefieldEntryTimestamp = 401L,
        )
        val event = damageEvent.copy(
            sourceId = sourceId,
            damageSourceLastKnownSnapshot = snapshot,
        )

        TriggerMatcher(PredicateEvaluator(), ConditionEvaluator()).matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(
                recipient = RecipientFilter.AnyCreature,
                sourceFilter = GameObjectFilter(
                    cardPredicates = listOf(CardPredicate.IsBasicLand),
                ),
            ),
            event,
            GameState(),
        ) shouldBe true
    }

    test("live basic-land filters read projected supertypes") {
        val controllerId = EntityId("live-basic-land-controller")
        val landId = EntityId("live-basic-land")
        val state = GameState(
            zones = mapOf(ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(landId)),
        ).withEntity(
            landId,
            ComponentContainer.of(
                CardComponent(
                    cardDefinitionId = "live-basic-land-card",
                    name = "Projected Basic Land",
                    manaCost = ManaCost.ZERO,
                    typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
                ),
                ControllerComponent(controllerId),
                BattlefieldEntryTimestampComponent(402L),
            ),
        )
        val projected = ProjectedState(
            state,
            mapOf(
                landId to ProjectedValues(
                    types = setOf("LAND", Supertype.BASIC.name),
                    controllerId = controllerId,
                ),
            ),
        )

        PredicateEvaluator().matches(
            state = state,
            projected = projected,
            entityId = landId,
            filter = GameObjectFilter(
                cardPredicates = listOf(CardPredicate.IsBasicLand),
            ),
            context = PredicateContext(controllerId = controllerId),
        ) shouldBe true
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
