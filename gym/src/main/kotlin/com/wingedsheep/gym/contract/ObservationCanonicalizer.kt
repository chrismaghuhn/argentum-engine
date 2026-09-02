package com.wingedsheep.gym.contract

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Deterministic projections of an already perspective-safe observation.
 *
 * This object is deliberately downstream of [ObservationBuilder]. It accepts no [GameState],
 * performs no visibility decisions, and is not a second public observation model.
 */
internal object ObservationCanonicalizer {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
        classDiscriminator = "type"
        allowStructuredMapKeys = true
    }

    /** Serialize the actual wire DTO, retaining transport IDs and presentation fields. */
    fun wireJson(observation: TrainingObservation): String {
        val result = canonicalize(
            json.encodeToJsonElement(TrainingObservation.serializer(), observation),
        ).toString()
        B1CanonicalizationProbe.recordWireJson(result.length)
        return result
    }

    /**
     * Produce the deterministic internal semantic projection used for equality and digesting.
     * Transport handles and presentation-only text are intentionally absent.
     */
    fun semanticJson(observation: TrainingObservation): String {
        val call = B1CanonicalizationProbe.beginSemanticJson(observation.legalActions.size)
        return try {
            val encoded = json.encodeToJsonElement(TrainingObservation.serializer(), observation).jsonObject
            val semantic = encoded.toMutableMap()
            semantic.remove("stateDigest")

            encoded["pendingDecision"]?.let { pending ->
                semantic["pendingDecision"] = if (pending is JsonObject) {
                    val pendingSemantic = pending.filterKeys {
                        it !in setOf("decisionId", "prompt", "sourceName", "effectHint")
                    }.toMutableMap()
                    pending["structuredDomain"]?.let { domain ->
                        if (domain is JsonObject) {
                            pendingSemantic["structuredDomain"] = semanticStructuredDomain(domain)
                        }
                    }
                    JsonObject(pendingSemantic)
                } else {
                    pending
                }
            }

            semantic["legalActions"] = JsonArray(
                observation.legalActions
                    .map(::semanticActionFingerprint)
                    .sortedBy { canonicalSortKey(it, B1CanonicalizationProbe.SortSite.LEGAL_ACTION) }
            )

            val result = canonicalize(JsonObject(semantic)).toString()
            B1CanonicalizationProbe.recordFinalCanonicalization()
            B1CanonicalizationProbe.finishSemanticJson(call, result.length)
            result
        } catch (failure: Throwable) {
            B1CanonicalizationProbe.finishSemanticJson(call, -1)
            throw failure
        }
    }

    /** The structured, transport-ID-free semantic identity of one legal action. */
    fun semanticActionFingerprint(action: LegalActionView): JsonObject {
        B1CanonicalizationProbe.recordSemanticActionFingerprint()
        return buildJsonObject {
            put("kind", action.kind)
            put("affordable", action.affordable)
            put("sourceEntityId", action.sourceEntityId?.value)
            put(
                "targetEntityIds",
                buildJsonArray {
                    action.targetEntityIds.forEach { add(JsonPrimitive(it.value)) }
                }
            )
            action.targetDomain?.let { put("targetDomain", semanticActionTargetDomain(it)) }
            action.attackDeclarationDomain?.let {
                put("attackDeclarationDomain", semanticAttackDeclarationDomain(it))
            }
            action.blockerDeclarationDomain?.let {
                put("blockerDeclarationDomain", semanticBlockerDeclarationDomain(it))
            }
            put("manaCost", action.manaCost)
            action.paymentDomain?.let {
                put("paymentDomain", json.encodeToJsonElement(PaymentDomainV5.serializer(), it))
            }
            action.targetPaymentDomain?.let {
                put("targetPaymentDomain", json.encodeToJsonElement(TargetPaymentDomainV1.serializer(), it))
            }
            put("hasXCost", action.hasXCost)
            put("maxAffordableX", action.maxAffordableX)
            put("minTargets", action.minTargets)
            put("maxTargets", action.maxTargets)
            put(
                "validSacrificeTargets",
                buildJsonArray {
                    action.validSacrificeTargets.forEach { add(JsonPrimitive(it.value)) }
                }
            )
            put("sacrificeCount", action.sacrificeCount)
            put("sacrificeMinCount", action.sacrificeMinCount)
            put("sacrificeMaxCount", action.sacrificeMaxCount)
            put("requiresDamageDistribution", action.requiresDamageDistribution)
            put("isManaAbility", action.isManaAbility)
            action.availableManaColors?.let { colors ->
                put("availableManaColors", buildJsonArray {
                    colors.sortedBy(Color::ordinal).forEach { add(JsonPrimitive(it.name)) }
                })
            }
            put("requiresStructuredAction", action.requiresStructuredAction)
            put("requiredPayloadFields", buildJsonArray {
                action.requiredPayloadFields.forEach { add(JsonPrimitive(it)) }
            })
            action.actionSemantics?.let { put("actionSemantics", it) }
            put("isDecisionOption", action.isDecisionOption)
        }
    }

    /**
     * Canonical semantic identity for the fixed action-level target contract.
     *
     * This is intentionally separate from [semanticStructuredDomain]: action target candidates
     * are legal-domain semantics, not presentation metadata. Requirement order is semantic, while
     * candidate order is an unordered public set and is therefore normalized by EntityId value.
     */
    private fun semanticActionTargetDomain(domain: ActionTargetDomainV1): JsonObject {
        require(domain.version == ACTION_TARGET_DOMAIN_VERSION) {
            "Unsupported action target domain version: ${domain.version}"
        }
        require(domain.composition == ActionTargetComposition.FIXED) {
            "Unsupported action target domain composition: ${domain.composition}"
        }

        return buildJsonObject {
            put("version", domain.version)
            put("composition", domain.composition.name)
            put("requirements", buildJsonArray {
                domain.requirements.forEach { requirement ->
                    add(buildJsonObject {
                        put("index", requirement.index)
                        put("minTargets", requirement.minTargets)
                        put("maxTargets", requirement.maxTargets)
                        put("candidates", buildJsonArray {
                            requirement.candidates
                                .sortedBy { it.value }
                                .forEach { add(JsonPrimitive(it.value)) }
                        })
                        put("targetZone", requirement.targetZone)
                        put("mustDifferFromEarlier", requirement.mustDifferFromEarlier)
                        put("sameController", requirement.sameController)
                        put("sameOwner", requirement.sameOwner)
                        put("sameCreatureType", requirement.sameCreatureType)
                        put("sameCardType", requirement.sameCardType)
                        put("totalManaValueAtMost", requirement.totalManaValueAtMost)
                        put("differentNames", requirement.differentNames)
                        put("xConstrainsManaValue", requirement.xConstrainsManaValue)
                        put("xConstrainsManaValueExactly", requirement.xConstrainsManaValueExactly)
                        put("xConstrainsPower", requirement.xConstrainsPower)
                        put("xConstrainsCount", requirement.xConstrainsCount)
                    })
                }
            })
        }
    }

    /** Canonical semantic identity for the complete Rules-owned attacker declaration domain. */
    private fun semanticAttackDeclarationDomain(domain: AttackDeclarationDomainV2): JsonObject {
        require(domain.version == ATTACK_DECLARATION_DOMAIN_V2_VERSION) {
            "Unsupported attack declaration domain version: ${domain.version}"
        }

        val defenderOrder = domain.attackerOrder
            .flatMap { attackerId -> domain.attackerToDefenders.getValue(attackerId) }
            .distinct()
        return buildJsonObject {
            put("version", domain.version)
            put("attackerOrder", entityArray(domain.attackerOrder))
            put("attackerToDefenders", orderedEntityRelation(domain.attackerOrder) { attackerId ->
                domain.attackerToDefenders.getValue(attackerId)
            })
            put(
                "mandatoryAttackers",
                buildJsonArray {
                    domain.mandatoryAttackers.forEach { add(JsonPrimitive(it.value)) }
                },
            )
            put("canDeclareZeroAttackers", domain.canDeclareZeroAttackers)
            put("maxAttackers", domain.maxAttackers)
            put("coAttackerRequirements", buildJsonObject {
                domain.attackerOrder
                    .filter { it in domain.coAttackerRequirements }
                    .forEach { attacker ->
                        put(attacker.value, buildJsonArray {
                            domain.coAttackerRequirements.getValue(attacker).forEach { requirement ->
                                add(entityArray(requirement.anyOf))
                            }
                        })
                    }
            })
            put("bandConstraints", buildJsonObject {
                put(
                    "bandingAttackersByDefender",
                    orderedEntityListMap(defenderOrder, domain.bandConstraints.bandingAttackersByDefender),
                )
                put(
                    "nonBandingAttackersByDefender",
                    orderedEntityListMap(defenderOrder, domain.bandConstraints.nonBandingAttackersByDefender),
                )
            })
        }
    }

    /** Canonical semantic identity for the complete Rules-owned blocker declaration domain. */
    private fun semanticBlockerDeclarationDomain(domain: BlockerDeclarationDomainV1): JsonObject {
        require(domain.version == BLOCKER_DECLARATION_DOMAIN_VERSION) {
            "Unsupported blocker declaration domain version: ${domain.version}"
        }

        return buildJsonObject {
            put("version", domain.version)
            put("blockerOrder", entityArray(domain.blockerOrder))
            put("attackerOrder", entityArray(domain.attackerOrder))
            put("blockerToAttackers", orderedEntityRelation(domain.blockerOrder) { blockerId ->
                domain.blockerToAttackers.getValue(blockerId)
            })
            put("maxAttackersByBlocker", orderedEntityIntMap(domain.blockerOrder) { blockerId ->
                domain.maxAttackersByBlocker.getValue(blockerId)
            })
            put("minBlockersByAttacker", orderedPresentEntityIntMap(domain.attackerOrder, domain.minBlockersByAttacker))
            put("maxBlockersByAttacker", orderedPresentEntityIntMap(domain.attackerOrder, domain.maxBlockersByAttacker))
            put("globalMaxBlockers", domain.globalMaxBlockers)
            put("coBlockerRequirements", buildJsonObject {
                domain.blockerOrder
                    .filter { it in domain.coBlockerRequirements }
                    .forEach { blockerId ->
                        put(blockerId.value, buildJsonArray {
                            domain.coBlockerRequirements.getValue(blockerId).forEach { requirement ->
                                add(entityArray(requirement.eligibleCoBlockers))
                            }
                        })
                    }
            })
            put("requirements", buildJsonArray {
                domain.requirements.forEach { requirement ->
                    add(semanticBlockRequirement(requirement))
                }
            })
            put("minimumSatisfiedRequirementCount", domain.minimumSatisfiedRequirementCount)
            put("canDeclareZeroBlockers", domain.canDeclareZeroBlockers)
        }
    }

    private fun semanticBlockRequirement(requirement: BlockRequirementV1): JsonObject = when (requirement) {
        is BlockRequirementV1.BlockSpecific -> buildJsonObject {
            put("type", "block-specific")
            put("blockerId", requirement.blockerId.value)
            put("attackerId", requirement.attackerId.value)
        }
        is BlockRequirementV1.BlockOneOf -> buildJsonObject {
            put("type", "block-one-of")
            put("blockerId", requirement.blockerId.value)
            put("attackerIds", entityArray(requirement.attackerIds))
        }
        is BlockRequirementV1.AttackerMustBeBlockedIfAble -> buildJsonObject {
            put("type", "attacker-must-be-blocked-if-able")
            put("attackerId", requirement.attackerId.value)
        }
        is BlockRequirementV1.AttackerMustBeBlockedByAll -> buildJsonObject {
            put("type", "attacker-must-be-blocked-by-all")
            put("attackerId", requirement.attackerId.value)
        }
        is BlockRequirementV1.BlockerMustBlockIfAble -> buildJsonObject {
            put("type", "blocker-must-block-if-able")
            put("blockerId", requirement.blockerId.value)
        }
    }

    private fun entityArray(entityIds: List<EntityId>) = buildJsonArray {
        entityIds.forEach { add(JsonPrimitive(it.value)) }
    }

    private fun orderedEntityRelation(
        order: List<EntityId>,
        related: (EntityId) -> List<EntityId>,
    ) = buildJsonObject {
        order.forEach { entityId ->
            put(entityId.value, entityArray(related(entityId)))
        }
    }

    private fun orderedEntityIntMap(
        order: List<EntityId>,
        value: (EntityId) -> Int,
    ) = buildJsonObject {
        order.forEach { entityId -> put(entityId.value, value(entityId)) }
    }

    private fun orderedPresentEntityIntMap(
        order: List<EntityId>,
        values: Map<EntityId, Int>,
    ) = buildJsonObject {
        order.filter { it in values }.forEach { entityId -> put(entityId.value, values.getValue(entityId)) }
    }

    private fun orderedEntityListMap(
        order: List<EntityId>,
        values: Map<EntityId, List<EntityId>>,
    ) = buildJsonObject {
        order.filter { it in values }.forEach { entityId ->
            put(entityId.value, entityArray(values.getValue(entityId)))
        }
    }

    /**
     * Remove opaque routing handles from the semantic identity of ordering domains.  Ordinary
     * entity IDs remain established public references; trigger ordering uses generated
     * `trigger-order-object-*` handles whose stable actor-facing labels are the semantic value.
     */
    private fun semanticStructuredDomain(domain: JsonObject): JsonObject {
        val type = domain["type"]?.jsonPrimitive?.content
        if (type == "mana-sources") {
            return stripStructuredPresentation(
                JsonObject(domain.entries
                    .filter { (key, _) -> key != "autoPaySuggestion" }
                    .associate { (key, value) -> key to value })
            ).jsonObject
        }
        if (type != "ordering") return stripStructuredPresentation(domain).jsonObject

        val objectIds = domain["objects"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        val labels = domain["objectLabels"]?.jsonObject
        val cardInfo = domain["cardInfo"]?.jsonObject
        val objectSemantics = objectIds.map { id ->
            buildJsonObject {
                val opaque = id.startsWith("trigger-order-object-")
                if (!opaque) put("entityId", id)
                if (opaque) labels?.get(id)?.let { put("label", it) }
                if (opaque) {
                    cardInfo?.get(id)?.let {
                        put("cardInfo", stripStructuredPresentation(it))
                    }
                }
            }
        }.sortedBy {
            canonicalSortKey(it, B1CanonicalizationProbe.SortSite.STRUCTURED_DOMAIN)
        }

        return stripStructuredPresentation(buildJsonObject {
            domain.entries
                .filter { (key, _) -> key !in setOf("objects", "cardInfo", "objectLabels") }
                .forEach { (key, value) -> put(key, value) }
            put("objectSemantics", JsonArray(objectSemantics))
        }).jsonObject
    }

    /**
     * Keep legal constraints and visible card characteristics in semantic identity, but remove
     * fields that only select a renderer or provide human-facing explanatory copy. These fields
     * remain on the wire for clients; they must not make equivalent information sets hash apart.
     */
    private fun stripStructuredPresentation(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .filter { (key, _) -> key !in structuredPresentationKeys }
                .associate { (key, value) -> key to stripStructuredPresentation(value) }
        )

        is JsonArray -> JsonArray(element.map(::stripStructuredPresentation))
        else -> element
    }

    private val structuredPresentationKeys = setOf(
        "description",
        "filterDescription",
        "iconKey",
        "imageUri",
        "pileLabels",
        "remainderLabel",
        "selectedLabel",
        "text",
        "useTargetingUI"
    )

    private val unorderedArrayKeys = setOf(
        "types",
        "subtypes",
        "colors",
        "keywords",
        "availableColors",
        "attachments",
        "targetEntityIds",
        "validSacrificeTargets",
        "candidates",
        "nonSelectableOptions",
        "matchingOptions",
        "availableSources",
        "waterbendPermanents",
        "producesColors",
        "sourceSubtypes",
        "sourceBuckets",
        "sourceColorBuckets",
        "certifiedFloatingBuckets",
        "blockedByIds",
        "blockedAttackerIds"
    )

    private fun canonicalSortKey(
        element: JsonElement,
        site: B1CanonicalizationProbe.SortSite,
    ): String {
        B1CanonicalizationProbe.recordSortKeyEvaluation(site)
        return canonicalize(element).toString()
    }

    private fun canonicalize(element: JsonElement, propertyName: String? = null): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associate { (key, value) -> key to canonicalize(value, key) }
        )

        is JsonArray -> {
            val values = element.map { canonicalize(it) }
            if (propertyName in unorderedArrayKeys) {
                JsonArray(values.sortedBy { it.toString() })
            } else {
                JsonArray(values)
            }
        }
        else -> element
    }
}
