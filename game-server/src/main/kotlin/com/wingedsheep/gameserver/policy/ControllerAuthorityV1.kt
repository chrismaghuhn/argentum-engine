package com.wingedsheep.gameserver.policy

import kotlinx.serialization.Serializable

const val CONTROLLER_AUTHORITY_V1_VERSION: Int = 1
const val CONTROLLER_AUTHORITY_V1_SCHEMA_IDENTITY: String =
    "argentum-gameserver-controller-authority@v1"

const val C1_07B_POLICY_PROFILE_IDENTITY: String =
    "argentum-mtg-ml-akiri-vs-engine-chevill@v1"
const val C1_07B_CHECKPOINT_IDENTITY: String =
    "f4b191d99734af66a5643c988ce3c2f2198a2957be24e2a717287006a43124c5"
const val C1_07B_INFERENCE_CONTRACT_IDENTITY: String = "argentum-ml-inference@v1"
const val C1_07B_SELECTION_CONTRACT_IDENTITY: String = "argentum-ml-policy-selection@v2"
const val C1_07B_POLICY_RNG_CONTRACT_IDENTITY: String = "argentum-ml-policy-tie-rng@v1"

/** Explicit server-side ownership of a seat. Legacy AI is retained only for existing LLM wiring. */
@Serializable
enum class ControllerKindV1 {
    HUMAN,
    ENGINE_AI,
    ML_POLICY,
    LEGACY_AI,
}

/**
 * Immutable semantic authority needed to reconstruct one seat controller.
 *
 * Runtime handles, process details, paths, sockets, and request identities deliberately have no
 * representation here. The ML branch is a fixed server-owned profile; callers cannot substitute a
 * checkpoint, executable, or contract identity.
 */
@Serializable
data class ControllerAuthorityV1(
    val version: Int = CONTROLLER_AUTHORITY_V1_VERSION,
    val schemaIdentity: String = CONTROLLER_AUTHORITY_V1_SCHEMA_IDENTITY,
    val seatIndex: Int,
    val controllerKind: ControllerKindV1,
    val policyProfileIdentity: String? = null,
    val checkpointId: String? = null,
    val inferenceContractIdentity: String? = null,
    val selectionContractIdentity: String? = null,
    val policyRngContractIdentity: String? = null,
    val policySeed: Long? = null,
) {
    init {
        require(version == CONTROLLER_AUTHORITY_V1_VERSION) {
            "Unsupported controller-authority version: $version"
        }
        require(schemaIdentity == CONTROLLER_AUTHORITY_V1_SCHEMA_IDENTITY) {
            "Unsupported controller-authority identity: $schemaIdentity"
        }
        require(seatIndex >= 0) { "Controller authority seat index must be non-negative" }
        when (controllerKind) {
            ControllerKindV1.ML_POLICY -> {
                require(policyProfileIdentity == C1_07B_POLICY_PROFILE_IDENTITY) {
                    "ML controller authority must use the server-owned C1_07B profile"
                }
                require(checkpointId == C1_07B_CHECKPOINT_IDENTITY) {
                    "ML controller authority must use the accepted C1_06 checkpoint"
                }
                require(inferenceContractIdentity == C1_07B_INFERENCE_CONTRACT_IDENTITY) {
                    "ML controller authority must use the accepted inference contract"
                }
                require(selectionContractIdentity == C1_07B_SELECTION_CONTRACT_IDENTITY) {
                    "ML controller authority must use Selection V2"
                }
                require(policyRngContractIdentity == C1_07B_POLICY_RNG_CONTRACT_IDENTITY) {
                    "ML controller authority must use PolicyTieRng V1"
                }
                require(policySeed != null) { "ML controller authority requires a policy seed" }
            }

            ControllerKindV1.HUMAN,
            ControllerKindV1.ENGINE_AI,
            ControllerKindV1.LEGACY_AI,
            -> requireNoPolicyFields()
        }
    }

    val isMlPolicy: Boolean get() = controllerKind == ControllerKindV1.ML_POLICY

    private fun requireNoPolicyFields() {
        require(
            policyProfileIdentity == null &&
                checkpointId == null &&
                inferenceContractIdentity == null &&
                selectionContractIdentity == null &&
                policyRngContractIdentity == null &&
                policySeed == null,
        ) {
            "Non-ML controller authority cannot carry policy fields"
        }
    }

    companion object {
        fun human(seatIndex: Int): ControllerAuthorityV1 =
            ControllerAuthorityV1(seatIndex = seatIndex, controllerKind = ControllerKindV1.HUMAN)

        fun engineAi(seatIndex: Int): ControllerAuthorityV1 =
            ControllerAuthorityV1(seatIndex = seatIndex, controllerKind = ControllerKindV1.ENGINE_AI)

        fun legacyAi(seatIndex: Int): ControllerAuthorityV1 =
            ControllerAuthorityV1(seatIndex = seatIndex, controllerKind = ControllerKindV1.LEGACY_AI)

        fun mlPolicy(seatIndex: Int, policySeed: Long): ControllerAuthorityV1 =
            ControllerAuthorityV1(
                seatIndex = seatIndex,
                controllerKind = ControllerKindV1.ML_POLICY,
                policyProfileIdentity = C1_07B_POLICY_PROFILE_IDENTITY,
                checkpointId = C1_07B_CHECKPOINT_IDENTITY,
                inferenceContractIdentity = C1_07B_INFERENCE_CONTRACT_IDENTITY,
                selectionContractIdentity = C1_07B_SELECTION_CONTRACT_IDENTITY,
                policyRngContractIdentity = C1_07B_POLICY_RNG_CONTRACT_IDENTITY,
                policySeed = policySeed,
            )
    }
}
