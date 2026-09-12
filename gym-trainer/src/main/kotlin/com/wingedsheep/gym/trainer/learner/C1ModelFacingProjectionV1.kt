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
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.sdk.model.EntityId

data class C1ProjectionContext(
    val datasetId: String,
    val sourceManifestContentDigest: String,
)

internal class C1SampleRelationTable private constructor(
    private val perspectivePlayerId: EntityId?,
    private val opponentPlayerId: EntityId?,
    private val aliasesBySourceId: LinkedHashMap<String, String>,
) {
    companion object {
        fun forObservation(observation: PlayerObservationV1): C1SampleRelationTable {
            val playerIds = observation.players.map { it.id }
            require(playerIds.size == 2 && playerIds.distinct().size == 2) {
                "C1 learner projection requires exactly two distinct validated players"
            }
            require(observation.perspectivePlayerId in playerIds) {
                "Perspective player is absent from the validated player set"
            }
            val opponent = playerIds.single { it != observation.perspectivePlayerId }
            listOfNotNull(
                observation.agentToAct,
                observation.activePlayerId,
                observation.priorityPlayerId,
                observation.winnerId,
            ).forEach { id ->
                require(id in playerIds) {
                    "Observation contains an unknown player identity"
                }
            }
            return C1SampleRelationTable(
                perspectivePlayerId = observation.perspectivePlayerId,
                opponentPlayerId = opponent,
                aliasesBySourceId = linkedMapOf<String, String>().also { aliases ->
                    playerIds.forEachIndexed { index, id -> aliases[id.value] = "entity-$index" }
                },
            )
        }

        fun empty(): C1SampleRelationTable = C1SampleRelationTable(
            perspectivePlayerId = null,
            opponentPlayerId = null,
            aliasesBySourceId = linkedMapOf(),
        )

        fun standalonePlayers(
            perspective: EntityId,
            opponent: EntityId,
        ): C1SampleRelationTable {
            require(perspective != opponent) { "Standalone players must be distinct" }
            return C1SampleRelationTable(
                perspectivePlayerId = perspective,
                opponentPlayerId = opponent,
                aliasesBySourceId = linkedMapOf(
                    perspective.value to "entity-0",
                    opponent.value to "entity-1",
                ),
            )
        }
    }

    fun alias(id: EntityId): String = alias(id.value)

    fun alias(sourceId: String): String {
        require(sourceId.isNotBlank()) { "Source entity identity must not be blank" }
        return aliasesBySourceId.getOrPut(sourceId) {
            "entity-${aliasesBySourceId.size}"
        }
    }

    fun roleOf(id: EntityId): String {
        require(id == perspectivePlayerId || id == opponentPlayerId) {
            "Observation contains an unknown player identity"
        }
        return if (id == perspectivePlayerId) "SELF" else "OPPONENT"
    }

    fun bindings(): List<C1EntityAliasBindingV1> = aliasesBySourceId.map { (sourceId, alias) ->
        C1EntityAliasBindingV1(alias = alias, sourceEntityId = sourceId)
    }
}

object C1ModelFacingProjectionV1 {
    fun project(
        trajectory: TrajectoryV1,
        record: DecisionRecordV1,
        context: C1ProjectionContext,
        partition: C1DatasetPartition,
    ): C1DerivedSampleV1 {
        requireOwnedRecord(trajectory, record)

        val observation = record.observationBefore
        require(!observation.terminated) {
            "Learner projection requires an accepted pre-choice observation"
        }
        require(!observation.truncated) {
            "Learner projection requires an accepted pre-choice observation"
        }
        require(observation.winnerId == null) {
            "Learner projection must not admit a terminal winner field"
        }
        val relations = C1SampleRelationTable.forObservation(observation)
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
                    C1ProjectedCandidateForTie(
                        index,
                        projectCandidate(candidate, FeatureProjectionMode.TIE, null),
                    )
                },
            ).mapKeys { (ordinal, _) -> ordinal.toString() }

            CompleteLegalDomainKind.STRUCTURED_DECISION -> emptyMap()
        }
        return C1DerivedSampleV1(
            partition = partition,
            sourceReference = sourceReference,
            input = projectInput(observation, domain, relations),
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
                entityAliasBindings = relations.bindings(),
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
        observation: PlayerObservationV1,
        domain: CompleteLegalDomainV1,
        relations: C1SampleRelationTable,
    ): JsonObject = buildJsonObject {
        put(
            "decisionContext",
            buildJsonObject {
                put("domainKind", domain.kind.name)
                put("turnNumber", observation.turnNumber)
                put("phase", observation.phase.name)
                put("step", observation.step.name)
                put(
                    "agentToActRole",
                    observation.agentToAct?.let(relations::roleOf) ?: "ABSENT",
                )
                put(
                    "activePlayerRole",
                    observation.activePlayerId?.let(relations::roleOf) ?: "ABSENT",
                )
                put(
                    "priorityPlayerRole",
                    observation.priorityPlayerId?.let(relations::roleOf) ?: "ABSENT",
                )
            },
        )
        put(
            "observation",
            projectObservation(observation, relations),
        )
        put("domain", projectDomain(domain, relations))
    }

    private fun projectObservation(
        observation: PlayerObservationV1,
        relations: C1SampleRelationTable,
    ): JsonObject = buildJsonObject {
        put("turnNumber", observation.turnNumber)
        put("phase", observation.phase.name)
        put("step", observation.step.name)
        put("players", buildJsonArray {
            observation.players.forEach { player ->
                add(buildJsonObject {
                    put("entityAlias", relations.alias(player.id))
                    put("role", relations.roleOf(player.id))
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
                    put("ownerRole", relations.roleOf(zone.ownerId))
                    put("zoneType", zone.zoneType.name)
                    put("hidden", zone.hidden)
                    put("size", zone.size)
                    put("cards", buildJsonArray {
                        zone.cards.forEach { card -> add(projectEntity(card, relations)) }
                    })
                })
            }
        })
        put("stack", buildJsonArray {
            observation.stack.forEach { item ->
                add(buildJsonObject {
                    put("entityAlias", relations.alias(item.entityId))
                    put("name", item.name)
                    put("kind", item.kind.name)
                    put("oracleText", item.oracleText)
                    put("targetCount", item.targets.size)
                    put("controllerRole", item.controllerId?.let {
                        relations.roleOf(it)
                    } ?: "UNKNOWN")
                    item.sourceEntityId?.let { put("sourceAlias", relations.alias(it)) }
                    put("targetAliases", buildJsonArray {
                        item.targets.forEach { target ->
                            add(JsonPrimitive(relations.alias(target)))
                        }
                    })
                })
            }
        })
        observation.pendingDecision?.let { pending ->
            put("pendingDecision", buildJsonObject {
                put("kind", pending.kind.name)
                put("playerRole", relations.roleOf(pending.playerId))
                put("requiresStructuredResponse", pending.requiresStructuredResponse)
                pending.sourceEntityId?.let { put("sourceAlias", relations.alias(it)) }
                pending.triggeringEntityId?.let { put("triggeringAlias", relations.alias(it)) }
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
        relations: C1SampleRelationTable,
    ): JsonObject = buildJsonObject {
        put("entityAlias", relations.alias(card.entityId))
        card.cardDefinitionId?.let { put("cardDefinitionId", it) }
        if (!(card.faceDown && card.cardDefinitionId == null)) {
            put("name", card.name)
        }
        put("zone", card.zone.name)
        put("ownerRole", card.ownerId?.let(relations::roleOf) ?: "UNKNOWN")
        put("controllerRole", card.controllerId?.let(relations::roleOf) ?: "UNKNOWN")
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
        card.attachedTo?.let { put("attachedToAlias", relations.alias(it)) }
        put("attachmentAliases", buildJsonArray {
            card.attachments.forEach { add(JsonPrimitive(relations.alias(it))) }
        })
    }

    private fun projectDomain(
        domain: CompleteLegalDomainV1,
        relations: C1SampleRelationTable,
    ): JsonObject = buildJsonObject {
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
                        add(projectCandidate(it, FeatureProjectionMode.MODEL, relations))
                    }
                },
            )

            CompleteLegalDomainKind.STRUCTURED_DECISION -> {
                val structured = requireNotNull(domain.structuredDomain)
                put(
                    "structuredType",
                    projectStructuredDomainRepresentation(structured, relations),
                )
            }
        }
    }

    internal fun projectStructuredDomainRepresentation(
        domain: com.wingedsheep.gym.contract.StructuredDecisionDomain,
        relations: C1SampleRelationTable = C1SampleRelationTable.empty(),
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
            projectFeatureElement(encoded, FeatureProjectionMode.MODEL, relations),
        ).jsonObject
        val base = buildJsonObject {
            put("type", type)
            projected.forEach { (key, value) -> put(key, value) }
        }
        return projectStructuredPresentationAndPlayerRelations(domain, base, relations)
    }

    private fun projectStructuredPresentationAndPlayerRelations(
        domain: com.wingedsheep.gym.contract.StructuredDecisionDomain,
        projected: JsonObject,
        relations: C1SampleRelationTable,
    ): JsonObject = when (domain) {
        is com.wingedsheep.gym.contract.ModeSelectionDomain -> buildJsonObject {
            projected.forEach { (key, value) ->
                if (key != "modes") put(key, value)
            }
            projected["modes"]?.jsonArray?.let { modes ->
                put("modes", buildJsonArray {
                    modes.forEach { mode ->
                        add(JsonObject(mode.jsonObject.filterKeys { it != "text" }))
                    }
                })
            }
        }

        is com.wingedsheep.gym.contract.OrderingDomain -> buildJsonObject {
            projected.forEach { (key, value) -> put(key, value) }
            domain.objectLabels?.let { labels ->
                put("objectLabels", buildJsonObject {
                    labels.forEach { (objectId, label) ->
                        put(relations.alias(objectId), label)
                    }
                })
            }
        }

        is com.wingedsheep.gym.contract.CombatResolutionDomain ->
            projectCombatPlayerRelations(projected, domain, relations)

        else -> projected
    }

    private fun projectCombatPlayerRelations(
        projected: JsonObject,
        domain: com.wingedsheep.gym.contract.CombatResolutionDomain,
        relations: C1SampleRelationTable,
    ): JsonObject = buildJsonObject {
        projected.forEach { (key, value) ->
            when (key) {
                "attackers" -> put("attackers", buildJsonArray {
                    value.jsonArray.forEachIndexed { index, entry ->
                        val source = domain.attackers[index]
                        add(buildJsonObject {
                            entry.jsonObject.forEach { (entryKey, entryValue) ->
                                if (entryKey != "name" && entryKey != "attackedDefenderAlias") {
                                    put(entryKey, entryValue)
                                }
                            }
                            put("attackerAlias", relations.alias(source.id))
                            val defender = domain.defenders.firstOrNull {
                                it.id == source.attackedDefenderId
                            }
                            if (defender?.kind == com.wingedsheep.gym.contract.CombatTargetKind.PLAYER) {
                                put("attackedDefenderRole", relations.roleOf(source.attackedDefenderId))
                            } else {
                                put("attackedDefenderAlias", relations.alias(source.attackedDefenderId))
                            }
                        })
                    }
                })

                "blockers" -> put("blockers", buildJsonArray {
                    value.jsonArray.forEachIndexed { index, entry ->
                        val source = domain.blockers[index]
                        add(buildJsonObject {
                            entry.jsonObject.forEach { (entryKey, entryValue) ->
                                if (entryKey != "name") put(entryKey, entryValue)
                            }
                            put("blockerAlias", relations.alias(source.id))
                        })
                    }
                })

                "defenders" -> put("defenders", buildJsonArray {
                    value.jsonArray.forEachIndexed { index, entry ->
                        val source = domain.defenders[index]
                        add(buildJsonObject {
                            entry.jsonObject.forEach { (entryKey, entryValue) ->
                                if (entryKey != "name") put(entryKey, entryValue)
                            }
                            if (source.kind == com.wingedsheep.gym.contract.CombatTargetKind.PLAYER) {
                                put("defenderRole", relations.roleOf(source.id))
                            } else {
                                put("defenderAlias", relations.alias(source.id))
                            }
                        })
                    }
                })

                "edges" -> put("edges", buildJsonArray {
                    value.jsonArray.forEachIndexed { index, entry ->
                        val source = domain.edges[index]
                        add(buildJsonObject {
                            entry.jsonObject.forEach { (entryKey, entryValue) ->
                                if (entryKey != "editableByAlias" && entryKey != "editableByAliases") {
                                    put(entryKey, entryValue)
                                }
                            }
                            put("editableByRole", relations.roleOf(source.editableBy))
                        })
                    }
                })

                "coChooserAlias" -> Unit
                else -> put(key, value)
            }
        }
        domain.coChooserId?.let { put("coChooserRole", relations.roleOf(it)) }
    }

    private fun projectCandidate(
        candidate: JsonObject,
        mode: FeatureProjectionMode,
        relations: C1SampleRelationTable?,
    ): JsonObject = buildJsonObject {
        if (mode == FeatureProjectionMode.MODEL) {
            candidate["sourceEntityId"]?.let { source ->
                if (source !is JsonNull) {
                    put("sourceAlias", requireEntityIdString(source, "candidate sourceEntityId")
                        .let { requireNotNull(relations).alias(it) })
                }
            }
        }
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
                projectFeatureElement(value, mode, relations, fieldName = key)?.let {
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
            put("actionSemantics", projectActionSemantics(value.jsonObject, mode, relations))
        }
        candidate["targetDomain"]?.jsonObject?.let {
            put("targetDomain", projectTargetDomain(it, mode, relations))
        }
        listOf(
            "attackDeclarationDomain",
            "blockerDeclarationDomain",
            "paymentDomain",
            "targetPaymentDomain",
            "repeatCountDomain",
        ).forEach { key ->
            candidate[key]?.let { value ->
                projectFeatureElement(value, mode, relations, fieldName = key)?.let {
                    put(key, it)
                }
            }
        }
    }

    private fun projectTargetDomain(
        value: JsonObject,
        mode: FeatureProjectionMode,
        relations: C1SampleRelationTable?,
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
                                put(
                                    "candidateAliases",
                                    projectIdentityValue(it, requireNotNull(relations)),
                                )
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

    private val routingForbiddenFeatureKeys = setOf(
        "id",
        "actionId",
        "decisionId",
        "abilityId",
        "runtimeAbilityId",
        "envId",
        "pendingDecisionId",
        "manaAbilityKey",
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
        "selectedCards",
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
        "useTargetingUI",
        "pileLabels",
        "objectLabels",
    )

    private val actionSemanticFeatureKeys = setOf(
        "type",
        "abilityKey",
        "playerId",
        "cardId",
        "sourceId",
        "targets",
        "xValue",
        "manaColorChoice",
        "castFaceDown",
        "declaredCostSlot",
        "wasWaterbendPaid",
        "chosenModes",
        "modeTargetsOrdered",
        "graveyardLifeCost",
        "useAlternativeCost",
        "useWithoutPayingManaCost",
        "alternativeCostType",
        "color",
        "repeatCount",
    )

    private val actionSemanticIgnoredKeys = setOf(
        "abilityId",
        "giftRecipient",
        "splicedCardIds",
        "damageDistribution",
        "modeDamageDistribution",
        "conspiredCreatures",
        "casualtyCreature",
        "faceIndex",
        "attackers",
        "bands",
        "blockers",
        "attackerId",
        "orderedBlockers",
        "cardIds",
        "vehicleId",
        "crewCreatures",
        "mountId",
        "saddleCreatures",
        "costTargetIds",
        "roomId",
        "faceId",
        "procedureIndex",
        "paymentStrategy",
        "alternativePayment",
        "additionalCostPayment",
        "costPayment",
        "graveyardCastRider",
        "modeTargetRequirementsOrdered",
        "crewAbilityKey",
        "response",
        "actionId",
        "decisionId",
    )

    private val actionSemanticContinuationKeys = setOf(
        "preResolvedZoneChangeIds",
        "preResolvedSneakAttackDefenderId",
        "preResolvedWebSlingReturnedManaValue",
        "opponentTargetsChosen",
    )

    private val foldedResponseTypes = setOf(
        "YesNoResponse",
        "ModesChosenResponse",
        "ColorChosenResponse",
        "NumberChosenResponse",
        "OptionChosenResponse",
        "CardsSelectedResponse",
    )

    private fun projectActionSemantics(
        value: JsonObject,
        mode: FeatureProjectionMode,
        relations: C1SampleRelationTable?,
    ): JsonObject {
        val type = (value["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (type in foldedResponseTypes) {
            projectFoldedResponseSemantics(value, mode, relations)
        } else {
            projectGameActionSemantics(value, mode, relations)
        }
    }

    private fun projectGameActionSemantics(
        value: JsonObject,
        mode: FeatureProjectionMode,
        relations: C1SampleRelationTable?,
    ): JsonObject = buildJsonObject {
        value.forEach { (key, child) ->
            when {
                key in actionSemanticContinuationKeys -> {
                    requireActionContinuationIsInert(key, child)
                }

                key in actionSemanticIgnoredKeys -> Unit
                key !in actionSemanticFeatureKeys -> {
                    throw IllegalArgumentException(
                        "Unsupported action semantic field: $key",
                    )
                }

                key == "playerId" -> {
                    if (mode == FeatureProjectionMode.MODEL) {
                        val playerId = EntityId(requireEntityIdString(child, "action playerId"))
                        put("actorRole", requireNotNull(relations).roleOf(playerId))
                    }
                }

                key == "abilityKey" -> {
                    put("abilityKey", projectAbilityKey(child, mode))
                }

                key == "chosenModes" -> {
                    put("modeSlots", projectModeSlots(child))
                }

                key == "modeTargetsOrdered" -> {
                    put(
                        "modeTargetSlots",
                        projectModeTargetSlots(child, mode, relations),
                    )
                }

                mode == FeatureProjectionMode.TIE &&
                    (isRawIdentityKey(key) || isRawIdentityListField(key)) -> Unit

                else -> projectFeatureElement(child, mode, relations, key)?.let {
                    val outputKey = if (isRawIdentityKey(key) || isRawIdentityListField(key)) {
                        identityAliasKey(key)
                    } else {
                        key
                    }
                    put(outputKey, it)
                }
            }
        }
    }

    private fun projectAbilityKey(
        value: JsonElement,
        mode: FeatureProjectionMode,
    ): JsonObject {
        val objectValue = value as? JsonObject
            ?: throw IllegalArgumentException("Malformed ActivateAbility abilityKey")
        val allowed = setOf("origin", "ordinal", "cardDefinitionId", "ability")
        require(objectValue.keys.all { it in allowed }) {
            "Unknown ActivateAbility abilityKey field"
        }
        require(objectValue["origin"] is JsonPrimitive) {
            "ActivateAbility abilityKey has no origin"
        }
        require(objectValue["ordinal"] is JsonPrimitive) {
            "ActivateAbility abilityKey has no ordinal"
        }
        return buildJsonObject {
            put("origin", objectValue.getValue("origin"))
            objectValue["cardDefinitionId"]?.let { cardDefinitionId ->
                require(cardDefinitionId is JsonPrimitive && cardDefinitionId.isString) {
                    "ActivateAbility abilityKey has malformed cardDefinitionId"
                }
                put(
                    if (mode == FeatureProjectionMode.TIE) "cardDefinition" else "cardDefinitionId",
                    cardDefinitionId,
                )
            }
            if (mode == FeatureProjectionMode.MODEL) {
                val ordinal = objectValue.getValue("ordinal") as JsonPrimitive
                require(!ordinal.isString) { "ActivateAbility abilityKey ordinal must be numeric" }
                put("ordinalRelation", "ability-${ordinal.content}")
            }
        }
    }

    private fun projectModeSlots(value: JsonElement): JsonArray {
        val modes = value as? JsonArray
            ?: throw IllegalArgumentException("CastSpell chosenModes must be an array")
        return buildJsonArray {
            modes.forEachIndexed { occurrence, modeIndex ->
                val primitive = modeIndex as? JsonPrimitive
                require(primitive != null && !primitive.isString && primitive.content.toIntOrNull() != null) {
                    "CastSpell chosenModes contains a non-numeric mode index"
                }
                add(buildJsonObject {
                    put("occurrence", occurrence)
                    put("modeIndex", primitive)
                })
            }
        }
    }

    private fun projectModeTargetSlots(
        value: JsonElement,
        mode: FeatureProjectionMode,
        relations: C1SampleRelationTable?,
    ): JsonArray {
        val modeTargets = value as? JsonArray
            ?: throw IllegalArgumentException("CastSpell modeTargetsOrdered must be an array")
        return buildJsonArray {
            modeTargets.forEachIndexed { occurrence, targetsValue ->
                val targets = targetsValue as? JsonArray
                    ?: throw IllegalArgumentException("CastSpell mode target slots must be arrays")
                add(buildJsonObject {
                    put("occurrence", occurrence)
                    if (mode == FeatureProjectionMode.TIE) {
                        put("targetCount", targets.size)
                    } else {
                        put("targets", buildJsonArray {
                            targets.forEach { target ->
                                add(
                                    projectFeatureElement(
                                        target,
                                        FeatureProjectionMode.MODEL,
                                        relations,
                                        "targets",
                                    ) ?: JsonNull,
                                )
                            }
                        })
                    }
                })
            }
        }
    }

    private fun projectFoldedResponseSemantics(
        value: JsonObject,
        mode: FeatureProjectionMode,
        relations: C1SampleRelationTable?,
    ): JsonObject = buildJsonObject {
        val type = (value["type"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("Folded response has no type")
        val allowed = when (type) {
            "YesNoResponse" -> setOf("type", "choice")
            "ModesChosenResponse" -> setOf("type", "selectedModes")
            "ColorChosenResponse" -> setOf("type", "color")
            "NumberChosenResponse" -> setOf("type", "number")
            "OptionChosenResponse" -> setOf("type", "optionIndex", "optionMetadata")
            "CardsSelectedResponse" -> setOf("type", "selectedCards")
            else -> throw IllegalArgumentException("Unsupported folded response type: $type")
        }
        value.forEach { (key, child) ->
            require(key in allowed) { "Unsupported folded response field: $key" }
            when (key) {
                "optionMetadata" -> put(
                    key,
                    projectOptionMetadata(child, mode, relations),
                )

                else -> projectFeatureElement(child, mode, relations, key)?.let { put(key, it) }
            }
        }
    }

    private fun projectOptionMetadata(
        value: JsonElement,
        mode: FeatureProjectionMode,
        relations: C1SampleRelationTable?,
    ): JsonObject {
        val objectValue = value as? JsonObject
            ?: throw IllegalArgumentException("Malformed folded optionMetadata")
        val allowed = setOf("id", "description", "iconKey", "triggeringPlayerId")
        require(objectValue.keys.all { it in allowed }) {
            "Unknown folded optionMetadata field"
        }
        return buildJsonObject {
            if (mode == FeatureProjectionMode.MODEL) {
                objectValue["triggeringPlayerId"]?.let { triggeringPlayerId ->
                    if (triggeringPlayerId !is JsonNull) {
                        val player = EntityId(
                            requireEntityIdString(
                                triggeringPlayerId,
                                "folded optionMetadata triggeringPlayerId",
                            ),
                        )
                        put("triggeringPlayerRole", requireNotNull(relations).roleOf(player))
                    }
                }
            }
        }
    }

    private fun requireActionContinuationIsInert(key: String, value: JsonElement) {
        when (key) {
            "preResolvedZoneChangeIds" -> require(value is JsonArray && value.isEmpty()) {
                "Non-inert preResolvedZoneChangeIds are not model-facing features"
            }

            "preResolvedSneakAttackDefenderId",
            "preResolvedWebSlingReturnedManaValue",
            -> require(value is JsonNull) {
                "Non-inert $key is not a model-facing feature"
            }

            "opponentTargetsChosen" -> require(
                value is JsonPrimitive && !value.isString && value.content == "false",
            ) {
                "Non-inert opponentTargetsChosen is not a model-facing feature"
            }
        }
    }

    private fun projectFeatureElement(
        value: JsonElement,
        mode: FeatureProjectionMode,
        relations: C1SampleRelationTable?,
        fieldName: String? = null,
    ): JsonElement? = when (value) {
        is JsonObject -> when {
            fieldName == "modeDamageDistribution" && mode == FeatureProjectionMode.MODEL ->
                projectModeDamageDistribution(value, requireNotNull(relations))

            fieldName in identityRelationMapFields && mode == FeatureProjectionMode.MODEL ->
                projectIdentityRelationMap(value, requireNotNull(relations))

            fieldName in identityMapFields && mode == FeatureProjectionMode.MODEL ->
                projectIdentityKeyedMap(value, requireNotNull(relations))

            else -> buildJsonObject {
                value.forEach { (key, child) ->
                    when {
                        key in forbiddenPresentationKeys || key in routingForbiddenFeatureKeys -> Unit
                        isRawIdentityListField(key) -> {
                            if (mode == FeatureProjectionMode.MODEL) {
                                put(
                                    identityAliasKey(key),
                                    projectIdentityValue(child, requireNotNull(relations)),
                                )
                            }
                        }

                        isRawIdentityKey(key) -> {
                            if (mode == FeatureProjectionMode.MODEL) {
                                put(
                                    identityAliasKey(key),
                                    projectIdentityValue(child, requireNotNull(relations)),
                                )
                            }
                        }

                        key in identityRelationMapFields || key in identityMapFields -> {
                            if (mode == FeatureProjectionMode.MODEL) {
                                projectFeatureElement(child, mode, relations, key)?.let {
                                    put(key, it)
                                }
                            }
                        }

                        key in forbiddenFeatureKeys -> Unit
                        else -> projectFeatureElement(child, mode, relations, key)?.let {
                            put(key, it)
                        }
                    }
                }
            }
        }

        is JsonArray -> if (
            mode == FeatureProjectionMode.TIE && fieldName in identityRelationMapFields
        ) {
            JsonArray(emptyList())
        } else if (
            mode == FeatureProjectionMode.MODEL && fieldName in identityRelationMapFields
        ) {
            buildJsonArray {
                value.forEach { child -> add(projectRelationValue(child, requireNotNull(relations))) }
            }
        } else if (
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
                                add(JsonPrimitive(requireNotNull(relations).alias(child.content)))
                            } else {
                                add(child)
                            }

                            else -> projectFeatureElement(child, mode, relations, fieldName)?.let(::add)
                        }
                    }
                }
            }
        } else {
            buildJsonArray {
                value.forEach {
                    projectFeatureElement(it, mode, relations, fieldName)?.let(::add)
                }
            }
        }

        is JsonNull -> value
        is JsonPrimitive -> if (
            mode == FeatureProjectionMode.MODEL &&
            fieldName != null &&
            (isRawIdentityKey(fieldName) || isRawIdentityListField(fieldName)) &&
            value.isString
        ) {
            JsonPrimitive(requireNotNull(relations).alias(value.content))
        } else {
            value
        }
    }

    private fun projectIdentityKeyedMap(
        value: JsonObject,
        relations: C1SampleRelationTable,
    ): JsonObject = buildJsonObject {
        value.forEach { (key, child) ->
            projectFeatureElement(child, FeatureProjectionMode.MODEL, relations)?.let {
                put(relations.alias(key), it)
            }
        }
    }

    private fun projectIdentityRelationMap(
        value: JsonObject,
        relations: C1SampleRelationTable,
    ): JsonObject = buildJsonObject {
        value.forEach { (key, child) ->
            put(relations.alias(key), projectRelationValue(child, relations))
        }
    }

    private fun projectModeDamageDistribution(
        value: JsonObject,
        relations: C1SampleRelationTable,
    ): JsonObject = buildJsonObject {
        value.forEach { (modeIndex, allocations) ->
            put(
                modeIndex,
                projectIdentityRelationMap(allocations.jsonObject, relations),
            )
        }
    }

    private fun projectRelationValue(
        value: JsonElement,
        relations: C1SampleRelationTable,
    ): JsonElement = when (value) {
        is JsonObject -> buildJsonObject {
            value.forEach { (key, child) ->
                if (isRawIdentityKey(key) || isRawIdentityListField(key)) {
                    put(identityAliasKey(key), projectRelationValue(child, relations))
                } else if (key !in forbiddenPresentationKeys && key !in routingForbiddenFeatureKeys) {
                    put(key, projectRelationValue(child, relations))
                }
            }
        }

        is JsonArray -> buildJsonArray {
            value.forEach { child -> add(projectRelationValue(child, relations)) }
        }

        is JsonPrimitive -> if (value.isString) {
            JsonPrimitive(relations.alias(value.content))
        } else {
            value
        }

        is JsonNull -> value
    }

    private fun projectIdentityValue(
        value: JsonElement,
        relations: C1SampleRelationTable,
    ): JsonElement = when (value) {
        is JsonObject -> buildJsonObject {
            value.forEach { (key, child) ->
                projectFeatureElement(child, FeatureProjectionMode.MODEL, relations)?.let {
                    put(relations.alias(key), it)
                }
            }
        }

        is JsonArray -> buildJsonArray {
            value.forEach { child ->
                when (child) {
                    is JsonPrimitive -> if (child.isString) {
                        add(JsonPrimitive(relations.alias(child.content)))
                    } else {
                        add(child)
                    }
                    else -> add(
                        projectFeatureElement(child, FeatureProjectionMode.MODEL, relations)
                            ?: JsonNull,
                    )
                }
            }
        }

        is JsonPrimitive -> if (value.isString) JsonPrimitive(relations.alias(value.content)) else value
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

    private fun requireEntityIdString(value: JsonElement, label: String): String {
        val primitive = value as? JsonPrimitive
        require(primitive != null && primitive.isString) {
            "$label must be a string EntityId"
        }
        return primitive.content
    }

    private fun encodeDomain(domain: CompleteLegalDomainV1): JsonObject =
        A3SemanticJson.strictJson.encodeToJsonElement(
            CompleteLegalDomainV1.serializer(),
            domain,
        ).jsonObject
}
