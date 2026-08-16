package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.BatchYesNoDecision
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.LegendRuleContinuation
import com.wingedsheep.engine.core.ModeOption
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.PlayerYields
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.gameserver.persistence.persistenceJson
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.AbilityIdentity
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
import kotlinx.serialization.json.jsonObject

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

    test("v3 fingerprint includes semantic yields and canonicalizes unordered sets") {
        val player = EntityId("p1")
        val identity = AbilityIdentity("Test Card#TST-1", AbilityId("test-ability"))
        val secondIdentity = AbilityIdentity("Second Card#TST-2", AbilityId("second-ability"))
        val yielded = GameState(
            priorityPassedBy = linkedSetOf(EntityId("p2"), EntityId("p1")),
            yieldsByPlayer = mapOf(
                player to PlayerYields(
                    untilEndOfTurn = linkedSetOf(identity, secondIdentity),
                    wholeGame = linkedSetOf(secondIdentity, identity),
                    autoAnswer = mapOf(identity to true),
                ),
            ),
        )
        val reordered = yielded.copy(
            priorityPassedBy = linkedSetOf(EntityId("p1"), EntityId("p2")),
            yieldsByPlayer = linkedMapOf(
                player to PlayerYields(
                    untilEndOfTurn = linkedSetOf(secondIdentity, identity),
                    wholeGame = linkedSetOf(identity, secondIdentity),
                    autoAnswer = mapOf(identity to true),
                ),
            ),
        )

        ReplayFingerprint.of(reordered, 3) shouldBe ReplayFingerprint.of(yielded, 3)
        ReplayFingerprint.of(
            yielded.copy(yieldsByPlayer = mapOf(player to PlayerYields(autoAnswer = mapOf(identity to false))))
        ) shouldNotBe ReplayFingerprint.of(yielded, 3)
    }

    test("v3 fingerprint canonicalizes structured map iteration order") {
        val p1Library = ZoneKey(EntityId("p1"), Zone.LIBRARY)
        val p2Library = ZoneKey(EntityId("p2"), Zone.LIBRARY)
        val first = GameState(
            zones = linkedMapOf(
                p1Library to listOf(EntityId("p1-card")),
                p2Library to listOf(EntityId("p2-card")),
            ),
        )
        val reordered = first.copy(
            zones = linkedMapOf(
                p2Library to listOf(EntityId("p2-card")),
                p1Library to listOf(EntityId("p1-card")),
            ),
        )

        ReplayFingerprint.of(first, 3) shouldBe ReplayFingerprint.of(reordered, 3)
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

    test("v3 excludes audited presentation-only decision labels") {
        val yesNoA = GameState(
            pendingDecision = YesNoDecision(
                id = "yes-a",
                playerId = EntityId("p1"),
                prompt = "same semantic question",
                context = DecisionContext(),
                yesText = "Accept the offer",
                noText = "Decline the offer",
                hint = "A very specific UI hint",
            ),
        )
        val yesNoB = yesNoA.copy(
            pendingDecision = (yesNoA.pendingDecision as YesNoDecision).copy(
                id = "yes-b",
                yesText = "Do it",
                noText = "Do not do it",
                hint = "A different UI hint",
            ),
        )
        ReplayFingerprint.of(yesNoA, 3) shouldBe ReplayFingerprint.of(yesNoB, 3)

        val batchA = GameState(
            pendingDecision = BatchYesNoDecision(
                id = "batch-a",
                playerId = EntityId("p1"),
                prompt = "same batch question",
                context = DecisionContext(),
                count = 2,
                yesText = "Accept all",
                noText = "Decline all",
            ),
        )
        val batchB = batchA.copy(
            pendingDecision = (batchA.pendingDecision as BatchYesNoDecision).copy(
                id = "batch-b",
                yesText = "All yes",
                noText = "All no",
            ),
        )
        ReplayFingerprint.of(batchA, 3) shouldBe ReplayFingerprint.of(batchB, 3)

        val modesA = GameState(
            pendingDecision = ChooseModeDecision(
                id = "modes-a",
                playerId = EntityId("p1"),
                prompt = "same mode question",
                context = DecisionContext(),
                modes = listOf(ModeOption(index = 0, text = "Draw two cards", available = true)),
            ),
        )
        val modesB = modesA.copy(
            pendingDecision = (modesA.pendingDecision as ChooseModeDecision).copy(
                id = "modes-b",
                modes = listOf(ModeOption(index = 0, text = "A completely different label", available = true)),
            ),
        )
        ReplayFingerprint.of(modesA, 3) shouldBe ReplayFingerprint.of(modesB, 3)
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

    test("canonical inventory covers every serialized GameState constructor field") {
        val canonical = persistenceJson.parseToJsonElement(
            TransitionSemanticGameStateCanonicalizer.canonicalJson(GameState())
        ).jsonObject
        val descriptor = GameState.serializer().descriptor

        (0 until descriptor.elementsCount).forEach { index ->
            canonical.containsKey(descriptor.getElementName(index)).shouldBeTrue()
        }
        canonical.containsKey("projectedState").shouldBeFalse()
    }
})
