package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ReplayFingerprintV3Test : FunSpec({

    fun decision(id: String, yesText: String = "Yes") = YesNoDecision(
        id = id,
        playerId = EntityId("p1"),
        prompt = "Choose whether to continue",
        context = DecisionContext(),
        yesText = yesText,
    )

    test("v3 fingerprint is a complete 64-hex SHA-256 value") {
        val fingerprint = ReplayFingerprint.of(GameState())

        fingerprint.length shouldBe 64
        fingerprint.matches(Regex("[0-9a-f]{64}")).shouldBeTrue()
    }

    test("v3 fingerprint includes RNG, next entity id, and ordered library state") {
        val library = ZoneKey(EntityId("p1"), Zone.LIBRARY)
        val base = GameState(
            zones = mapOf(library to listOf(EntityId("e1"), EntityId("e2"))),
            rng = GameRng(7L),
            nextEntityId = 2L,
        )

        ReplayFingerprint.of(base.copy(rng = GameRng(8L))) shouldNotBe ReplayFingerprint.of(base)
        ReplayFingerprint.of(base.copy(nextEntityId = 3L)) shouldNotBe ReplayFingerprint.of(base)
        ReplayFingerprint.of(
            base.copy(zones = mapOf(library to listOf(EntityId("e2"), EntityId("e1"))))
        ) shouldNotBe ReplayFingerprint.of(base)
    }

    test("nonce changes are ignored but decision semantics remain fingerprinted") {
        val withAbc = GameState(pendingDecision = decision("abc"))
        val withXyz = GameState(pendingDecision = decision("xyz"))
        val differentSemantics = GameState(pendingDecision = decision("xyz", yesText = "Resolve"))

        ReplayFingerprint.of(withAbc) shouldBe ReplayFingerprint.of(withXyz)
        ReplayFingerprint.of(withAbc) shouldNotBe ReplayFingerprint.of(differentSemantics)
    }
})
