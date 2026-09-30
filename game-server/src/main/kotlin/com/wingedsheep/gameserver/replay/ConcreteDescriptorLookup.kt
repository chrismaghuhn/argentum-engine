package com.wingedsheep.gameserver.replay

import kotlinx.serialization.descriptors.SerialDescriptor
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Memoized depth-first search for the concrete descriptor of a polymorphic JSON object's `type`.
 *
 * Serializer descriptor graphs are immutable, so the search is a pure function of
 * (descriptor instance, type name). Uncached, every polymorphic object in every canonicalized
 * state or replay re-walked the whole sealed-hierarchy graph, which dominated shard-generation CPU
 * time. The cache is keyed by descriptor identity, so each answer is exactly what the uncached
 * search returns for that instance.
 */
internal object ConcreteDescriptorLookup {
    private val cache = ConcurrentHashMap<Key, Optional<SerialDescriptor>>()

    private class Key(val descriptor: SerialDescriptor, val typeName: String) {
        override fun equals(other: Any?): Boolean =
            other is Key && other.descriptor === descriptor && other.typeName == typeName

        override fun hashCode(): Int = 31 * System.identityHashCode(descriptor) + typeName.hashCode()
    }

    fun find(descriptor: SerialDescriptor, typeName: String): SerialDescriptor? =
        cache.computeIfAbsent(Key(descriptor, typeName)) {
            Optional.ofNullable(descriptor.search(typeName, mutableSetOf()))
        }.orElse(null)

    private fun SerialDescriptor.search(typeName: String, seen: MutableSet<String>): SerialDescriptor? {
        if (!seen.add(serialName)) return null
        if (serialName == typeName || serialName.substringAfterLast('.') == typeName.substringAfterLast('.')) {
            return this
        }
        for (index in 0 until elementsCount) {
            val match = getElementDescriptor(index).search(typeName, seen)
            if (match != null) return match
        }
        return null
    }
}
