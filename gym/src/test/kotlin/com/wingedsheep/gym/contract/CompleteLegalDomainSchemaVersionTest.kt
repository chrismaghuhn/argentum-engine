package com.wingedsheep.gym.contract

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CompleteLegalDomainSchemaVersionTest : FunSpec({

    fun domain(
        version: Int = COMPLETE_LEGAL_DOMAIN_VERSION,
        schemaIdentity: String = COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY,
    ): CompleteLegalDomainV1 = CompleteLegalDomainV1(
        version = version,
        schemaIdentity = schemaIdentity,
        kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
    )

    test("the repeat-count candidate contract uses action-domain v2") {
        val current = domain()

        current.version shouldBe 2
        current.schemaIdentity shouldBe "argentum-gym-action-domain@v2"
        CandidateDomainDigestV1.from(current).version shouldBe CANDIDATE_DOMAIN_DIGEST_VERSION
        CandidateDomainDigestV1.from(current).schemaIdentity shouldBe
            CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY
    }

    test("unsupported action-domain version and identity pairs fail closed") {
        listOf(
            1 to "argentum-gym-action-domain@v1",
            2 to "argentum-gym-action-domain@v1",
            1 to "argentum-gym-action-domain@v2",
            3 to "argentum-gym-action-domain@v2",
        ).forEach { (version, schemaIdentity) ->
            shouldThrow<IllegalArgumentException> {
                domain(version = version, schemaIdentity = schemaIdentity)
            }
        }
    }
})
