package com.wingedsheep.gym.contract

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * SHA-256 over [ObservationCanonicalizer]'s perspective-safe semantic projection.
 *
 * The semantic projection includes structured legal-action payloads/fingerprints but excludes
 * transport handles (`actionId`, `decisionId`, and generated activated-ability handles),
 * presentation-only text, and the digest field itself.
 */
object StateDigest {

    fun compute(obs: TrainingObservation): String {
        val semantic = ObservationCanonicalizer.semanticJson(obs)
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(semantic.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
