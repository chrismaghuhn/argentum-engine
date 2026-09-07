package com.wingedsheep.engine.state.components.player

import com.wingedsheep.engine.state.Component
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import kotlinx.serialization.Serializable

/** Version of the Rules-owned known-information state carried by a player entity. */
const val KNOWN_INFORMATION_LEDGER_V1_VERSION: Int = 1

/** Stable identity of the Rules-owned known-information state contract. */
const val KNOWN_INFORMATION_LEDGER_V1_SCHEMA_IDENTITY: String =
    "argentum-rules-known-information-ledger@v1"

/** The independent dimensions of a fact a player may know about a game object. */
@Serializable
enum class KnownInformationFactKind {
    IDENTITY,
    ZONE_MEMBERSHIP,
    POSITION_OR_ORDER,
}

/** Whether a fact was acquired by everyone or only by the owning perspective. */
@Serializable
enum class KnownInformationAudience {
    PUBLIC,
    PERSPECTIVE_PRIVATE,
}

/** Rules-side reason for acquiring a known-information fact. */
@Serializable
enum class KnownInformationAcquisitionReason {
    PUBLIC_REVEAL,
    HAND_REVEAL,
    PRIVATE_HAND_LOOK,
    PRIVATE_CARD_LOOK,
    PRIVATE_LIBRARY_LOOK,
    PRIVATE_SEARCH,
    PRIVATE_DECISION_LOOK,
    VISIBLE_ZONE_TRANSITION,
    EXPLICIT_REVEAL_TO_PLAYER,
}

/**
 * One active fact for the player entity that owns the containing ledger component.
 *
 * The object ID and incarnation stamp are Rules-internal witnesses only. This component is never a
 * model-facing observation or semantic reference. History-C owns any future learner-safe alias.
 */
@Serializable
data class KnownInformationFactV1(
    val subjectEntityId: EntityId,
    val objectIdentityStamp: Long,
    val factKind: KnownInformationFactKind,
    val cardDefinitionId: String? = null,
    val knownZone: Zone? = null,
    val knownPosition: Int? = null,
    val audience: KnownInformationAudience,
    val acquisitionReason: KnownInformationAcquisitionReason,
    /** The ledger epoch at which this active fact was acquired. Rules-internal provenance. */
    val acquiredAtEpoch: Long,
) {
    init {
        require(objectIdentityStamp > 0L) {
            "Known-information facts require a positive object-incarnation stamp"
        }
        require(acquiredAtEpoch >= 0L) {
            "Known-information facts require a non-negative acquisition epoch"
        }
        when (factKind) {
            KnownInformationFactKind.IDENTITY -> require(!cardDefinitionId.isNullOrBlank()) {
                "Identity facts require a card-definition identity"
            }

            KnownInformationFactKind.ZONE_MEMBERSHIP -> require(knownZone != null) {
                "Zone-membership facts require a known zone"
            }

            KnownInformationFactKind.POSITION_OR_ORDER -> {
                require(knownZone == Zone.LIBRARY) {
                    "Position/order facts are currently supported only for libraries"
                }
                require(knownPosition != null && knownPosition >= 0) {
                    "Position/order facts require a non-negative library position"
                }
            }
        }
    }
}

/**
 * Immutable per-player known-information ledger.
 *
 * The component is attached to the player entity whose perspective it represents. It contains only
 * active facts, not an unbounded event log; epoch changes and the committed event source provide the
 * later history composition boundary. Position/order facts are present only when an authoritative
 * producer explicitly supplied order knowledge. The component is Rules state, not a public learner DTO.
 */
@Serializable
data class KnownInformationLedgerComponentV1(
    val version: Int = KNOWN_INFORMATION_LEDGER_V1_VERSION,
    val schemaIdentity: String = KNOWN_INFORMATION_LEDGER_V1_SCHEMA_IDENTITY,
    val knowledgeEpoch: Long = 0L,
    val activeFacts: List<KnownInformationFactV1> = emptyList(),
) : Component {
    init {
        require(version == KNOWN_INFORMATION_LEDGER_V1_VERSION) {
            "Unsupported known-information ledger version: $version"
        }
        require(schemaIdentity == KNOWN_INFORMATION_LEDGER_V1_SCHEMA_IDENTITY) {
            "Unsupported known-information ledger schema: $schemaIdentity"
        }
        require(knowledgeEpoch >= 0L) {
            "Known-information ledger epoch must not be negative"
        }
        require(activeFacts == activeFacts.sortedWith(KnownInformationLedgerOrdering.comparator)) {
            "Known-information facts must use canonical producer ordering"
        }
    }

    companion object {
        val EMPTY = KnownInformationLedgerComponentV1()
    }
}

/** Stable ordering for the state-owned active fact list. */
internal object KnownInformationLedgerOrdering {
    val comparator: Comparator<KnownInformationFactV1> = compareBy(
        { it.subjectEntityId.value },
        { it.objectIdentityStamp },
        { it.factKind.ordinal },
        { it.knownZone?.ordinal ?: -1 },
        { it.knownPosition ?: -1 },
        { it.cardDefinitionId ?: "" },
        { it.audience.ordinal },
        { it.acquisitionReason.ordinal },
        { it.acquiredAtEpoch },
    )
}
