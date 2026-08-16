package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.LegendRuleContinuation
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class ReplayFingerprintV3Test : FunSpec({

    fun decision(
        id: String,
        sourceId: EntityId? = null,
        prompt: String = "Choose whether to continue",
        effectHint: String? = null,
    ) = YesNoDecision(
        id = id,
        playerId = EntityId("p1"),
        prompt = prompt,
        context = DecisionContext(sourceId = sourceId, effectHint = effectHint),
    )

    test("v3 fingerprint is a complete 64-hex SHA-256 value") {
        val fingerprint = ReplayFingerprint.of(GameState())

        fingerprint.length shouldBe 64
        fingerprint.matches(Regex("[0-9a-f]{64}")).shouldBeTrue()
    }

    test("version dispatch preserves legacy v1/v2 semantics") {
        val state = GameState(turnNumber = 4, nextEntityId = 3L)

        ReplayFingerprint.of(state, 1) shouldBe ReplayFingerprint.of(state, 2)
        ReplayFingerprint.of(state, 1).length shouldBe 16
        ReplayFingerprint.of(state, 3) shouldNotBe ReplayFingerprint.of(state, 1)
        shouldThrow<UnsupportedReplayVersionException> {
            ReplayFingerprint.of(state, CompactReplay.CURRENT_VERSION + 1)
        }
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
        val differentSemantics = GameState(
            pendingDecision = decision("xyz", sourceId = EntityId("source"))
        )

        ReplayFingerprint.of(withAbc) shouldBe ReplayFingerprint.of(withXyz)
        ReplayFingerprint.of(withAbc) shouldNotBe ReplayFingerprint.of(differentSemantics)

        ReplayFingerprint.of(
            GameState(pendingDecision = decision("abc", prompt = "Prompt A", effectHint = "Hint A"))
        ) shouldBe ReplayFingerprint.of(
            GameState(pendingDecision = decision("xyz", prompt = "Prompt B", effectHint = "Hint B"))
        )
    }

    test("decision routing fields remain present through shared canonical aliases") {
        val state = GameState(
            pendingDecision = decision("abc"),
            continuationStack = listOf(
                LegendRuleContinuation(
                    decisionId = "abc",
                    playerId = EntityId("p1"),
                    allDuplicates = listOf(EntityId("e1")),
                )
            ),
        )

        val canonical = TransitionSemanticGameStateCanonicalizer.canonicalJson(state)

        canonical shouldContain "\"pendingDecision\""
        canonical shouldContain "\"continuationStack\""
        canonical shouldContain "\"id\":\"D0\""
        canonical shouldContain "\"decisionId\":\"D0\""
        canonical.contains("abc").shouldBeFalse()
    }
})
