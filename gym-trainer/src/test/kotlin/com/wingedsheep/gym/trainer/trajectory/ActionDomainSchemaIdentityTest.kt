package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.gym.contract.COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ActionDomainSchemaIdentityTest : FunSpec({

    fun environmentIdentity(
        actionDomainSchemaIdentity: String = COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY,
    ): EnvironmentIdentityV1 = EnvironmentIdentityV1(
        engineCommit = "a".repeat(40),
        cardDefinitionIdentity = "cards@v1",
        akiriDeckIdentity = "akiri@v1",
        chevillDeckIdentity = "chevill@v1",
        format = "COMMANDER",
        attackMode = "MULTIPLE",
        startingHandSize = 7,
        skipMulligans = true,
        useHandSmoother = false,
        roster = listOf(
            RosterSeatV1(
                seatIndex = 0,
                playerId = EntityId("p0"),
                role = "AKIRI",
                deckIdentity = "akiri@v1",
            ),
        ),
        startingPlayer = EntityId("p0"),
        actualEngineSeed = 0L,
        actionDomainSchemaIdentity = actionDomainSchemaIdentity,
    )

    test("environment and dataset metadata default to action-domain v2") {
        environmentIdentity().actionDomainSchemaIdentity shouldBe
            "argentum-gym-action-domain@v2"
        DatasetMetadataV1().completeLegalDomainSchemaIdentity shouldBe
            "argentum-gym-action-domain@v2"
    }

    test("legacy action-domain v1 identity is rejected by A5 metadata") {
        shouldThrow<IllegalArgumentException> {
            environmentIdentity("argentum-gym-action-domain@v1")
        }
        shouldThrow<IllegalArgumentException> {
            DatasetMetadataV1(completeLegalDomainSchemaIdentity = "argentum-gym-action-domain@v1")
        }
    }
})
