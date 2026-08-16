package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gameserver.persistence.persistenceJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.modules.SerializersModuleCollector
import kotlin.reflect.KClass

/**
 * Canonical v3 representation of the transition-semantic GameState.
 *
 * JSON object keys are sorted, serializer-described unordered collections are sorted by their
 * canonical element bytes, and semantically ordered arrays (notably library, stack, turn order,
 * and options) retain their source order. Runtime decision nonces are replaced only in the typed
 * pending-decision and continuation-reference slots. Allocation-order generated AbilityId handles
 * are replaced only in serializer-typed AbilityId slots, using one state-local alias table so
 * relationships remain represented. Presentation-only decision text is omitted after the replay
 * field audit; semantic decision shape remains in the representation.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object TransitionSemanticGameStateCanonicalizer {

    // Audited against decision validation and resume paths: these values are labels/hints consumed
    // by observation/log presentation only. Their descriptor fields remain in the wire inventory,
    // but their UI text must not make an otherwise identical transition state diverge.
    private val presentationOnlyKeys = setOf("prompt", "effectHint", "yesText", "noText", "hint")

    /**
     * Descriptor-scoped presentation fields from the PendingDecision audit. Keep this map narrow:
     * the surrounding decision objects still contribute their option IDs, ordering, constraints,
     * payment shape, and continuation relationships to the transition fingerprint.
     */
    private val presentationOnlyFieldsByType = mapOf(
        "DecisionContext" to setOf("sourceName", "inlineOnTrigger"),
        "TargetRequirementInfo" to setOf("description"),
        "ConditionalSelectionMinimum" to setOf("description"),
            "SelectCardsDecision" to setOf(
                "cardInfo",
                "useTargetingUI",
                "selectedLabel",
                "remainderLabel",
                "nonSelectableOptions",
            ),
        "OrderObjectsDecision" to setOf("cardInfo"),
        "SplitPilesDecision" to setOf("pileLabels", "cardInfo"),
        "OptionMetadata" to setOf("description", "iconKey"),
        "ChooseOptionDecision" to setOf("defaultSearch", "optionCardIds"),
        "SearchLibraryDecision" to setOf("cards", "filterDescription"),
        "ReorderLibraryDecision" to setOf("cardInfo"),
        "ManaSourceOption" to setOf("name"),
        "WaterbendPermanentChoice" to setOf("name", "isCreature"),
        "BudgetModeOption" to setOf("description"),
    )

    /** Polymorphic state values need their concrete descriptor to discover nested Set fields. */
    private val polymorphicDescriptors: Map<String, SerialDescriptor> by lazy {
        val descriptors = linkedMapOf<String, SerialDescriptor>()
        persistenceJson.serializersModule.dumpTo(object : SerializersModuleCollector {
            override fun <T : Any> contextual(
                kClass: KClass<T>,
                provider: (List<KSerializer<*>>) -> KSerializer<*>,
            ) = Unit

            override fun <Base : Any, Sub : Base> polymorphic(
                baseClass: KClass<Base>,
                actualClass: KClass<Sub>,
                actualSerializer: KSerializer<Sub>,
            ) {
                descriptors.putIfAbsent(actualSerializer.descriptor.serialName, actualSerializer.descriptor)
            }

            override fun <Base : Any> polymorphicDefaultSerializer(
                baseClass: KClass<Base>,
                defaultSerializerProvider: (Base) -> SerializationStrategy<Base>?,
            ) = Unit

            override fun <Base : Any> polymorphicDefaultDeserializer(
                baseClass: KClass<Base>,
                defaultDeserializerProvider: (String?) -> DeserializationStrategy<Base>?,
            ) = Unit
        })
        descriptors
    }

    fun canonicalJson(state: GameState): String {
        val serialized = persistenceJson.encodeToJsonElement(GameState.serializer(), state)
        val decisionAliases = DecisionNonceAliasTable()
        val abilityPlan = collectAbilityAliases(serialized, GameState.serializer().descriptor)
        val abilityAliases = AbilityIdAliasTable(abilityPlan.aliases, abilityPlan.reservedRawIds)
        val canonical = canonicalize(
            element = serialized,
            path = emptyList(),
            decisionAliases = decisionAliases,
            abilityAliases = abilityAliases,
            descriptor = GameState.serializer().descriptor,
        )
        return persistenceJson.encodeToString(JsonElement.serializer(), canonical)
    }

    private data class AbilityOccurrence(
        val rawId: String,
        val signature: String,
        val specificity: Int,
    )

    private data class AbilityAliasPlan(
        val aliases: Map<String, String>,
        val reservedRawIds: Set<String>,
    )

    private data class UnorderedMember(
        val index: Int,
        val pathToken: String,
    )

    /**
     * Assign aliases before canonicalization so an unordered collection cannot decide an alias
     * merely by its source iteration order. The scan records normalized paths (unique map/set
     * shapes receive ranks; tied shapes share one path token) for every occurrence, then assigns
     * aliases by those semantic occurrence signatures. Repeated references remain grouped by raw
     * handle.
     */
    private fun collectAbilityAliases(
        element: JsonElement,
        descriptor: SerialDescriptor?,
    ): AbilityAliasPlan {
        val occurrences = mutableListOf<AbilityOccurrence>()
        val rawIds = linkedSetOf<String>()
        collectAbilityOccurrences(element, emptyList(), descriptor, occurrences, rawIds)
        val generatedIds = occurrences.mapTo(hashSetOf()) { it.rawId }
        val sortedGeneratedIds = occurrences
            .groupBy { it.rawId }
            .entries
            .sortedWith(
                compareBy<Map.Entry<String, List<AbilityOccurrence>>>(
                    { entry ->
                        entry.value
                            .sortedWith(compareByDescending<AbilityOccurrence> { it.specificity }.thenBy { it.signature })
                            .joinToString("\u0000") { "${it.specificity}:${it.signature}" }
                    },
                )
            )
            .map { (rawId, _) -> rawId }
        val aliasTable = AbilityIdAliasTable(reservedRawIds = rawIds - generatedIds)
        val aliases = sortedGeneratedIds.associateWithTo(LinkedHashMap()) { rawId ->
            aliasTable.aliasIfGenerated(rawId)
        }
        return AbilityAliasPlan(aliases, rawIds - generatedIds)
    }

    private fun collectAbilityOccurrences(
        element: JsonElement,
        path: List<String>,
        descriptor: SerialDescriptor?,
        occurrences: MutableList<AbilityOccurrence>,
        rawIds: MutableSet<String>,
    ) {
        when (element) {
            is JsonObject -> {
                val decisionTree = path.contains("pendingDecision") || path.contains("continuationStack")
                val concreteDescriptor = resolvePolymorphicDescriptor(descriptor, element)
                val mapDescriptor = concreteDescriptor?.takeIf { it.kind == StructureKind.MAP }
                val entries = element.entries.toList()
                val orderedEntries = if (mapDescriptor != null) {
                    val keyDescriptor = mapDescriptor.getElementDescriptor(0)
                    val valueDescriptor = mapDescriptor.getElementDescriptor(1)
                    unorderedMembers(
                        size = entries.size,
                        tokenPrefix = "map",
                    ) { index ->
                        val (rawKey, value) = entries[index]
                        JsonArray(
                            listOf(
                                shapeMapKey(rawKey, keyDescriptor),
                                shape(value, path + "map-value", valueDescriptor),
                            )
                        )
                    }
                } else {
                    entries.indices
                        .sortedBy { entries[it].key }
                        .map { index -> UnorderedMember(index, entries[index].key) }
                }

                orderedEntries.forEach { member ->
                    val index = member.index
                    val (key, value) = entries[index]
                    if (decisionTree && isPresentationOnlyField(key, concreteDescriptor)) return@forEach
                    if (mapDescriptor != null) {
                        val keyDescriptor = mapDescriptor.getElementDescriptor(0)
                        if (keyDescriptor.isAbilityIdDescriptor()) {
                            rawIds += key
                            if (AbilityIdAliasTable.isGenerated(key)) {
                                occurrences += AbilityOccurrence(
                                    rawId = key,
                                    signature = (path + member.pathToken + ".key" + keyDescriptor.serialName).joinToString("/"),
                                    // A map key carries the associated map value's semantic
                                    // distinction; let it outrank a bare Set occurrence when the same
                                    // handle is referenced by both structures.
                                    specificity = path.size + 100,
                                )
                            }
                        }
                        collectAbilityOccurrences(
                            element = value,
                            path = path + member.pathToken + ".value",
                            descriptor = mapDescriptor.getElementDescriptor(1),
                            occurrences = occurrences,
                            rawIds = rawIds,
                        )
                    } else {
                        collectAbilityOccurrences(
                            element = value,
                            path = path + key,
                            descriptor = concreteDescriptor?.fieldDescriptor(key),
                            occurrences = occurrences,
                            rawIds = rawIds,
                        )
                    }
                }
            }

            is JsonArray -> {
                if (descriptor?.kind == StructureKind.MAP) {
                    val keyDescriptor = descriptor.getElementDescriptor(0)
                    val valueDescriptor = descriptor.getElementDescriptor(1)
                    val pairIndexes = unorderedMembers(
                        size = element.size / 2,
                        tokenPrefix = "map",
                    ) { index ->
                        JsonArray(
                            listOf(
                                shape(element[index * 2], path + "map-key", keyDescriptor),
                                shape(element[index * 2 + 1], path + "map-value", valueDescriptor),
                            )
                        )
                    }
                    pairIndexes.forEach { member ->
                        val index = member.index
                        collectAbilityOccurrences(
                            element = element[index * 2],
                            path = path + member.pathToken + ".key",
                            descriptor = keyDescriptor,
                            occurrences = occurrences,
                            rawIds = rawIds,
                        )
                        collectAbilityOccurrences(
                            element = element[index * 2 + 1],
                            path = path + member.pathToken + ".value",
                            descriptor = valueDescriptor,
                            occurrences = occurrences,
                            rawIds = rawIds,
                        )
                    }
                    return
                }

                val elementDescriptor = descriptor?.getElementDescriptorOrNull()
                val indexes = if (descriptor.isUnorderedSetDescriptor()) {
                    unorderedMembers(
                        size = element.size,
                        tokenPrefix = "set",
                    ) { index -> shape(element[index], path + "set-value", elementDescriptor) }
                } else {
                    element.indices.map { index -> UnorderedMember(index, "[$index]") }
                }
                indexes.forEach { member ->
                    collectAbilityOccurrences(
                        element = element[member.index],
                        path = path + member.pathToken,
                        descriptor = elementDescriptor,
                        occurrences = occurrences,
                        rawIds = rawIds,
                    )
                }
            }

            is JsonPrimitive -> {
                if (element.isString && descriptor.isAbilityIdDescriptor()) {
                    rawIds += element.content
                }
                if (
                    element.isString &&
                    descriptor.isAbilityIdDescriptor() &&
                    AbilityIdAliasTable.isGenerated(element.content)
                ) {
                    occurrences += AbilityOccurrence(
                        rawId = element.content,
                        signature = (path + (descriptor?.serialName ?: "unknown")).joinToString("/"),
                        specificity = path.size,
                    )
                }
            }
        }
    }

    /**
     * Sort unordered members by their raw-ID-free shape. Members whose shapes tie share one path
     * token instead of receiving a source-order rank; they are semantically interchangeable in
     * that collection, so source insertion order must not influence the alias plan.
     */
    private fun unorderedMembers(
        size: Int,
        tokenPrefix: String,
        shapeAt: (Int) -> JsonElement,
    ): List<UnorderedMember> {
        val shaped = (0 until size)
            .map { index -> index to render(shapeAt(index)) }
            .sortedBy { (_, shapeKey) -> shapeKey }
        return shaped
            .groupBy { (_, shapeKey) -> shapeKey }
            .entries
            .flatMapIndexed { groupIndex, (_, members) ->
                val pathToken = if (members.size == 1) {
                    "$tokenPrefix[$groupIndex]"
                } else {
                    "$tokenPrefix[tie-$groupIndex]"
                }
                members.map { (index, _) -> UnorderedMember(index, pathToken) }
            }
    }

    /** Raw-ID-free shape used only to order unordered collection members during alias discovery. */
    private fun shape(
        element: JsonElement,
        path: List<String>,
        descriptor: SerialDescriptor?,
    ): JsonElement = when (element) {
        is JsonObject -> {
            val decisionTree = path.contains("pendingDecision") || path.contains("continuationStack")
            val concreteDescriptor = resolvePolymorphicDescriptor(descriptor, element)
            val mapDescriptor = concreteDescriptor?.takeIf { it.kind == StructureKind.MAP }
            if (mapDescriptor != null) {
                val keyDescriptor = mapDescriptor.getElementDescriptor(0)
                val valueDescriptor = mapDescriptor.getElementDescriptor(1)
                JsonArray(
                    element.entries
                        .map { (key, value) ->
                            JsonArray(
                                listOf(
                                    shapeMapKey(key, keyDescriptor),
                                    shape(value, path + "map-value", valueDescriptor),
                                )
                            )
                        }
                        .sortedBy { render(it) }
                )
            } else {
                val entries = element.entries
                    .asSequence()
                    .filterNot { (key, _) -> decisionTree && isPresentationOnlyField(key, concreteDescriptor) }
                    .map { (key, value) ->
                        key to shape(value, path + key, concreteDescriptor?.fieldDescriptor(key))
                    }
                    .sortedBy { it.first }
                    .toList()
                JsonObject(LinkedHashMap<String, JsonElement>().apply {
                    entries.forEach { (key, value) -> put(key, value) }
                })
            }
        }

        is JsonArray -> {
            if (descriptor?.kind == StructureKind.MAP) {
                val keyDescriptor = descriptor.getElementDescriptor(0)
                val valueDescriptor = descriptor.getElementDescriptor(1)
                val pairs = (0 until element.size / 2)
                    .map { index ->
                        JsonArray(
                            listOf(
                                shape(element[index * 2], path + "map-key", keyDescriptor),
                                shape(element[index * 2 + 1], path + "map-value", valueDescriptor),
                            )
                        )
                    }
                    .sortedBy { render(it) }
                JsonArray(pairs)
            } else {
                val elementDescriptor = descriptor?.getElementDescriptorOrNull()
                val shaped = element.map { child -> shape(child, path + "array-value", elementDescriptor) }
                if (descriptor.isUnorderedSetDescriptor()) {
                    JsonArray(shaped.sortedBy { render(it) })
                } else {
                    JsonArray(shaped)
                }
            }
        }

        is JsonPrimitive -> {
            if (
                element.isString &&
                descriptor.isAbilityIdDescriptor() &&
                AbilityIdAliasTable.isGenerated(element.content)
            ) {
                JsonPrimitive("<generated-ability>")
            } else {
                element
            }
        }
    }

    private fun shapeMapKey(rawKey: String, descriptor: SerialDescriptor): JsonPrimitive =
        if (descriptor.isAbilityIdDescriptor() && AbilityIdAliasTable.isGenerated(rawKey)) {
            JsonPrimitive("<generated-ability>")
        } else {
            JsonPrimitive(rawKey)
        }

    private fun canonicalize(
        element: JsonElement,
        path: List<String>,
        decisionAliases: DecisionNonceAliasTable,
        abilityAliases: AbilityIdAliasTable,
        descriptor: SerialDescriptor?,
    ): JsonElement = when (element) {
        is JsonObject -> {
            val decisionTree = path.contains("pendingDecision") || path.contains("continuationStack")
            val concreteDescriptor = resolvePolymorphicDescriptor(descriptor, element)
            val mapDescriptor = concreteDescriptor?.takeIf { it.kind == StructureKind.MAP }
            val entries = if (
                mapDescriptor != null &&
                mapDescriptor.getElementDescriptor(0).isAbilityIdDescriptor()
            ) {
                val keyDescriptor = mapDescriptor.getElementDescriptor(0)
                val valueDescriptor = mapDescriptor.getElementDescriptor(1)
                element.entries.sortedBy { (rawKey, value) ->
                    val localDecisionAliases = DecisionNonceAliasTable()
                    val localAbilityAliases = AbilityIdAliasTable()
                    val canonicalKey = canonicalizeMapKey(rawKey, keyDescriptor, localAbilityAliases)
                    val canonicalValue = canonicalize(
                        element = value,
                        path = path + rawKey,
                        decisionAliases = localDecisionAliases,
                        abilityAliases = localAbilityAliases,
                        descriptor = valueDescriptor,
                    )
                    render(JsonArray(listOf(JsonPrimitive(canonicalKey), canonicalValue)))
                }
            } else {
                element.entries
            }
            val sorted = entries
                .asSequence()
                .filterNot { (key, _) -> decisionTree && isPresentationOnlyField(key, concreteDescriptor) }
                .map { (key, value) ->
                    val canonicalKey = if (mapDescriptor != null) {
                        canonicalizeMapKey(
                            rawKey = key,
                            descriptor = mapDescriptor.getElementDescriptor(0),
                            abilityAliases = abilityAliases,
                        )
                    } else {
                        key
                    }
                    canonicalKey to canonicalize(
                        element = value,
                        path = path + key,
                        decisionAliases = decisionAliases,
                        abilityAliases = abilityAliases,
                        descriptor = if (mapDescriptor != null) {
                            mapDescriptor.getElementDescriptor(1)
                        } else {
                            concreteDescriptor?.fieldDescriptor(key)
                        },
                    )
                }
                .sortedBy { it.first }
                .toList()
            val ordered = LinkedHashMap<String, JsonElement>(sorted.size)
            sorted.forEach { (key, value) -> ordered[key] = value }
            JsonObject(ordered)
        }

        is JsonArray -> {
            if (descriptor?.kind == StructureKind.MAP) {
                val keyDescriptor = descriptor.getElementDescriptor(0)
                val valueDescriptor = descriptor.getElementDescriptor(1)
                // allowStructuredMapKeys encodes a map with structured keys as interleaved
                // key/value entries. Keep that wire shape, but sort the key/value pairs.
                if (keyDescriptor.kind is StructureKind || keyDescriptor.kind is PolymorphicKind) {
                    val pairIndexes = (0 until element.size / 2).sortedBy { index ->
                        val localDecisionAliases = DecisionNonceAliasTable()
                        val localAbilityAliases = AbilityIdAliasTable()
                        val key = canonicalize(
                            element = element[index * 2],
                            path = path + "key",
                            decisionAliases = localDecisionAliases,
                            abilityAliases = localAbilityAliases,
                            descriptor = keyDescriptor,
                        )
                        val value = canonicalize(
                            element = element[index * 2 + 1],
                            path = path + "value",
                            decisionAliases = localDecisionAliases,
                            abilityAliases = localAbilityAliases,
                            descriptor = valueDescriptor,
                        )
                        render(JsonArray(listOf(key, value)))
                    }
                    val pairs = pairIndexes.map { index ->
                        canonicalize(
                            element = element[index * 2],
                            path = path + "key",
                            decisionAliases = decisionAliases,
                            abilityAliases = abilityAliases,
                            descriptor = keyDescriptor,
                        ) to canonicalize(
                            element = element[index * 2 + 1],
                            path = path + "value",
                            decisionAliases = decisionAliases,
                            abilityAliases = abilityAliases,
                            descriptor = valueDescriptor,
                        )
                    }
                    return JsonArray(pairs.flatMap { (key, value) -> listOf(key, value) })
                }

                val canonicalEntries = element.map { entry ->
                    val pair = entry as? JsonArray ?: return@map canonicalize(
                        element = entry,
                        path = path + "entry",
                        decisionAliases = decisionAliases,
                        abilityAliases = abilityAliases,
                        descriptor = null,
                    )
                    JsonArray(
                        listOf(
                            canonicalize(
                                pair[0],
                                path + "key",
                                decisionAliases,
                                abilityAliases,
                                keyDescriptor,
                            ),
                            canonicalize(pair[1], path + "value", decisionAliases, abilityAliases, valueDescriptor),
                        )
                    )
                }
                return JsonArray(canonicalEntries.sortedBy {
                    val pair = it as? JsonArray
                    render(if (pair != null && pair.size > 0) pair[0] else JsonPrimitive(""))
                })
            }

            val elementDescriptor = descriptor?.getElementDescriptorOrNull()
            val indexes = if (descriptor.isUnorderedSetDescriptor()) {
                element.indices.sortedBy { index ->
                    val localDecisionAliases = DecisionNonceAliasTable()
                    val localAbilityAliases = AbilityIdAliasTable()
                    render(
                        canonicalize(
                            element = element[index],
                            path = path + index.toString(),
                            decisionAliases = localDecisionAliases,
                            abilityAliases = localAbilityAliases,
                            descriptor = elementDescriptor,
                        )
                    )
                }
            } else {
                element.indices
            }
            val canonicalElements = indexes.map { index ->
                val child = element[index]
                canonicalize(
                    element = child,
                    path = path + index.toString(),
                    decisionAliases = decisionAliases,
                    abilityAliases = abilityAliases,
                    descriptor = elementDescriptor,
                )
            }
            if (descriptor.isUnorderedSetDescriptor()) {
                JsonArray(canonicalElements.sortedBy { render(it) })
            } else {
                JsonArray(canonicalElements)
            }
        }

        is JsonPrimitive -> canonicalizePrimitive(element, path, decisionAliases, abilityAliases, descriptor)
    }

    private fun resolvePolymorphicDescriptor(
        descriptor: SerialDescriptor?,
        element: JsonObject,
    ): SerialDescriptor? {
        if (descriptor == null || descriptor.kind !is PolymorphicKind) return descriptor
        val typeName = element["type"]?.jsonPrimitive?.contentOrNull ?: return descriptor
        return polymorphicDescriptors[typeName]
            ?: descriptor.findConcreteDescriptor(typeName)
            ?: descriptor
    }

    private fun SerialDescriptor.findConcreteDescriptor(
        typeName: String,
        seen: MutableSet<String> = mutableSetOf(),
    ): SerialDescriptor? {
        if (!seen.add(serialName)) return null
        if (serialName == typeName || serialName.substringAfterLast('.') == typeName.substringAfterLast('.')) {
            return this
        }
        for (index in 0 until elementsCount) {
            val match = getElementDescriptor(index).findConcreteDescriptor(typeName, seen)
            if (match != null) return match
        }
        return null
    }

    private fun isPresentationOnlyField(key: String, descriptor: SerialDescriptor?): Boolean {
        if (key in presentationOnlyKeys) return true
        val typeName = descriptor?.serialName?.substringAfterLast('.') ?: return false
        return key in presentationOnlyFieldsByType[typeName].orEmpty() ||
            (key == "text" && typeName == "ModeOption")
    }

    private fun SerialDescriptor.fieldDescriptor(key: String): SerialDescriptor? {
        if (kind == StructureKind.MAP) return getElementDescriptor(1)
        if (kind != StructureKind.CLASS && kind !is PolymorphicKind) return null
        val index = getElementIndex(key)
        return if (index < 0) null else getElementDescriptor(index)
    }

    private fun SerialDescriptor.getElementDescriptorOrNull(): SerialDescriptor? =
        if (elementsCount == 0) null else getElementDescriptor(0)

    private fun SerialDescriptor?.isUnorderedSetDescriptor(): Boolean =
        this != null && (
            serialName.endsWith(".HashSet") ||
                serialName.endsWith(".LinkedHashSet") ||
                serialName == "kotlin.collections.Set"
            )

    private fun canonicalizePrimitive(
        primitive: JsonPrimitive,
        path: List<String>,
        decisionAliases: DecisionNonceAliasTable,
        abilityAliases: AbilityIdAliasTable,
        descriptor: SerialDescriptor?,
    ): JsonPrimitive {
        if (!primitive.isString) return primitive
        if (descriptor.isAbilityIdDescriptor()) {
            return JsonPrimitive(abilityAliases.aliasIfGenerated(primitive.content))
        }
        val key = path.lastOrNull() ?: return primitive
        val pendingDecisionId = key == "id" && path.contains("pendingDecision")
        val continuationDecisionId = key == "decisionId" && path.contains("continuationStack")
        if (!pendingDecisionId && !continuationDecisionId) return primitive
        return JsonPrimitive(decisionAliases.alias(primitive.content))
    }

    private fun SerialDescriptor?.isAbilityIdDescriptor(): Boolean =
        this?.serialName == "com.wingedsheep.sdk.scripting.AbilityId"

    private fun canonicalizeMapKey(
        rawKey: String,
        descriptor: SerialDescriptor,
        abilityAliases: AbilityIdAliasTable,
    ): String = if (descriptor.isAbilityIdDescriptor()) {
        abilityAliases.aliasIfGenerated(rawKey)
    } else {
        rawKey
    }

    private fun render(element: JsonElement): String =
        persistenceJson.encodeToString(JsonElement.serializer(), element)
}
