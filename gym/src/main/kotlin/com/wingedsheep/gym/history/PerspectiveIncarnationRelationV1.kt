package com.wingedsheep.gym.history

const val PERSPECTIVE_INCARNATION_RELATION_V1_VERSION: Int = 1

const val PERSPECTIVE_INCARNATION_RELATION_V1_SCHEMA_IDENTITY: String =
    "argentum-gym-history-c-incarnation-relation@v1"

/** Relation kinds reserved for independently validated Rules/effect producer evidence. */
internal enum class PerspectiveIncarnationRelationKind {
    ZONE_TRANSITION,
}

/**
 * Internal, perspective-safe evidence that two already-authorized aliases are related.
 *
 * This type deliberately carries aliases only. It never stores the Rules witness, raw event
 * coordinates, a zone index, or a runtime identity. HISTC-C currently emits no relations because
 * the accepted A+B inputs do not yet contain a typed producer-owned relation witness.
 */
internal data class PerspectiveIncarnationRelationV1(
    val version: Int = PERSPECTIVE_INCARNATION_RELATION_V1_VERSION,
    val schemaIdentity: String = PERSPECTIVE_INCARNATION_RELATION_V1_SCHEMA_IDENTITY,
    val relationKind: PerspectiveIncarnationRelationKind,
    val oldAlias: PerspectiveSemanticAlias,
    val newAlias: PerspectiveSemanticAlias,
) {
    init {
        require(version == PERSPECTIVE_INCARNATION_RELATION_V1_VERSION) {
            "Unsupported History-C incarnation relation version: $version"
        }
        require(schemaIdentity == PERSPECTIVE_INCARNATION_RELATION_V1_SCHEMA_IDENTITY) {
            "Unsupported History-C incarnation relation schema: $schemaIdentity"
        }
        require(oldAlias != newAlias) {
            "An incarnation relation requires distinct old and new aliases"
        }
    }
}
