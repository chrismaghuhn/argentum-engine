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
 * pending-decision and continuation-reference slots. Presentation-only decision text is omitted
 * after the replay field audit; semantic decision shape remains in the representation.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object TransitionSemanticGameStateCanonicalizer {

    private val presentationOnlyKeys = setOf("prompt", "effectHint")

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
        val aliases = DecisionNonceAliasTable()
        val canonical = canonicalize(
            element = serialized,
            path = emptyList(),
            aliases = aliases,
            descriptor = GameState.serializer().descriptor,
        )
        return persistenceJson.encodeToString(JsonElement.serializer(), canonical)
    }

    private fun canonicalize(
        element: JsonElement,
        path: List<String>,
        aliases: DecisionNonceAliasTable,
        descriptor: SerialDescriptor?,
    ): JsonElement = when (element) {
        is JsonObject -> {
            val decisionTree = path.contains("pendingDecision") || path.contains("continuationStack")
            val concreteDescriptor = resolvePolymorphicDescriptor(descriptor, element)
            val sorted = element.entries
                .asSequence()
                .filterNot { (key, _) -> decisionTree && key in presentationOnlyKeys }
                .map { (key, value) ->
                    key to canonicalize(
                        element = value,
                        path = path + key,
                        aliases = aliases,
                        descriptor = concreteDescriptor?.fieldDescriptor(key),
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
                    val pairs = (0 until element.size / 2).map { index ->
                        canonicalize(
                            element = element[index * 2],
                            path = path + "key",
                            aliases = aliases,
                            descriptor = keyDescriptor,
                        ) to canonicalize(
                            element = element[index * 2 + 1],
                            path = path + "value",
                            aliases = aliases,
                            descriptor = valueDescriptor,
                        )
                    }.sortedBy { (key) -> render(key) }
                    return JsonArray(pairs.flatMap { (key, value) -> listOf(key, value) })
                }

                val canonicalEntries = element.map { entry ->
                    val pair = entry as? JsonArray ?: return@map canonicalize(
                        element = entry,
                        path = path + "entry",
                        aliases = aliases,
                        descriptor = null,
                    )
                    JsonArray(
                        listOf(
                            canonicalize(pair[0], path + "key", aliases, keyDescriptor),
                            canonicalize(pair[1], path + "value", aliases, valueDescriptor),
                        )
                    )
                }
                return JsonArray(canonicalEntries.sortedBy {
                    val pair = it as? JsonArray
                    render(if (pair != null && pair.size > 0) pair[0] else JsonPrimitive(""))
                })
            }

            val elementDescriptor = descriptor?.getElementDescriptorOrNull()
            val canonicalElements = element.mapIndexed { index, child ->
                canonicalize(
                    element = child,
                    path = path + index.toString(),
                    aliases = aliases,
                    descriptor = elementDescriptor,
                )
            }
            if (descriptor.isUnorderedSetDescriptor()) {
                JsonArray(canonicalElements.sortedBy { render(it) })
            } else {
                JsonArray(canonicalElements)
            }
        }

        is JsonPrimitive -> canonicalizePrimitive(element, path, aliases)
    }

    private fun resolvePolymorphicDescriptor(
        descriptor: SerialDescriptor?,
        element: JsonObject,
    ): SerialDescriptor? {
        if (descriptor == null || descriptor.kind !is PolymorphicKind) return descriptor
        val typeName = element["type"]?.jsonPrimitive?.contentOrNull ?: return descriptor
        return polymorphicDescriptors[typeName] ?: descriptor
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
        aliases: DecisionNonceAliasTable,
    ): JsonPrimitive {
        if (!primitive.isString) return primitive
        val key = path.lastOrNull() ?: return primitive
        val pendingDecisionId = key == "id" && path.contains("pendingDecision")
        val continuationDecisionId = key == "decisionId" && path.contains("continuationStack")
        if (!pendingDecisionId && !continuationDecisionId) return primitive
        return JsonPrimitive(aliases.alias(primitive.content))
    }

    private fun render(element: JsonElement): String =
        persistenceJson.encodeToString(JsonElement.serializer(), element)
}
