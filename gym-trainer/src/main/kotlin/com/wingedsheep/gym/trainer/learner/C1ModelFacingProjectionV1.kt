package com.wingedsheep.gym.trainer.learner

import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.ChosenSemanticActionV1
import com.wingedsheep.gym.contract.ChosenSemanticResponseV1
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.trainer.trajectory.DecisionRecordV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class C1ProjectionContext(
    val datasetId: String,
    val sourceManifestContentDigest: String,
)

object C1ModelFacingProjectionV1 {
    fun project(
        trajectory: TrajectoryV1,
        record: DecisionRecordV1,
        context: C1ProjectionContext,
        partition: C1DatasetPartition,
    ): C1DerivedSampleV1 {
        requireOwnedRecord(trajectory, record)

        val observation = record.observationBefore
        val domain = record.completeLegalDomain
        val chosenAction = record.chosenSemanticAction
        val chosenResponse = record.chosenSemanticResponse
        require((chosenAction == null) != (chosenResponse == null)) {
            "Decision record must contain exactly one chosen semantic value"
        }

        val target = when {
            chosenAction != null -> {
                val rebound = ChosenSemanticActionV1.from(
                    domain = domain,
                    candidate = chosenAction.candidate,
                    choicePayload = chosenAction.choicePayload,
                )
                C1DerivedTargetChannel(
                    chosenSemanticAction = rebound.replaySemanticElement(),
                )
            }

            chosenResponse != null -> {
                val rebound = ChosenSemanticResponseV1.from(
                    domain = domain,
                    response = chosenResponse.response,
                )
                C1DerivedTargetChannel(
                    chosenSemanticResponse = rebound.replaySemanticElement(),
                )
            }

            else -> error("Unreachable chosen semantic value")
        }
        val selectedBinding = target.chosenSemanticAction ?: target.chosenSemanticResponse
            ?: error("Target has no source binding")
        val sourceReference = C1DerivedSourceReference(
            datasetId = context.datasetId,
            sourceManifestContentDigest = context.sourceManifestContentDigest,
            trajectoryId = trajectory.trajectoryId,
            semanticEpisodeId = trajectory.semanticEpisodeId,
            collectionJobId = trajectory.collectionJobId,
            decisionIndex = record.decisionIndex,
            replayActionIndex = record.replayActionIndex,
            replayFrameIndex = record.replayFrameIndex,
            semanticDecisionId = A3SemanticJson.strictJson.encodeToJsonElement(
                record.semanticDecisionId,
            ).jsonObject,
            perspectivePlayerId = record.perspectivePlayerId.value,
        )
        val provenance = A3SemanticJson.strictJson.encodeToJsonElement(
            trajectory.episodeMetadata,
        ).jsonObject
        val tieDiscriminators = when (domain.kind) {
            CompleteLegalDomainKind.ACTION_CANDIDATES,
            CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
            -> C1SourceTieDiscriminatorV1.produce(
                domain.candidates.mapIndexed { index, candidate ->
                    C1ProjectedCandidateForTie(index, projectCandidate(candidate, FeatureProjectionMode.TIE))
                },
            ).mapKeys { (ordinal, _) -> ordinal.toString() }

            CompleteLegalDomainKind.STRUCTURED_DECISION -> emptyMap()
        }
        return C1DerivedSampleV1(
            partition = partition,
            sourceReference = sourceReference,
            input = projectInput(observation, domain),
            target = target,
            binding = C1DerivedBindingChannel(
                completeLegalDomain = encodeDomain(domain),
                selectedExactSourceBinding = selectedBinding,
                sourceBindingOrdinals = when (domain.kind) {
                    CompleteLegalDomainKind.ACTION_CANDIDATES,
                    CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
                    -> domain.candidates.indices.toList()

                    CompleteLegalDomainKind.STRUCTURED_DECISION -> emptyList()
                },
                semanticTieDiscriminators = tieDiscriminators,
            ),
            provenance = provenance,
        )
    }

    private fun requireOwnedRecord(trajectory: TrajectoryV1, record: DecisionRecordV1) {
        val owned = trajectory.decisions.getOrNull(record.decisionIndex)
            ?: throw IllegalArgumentException("Decision record is absent from its trajectory")
        require(owned.decisionIndex == record.decisionIndex) {
            "Decision record index is not contiguous in its trajectory"
        }
        require(owned.replayActionIndex == record.replayActionIndex) {
            "Decision record replay coordinate does not belong to its trajectory"
        }
        require(owned.replayFrameIndex == record.replayFrameIndex) {
            "Decision record frame coordinate does not belong to its trajectory"
        }
        val expected = A3SemanticJson.canonicalJson(
            A3SemanticJson.strictJson.encodeToJsonElement(DecisionRecordV1.serializer(), owned),
        )
        val actual = A3SemanticJson.canonicalJson(
            A3SemanticJson.strictJson.encodeToJsonElement(DecisionRecordV1.serializer(), record),
        )
        require(expected == actual) {
            "Decision record does not belong to the supplied trajectory"
        }
    }

    private fun projectInput(
        observation: com.wingedsheep.gym.contract.PlayerObservationV1,
        domain: CompleteLegalDomainV1,
    ): JsonObject = buildJsonObject {
        put("contractIdentity", C1_MODEL_FACING_CONTRACT_IDENTITY)
        put(
            "decisionContext",
            buildJsonObject {
                put("domainKind", domain.kind.name)
                put("turnNumber", observation.turnNumber)
                put("phase", observation.phase.name)
                put("step", observation.step.name)
                put(
                    "agentToActRole",
                    observation.agentToAct?.let { roleOf(it, observation.perspectivePlayerId) } ?: "ABSENT",
                )
                put(
                    "activePlayerRole",
                    observation.activePlayerId?.let { roleOf(it, observation.perspectivePlayerId) } ?: "ABSENT",
                )
                put(
                    "priorityPlayerRole",
                    observation.priorityPlayerId?.let { roleOf(it, observation.perspectivePlayerId) } ?: "ABSENT",
                )
            },
        )
        put(
            "observation",
            projectObservation(observation),
        )
        put("domain", projectDomain(domain))
    }

    private fun projectObservation(
        observation: com.wingedsheep.gym.contract.PlayerObservationV1,
    ): JsonObject = buildJsonObject {
        put("turnNumber", observation.turnNumber)
        put("phase", observation.phase.name)
        put("step", observation.step.name)
        put("terminated", observation.terminated)
        put("truncated", observation.truncated)
        put("players", buildJsonArray {
            observation.players.forEach { player ->
                add(buildJsonObject {
                    put("role", roleOf(player.id, observation.perspectivePlayerId))
                    put("lifeTotal", player.lifeTotal)
                    put("handSize", player.handSize)
                    put("librarySize", player.librarySize)
                    put("graveyardSize", player.graveyardSize)
                    put("exileSize", player.exileSize)
                    put("manaPool", projectManaPool(player.manaPool))
                    put("isPerspective", player.isPerspective)
                    put("isActive", player.isActive)
                    put("hasPriority", player.hasPriority)
                    put("hasLost", player.hasLost)
                })
            }
        })
        put("zones", buildJsonArray {
            observation.zones.forEach { zone ->
                add(buildJsonObject {
                    put("ownerRole", roleOf(zone.ownerId, observation.perspectivePlayerId))
                    put("zoneType", zone.zoneType.name)
                    put("hidden", zone.hidden)
                    put("size", zone.size)
                    put("cards", buildJsonArray {
                        zone.cards.forEach { card -> add(projectEntity(card, observation)) }
                    })
                })
            }
        })
        put("stack", buildJsonArray {
            observation.stack.forEach { item ->
                add(buildJsonObject {
                    put("name", item.name)
                    put("kind", item.kind.name)
                    put("oracleText", item.oracleText)
                    put("targetCount", item.targets.size)
                    put("controllerRole", item.controllerId?.let {
                        roleOf(it, observation.perspectivePlayerId)
                    } ?: "UNKNOWN")
                    put("sourcePresent", item.sourceEntityId != null)
                })
            }
        })
        observation.pendingDecision?.let { pending ->
            put("pendingDecision", buildJsonObject {
                put("kind", pending.kind.name)
                put("playerRole", roleOf(pending.playerId, observation.perspectivePlayerId))
                put("requiresStructuredResponse", pending.requiresStructuredResponse)
                put("sourcePresent", pending.sourceEntityId != null)
                put("triggeringPresent", pending.triggeringEntityId != null)
                put("minSelections", pending.shape.minSelections)
                put("maxSelections", pending.shape.maxSelections)
                pending.shape.numericMin?.let { put("numericMin", it) }
                pending.shape.numericMax?.let { put("numericMax", it) }
                put("availableColors", buildJsonArray {
                    pending.shape.availableColors.map { it.name }.sorted().forEach {
                        add(JsonPrimitive(it))
                    }
                })
                pending.shape.totalToDistribute?.let { put("totalToDistribute", it) }
                pending.shape.budget?.let { put("budget", it) }
            })
        }
    }

    private fun projectManaPool(pool: com.wingedsheep.gym.contract.ManaPoolView): JsonObject =
        buildJsonObject {
            put("white", pool.white)
            put("blue", pool.blue)
            put("black", pool.black)
            put("red", pool.red)
            put("green", pool.green)
            put("colorless", pool.colorless)
        }

    private fun projectEntity(
        card: com.wingedsheep.gym.contract.EntityFeatures,
        observation: com.wingedsheep.gym.contract.PlayerObservationV1,
    ): JsonObject = buildJsonObject {
        card.cardDefinitionId?.let { put("cardDefinitionId", it) }
        put("name", card.name)
        put("zone", card.zone.name)
        put("ownerRole", card.ownerId?.let { roleOf(it, observation.perspectivePlayerId) } ?: "UNKNOWN")
        put("controllerRole", card.controllerId?.let { roleOf(it, observation.perspectivePlayerId) } ?: "UNKNOWN")
        put("types", buildJsonArray { card.types.sorted().forEach { add(JsonPrimitive(it)) } })
        put("subtypes", buildJsonArray { card.subtypes.sorted().forEach { add(JsonPrimitive(it)) } })
        put("colors", buildJsonArray { card.colors.sorted().forEach { add(JsonPrimitive(it)) } })
        put("keywords", buildJsonArray { card.keywords.sorted().forEach { add(JsonPrimitive(it)) } })
        put("manaCost", card.manaCost)
        put("manaValue", card.manaValue)
        put("oracleText", card.oracleText)
        card.power?.let { put("power", it) }
        card.toughness?.let { put("toughness", it) }
        put("tapped", card.tapped)
        put("summoningSick", card.summoningSick)
        put("faceDown", card.faceDown)
        put("damageMarked", card.damageMarked)
        put("counters", buildJsonObject {
            card.counters.toSortedMap().forEach { (key, value) -> put(key, value) }
        })
        put("attached", card.attachedTo != null)
        put("attachmentCount", card.attachments.size)
    }

    private fun roleOf(
        id: com.wingedsheep.sdk.model.EntityId,
        perspective: com.wingedsheep.sdk.model.EntityId,
    ): String = if (id == perspective) "SELF" else "OPPONENT"

    private fun projectDomain(domain: CompleteLegalDomainV1): JsonObject = buildJsonObject {
        put("kind", domain.kind.name)
        domain.decisionKind?.let { put("decisionKind", it.name) }
        domain.shape?.let {
            put(
                "shape",
                A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.DecisionShape.serializer(),
                    it,
                ),
            )
        }
        when (domain.kind) {
            CompleteLegalDomainKind.ACTION_CANDIDATES,
            CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS,
            -> put(
                "candidates",
                buildJsonArray {
                    domain.candidates.forEach {
                        add(projectCandidate(it, FeatureProjectionMode.MODEL))
                    }
                },
            )

            CompleteLegalDomainKind.STRUCTURED_DECISION -> {
                val structured = requireNotNull(domain.structuredDomain)
                put(
                    "structuredType",
                    projectStructuredDomainRepresentation(structured),
                )
            }
        }
    }

    internal fun projectStructuredDomainRepresentation(
        domain: com.wingedsheep.gym.contract.StructuredDecisionDomain,
    ): JsonObject {
        val type = when (domain) {
            is com.wingedsheep.gym.contract.TargetsDomain -> "targets"
            is com.wingedsheep.gym.contract.CardSelectionDomain -> "card-selection"
            is com.wingedsheep.gym.contract.ModeSelectionDomain -> "mode-selection"
            is com.wingedsheep.gym.contract.DistributionDomain -> "distribution"
            is com.wingedsheep.gym.contract.OrderingDomain -> "ordering"
            is com.wingedsheep.gym.contract.SplitPilesDomain -> "split-piles"
            is com.wingedsheep.gym.contract.SearchLibraryDomain -> "search-library"
            is com.wingedsheep.gym.contract.ReorderLibraryDomain -> "reorder-library"
            is com.wingedsheep.gym.contract.CombatResolutionDomain -> "combat-resolution"
            is com.wingedsheep.gym.contract.ManaSourcesDomain -> "mana-sources"
            is com.wingedsheep.gym.contract.ReplacementDomain -> "replacement"
            is com.wingedsheep.gym.contract.BudgetModalDomain -> "budget-modal"
        }
        val encoded = when (domain) {
            is com.wingedsheep.gym.contract.TargetsDomain ->
                A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.TargetsDomain.serializer(),
                    domain,
                )

            is com.wingedsheep.gym.contract.CardSelectionDomain ->
                A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.CardSelectionDomain.serializer(),
                    domain,
                )

            is com.wingedsheep.gym.contract.ModeSelectionDomain ->
                A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.ModeSelectionDomain.serializer(),
                    domain,
                )

            is com.wingedsheep.gym.contract.DistributionDomain ->
                A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.DistributionDomain.serializer(),
                    domain,
                )

            is com.wingedsheep.gym.contract.OrderingDomain ->
                A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.OrderingDomain.serializer(),
                    domain,
                )

            is com.wingedsheep.gym.contract.SplitPilesDomain ->
                A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.SplitPilesDomain.serializer(),
                    domain,
                )

            is com.wingedsheep.gym.contract.SearchLibraryDomain ->
                A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.SearchLibraryDomain.serializer(),
                    domain,
                )

            is com.wingedsheep.gym.contract.ReorderLibraryDomain ->
                A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.ReorderLibraryDomain.serializer(),
                    domain,
                )

            is com.wingedsheep.gym.contract.CombatResolutionDomain ->
                A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.CombatResolutionDomain.serializer(),
                    domain,
                )

            is com.wingedsheep.gym.contract.ManaSourcesDomain ->
                A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.ManaSourcesDomain.serializer(),
                    domain,
                )

            is com.wingedsheep.gym.contract.ReplacementDomain ->
                A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.ReplacementDomain.serializer(),
                    domain,
                )

            is com.wingedsheep.gym.contract.BudgetModalDomain ->
                A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.BudgetModalDomain.serializer(),
                    domain,
                )
        }
        val projected = requireNotNull(
            projectFeatureElement(encoded, FeatureProjectionMode.MODEL),
        ).jsonObject
        return buildJsonObject {
            put("type", type)
            projected.forEach { (key, value) -> put(key, value) }
        }
    }

    private fun projectCandidate(
        candidate: JsonObject,
        mode: FeatureProjectionMode,
    ): JsonObject = buildJsonObject {
        listOf(
            "kind",
            "affordable",
            "targetEntityIds",
            "manaCost",
            "hasXCost",
            "maxAffordableX",
            "minTargets",
            "maxTargets",
            "validSacrificeTargets",
            "sacrificeCount",
            "sacrificeMinCount",
            "sacrificeMaxCount",
            "requiresDamageDistribution",
            "isManaAbility",
            "availableManaColors",
            "requiresStructuredAction",
            "requiredPayloadFields",
            "isDecisionOption",
        ).forEach { key ->
            if (mode == FeatureProjectionMode.TIE &&
                (isRawIdentityKey(key) || isRawIdentityListField(key))
            ) {
                return@forEach
            }
            candidate[key]?.let { value ->
                projectFeatureElement(value, mode, fieldName = key)?.let {
                    val outputKey = if (isRawIdentityKey(key) || isRawIdentityListField(key)) {
                        identityAliasKey(key)
                    } else {
                        key
                    }
                    put(outputKey, it)
                }
            }
        }
        candidate["actionSemantics"]?.let { value ->
            projectActionSemantics(value.jsonObject, mode)?.let { put("actionSemantics", it) }
        }
        candidate["targetDomain"]?.jsonObject?.let {
            put("targetDomain", projectTargetDomain(it, mode))
        }
        listOf(
            "attackDeclarationDomain",
            "blockerDeclarationDomain",
            "paymentDomain",
            "targetPaymentDomain",
            "repeatCountDomain",
        ).forEach { key ->
            candidate[key]?.let { value ->
                projectFeatureElement(value, mode, fieldName = key)?.let { put(key, it) }
            }
        }
    }

    private fun projectTargetDomain(
        value: JsonObject,
        mode: FeatureProjectionMode,
    ): JsonObject = buildJsonObject {
        value["version"]?.let { put("version", it) }
        value["composition"]?.let { put("composition", it) }
        value["requirements"]?.jsonArray?.let { requirements ->
            put("requirements", buildJsonArray {
                requirements.forEach { requirementElement ->
                    val requirement = requirementElement.jsonObject
                    add(buildJsonObject {
                        requirement["index"]?.let { put("index", it) }
                        requirement["minTargets"]?.let { put("minTargets", it) }
                        requirement["maxTargets"]?.let { put("maxTargets", it) }
                        requirement["candidates"]?.jsonArray?.let {
                            put("candidateCount", it.size)
                            if (mode == FeatureProjectionMode.MODEL) {
                                put("candidateAliases", buildJsonArray {
                                    it.indices.forEach { index -> add(JsonPrimitive("entity-$index")) }
                                })
                            }
                        }
                        requirement["targetZone"]?.let { put("targetZone", it) }
                        listOf(
                            "mustDifferFromEarlier",
                            "sameController",
                            "sameOwner",
                            "sameCreatureType",
                            "sameCardType",
                            "differentNames",
                            "xConstrainsManaValue",
                            "xConstrainsManaValueExactly",
                            "xConstrainsPower",
                            "xConstrainsCount",
                            "totalManaValueAtMost",
                        ).forEach { key -> requirement[key]?.let { put(key, it) } }
                    })
                }
            })
        }
    }

    private enum class FeatureProjectionMode {
        MODEL,
        TIE,
    }

    private class LocalEntityAliasState {
        private val aliases = linkedMapOf<String, String>()

        fun alias(raw: String): String = aliases.getOrPut(raw) { "entity-${aliases.size}" }
    }

    private val routingForbiddenFeatureKeys = setOf(
        "id",
        "actionId",
        "decisionId",
        "abilityId",
        "runtimeAbilityId",
        "envId",
        "pendingDecisionId",
        "sourceEntityId",
        "rowIndex",
        "sourceBindingOrdinal",
        "allocationOrder",
        "batchSlot",
    )

    private val identityFeatureKeys = setOf(
        "targetEntityIds",
        "validSacrificeTargets",
        "entityId",
        "sourceId",
        "targetId",
        "playerId",
        "cardId",
        "editableBy",
        "triggeringPlayerId",
        "attachedTo",
        "attachments",
        "sourceEntityId",
        "attackerId",
        "blockerId",
        "defenderId",
        "attackedDefenderId",
        "blockedByIds",
        "blockedAttackerIds",
        "coChooserId",
        "splicedCardIds",
        "conspiredCreatures",
        "costTargetIds",
        "crewCreatures",
        "saddleCreatures",
        "orderedBlockers",
        "targetIds",
        "attackerIds",
        "playerIds",
        "objectIds",
        "giftRecipient",
        "casualtyCreature",
    )

    private val identityListFields = setOf(
        "options",
        "nonSelectableOptions",
        "candidates",
        "anyOf",
        "matchingOptions",
        "eligibleCoBlockers",
        "targets",
        "objects",
        "cards",
        "attackerOrder",
        "blockerOrder",
        "mandatoryAttackers",
        "orderedObjects",
        "orderedBlockers",
        "splicedCardIds",
        "conspiredCreatures",
        "costTargetIds",
        "crewCreatures",
        "saddleCreatures",
        "targetIds",
        "attackerIds",
        "playerIds",
        "objectIds",
    )

    private val identityMapFields = setOf(
        "cardInfo",
        "objectLabels",
        "maxPerTarget",
        "maxAttackersByBlocker",
        "minBlockersByAttacker",
        "maxBlockersByAttacker",
    )

    private val identityRelationMapFields = setOf(
        "damageDistribution",
        "modeDamageDistribution",
        "attackers",
        "blockers",
        "bands",
        "attackerToDefenders",
        "coAttackerRequirements",
        "bandConstraints",
        "blockerToAttackers",
        "coBlockerRequirements",
    )

    private val forbiddenFeatureKeys = routingForbiddenFeatureKeys + identityFeatureKeys

    private val forbiddenPresentationKeys = setOf(
        "description",
        "filterDescription",
        "prompt",
        "sourceName",
        "effectHint",
        "imageUri",
        "iconKey",
        "selectedLabel",
        "remainderLabel",
        "pileLabels",
        "objectLabels",
    )

    private fun projectActionSemantics(
        value: JsonObject,
        mode: FeatureProjectionMode,
    ): JsonObject? = projectFeatureElement(value, mode)?.jsonObject

    private fun projectFeatureElement(
        value: JsonElement,
        mode: FeatureProjectionMode,
        aliases: LocalEntityAliasState = LocalEntityAliasState(),
        fieldName: String? = null,
    ): JsonElement? = when (value) {
        is JsonObject -> when {
            fieldName in identityRelationMapFields && mode == FeatureProjectionMode.MODEL ->
                projectIdentityRelationMap(value, aliases)

            fieldName in identityMapFields && mode == FeatureProjectionMode.MODEL ->
                projectIdentityKeyedMap(value, aliases)

            else -> buildJsonObject {
                value.forEach { (key, child) ->
                    when {
                        key in forbiddenPresentationKeys || key in routingForbiddenFeatureKeys -> Unit
                        isRawIdentityListField(key) -> {
                            if (mode == FeatureProjectionMode.MODEL) {
                                put(identityAliasKey(key), projectIdentityValue(child, aliases))
                            }
                        }

                        isRawIdentityKey(key) -> {
                            if (mode == FeatureProjectionMode.MODEL) {
                                put(identityAliasKey(key), projectIdentityValue(child, aliases))
                            }
                        }

                        key in identityRelationMapFields || key in identityMapFields -> {
                            if (mode == FeatureProjectionMode.MODEL) {
                                projectFeatureElement(child, mode, aliases, key)?.let { put(key, it) }
                            }
                        }

                        key in forbiddenFeatureKeys -> Unit
                        else -> projectFeatureElement(child, mode, aliases, key)?.let {
                            put(key, it)
                        }
                    }
                }
            }
        }

        is JsonArray -> if (
            fieldName != null &&
            (isRawIdentityKey(fieldName) || isRawIdentityListField(fieldName))
        ) {
            if (mode == FeatureProjectionMode.TIE) {
                JsonArray(emptyList())
            } else {
                buildJsonArray {
                    value.forEach { child ->
                        when (child) {
                            is JsonPrimitive -> if (child.isString) {
                                add(JsonPrimitive(aliases.alias(child.content)))
                            } else {
                                add(child)
                            }

                            else -> projectFeatureElement(child, mode, aliases, fieldName)?.let(::add)
                        }
                    }
                }
            }
        } else {
            buildJsonArray {
                value.forEach {
                    projectFeatureElement(it, mode, aliases, fieldName)?.let(::add)
                }
            }
        }

        is JsonNull -> value
        is JsonPrimitive -> value
    }

    private fun projectIdentityKeyedMap(
        value: JsonObject,
        aliases: LocalEntityAliasState,
    ): JsonObject = buildJsonObject {
        value.forEach { (key, child) ->
            projectFeatureElement(child, FeatureProjectionMode.MODEL, aliases)?.let {
                put(aliases.alias(key), it)
            }
        }
    }

    private fun projectIdentityRelationMap(
        value: JsonObject,
        aliases: LocalEntityAliasState,
    ): JsonObject = buildJsonObject {
        value.forEach { (key, child) ->
            put(aliases.alias(key), projectRelationValue(child, aliases))
        }
    }

    private fun projectRelationValue(
        value: JsonElement,
        aliases: LocalEntityAliasState,
    ): JsonElement = when (value) {
        is JsonObject -> buildJsonObject {
            value.forEach { (key, child) ->
                if (isRawIdentityKey(key) || isRawIdentityListField(key)) {
                    put(identityAliasKey(key), projectRelationValue(child, aliases))
                } else if (key !in forbiddenPresentationKeys && key !in routingForbiddenFeatureKeys) {
                    put(key, projectRelationValue(child, aliases))
                }
            }
        }

        is JsonArray -> buildJsonArray {
            value.forEach { child -> add(projectRelationValue(child, aliases)) }
        }

        is JsonPrimitive -> if (value.isString) {
            JsonPrimitive(aliases.alias(value.content))
        } else {
            value
        }

        is JsonNull -> value
    }

    private fun projectIdentityValue(
        value: JsonElement,
        aliases: LocalEntityAliasState,
    ): JsonElement = when (value) {
        is JsonObject -> buildJsonObject {
            value.forEach { (key, child) ->
                projectFeatureElement(child, FeatureProjectionMode.MODEL, aliases)?.let {
                    put(aliases.alias(key), it)
                }
            }
        }

        is JsonArray -> buildJsonArray {
            value.forEach { child ->
                when (child) {
                    is JsonPrimitive -> if (child.isString) {
                        add(JsonPrimitive(aliases.alias(child.content)))
                    } else {
                        add(child)
                    }
                    else -> add(
                        projectFeatureElement(child, FeatureProjectionMode.MODEL, aliases)
                            ?: JsonNull,
                    )
                }
            }
        }

        is JsonPrimitive -> if (value.isString) JsonPrimitive(aliases.alias(value.content)) else value
        is JsonNull -> value
    }

    private fun identityAliasKey(key: String): String = when {
        key.endsWith("Ids") -> key.removeSuffix("Ids") + "Aliases"
        key.endsWith("Id") -> key.removeSuffix("Id") + "Alias"
        else -> key + "Aliases"
    }

    private fun isRawIdentityKey(key: String): Boolean =
        key in identityFeatureKeys || key.endsWith("Id")

    private fun isRawIdentityListField(key: String): Boolean =
        key in identityListFields || key.endsWith("Ids")

    private fun encodeDomain(domain: CompleteLegalDomainV1): JsonObject =
        A3SemanticJson.strictJson.encodeToJsonElement(
            CompleteLegalDomainV1.serializer(),
            domain,
        ).jsonObject
}
