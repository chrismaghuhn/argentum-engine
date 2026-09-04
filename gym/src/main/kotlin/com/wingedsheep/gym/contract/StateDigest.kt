package com.wingedsheep.gym.contract

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * SHA-256 over [ObservationCanonicalizer]'s perspective-safe semantic projection.
 *
 * The semantic projection includes structured legal-action payloads/fingerprints but excludes
 * transport handles (`actionId`, `decisionId`, generated activated-ability handles, and donor
 * EntityIds embedded in those handles), presentation-only text, and the digest field itself.
 */
object StateDigest {

    fun compute(obs: TrainingObservation): String {
        val semantic = ObservationCanonicalizer.semanticJson(obs)
        return digest(semantic)
    }

    /**
     * Recompute the existing source observation digest from the durable A1/A2 public contracts.
     * This uses the same semantic JSON authority as [compute] and deliberately accepts no Rules
     * state, raw action, or transport handle.
     */
    fun compute(observation: PlayerObservationV1, domain: CompleteLegalDomainV1): String {
        val semantic = ObservationCanonicalizer.semanticJson(observation, domain)
        return digest(semantic)
    }

    private fun digest(semantic: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(semantic.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
