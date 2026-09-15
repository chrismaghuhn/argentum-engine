package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString

class C1_07ALiveSemanticContractTest : FunSpec({

    test("ML_SEAT_09 snapshot retains the complete flat domain and source digests") {
        val source = flatObservation()
        val table = exactBindings(0, 1)

        val snapshot = LivePolicyDecisionSnapshotV1.from(source, table)
        val domain = CompleteLegalDomainV1.from(source)

        snapshot.playerObservation.observationDigest shouldBe source.stateDigest
        snapshot.observationDigest shouldBe source.stateDigest
        snapshot.candidateDomainDigest shouldBe CandidateDomainDigestV1.from(domain)
        snapshot.selectionBindingChannel.sourceBindingOrdinals shouldBe listOf(0, 1)
        snapshot.selectionBindingChannel.presentMask shouldBe listOf(true, true)
        snapshot.selectionBindingChannel.executableSupportMask shouldBe listOf(true, false)
    }

    test("ML_SEAT_10 shared model-facing projection contains no raw source identity") {
        val source = flatObservation()
        val snapshot = LivePolicyDecisionSnapshotV1.from(source, exactBindings(0, 1))
        val input = snapshot.modelInput.toString()

        input shouldNotContain "raw-source-a"
        input shouldNotContain "raw-source-b"
        input shouldNotContain "actionId"
        input shouldNotContain "decisionId"
        input shouldContain "\"sourceAlias\""
    }

    test("ML_SEAT_11 exact JVM binding table rejects missing or duplicate membership") {
        val source = flatObservation()
        val missing = LiveExactSourceBindingTable.from(
            listOf(LiveExactSourceBindingEntry(0, "exact-a")),
        ) { value -> value }

        shouldThrow<IllegalArgumentException> {
            LivePolicyDecisionSnapshotV1.from(source, missing)
        }
        shouldThrow<IllegalArgumentException> {
            LiveExactSourceBindingTable.from(
                listOf(
                    LiveExactSourceBindingEntry(0, "same-value"),
                    LiveExactSourceBindingEntry(1, "same-value"),
                ),
            ) { value -> value }
        }
        val original = exactBindings(0, 1)
        val changed = LiveExactSourceBindingTable.from(
            listOf(
                LiveExactSourceBindingEntry(0, "changed-a"),
                LiveExactSourceBindingEntry(1, "exact-1"),
            ),
        ) { value -> value }
        original.bindingDigest shouldNotBe changed.bindingDigest
        val snapshot = LivePolicyDecisionSnapshotV1.from(source, exactBindings(0, 1))
        val request = snapshot.toRequest(
            requestId = "request-1",
            policyRngState = LivePolicyRngStateV1("0".repeat(64), 0uL),
        )
        shouldThrow<IllegalArgumentException> {
            LivePolicyDecisionResponseV1(
                requestId = "request-1",
                selectedSourceBindingOrdinal = 99,
                policyRngState = request.policyRngState,
                rngCursorBefore = 0uL,
                rngCursorAfter = 0uL,
                rngDrawCount = 0uL,
            ).requireCompatible(request)
        }
        shouldThrow<IllegalArgumentException> {
            LivePolicyDecisionResponseV1(
                requestId = "request-1",
                selectedSourceBindingOrdinal = 1,
                policyRngState = request.policyRngState,
                rngCursorBefore = 0uL,
                rngCursorAfter = 0uL,
                rngDrawCount = 0uL,
            ).requireCompatible(request)
        }
    }

    test("ML_SEAT_12 candidate permutations preserve ordinal addresses") {
        val source = flatObservation(
            actions = listOf(
                action("raw-source-b", affordable = false),
                action("raw-source-a", affordable = true),
            ),
        )
        val table = LiveExactSourceBindingTable.from(
            listOf(
                LiveExactSourceBindingEntry(1, "exact-a"),
                LiveExactSourceBindingEntry(0, "exact-b"),
            ),
        ) { value -> value }

        val snapshot = LivePolicyDecisionSnapshotV1.from(source, table)

        snapshot.selectionBindingChannel.sourceBindingOrdinals shouldBe listOf(0, 1)
        table.exactBindingFor(0) shouldBe "exact-b"
        table.exactBindingFor(1) shouldBe "exact-a"
    }

    test("ML_SEAT_13 PolicyTieRng state is carried without JVM tie breaking") {
        val source = flatObservation()
        val table = exactBindings(0, 1)
        val snapshot = LivePolicyDecisionSnapshotV1.from(source, table)

        snapshot.selectionBindingChannel.semanticDigest().length shouldBe 64
        snapshot.bindingDigest shouldBe table.bindingDigest
        val requestJson = A3SemanticJson.strictJson.encodeToString(
            LivePolicyDecisionRequestV1.serializer(),
            snapshot.toRequest(
                requestId = "request-1",
                policyRngState = LivePolicyRngStateV1("0".repeat(64), 0uL),
            ),
        )
        requestJson shouldNotContain "raw-source-a"
        requestJson shouldNotContain "exact-0"
        requestJson shouldNotContain "playerObservation"
        requestJson shouldNotContain "completeLegalDomain"
        LivePolicyDecisionResponseV1(
            requestId = "request-1",
            selectedSourceBindingOrdinal = 0,
            policyRngState = LivePolicyRngStateV1("0".repeat(64), 1uL),
            rngCursorBefore = 0uL,
            rngCursorAfter = 1uL,
            rngDrawCount = 1uL,
        ).requireCompatible(
            snapshot.toRequest(
                requestId = "request-1",
                policyRngState = LivePolicyRngStateV1("0".repeat(64), 0uL),
            ),
        )
        shouldThrow<IllegalArgumentException> {
            LivePolicyDecisionResponseV1(
                requestId = "request-1",
                selectedSourceBindingOrdinal = 0,
                policyRngState = LivePolicyRngStateV1("0".repeat(64), 2uL),
                rngCursorBefore = 0uL,
                rngCursorAfter = 2uL,
                rngDrawCount = 1uL,
            ).requireCompatible(
                snapshot.toRequest(
                    requestId = "request-1",
                    policyRngState = LivePolicyRngStateV1("0".repeat(64), 0uL),
                ),
            )
        }
    }

    test("ML_SEAT_14 complete structured alternatives are explicit and injective") {
        val source = structuredObservation(
            kind = PendingDecisionKind.CHOOSE_TARGETS,
            domain = targetsDomain(),
        )
        val domain = CompleteLegalDomainV1.from(source)
        val choices = LiveStructuredChoiceDomainV1.from(
            domain = domain,
            exactSourceBindings = exactBindings(0, 1),
            completenessValidator = LiveStructuredChoiceCompletenessValidator<String> {
                _, alternatives, bindings ->
                require(alternatives.size == 2)
                require(alternatives.map { it.sourceBindingOrdinal }.toSet() ==
                    bindings.sourceBindingOrdinals
                )
            },
            alternatives = listOf(
                LiveStructuredChoiceAlternativeV1(
                    sourceBindingOrdinal = 0,
                    featureView = buildJsonObject { put("kind", "target") },
                    semanticTieDiscriminator = "{\"semantic\":\"a\"}",
                ),
                LiveStructuredChoiceAlternativeV1(
                    sourceBindingOrdinal = 1,
                    featureView = buildJsonObject { put("kind", "target") },
                    semanticTieDiscriminator = "{\"semantic\":\"b\"}",
                ),
            ),
        )
        val snapshot = LivePolicyDecisionSnapshotV1.from(
            source,
            exactBindings(0, 1),
            structuredChoices = choices,
        )

        snapshot.selectionBindingChannel.sourceBindingOrdinals shouldBe listOf(0, 1)
        snapshot.selectionBindingChannel.presentMask shouldBe listOf(true, true)
    }

    test("ML_SEAT_14 rejects a partial structured alternative set") {
        val source = structuredObservation(
            kind = PendingDecisionKind.CHOOSE_TARGETS,
            domain = targetsDomain(),
        )
        val domain = CompleteLegalDomainV1.from(source)

        shouldThrow<IllegalArgumentException> {
            LiveStructuredChoiceDomainV1.from(
                domain = domain,
                exactSourceBindings = exactBindings(0),
                completenessValidator = LiveStructuredChoiceCompletenessValidator<String> {
                    _, alternatives, _ ->
                    require(alternatives.size == 2) {
                        "target domain completeness requires both target alternatives"
                    }
                },
                alternatives = listOf(
                    LiveStructuredChoiceAlternativeV1(
                        sourceBindingOrdinal = 0,
                        featureView = buildJsonObject { put("kind", "target") },
                    ),
                ),
            )
        }
    }

    test("ML_SEAT_15 structured feature values cannot reuse raw domain identities") {
        val source = structuredObservation(
            kind = PendingDecisionKind.CHOOSE_TARGETS,
            domain = targetsDomain(),
        )
        val domain = CompleteLegalDomainV1.from(source)

        shouldThrow<IllegalArgumentException> {
            LiveStructuredChoiceDomainV1.from(
                domain = domain,
                exactSourceBindings = exactBindings(0),
                completenessValidator = LiveStructuredChoiceCompletenessValidator<String> {
                    _, _, _ -> Unit
                },
                alternatives = listOf(
                    LiveStructuredChoiceAlternativeV1(
                        sourceBindingOrdinal = 0,
                        featureView = buildJsonObject {
                            put("label", "target-a")
                        },
                    ),
                ),
            )
        }
    }

    test("ML_SEAT_15 unsupported structured input fails closed") {
        val source = structuredObservation(
            kind = PendingDecisionKind.CHOOSE_TARGETS,
            domain = targetsDomain(),
        )

        val failure = shouldThrow<UnsupportedPathFailure> {
            LivePolicyDecisionSnapshotV1.from(source, emptyExactBindings())
        }

        failure.diagnostics.map { it.code } shouldBe
            listOf(DiagnosticCode.STRUCTURED_DOMAIN_UNSUPPORTED)
    }

    test("ML_SEAT_26 a changed observation cannot reuse the old snapshot") {
        val source = flatObservation()
        val snapshot = LivePolicyDecisionSnapshotV1.from(source, exactBindings(0, 1))
        val changed = source.copy(turnNumber = source.turnNumber + 1)

        shouldThrow<IllegalArgumentException> {
            snapshot.requireCurrent(changed, exactBindings(0, 1))
        }
        shouldThrow<IllegalArgumentException> {
            snapshot.copy(
                selectionBindingChannel = snapshot.selectionBindingChannel.copy(
                    executableSupportMask = listOf(false, false),
                ),
            )
        }
    }

    test("ML_SEAT_27 AssignDamageDecision without a typed domain fails closed") {
        val source = structuredObservation(
            kind = PendingDecisionKind.ASSIGN_DAMAGE,
            domain = null,
        )

        val failure = shouldThrow<UnsupportedPathFailure> {
            LivePolicyDecisionSnapshotV1.from(source, emptyExactBindings())
        }

        failure.diagnostics.map { it.code } shouldBe
            listOf(DiagnosticCode.STRUCTURED_DECISION_DOMAIN_MISSING)
    }

    test("ML_SEAT_28 partial mana-source domain fails closed") {
        val source = structuredObservation(
            kind = PendingDecisionKind.SELECT_MANA_SOURCES,
            domain = null,
        )

        val failure = shouldThrow<UnsupportedPathFailure> {
            LivePolicyDecisionSnapshotV1.from(source, emptyExactBindings())
        }

        failure.diagnostics.map { it.code } shouldBe
            listOf(DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED)
    }

    test("ML_SEAT_29 BatchYesNoResponse is not projected as ordinary YesNo") {
        val source = flatObservation(
            pending = PendingDecisionView(
                decisionId = "batch",
                kind = PendingDecisionKind.YES_NO,
                playerId = EntityId("self"),
                prompt = "presentation",
                requiresStructuredResponse = false,
                shape = DecisionShape(),
            ),
            actions = listOf(
                action(
                    "raw-source-a",
                    affordable = true,
                    actionSemantics = buildJsonObject {
                        put("type", "BatchYesNoResponse")
                        put("choice", true)
                        put("applyToAll", true)
                    },
                    isDecisionOption = true,
                ),
            ),
        )

        val failure = shouldThrow<UnsupportedPathFailure> {
            LivePolicyDecisionSnapshotV1.from(source, exactBindings(0))
        }

        failure.diagnostics.map { it.code } shouldBe
            listOf(DiagnosticCode.STRUCTURED_DOMAIN_UNSUPPORTED)
    }
})

private fun exactBindings(vararg ordinals: Int): LiveExactSourceBindingTable<String> =
    LiveExactSourceBindingTable.from(
        ordinals.map { ordinal ->
            LiveExactSourceBindingEntry(
                sourceBindingOrdinal = ordinal,
                value = "exact-$ordinal",
            )
        },
    ) { value -> value }

private fun emptyExactBindings(): LiveExactSourceBindingTable<String> =
    LiveExactSourceBindingTable.from(emptyList()) { value -> value }

private fun flatObservation(
    pending: PendingDecisionView? = null,
    actions: List<LegalActionView> = listOf(
        action("raw-source-a", affordable = true),
        action("raw-source-b", affordable = false),
    ),
): TrainingObservation {
    val observation = TrainingObservation(
        schemaHash = SchemaHash.CURRENT,
        perspectivePlayerId = EntityId("self"),
        agentToAct = EntityId("self"),
        turnNumber = 3,
        phase = Phase.PRECOMBAT_MAIN,
        step = Step.PRECOMBAT_MAIN,
        activePlayerId = EntityId("self"),
        priorityPlayerId = EntityId("self"),
        players = listOf(
            PlayerView(
                id = EntityId("self"),
                name = "Self",
                lifeTotal = 20,
                handSize = 0,
                librarySize = 0,
                graveyardSize = 0,
                exileSize = 0,
                manaPool = ManaPoolView(),
                isPerspective = true,
                isActive = true,
                hasPriority = true,
                hasLost = false,
            ),
            PlayerView(
                id = EntityId("opponent"),
                name = "Opponent",
                lifeTotal = 20,
                handSize = 0,
                librarySize = 0,
                graveyardSize = 0,
                exileSize = 0,
                manaPool = ManaPoolView(),
                isPerspective = false,
                isActive = false,
                hasPriority = false,
                hasLost = false,
            ),
        ),
        zones = emptyList(),
        stack = emptyList(),
        pendingDecision = pending,
        legalActions = actions,
        terminated = false,
        truncated = false,
        winnerId = null,
        stateDigest = "",
    )
    return observation.copy(stateDigest = StateDigest.compute(observation))
}

private fun structuredObservation(
    kind: PendingDecisionKind,
    domain: StructuredDecisionDomain?,
): TrainingObservation {
    val pending = PendingDecisionView(
        decisionId = "structured",
        kind = kind,
        playerId = EntityId("self"),
        prompt = "presentation",
        requiresStructuredResponse = true,
        shape = DecisionShape(minSelections = 1, maxSelections = 1),
        structuredDomain = domain,
    )
    return flatObservation(pending = pending, actions = emptyList())
}

private fun targetsDomain(): TargetsDomain = TargetsDomain(
    requirements = listOf(
        TargetRequirementDomain(
            index = 0,
            description = "target",
            minTargets = 1,
            maxTargets = 1,
            candidates = listOf(EntityId("target-a"), EntityId("target-b")),
            targetZone = null,
            mustDifferFromEarlier = false,
            sameController = false,
            sameOwner = false,
            sameCreatureType = false,
            sameCardType = false,
            totalManaValueAtMost = null,
            differentNames = false,
            xConstrainsManaValue = false,
            xConstrainsManaValueExactly = false,
            xConstrainsPower = false,
            xConstrainsCount = false,
        ),
    ),
    canCancel = false,
)

private fun action(
    source: String,
    affordable: Boolean,
    actionSemantics: kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("type", "PlayLand")
    },
    isDecisionOption: Boolean = false,
): LegalActionView = LegalActionView(
    actionId = source.hashCode(),
    kind = "PlayLand",
    description = "presentation",
    affordable = affordable,
    sourceEntityId = EntityId(source),
    actionSemantics = actionSemantics,
    isDecisionOption = isDecisionOption,
)
