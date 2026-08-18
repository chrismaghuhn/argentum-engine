package com.wingedsheep.engine.event

import com.wingedsheep.engine.handlers.PipelineState
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.battlefield.DamageSourceLki
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.AbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.serialization.CardSerialization
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.conditions.Condition
import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.targets.TargetRequirement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Private, deterministic identity used only to normalize an unordered trigger-choice domain.
 *
 * This deliberately does not use EntityId, AbilityId, delayed-trigger IDs, UUIDs, map iteration,
 * or object toString output. Equal keys mean that the two occurrences have the same semantic
 * payload available to this choice boundary, so retaining either internal pairing is equivalent;
 * the ordinal handles exposed to the actor remain the small, opaque decision-domain tokens.
 */
internal object TriggerOrderingKey {

    /**
     * These SDK arrays represent sets or commutative unions/conjunctions.  Ordered arrays such as
     * effect sequences, target slots, and mode selections deliberately remain in source order.
     */
    private val unorderedJsonArrayFields = setOf(
        "activeZones",
        "alternatives",
        "anyOf",
        "cardPredicates",
        "conditions",
        "delvedCards",
        "discardedCards",
        "entityIds",
        "exiledCards",
        "predicates",
        "sacrificedPermanents",
        "statePredicates",
        "subtypes",
        "tapForGenericPermanents",
        "tappedPermanents",
        "variableCostPermanents",
        "zones",
    )

    /**
     * EntityId types are erased when SDK values are encoded to JSON. Keep this list explicit rather
     * than treating every `*Id` field as an entity reference: fields such as `modeId` are semantic
     * card-defined keys, while these fields are the SDK's actual EntityId-bearing coordinates.
     */
    private val entityReferenceFields = setOf(
        "beheldCards",
        "blightTargets",
        "bouncedPermanents",
        "delvedCards",
        "discardedCards",
        "entityId",
        "entityIds",
        "exiledCards",
        "excludeSourceId",
        "harmonizeCreature",
        "sacrificedPermanents",
        "tapForGenericPermanents",
        "tappedPermanents",
        "variableCostPermanents",
    )

    /** Map fields whose JSON object keys are EntityIds rather than ordinary semantic strings. */
    private val entityReferenceObjectKeyFields = setOf("convokedCreatures")

    /** Fields that contain nested ability objects whose generated `id` is runtime identity. */
    private val abilityObjectFields = setOf("ability", "grantedAbility")
    private val abilityCollectionFields = setOf(
        "activatedAbilities",
        "grantedActivatedAbilities",
        "stateTriggeredAbilities",
        "triggeredAbilities",
    )

    /** Raw generated ability handles are intentionally absent from semantic serialization. */
    private val runtimeFieldsToOmit = setOf("abilityId")

    fun forTrigger(state: GameState, trigger: PendingTrigger): String = fields(
        "source", semanticEntityKey(state, trigger.sourceId), trigger.sourceName,
        "ability", abilityKey(state, trigger.ability),
        "controller-stage", trigger.placementStage.name,
        "trigger-stage", trigger.stage.name,
        "granter", semanticEntityKey(state, trigger.granterId),
        "context", contextKey(state, trigger.triggerContext),
        "delayed", trigger.consumesDelayedTriggerId != null,
        "saga", trigger.sagaChapterInfo?.let { fields(it.chapterNumber, it.finalChapterNumber) },
        "pipeline", pipelineKey(state, trigger.carriedPipeline),
        "occurrences", canonicalOccurrenceCandidates(state, trigger.occurrenceChoice).joinToString("\u0002") {
            occurrenceKey(state, it)
        },
    )

    internal fun canonicalOccurrenceCandidates(
        state: GameState,
        candidates: List<DelayedTriggerOccurrenceCandidate>,
    ): List<DelayedTriggerOccurrenceCandidate> = candidates.sortedBy { occurrenceKey(state, it) }

    internal fun occurrenceCandidateKey(
        state: GameState,
        candidate: DelayedTriggerOccurrenceCandidate,
    ): String = occurrenceKey(state, candidate)

    private fun occurrenceKey(
        state: GameState,
        candidate: DelayedTriggerOccurrenceCandidate,
    ): String = fields(
        candidate.sourceName,
        semanticEntityKey(state, candidate.sourceId),
        semanticEntityKey(state, candidate.controllerId),
        abilityKey(state, candidate.ability),
        candidate.stage.name,
        candidate.observedPlacementStage?.name ?: "<legacy>",
        contextKey(state, candidate.triggerContext),
        semanticEntityKey(state, candidate.granterId),
        candidate.consumesDelayedTriggerId != null,
        candidate.sagaChapterInfo?.let { fields(it.chapterNumber, it.finalChapterNumber) },
        pipelineKey(state, candidate.carriedPipeline),
    )

    private fun abilityKey(
        state: GameState,
        ability: TriggeredAbility,
    ): String = canonicalJsonKey(
        CardSerialization.compactJson.encodeToJsonElement(
            TriggeredAbility.serializer(),
            ability,
        ),
        state,
        emptySet(),
        rootFieldsToOmit = setOf("id"),
    )

    private fun conditionKey(
        state: GameState,
        condition: Condition?,
        visited: Set<EntityId> = emptySet(),
    ): String = condition?.let {
        canonicalJsonKey(
            CardSerialization.compactJson.encodeToJsonElement(Condition.serializer(), it),
            state,
            visited,
        )
    } ?: "<none>"

    private fun effectKey(
        state: GameState,
        effect: Effect,
        visited: Set<EntityId> = emptySet(),
    ): String = canonicalJsonKey(
        CardSerialization.compactJson.encodeToJsonElement(Effect.serializer(), effect),
        state,
        visited,
    )

    private fun abilityIdentityKey(identity: com.wingedsheep.sdk.scripting.AbilityIdentity): String =
        // The ability handle is a process-local/runtime value. The definition coordinate is the
        // stable semantic part; the stack payload and effect shape carry the behavior itself.
        fields(identity.cardDefinitionId)

    private fun contextKey(state: GameState, context: TriggerContext): String = fields(
        semanticEntityKey(state, context.triggeringEntityId),
        semanticEntityKey(state, context.triggeringPlayerId),
        context.damageAmount,
        context.step?.name,
        context.xValue,
        context.counterCount,
        context.totalCounterCount,
        context.minusOneMinusOneCounterCount,
        semanticEntityKey(state, context.targetingSourceEntityId),
        context.lastKnownPower,
        context.lastKnownToughness,
        context.diedBatchTotalPower,
        context.lastKnownSubtypes?.sorted(),
        context.lastKnownCardTypes?.sorted(),
        context.lastKnownCounters?.entries
            ?.sortedBy { it.key }
            ?.map { listOf(it.key, it.value) },
        context.lastKnownDamageDealtByPlayers?.let { sortedEntityIntMap(state, it) },
        context.lastKnownBlockingOrBlockedByIds
            ?.map { semanticEntityKey(state, it) }
            ?.sorted(),
        context.modesChosenCount,
        context.manaSpentOnTriggeringSpell,
        context.colorsSpentOnTriggeringSpell,
        context.manaValueOfTriggeringSpell,
        context.xValueOfTriggeringSpell,
        context.enchantedCreatureLastKnownPower,
        context.scryCount,
        context.discardedCardCount,
        context.discoverValue,
        context.excessDamageAmount,
        context.recipientToughnessAtDamage,
        context.capturedEntityIds?.map { semanticEntityKey(state, it) },
        semanticEntityKey(state, context.attachedToEntityId),
        semanticEntityKey(state, context.unattachedFromEntityId),
    )

    private fun pipelineKey(
        state: GameState,
        pipeline: PipelineState?,
        visited: Set<EntityId> = emptySet(),
    ): String = pipeline?.let {
        fields(
            it.storedCollections.entries.sortedBy { entry -> entry.key }.map { entry ->
                listOf(entry.key, entry.value.map { id -> semanticEntityKey(state, id, visited) })
            },
            it.namedTargets.entries.sortedBy { entry -> entry.key }.map { entry ->
                listOf(entry.key, chosenTargetKey(state, entry.value, visited))
            },
            it.chosenValues.entries.sortedBy { entry -> entry.key }.map { listOf(it.key, it.value) },
            it.storedNumbers.entries.sortedBy { entry -> entry.key }.map { listOf(it.key, it.value) },
            it.storedStringLists.entries.sortedBy { entry -> entry.key }
                .map { listOf(it.key, it.value) },
            it.storedSubtypeGroups.entries.sortedBy { entry -> entry.key }.map { entry ->
                listOf(entry.key, entry.value.map { group -> group.sorted() })
            },
            semanticEntityKey(state, it.iterationTarget, visited),
        )
    } ?: "<none>"

    private fun chosenTargetKey(
        state: GameState,
        target: ChosenTarget,
        visited: Set<EntityId> = emptySet(),
    ): String = when (target) {
        is ChosenTarget.Player -> fields("player", semanticEntityKey(state, target.playerId, visited))
        is ChosenTarget.Permanent -> fields("permanent", semanticEntityKey(state, target.entityId, visited))
        is ChosenTarget.Card -> fields(
            "card", semanticEntityKey(state, target.cardId, visited),
            semanticEntityKey(state, target.ownerId, visited), target.zone.name,
        )
        is ChosenTarget.Spell -> fields("spell", semanticEntityKey(state, target.spellEntityId, visited))
    }

    /**
     * Chosen targets are a separate stack component rather than part of the spell/ability
     * component.  They therefore have to be included explicitly: two otherwise identical
     * cardless stack objects can have different target slots and resolve differently.
     */
    private fun targetsKey(
        state: GameState,
        targets: TargetsComponent?,
        visited: Set<EntityId>,
    ): String = targets?.let {
        fields(
            it.targets.map { target -> chosenTargetKey(state, target, visited) },
            it.targetRequirements.map { requirement -> targetRequirementKey(state, requirement, visited) },
            it.targetEntryStamps.entries
                .map { (entityId, stamp) ->
                    fields(semanticEntityKey(state, entityId, visited), stamp)
                }
                .sorted(),
        )
    } ?: "<none>"

    /**
     * Target legality is defined by the serialized SDK data, not by presentation text.  The
     * description intentionally collapses distinct predicate variants (for example a target
     * player reference and a referenced-player expression), so it cannot be part of a semantic
     * ordering key.  Canonicalize the complete polymorphic requirement tree instead, sorting JSON
     * object keys while preserving semantically ordered arrays.
     *
     * EntityId values embedded in a requirement (for example SpecificEntity) are projected
     * through the same state-relative identity used elsewhere in this key.  Unknown references
     * fail closed to one non-ID marker rather than leaking an allocation handle into replay data.
     */
    private fun targetRequirementKey(
        state: GameState,
        requirement: TargetRequirement,
        visited: Set<EntityId> = emptySet(),
    ): String = canonicalJsonKey(
        CardSerialization.compactJson.encodeToJsonElement(
            TargetRequirement.serializer(),
            requirement,
        ),
        state,
        visited,
    )

    private fun canonicalJsonKey(
        element: JsonElement,
        state: GameState,
        visited: Set<EntityId>,
        fieldName: String? = null,
        rootFieldsToOmit: Set<String> = emptySet(),
        atRoot: Boolean = true,
        abilityObject: Boolean = false,
    ): String = when (element) {
        is JsonObject -> fields(
            element.entries
                // Description overrides and ability handles are presentation/runtime data, not
                // part of the semantic behavior represented by this ordering key.
                .filter {
                    it.key != "descriptionOverride" &&
                        it.key !in runtimeFieldsToOmit &&
                        (!atRoot || it.key !in rootFieldsToOmit) &&
                        !(abilityObject && it.key == "id")
                }
                .map { (key, value) ->
                    val canonicalObjectKey = if (fieldName in entityReferenceObjectKeyFields) {
                        fields("entity-id", semanticEntityKey(state, EntityId(key), visited))
                    } else {
                        key
                    }
                    canonicalObjectKey to canonicalJsonKey(
                        value,
                        state,
                        visited,
                        fieldName = key,
                        rootFieldsToOmit = rootFieldsToOmit,
                        atRoot = false,
                        abilityObject = key in abilityObjectFields,
                    )
                }
                .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
                .map { (key, value) -> listOf(key, value) }
        )
        is JsonArray -> {
            val values = element.map {
                canonicalJsonKey(
                    it,
                    state,
                    visited,
                    fieldName = fieldName,
                    rootFieldsToOmit = rootFieldsToOmit,
                    atRoot = false,
                    abilityObject = fieldName in abilityCollectionFields,
                )
            }
            fields(if (fieldName in unorderedJsonArrayFields) values.sorted() else values)
        }
        is JsonNull -> "null"
        is JsonPrimitive -> {
            if (element.isString && fieldName in entityReferenceFields) {
                val referencedId = EntityId(element.content)
                val projectedReference = when {
                    state.turnOrder.contains(referencedId) || state.getEntity(referencedId) != null ->
                        semanticEntityKey(state, referencedId, visited)
                    else -> "<missing-entity>"
                }
                fields("entity-id", projectedReference)
            } else {
                fields(
                    if (element.isString) "string" else "primitive",
                    element.content,
                )
            }
        }
    }

    /**
     * Last-known snapshots may outlive their entity ids.  Encode the captured semantic values,
     * never the allocation handle, so a copied/snapshotted stack object remains replay-stable.
     */
    private fun snapshotKey(
        state: GameState,
        snapshot: EntitySnapshot,
        visited: Set<EntityId>,
    ): String = fields(
        snapshot.power,
        snapshot.toughness,
        snapshot.subtypes.map { it }.sorted(),
        snapshot.supertypes.map { it }.sorted(),
        semanticEntityKey(state, snapshot.controllerId, visited),
        snapshot.counters.entries.sortedBy { it.key }.map { listOf(it.key, it.value) },
        snapshot.keywords.toList().sorted(),
        snapshot.lostAllAbilities,
        snapshot.typeLine?.let { typeLine ->
            fields(
                typeLine.supertypes.map { it.name }.sorted(),
                typeLine.cardTypes.map { it.name }.sorted(),
                typeLine.subtypes.map { it.value }.sorted(),
            )
        },
        snapshot.cardDefinitionId,
        snapshot.name,
        snapshot.copyOfOriginalName,
        semanticEntityKey(state, snapshot.attachedTo, visited),
        snapshot.wasEquipped,
        snapshot.attachmentIds.map { semanticEntityKey(state, it, visited) },
        snapshot.wasEnchanted,
        snapshot.blockingOrBlockedByIds
            .map { semanticEntityKey(state, it, visited) }
            .sorted(),
        snapshot.wasAttacking,
        snapshot.wasToken,
        snapshot.wasSuspected,
        sortedEntityIntMap(state, snapshot.damageDealtByPlayers, visited),
        snapshot.damageSources.map { damageSourceKey(state, it, visited) }.sorted(),
        snapshot.castX,
    )

    private fun damageSourceKey(
        state: GameState,
        source: DamageSourceLki,
        visited: Set<EntityId>,
    ): String = fields(
        semanticEntityKey(state, source.sourceControllerId, visited),
        source.sourceSubtypes.map { it.value }.sorted(),
        source.sourceWasCreature,
    )

    /**
     * The state-relative description is enough to distinguish semantic roles without making the
     * routing identity depend on an allocation-order handle. Players use their turn-order role;
     * cards use definition/visible characteristics, zone role, and projected combat state.
     */
    private fun semanticEntityKey(
        state: GameState,
        entityId: EntityId?,
        visited: Set<EntityId> = emptySet(),
    ): String {
        if (entityId == null) return "<none>"
        val playerRole = state.turnOrder.indexOf(entityId)
        if (playerRole >= 0) return "player-role:$playerRole"
        if (entityId in visited) return "entity-cycle"

        val entity = state.getEntity(entityId)
        val nextVisited = visited + entityId
        val card = entity?.get<CardComponent>()
        val hasStackPayload = entity?.let {
            it.has<TriggeredAbilityOnStackComponent>() ||
                it.has<ActivatedAbilityOnStackComponent>() ||
                it.has<AbilityOnStackComponent>() ||
                it.has<SpellOnStackComponent>()
        } == true
        if (hasStackPayload) {
            return fields(
                "stack-entity",
                card?.let { cardEntityKey(state, entityId, entity, it, nextVisited) } ?: "<none>",
                stackPayloadKey(state, entity, nextVisited),
            )
        }
        if (card == null) {
            return stackPayloadKey(state, entity, nextVisited)
        }
        return cardEntityKey(state, entityId, entity, card, nextVisited)
    }

    private fun cardEntityKey(
        state: GameState,
        entityId: EntityId,
        entity: com.wingedsheep.engine.state.ComponentContainer,
        card: CardComponent,
        visited: Set<EntityId>,
    ): String {
        val zoneRoles = state.zones.entries
            .filter { (_, contents) -> entityId in contents }
            .map { (key, _) ->
                val ownerRole = state.turnOrder.indexOf(key.ownerId)
                "${key.zoneType.name}:owner-role:$ownerRole"
            }
            .sorted()
        val counters = entity.get<CountersComponent>()?.counters
            ?.entries
            ?.sortedBy { it.key.name }
            ?.map { listOf(it.key.name, it.value) }
        val projected = state.projectedState
        val controllerRole = projected.getController(entityId)?.let { state.turnOrder.indexOf(it) }
        val attachedTo = entity.get<AttachedToComponent>()?.targetId

        return fields(
            card.cardDefinitionId,
            card.name,
            semanticEntityKey(state, card.ownerId, visited),
            card.typeLine.cardTypes.map { it.name }.sorted(),
            card.typeLine.supertypes.map { it.name }.sorted(),
            card.typeLine.subtypes.map { it.value }.sorted(),
            zoneRoles,
            controllerRole,
            projected.getPower(entityId),
            projected.getToughness(entityId),
            entity.has<TappedComponent>(),
            counters,
            semanticEntityKey(state, attachedTo, visited),
        )
    }

    /**
     * Stack payloads may be cardless or may retain their CardComponent (notably spell copies).
     * Their allocation handles are not semantic identity, but the carried ability/spell object is:
     * effects such as Firebender Ascension copy the exact triggering ability object. Keep the stable
     * payload of that object in the key so two distinct stack targets do not collapse to a placeholder.
     */
    private fun stackPayloadKey(
        state: GameState,
        entity: com.wingedsheep.engine.state.ComponentContainer?,
        visited: Set<EntityId>,
    ): String {
        if (entity == null) return "entity-without-card"
        val stackTargetsKey = targetsKey(state, entity.get<TargetsComponent>(), visited)
        entity.get<TriggeredAbilityOnStackComponent>()?.let { triggered ->
            return fields(
                "triggered-stack",
                stackTargetsKey,
                semanticEntityKey(state, triggered.sourceId, visited),
                triggered.sourceName,
                semanticEntityKey(state, triggered.controllerId, visited),
                effectKey(state, triggered.effect, visited),
                triggered.abilityIdentity?.let(::abilityIdentityKey),
                triggered.triggerDamageAmount,
                semanticEntityKey(state, triggered.triggeringEntityId, visited),
                semanticEntityKey(state, triggered.triggeringPlayerId, visited),
                triggered.xValue,
                triggered.triggerCounterCount,
                triggered.triggerTotalCounterCount,
                triggered.triggerLastKnownCounters?.entries
                    ?.sortedBy { it.key }
                    ?.map { listOf(it.key, it.value) },
                triggered.triggerLastKnownDamageDealtByPlayers?.let {
                    sortedEntityIntMap(state, it, visited)
                },
                triggered.triggerLastKnownBlockingOrBlockedByIds
                    ?.map { semanticEntityKey(state, it, visited) }
                    ?.sorted(),
                triggered.triggerLastKnownSubtypes?.sorted(),
                triggered.triggerLastKnownCardTypes?.sorted(),
                triggered.lastKnownPower,
                triggered.lastKnownToughness,
                triggered.diedBatchTotalPower,
                triggered.triggerModesChosenCount,
                triggered.enchantedCreatureLastKnownPower,
                semanticEntityKey(state, triggered.targetingSourceEntityId, visited),
                semanticEntityKey(state, triggered.triggerUnattachedFromEntityId, visited),
                semanticEntityKey(state, triggered.granterId, visited),
                triggered.damageDistribution?.let {
                    damageDistributionKey(state, it, entity.get<TargetsComponent>()?.targets.orEmpty(), visited)
                },
                triggered.copyIndex,
                triggered.copyTotal,
                triggered.triggerScryCount,
                triggered.triggerDiscardCount,
                triggered.triggerDiscoverValue,
                triggered.triggerExcessDamageAmount,
                triggered.triggerRecipientToughness,
                triggered.triggerManaSpentOnTriggeringSpell,
                triggered.triggerColorsSpentOnTriggeringSpell,
                triggered.triggerManaValueOfTriggeringSpell,
                triggered.triggerXValueOfTriggeringSpell,
                triggered.chosenModes,
                triggered.modeTargetsOrdered.map { targets ->
                    targets.map { target -> chosenTargetKey(state, target, visited) }
                },
                triggered.modeTargetRequirements.entries
                    .sortedBy { it.key }
                    .map { (mode, requirements) ->
                        listOf(mode, requirements.map { targetRequirementKey(state, it, visited) })
                    },
                triggered.modeDamageDistribution.entries
                    .sortedBy { it.key }
                    .map { (mode, allocation) ->
                        listOf(
                            mode,
                            damageDistributionKey(
                                state,
                                allocation,
                                modeTargetsFor(triggered.chosenModes, triggered.modeTargetsOrdered, mode),
                                visited,
                            )
                        )
                    },
                triggered.capturedEntityIds.map { semanticEntityKey(state, it, visited) },
                triggered.sagaChapterInfo?.let {
                    fields(it.chapterNumber, it.finalChapterNumber)
                },
                pipelineKey(state, triggered.carriedPipeline, visited),
                conditionKey(state, triggered.interveningIf, visited),
            )
        }
        entity.get<ActivatedAbilityOnStackComponent>()?.let { activated ->
            return fields(
                "activated-stack",
                stackTargetsKey,
                semanticEntityKey(state, activated.sourceId, visited),
                activated.sourceName,
                semanticEntityKey(state, activated.controllerId, visited),
                effectKey(state, activated.effect, visited),
                activated.sacrificedPermanents.map { snapshotKey(state, it, visited) },
                activated.abilityIdentity?.let(::abilityIdentityKey),
                semanticEntityKey(state, activated.granterId, visited),
                activated.xValue,
                activated.tappedPermanents.map { semanticEntityKey(state, it, visited) },
                activated.tappedEntitySnapshots.map { snapshotKey(state, it, visited) },
                activated.lastKnownSourceCounters.entries
                    .sortedBy { it.key }
                    .map { listOf(it.key, it.value) },
                activated.lastKnownSourceSnapshot?.let { snapshotKey(state, it, visited) },
                activated.lastKnownSourceAttachments
                    .map { semanticEntityKey(state, it, visited) }
                    .sorted(),
                activated.damageDistribution?.let {
                    damageDistributionKey(state, it, entity.get<TargetsComponent>()?.targets.orEmpty(), visited)
                },
            )
        }
        entity.get<AbilityOnStackComponent>()?.let { legacy ->
            return fields(
                "legacy-ability-stack",
                stackTargetsKey,
                semanticEntityKey(state, legacy.sourceId, visited),
                semanticEntityKey(state, legacy.controllerId, visited),
                effectKey(state, legacy.effect, visited),
            )
        }
        entity.get<SpellOnStackComponent>()?.let { spell ->
            return fields(
                "spell-stack",
                stackTargetsKey,
                semanticEntityKey(state, spell.casterId, visited),
                spell.xValue,
                spell.declaredCostSlot?.name,
                spell.wasBlightPaid,
                spell.wasWaterbendPaid,
                semanticEntityKey(state, spell.giftRecipient, visited),
                spell.splicedCardNames,
                spell.splicedTargetsOrdered.map { targets ->
                    targets.map { target -> chosenTargetKey(state, target, visited) }
                },
                spell.chosenModes,
                spell.modeTargetsOrdered.map { targets ->
                    targets.map { target -> chosenTargetKey(state, target, visited) }
                },
                spell.modeTargetRequirements.entries
                    .sortedBy { it.key }
                    .map { (mode, requirements) ->
                        listOf(mode, requirements.map { targetRequirementKey(state, it, visited) })
                    },
                spell.modeDamageDistribution.entries
                    .sortedBy { it.key }
                    .map { (mode, allocation) ->
                        listOf(
                            mode,
                            damageDistributionKey(
                                state,
                                allocation,
                                modeTargetsFor(spell.chosenModes, spell.modeTargetsOrdered, mode),
                                visited,
                            )
                        )
                    },
                spell.sacrificedPermanents.map { snapshotKey(state, it, visited) },
                spell.castFaceDown,
                spell.damageDistribution?.let {
                    damageDistributionKey(state, it, entity.get<TargetsComponent>()?.targets.orEmpty(), visited)
                },
                spell.chosenCreatureType,
                spell.exiledCardCount,
                spell.additionalCostBlightAmount,
                spell.additionalCostPayXLifeAmount,
                spell.castFromZone?.name,
                spell.alternativeCost?.name,
                spell.wasWarped,
                spell.wasDashed,
                spell.wasEvoked,
                spell.wasImpending,
                spell.wasCleaved,
                spell.wasSneaked,
                semanticEntityKey(state, spell.sneakAttackDefenderId, visited),
                spell.wasWebSlung,
                spell.webSlungReturnedManaValue,
                spell.wasMayhem,
                spell.beheldCards.map { semanticEntityKey(state, it, visited) },
                spell.discardedAsCostCards.map { semanticEntityKey(state, it, visited) },
                spell.chosenEntitySnapshots.map { snapshotKey(state, it, visited) },
                spell.manaSpentWhite,
                spell.manaSpentBlue,
                spell.manaSpentBlack,
                spell.manaSpentRed,
                spell.manaSpentGreen,
                spell.manaSpentColorless,
                spell.manaSpentBySubtype.entries
                    .sortedBy { it.key.value }
                    .map { listOf(it.key.value, it.value) },
                spell.manaSpentOnXByColor.entries
                    .sortedBy { it.key.name }
                    .map { listOf(it.key.name, it.value) },
                spell.faceIndex,
                spell.castTimeFlags.sorted(),
            )
        }
        return "entity-without-card"
    }

    private fun sortedEntityIntMap(
        state: GameState,
        values: Map<EntityId, Int>,
        visited: Set<EntityId> = emptySet(),
    ): List<List<Any?>> = values.entries
        .sortedWith(
            compareBy<Map.Entry<EntityId, Int>> { semanticEntityKey(state, it.key, visited) }
                .thenBy { it.value }
        )
        .map { listOf(semanticEntityKey(state, it.key, visited), it.value) }

    /**
     * A damage distribution is a relation from a chosen target slot to its assigned amount.
     * Sorting only by the target's semantic key loses that relation when two distinct targets
     * happen to have the same projected identity.  The ordered target slot is already part of
     * the stack payload, so it is the stable, ID-free discriminator for a valid distribution.
     *
     * Entries that do not resolve to a target slot are malformed or from a legacy payload.  They
     * retain the old ID-free semantic-key fallback so allocation handles never enter the key.
     */
    private fun damageDistributionKey(
        state: GameState,
        values: Map<EntityId, Int>,
        targets: List<ChosenTarget>,
        visited: Set<EntityId>,
    ): List<List<Any?>> {
        val targetSlotByEntity = targets
            .withIndex()
            .associateBy { chosenTargetEntityId(it.value) }
        val matched = values.entries
            .mapNotNull { entry ->
                targetSlotByEntity[entry.key]?.let { slot ->
                    listOf<Any?>("target-slot", slot.index, entry.value)
                }
            }
            .sortedBy { it[1] as Int }
        val unmatched = values.entries
            .filter { it.key !in targetSlotByEntity }
            .map { entry ->
                listOf<Any?>(
                    "unmatched-target",
                    semanticEntityKey(state, entry.key, visited),
                    entry.value,
                )
            }
            .sortedWith(
                compareBy<List<Any?>> { it[1] as String }
                    .thenBy { it[2] as Int }
            )
        return matched + unmatched
    }

    private fun modeTargetsFor(
        chosenModes: List<Int>,
        modeTargetsOrdered: List<List<ChosenTarget>>,
        mode: Int,
    ): List<ChosenTarget> = chosenModes
        .indexOf(mode)
        .takeIf { it >= 0 }
        ?.let { modeTargetsOrdered.getOrNull(it) }
        .orEmpty()

    private fun chosenTargetEntityId(target: ChosenTarget): EntityId = when (target) {
        is ChosenTarget.Player -> target.playerId
        is ChosenTarget.Permanent -> target.entityId
        is ChosenTarget.Card -> target.cardId
        is ChosenTarget.Spell -> target.spellEntityId
    }

    private fun fields(vararg values: Any?): String = values.joinToString("\u0001") { value ->
        when (value) {
            null -> "0:"
            is Boolean -> if (value) "1:true" else "1:false"
            is Int, is Long, is Double, is Float -> {
                val text = value.toString()
                "${text.length}:$text"
            }
            is String -> "${value.length}:$value"
            is Iterable<*> -> {
                val nested = value.joinToString("\u0002") { item -> fields(item) }
                "${nested.length}:[$nested]"
            }
            else -> error("Trigger ordering key received an unsupported semantic value")
        }
    }
}
