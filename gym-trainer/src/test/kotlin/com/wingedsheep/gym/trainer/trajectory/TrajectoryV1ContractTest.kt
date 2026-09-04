package com.wingedsheep.gym.trainer.trajectory

import com.wingedsheep.engine.core.AtomicManaCostUnitV1
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameEndReason
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.InitialPoolBucketV1
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentCostKindV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.contract.CandidateDomainDigestV1
import com.wingedsheep.gym.contract.AttackBandConstraintsV1
import com.wingedsheep.gym.contract.AttackDeclarationDomainV2
import com.wingedsheep.gym.contract.BlockerDeclarationDomainV1
import com.wingedsheep.gym.contract.CardSelectionDomain
import com.wingedsheep.gym.contract.CombatAttackerDomain
import com.wingedsheep.gym.contract.CombatDamageDirection
import com.wingedsheep.gym.contract.CombatDamageEdgeDomain
import com.wingedsheep.gym.contract.CombatDefenderDomain
import com.wingedsheep.gym.contract.CombatResolutionDomain
import com.wingedsheep.gym.contract.CombatTargetKind
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.DecisionShape
import com.wingedsheep.gym.contract.DistributionDomain
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ModeOptionDomain
import com.wingedsheep.gym.contract.ModeSelectionDomain
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.OptionMetadataDomain
import com.wingedsheep.gym.contract.OrderingDomain
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.PendingDecisionView
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.gym.contract.ReplacementDomain
import com.wingedsheep.gym.contract.StructuredDecisionDomain
import com.wingedsheep.gym.contract.StructuredCardInfo
import com.wingedsheep.gym.contract.TargetRequirementDomain
import com.wingedsheep.gym.contract.TargetsDomain
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private data class DecisionFixture(
    val observation: PlayerObservationV1,
    val domain: CompleteLegalDomainV1,
    val chosenAction: ChosenSemanticActionV1? = null,
    val chosenResponse: ChosenSemanticResponseV1? = null,
) {
    init {
        require((chosenAction == null) != (chosenResponse == null))
    }
}

class TrajectoryV1ContractTest : FunSpec({

    fun registry(): CardRegistry = CardRegistry().also {
        it.register(PortalSet.cards)
        it.register(PortalSet.basicLands)
    }

    fun sourceObservation(environment: GameEnvironment): TrainingObservation =
        ObservationBuilder(cardRegistry = registry()).build(
            state = environment.state,
            perspectivePlayerId = environment.playerIds.first(),
            legalActions = environment.legalActions(),
        ).observation as TrainingObservation

    fun environment(): GameEnvironment {
        val environment = GameEnvironment.create(registry())
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 2,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 70L,
            )
        )
        return environment
    }

    fun defaultPolicy(): PolicyProvenanceV1 = PolicyProvenanceV1(
        behaviorPolicyIdentity = "b2-test-behavior@v1",
        opponentPolicyIdentity = "b2-test-opponent@v1",
        behaviorPolicyRole = "EXTERNAL_CONTROLLER",
        opponentPolicyRole = "EXTERNAL_CONTROLLER",
        policyRngIdentity = "explicit-seed/kotlin-policy-state-v1",
        policySeed = 4259905L,
        policySourceIdentity = "b".repeat(64),
    )

    fun validMetadata(
        replayActionCount: Int = 1,
        closure: EpisodeClosureV1? = null,
        policy: PolicyProvenanceV1? = null,
    ): EpisodeMetadataV1 {
        val policyValue = policy ?: defaultPolicy()
        val environment = EnvironmentIdentityV1(
            environmentIdentity = "argentum-b2-test-environment-v1",
            engineCommit = "d7a81325783e8bdc5c91c4b24d42fd5f8f9f3a98",
            cardDefinitionIdentity = "portal-card-definitions-v1",
            akiriDeckIdentity = "akiri-deck-test",
            chevillDeckIdentity = "chevill-deck-test",
            format = "COMMANDER",
            attackMode = "MULTIPLE",
            startingHandSize = 2,
            skipMulligans = true,
            useHandSmoother = false,
            roster = listOf(
                RosterSeatV1(
                    seatIndex = 0,
                    playerId = EntityId("e0"),
                    role = "AKIRI",
                    deckIdentity = "akiri-deck-test",
                    commanderDefinitionIdentity = "Akiri",
                ),
                RosterSeatV1(
                    seatIndex = 1,
                    playerId = EntityId("e1"),
                    role = "CHEVILL",
                    deckIdentity = "chevill-deck-test",
                    commanderDefinitionIdentity = "Chevill",
                ),
            ),
            startingPlayer = EntityId("e0"),
            actualEngineSeed = 70L,
        )
        val replayLink = CompactReplayLinkV1(
            replayContentIdentity = "c".repeat(64),
            replayActionCount = replayActionCount,
        )
        val metadataWithoutIds = EpisodeMetadataV1(
            semanticEpisodeId = "a".repeat(64),
            collectionJobId = "b".repeat(64),
            environmentIdentity = environment,
            policyProvenance = policyValue,
            compactReplayLink = replayLink,
            closure = closure ?: EpisodeClosureV1.GameTerminal(
                stepCount = replayActionCount,
                winnerId = EntityId("e0"),
                reason = GameEndReason.LIFE_ZERO,
            ),
        )
        return metadataWithoutIds.copy(
            semanticEpisodeId = metadataWithoutIds.recomputeSemanticEpisodeId(),
        ).let { withSemanticId ->
            withSemanticId.copy(collectionJobId = withSemanticId.recomputeCollectionJobId())
        }
    }

    fun actionFixture(): DecisionFixture {
        val environment = environment()
        val source = sourceObservation(environment)
        val observation = PlayerObservationV1.from(source)
        val domain = CompleteLegalDomainV1.from(source)
        val chosen = ChosenSemanticActionV1.from(
            domain = domain,
            candidate = domain.candidates.first { it["affordable"] == JsonPrimitive(true) },
        )
        return DecisionFixture(
            observation = observation,
            domain = domain,
            chosenAction = chosen,
        )
    }

    fun foldedFixture(decisionId: String = "runtime-decision-id"): DecisionFixture {
        val environment = environment()
        val source = sourceObservation(environment)
        val player = source.perspectivePlayerId
        val pending = PendingDecisionView(
            decisionId = decisionId,
            kind = PendingDecisionKind.YES_NO,
            playerId = player,
            prompt = "presentation",
            requiresStructuredResponse = false,
            shape = DecisionShape(),
        )
        val foldedSource = source.copy(
            pendingDecision = pending,
            legalActions = listOf(
                LegalActionView(
                    actionId = 0,
                    kind = "DECISION",
                    description = "Yes",
                    affordable = true,
                    actionSemantics = buildJsonObject {
                        put("type", "YesNoResponse")
                        put("choice", true)
                    },
                    isDecisionOption = true,
                ),
                LegalActionView(
                    actionId = 1,
                    kind = "DECISION",
                    description = "No",
                    affordable = true,
                    actionSemantics = buildJsonObject {
                        put("type", "YesNoResponse")
                        put("choice", false)
                    },
                    isDecisionOption = true,
                ),
            ),
        )
        val observation = PlayerObservationV1.from(foldedSource)
        val domain = CompleteLegalDomainV1.from(foldedSource)
        return DecisionFixture(
            observation = observation,
            domain = domain,
            chosenResponse = ChosenSemanticResponseV1.from(
                domain,
                YesNoResponse(decisionId, choice = true),
            ),
        )
    }

    fun targetsFixture(): DecisionFixture {
        val environment = environment()
        val source = sourceObservation(environment)
        val player = source.perspectivePlayerId
        val target = EntityId("target-a")
        val structuredDomain = TargetsDomain(
            requirements = listOf(
                TargetRequirementDomain(
                    index = 0,
                    description = "target",
                    minTargets = 1,
                    maxTargets = 1,
                    candidates = listOf(target),
                    targetZone = null,
                    mustDifferFromEarlier = false,
                    sameController = false,
                    sameOwner = false,
                    sameCreatureType = false,
                    sameCardType = false,
                    totalManaValueAtMost = null,
                    differentNames = false,
                    xConstrainsManaValue = false,
                    xConstrainsPower = false,
                    xConstrainsCount = false,
                    xConstrainsManaValueExactly = false,
                ),
            ),
            canCancel = false,
        )
        val structuredSource = source.copy(
            pendingDecision = PendingDecisionView(
                decisionId = "runtime-target-decision",
                kind = PendingDecisionKind.CHOOSE_TARGETS,
                playerId = player,
                prompt = "presentation",
                requiresStructuredResponse = true,
                shape = DecisionShape(),
                structuredDomain = structuredDomain,
            ),
            legalActions = emptyList(),
        )
        val observation = PlayerObservationV1.from(structuredSource)
        val domain = CompleteLegalDomainV1.from(structuredSource)
        return DecisionFixture(
            observation = observation,
            domain = domain,
            chosenResponse = ChosenSemanticResponseV1.from(
                domain,
                TargetsResponse("runtime-target-decision", mapOf(0 to listOf(target))),
            ),
        )
    }

    fun modeFixture(): DecisionFixture {
        val environment = environment()
        val source = sourceObservation(environment)
        val player = source.perspectivePlayerId
        val structuredSource = source.copy(
            pendingDecision = PendingDecisionView(
                decisionId = "runtime-mode-decision",
                kind = PendingDecisionKind.CHOOSE_MODE,
                playerId = player,
                prompt = "presentation",
                requiresStructuredResponse = true,
                shape = DecisionShape(minSelections = 1, maxSelections = 1),
                structuredDomain = ModeSelectionDomain(
                    modes = listOf(
                        ModeOptionDomain(index = 0, text = "mode", available = true),
                        ModeOptionDomain(index = 1, text = "other", available = false),
                    ),
                    minModes = 1,
                    maxModes = 1,
                ),
            ),
            legalActions = emptyList(),
        )
        val observation = PlayerObservationV1.from(structuredSource)
        val domain = CompleteLegalDomainV1.from(structuredSource)
        return DecisionFixture(
            observation = observation,
            domain = domain,
            chosenResponse = ChosenSemanticResponseV1.from(
                domain,
                ModesChosenResponse("runtime-mode-decision", listOf(0)),
            ),
        )
    }

    fun cardSelectionFixture(): DecisionFixture {
        val environment = environment()
        val source = sourceObservation(environment)
        val player = source.perspectivePlayerId
        val a = EntityId("card-a")
        val b = EntityId("card-b")
        val structuredSource = source.copy(
            pendingDecision = PendingDecisionView(
                decisionId = "runtime-card-decision",
                kind = PendingDecisionKind.SELECT_CARDS,
                playerId = player,
                prompt = "presentation",
                requiresStructuredResponse = true,
                shape = DecisionShape(minSelections = 1, maxSelections = 1),
                structuredDomain = CardSelectionDomain(
                    options = listOf(a, b),
                    minSelections = 1,
                    maxSelections = 1,
                    ordered = false,
                    cardInfo = null,
                    useTargetingUI = false,
                    selectedLabel = null,
                    remainderLabel = null,
                    nonSelectableOptions = emptyList(),
                    onePerCardType = false,
                    onePerColor = false,
                    availableColors = null,
                    onePerCardName = false,
                    onePerBasicLandType = false,
                    onePerPower = false,
                    maxTotalManaValue = null,
                    minTotalManaValue = null,
                    maxTotalPower = null,
                    conditionalMinimums = emptyList(),
                ),
            ),
            legalActions = emptyList(),
        )
        val observation = PlayerObservationV1.from(structuredSource)
        val domain = CompleteLegalDomainV1.from(structuredSource)
        return DecisionFixture(
            observation = observation,
            domain = domain,
            chosenResponse = ChosenSemanticResponseV1.from(
                domain,
                com.wingedsheep.engine.core.CardsSelectedResponse("runtime-card-decision", listOf(a)),
            ),
        )
    }

    fun orderingFixture(): DecisionFixture {
        val environment = environment()
        val source = sourceObservation(environment)
        val player = source.perspectivePlayerId
        val a = EntityId("order-a")
        val b = EntityId("order-b")
        val structuredSource = source.copy(
            pendingDecision = PendingDecisionView(
                decisionId = "runtime-order-decision",
                kind = PendingDecisionKind.ORDER_OBJECTS,
                playerId = player,
                prompt = "presentation",
                requiresStructuredResponse = true,
                shape = DecisionShape(),
                structuredDomain = com.wingedsheep.gym.contract.OrderingDomain(
                    objects = listOf(a, b),
                    cardInfo = emptyMap(),
                    objectLabels = emptyMap(),
                ),
            ),
            legalActions = emptyList(),
        )
        val observation = PlayerObservationV1.from(structuredSource)
        val domain = CompleteLegalDomainV1.from(structuredSource)
        return DecisionFixture(
            observation = observation,
            domain = domain,
            chosenResponse = ChosenSemanticResponseV1.from(
                domain,
                com.wingedsheep.engine.core.OrderedResponse("runtime-order-decision", listOf(a, b)),
            ),
        )
    }

    fun structuredFixture(
        kind: PendingDecisionKind,
        structuredDomain: StructuredDecisionDomain,
        response: (CompleteLegalDomainV1) -> ChosenSemanticResponseV1,
    ): DecisionFixture {
        val environment = environment()
        val source = sourceObservation(environment)
        val structuredSource = source.copy(
            pendingDecision = PendingDecisionView(
                decisionId = "runtime-${kind.name.lowercase()}",
                kind = kind,
                playerId = source.perspectivePlayerId,
                prompt = "presentation",
                requiresStructuredResponse = true,
                shape = DecisionShape(),
                structuredDomain = structuredDomain,
            ),
            legalActions = emptyList(),
        )
        val observation = PlayerObservationV1.from(structuredSource)
        val domain = CompleteLegalDomainV1.from(structuredSource)
        return DecisionFixture(observation = observation, domain = domain, chosenResponse = response(domain))
    }

    fun candidateWithRequiredPayload(
        sourceDomain: CompleteLegalDomainV1,
        fields: List<String>,
        updates: Map<String, JsonElement> = emptyMap(),
    ): JsonObject {
        fun replaceCandidateValue(value: JsonObject, key: String, replacement: JsonElement): JsonObject =
            buildJsonObject {
                value.forEach { (existingKey, existingValue) ->
                    put(existingKey, if (existingKey == key) replacement else existingValue)
                }
                if (key !in value) put(key, replacement)
            }

        var candidate = replaceCandidateValue(
            sourceDomain.candidates.first { it["affordable"] == JsonPrimitive(true) },
            "requiredPayloadFields",
            JsonArray(fields.map(::JsonPrimitive)),
        )
        candidate = replaceCandidateValue(candidate, "requiresStructuredAction", JsonPrimitive(true))
        updates.forEach { (key, value) -> candidate = replaceCandidateValue(candidate, key, value) }
        return candidate
    }

    fun actionPayloadFixture(
        requiredFields: List<String>,
        updates: Map<String, JsonElement> = emptyMap(),
        choicePayload: JsonObject,
    ): DecisionFixture {
        val environment = environment()
        val source = sourceObservation(environment)
        val observation = PlayerObservationV1.from(source)
        val sourceDomain = CompleteLegalDomainV1.from(source)
        val candidate = candidateWithRequiredPayload(sourceDomain, requiredFields, updates)
        val domain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
            candidates = listOf(candidate),
        )
        return DecisionFixture(
            observation = observation,
            domain = domain,
            chosenAction = ChosenSemanticActionV1.from(domain, candidate, choicePayload),
        )
    }

    fun paymentDomain(): com.wingedsheep.gym.contract.PaymentDomainV5 {
        val redBucket = InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.RED)
        return com.wingedsheep.gym.contract.PaymentDomainV5(
            requiredCost = "{R}",
            outerAtomicCostUnits = listOf(
                AtomicManaCostUnitV1(
                    symbolIndex = 0,
                    unitIndexWithinSymbol = 0,
                    kind = PaymentCostKindV1.COLORED,
                    allowedColors = setOf(PaymentManaColor.RED),
                ),
            ),
            initialPoolBuckets = listOf(InitialPoolBucketV1(redBucket, availableAmount = 1)),
            sourceActivationOptions = emptyList(),
        )
    }

    fun paymentPayload(): JsonObject {
        val redBucket = InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.RED)
        val plan = PaymentPlanV3(
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = com.wingedsheep.engine.core.PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.InitialPoolResource(redBucket),
                ),
            ),
        )
        return buildJsonObject {
            put(
                "paymentStrategy",
                A3SemanticJson.strictJson.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    PaymentStrategy.ExplicitV3(plan),
                ),
            )
        }
    }

    fun attackDomain(): AttackDeclarationDomainV2 = AttackDeclarationDomainV2(
        attackerOrder = listOf(EntityId("attacker-a"), EntityId("attacker-b")),
        attackerToDefenders = mapOf(
            EntityId("attacker-a") to listOf(EntityId("defender")),
            EntityId("attacker-b") to listOf(EntityId("defender")),
        ),
        mandatoryAttackers = emptyList(),
        canDeclareZeroAttackers = true,
        maxAttackers = 2,
        coAttackerRequirements = emptyMap(),
        bandConstraints = AttackBandConstraintsV1(
            bandingAttackersByDefender = mapOf(
                EntityId("defender") to listOf(EntityId("attacker-a"), EntityId("attacker-b")),
            ),
            nonBandingAttackersByDefender = emptyMap(),
        ),
    )

    fun blockerDomain(): BlockerDeclarationDomainV1 = BlockerDeclarationDomainV1(
        blockerOrder = listOf(EntityId("blocker-a")),
        attackerOrder = listOf(EntityId("attacker-a")),
        blockerToAttackers = mapOf(EntityId("blocker-a") to listOf(EntityId("attacker-a"))),
        maxAttackersByBlocker = mapOf(EntityId("blocker-a") to 1),
        minBlockersByAttacker = emptyMap(),
        maxBlockersByAttacker = emptyMap(),
        globalMaxBlockers = null,
        coBlockerRequirements = emptyMap(),
        requirements = emptyList(),
        minimumSatisfiedRequirementCount = 0,
        canDeclareZeroBlockers = true,
    )

    fun combatFixture(): DecisionFixture {
        val attacker = EntityId("combat-attacker")
        val defender = EntityId("combat-defender")
        return structuredFixture(
            PendingDecisionKind.COMBAT_RESOLUTION,
            CombatResolutionDomain(
                firstStrike = false,
                attackers = listOf(
                    CombatAttackerDomain(
                        id = attacker,
                        name = "attacker",
                        power = 2,
                        toughness = 2,
                        hasTrample = false,
                        hasDeathtouch = false,
                        hasFirstStrike = false,
                        hasDoubleStrike = false,
                        dealsDamageThisStep = true,
                        bandId = null,
                        attackedDefenderId = defender,
                        blockedByIds = emptyList(),
                        markedDamage = 0,
                    ),
                ),
                blockers = emptyList(),
                defenders = listOf(
                    CombatDefenderDomain(
                        id = defender,
                        kind = CombatTargetKind.PLAYER,
                        name = "defender",
                        lifeOrLoyaltyOrDefense = 20,
                    ),
                ),
                edges = listOf(
                    CombatDamageEdgeDomain(
                        id = "combat-edge",
                        sourceId = attacker,
                        targetId = defender,
                        direction = CombatDamageDirection.ATTACKER_TO_PLAYER,
                        amount = 2,
                        maximum = 2,
                        lethal = 0,
                        isTrampleDrain = false,
                        editableBy = EntityId("e0"),
                    ),
                ),
                coChooserId = null,
            ),
        ) { domain ->
            ChosenSemanticResponseV1.from(
                domain,
                CombatResolutionResponse(
                    decisionId = "runtime-combat-resolution",
                    edges = listOf(DamageEdgeAmount("combat-edge", 2)),
                ),
            )
        }
    }

    fun familyFixtures(): List<DecisionFixture> = listOf(
        actionPayloadFixture(
            requiredFields = listOf("xValue"),
            updates = mapOf("hasXCost" to JsonPrimitive(true), "maxAffordableX" to JsonPrimitive(3)),
            choicePayload = buildJsonObject { put("xValue", 2) },
        ),
        actionPayloadFixture(
            requiredFields = listOf("paymentStrategy"),
            updates = mapOf(
                "paymentDomain" to A3SemanticJson.strictJson.encodeToJsonElement(
                    com.wingedsheep.gym.contract.PaymentDomainV5.serializer(),
                    paymentDomain(),
                ),
            ),
            choicePayload = paymentPayload(),
        ),
        actionPayloadFixture(
            requiredFields = listOf("attackers", "bands"),
            updates = mapOf(
                "actionSemantics" to buildJsonObject { put("type", "DeclareAttackers") },
                "attackDeclarationDomain" to A3SemanticJson.strictJson.encodeToJsonElement(
                    AttackDeclarationDomainV2.serializer(),
                    attackDomain(),
                ),
            ),
            choicePayload = buildJsonObject {
                put("attackers", buildJsonObject {
                    put("attacker-a", "defender")
                    put("attacker-b", "defender")
                })
                put("bands", JsonArray(listOf(JsonArray(listOf(JsonPrimitive("attacker-a"), JsonPrimitive("attacker-b"))))))
            },
        ),
        actionPayloadFixture(
            requiredFields = listOf("blockers"),
            updates = mapOf(
                "actionSemantics" to buildJsonObject { put("type", "DeclareBlockers") },
                "blockerDeclarationDomain" to A3SemanticJson.strictJson.encodeToJsonElement(
                    BlockerDeclarationDomainV1.serializer(),
                    blockerDomain(),
                ),
            ),
            choicePayload = buildJsonObject {
                put("blockers", buildJsonObject {
                    put("blocker-a", JsonArray(listOf(JsonPrimitive("attacker-a"))))
                })
            },
        ),
        foldedFixture(),
        targetsFixture(),
        modeFixture(),
        cardSelectionFixture(),
        orderingFixture(),
        structuredFixture(
            PendingDecisionKind.DISTRIBUTE,
            DistributionDomain(
                totalAmount = 2,
                targets = listOf(EntityId("distribution-target")),
                minPerTarget = 0,
                maxPerTarget = mapOf(EntityId("distribution-target") to 2),
                allowPartial = false,
            ),
        ) { domain ->
            ChosenSemanticResponseV1.from(
                domain,
                com.wingedsheep.engine.core.DistributionResponse(
                    decisionId = "runtime-distribute",
                    distribution = mapOf(EntityId("distribution-target") to 2),
                ),
            )
        },
        structuredFixture(
            PendingDecisionKind.CHOOSE_REPLACEMENT,
            ReplacementDomain(
                fromOptions = listOf("from-a"),
                toOptions = listOf("to-a"),
                fromMetadata = listOf(OptionMetadataDomain("from-a", "from", null, null)),
                toMetadata = listOf(OptionMetadataDomain("to-a", "to", null, null)),
                allowedToByFrom = listOf(listOf(0)),
                defaultFromIndex = 0,
            ),
        ) { domain ->
            ChosenSemanticResponseV1.from(
                domain,
                ReplacementChosenResponse("runtime-replacement", fromIndex = 0, toIndex = 0),
            )
        },
        combatFixture(),
    )

    fun trajectoryOf(
        fixtures: List<DecisionFixture>,
        closure: EpisodeClosureV1 = EpisodeClosureV1.GameTerminal(
            stepCount = fixtures.size,
            winnerId = EntityId("e0"),
            reason = GameEndReason.LIFE_ZERO,
        ),
        policy: PolicyProvenanceV1? = null,
    ): TrajectoryV1 {
        val metadata = validMetadata(fixtures.size, closure, policy)
        val prefixAccumulator = SemanticReplayPrefixAccumulatorV1()
        val records = mutableListOf<DecisionRecordV1>()
        fixtures.forEachIndexed { index, fixture ->
            val identity = prefixAccumulator.semanticDecisionIdentity(
                semanticEpisodeId = metadata.semanticEpisodeId,
                replayActionIndex = index,
                observation = fixture.observation,
                domain = fixture.domain,
            )
            records += DecisionRecordV1(
                decisionIndex = index,
                replayActionIndex = index,
                perspectivePlayerId = fixture.observation.perspectivePlayerId,
                decisionKind = identity.decisionKind,
                semanticDecisionId = identity.semanticDecisionId(),
                observationBefore = fixture.observation,
                completeLegalDomain = fixture.domain,
                candidateDomainDigest = CandidateDomainDigestV1.from(fixture.domain),
                chosenSemanticAction = fixture.chosenAction,
                chosenSemanticResponse = fixture.chosenResponse,
            )
            prefixAccumulator.append(
                fixture.chosenAction?.let(SemanticReplayInputV1::action)
                    ?: SemanticReplayInputV1.response(requireNotNull(fixture.chosenResponse)),
            )
        }
        val withoutTrajectoryId = TrajectoryV1(
            trajectoryId = "d".repeat(64),
            episodeMetadata = metadata,
            decisions = records,
        )
        return withoutTrajectoryId.copy(trajectoryId = withoutTrajectoryId.recomputeTrajectoryId())
    }

    fun validTrajectory(): TrajectoryV1 = trajectoryOf(listOf(actionFixture()))

    fun replaceJsonValue(objectValue: JsonObject, key: String, value: JsonElement): JsonObject =
        buildJsonObject {
            objectValue.forEach { (existingKey, existingValue) ->
                put(existingKey, if (existingKey == key) value else existingValue)
            }
            if (key !in objectValue) put(key, value)
        }

    fun removeJsonValue(objectValue: JsonObject, key: String): JsonObject = buildJsonObject {
        objectValue.forEach { (existingKey, existingValue) ->
            if (existingKey != key) put(existingKey, existingValue)
        }
    }

    fun rootJson(trajectory: TrajectoryV1): JsonObject =
        A3SemanticJson.strictJson.parseToJsonElement(TrajectoryV1Json.encode(trajectory)).jsonObject

    fun encodeRoot(root: JsonObject): String = root.toString()

    fun updateNested(
        root: JsonObject,
        rootKey: String,
        nestedKey: String,
        value: JsonElement,
    ): JsonObject = replaceJsonValue(
        root,
        rootKey,
        replaceJsonValue(root.getValue(rootKey).jsonObject, nestedKey, value),
    )

    fun updateDecision(
        root: JsonObject,
        decisionIndex: Int,
        update: (JsonObject) -> JsonObject,
    ): JsonObject = replaceJsonValue(
        root,
        "decisions",
        JsonArray(root.getValue("decisions").jsonArray.mapIndexed { index, decision ->
            if (index == decisionIndex) update(decision.jsonObject) else decision
        }),
    )

    fun reidentified(
        trajectory: TrajectoryV1,
        metadata: EpisodeMetadataV1 = trajectory.episodeMetadata,
        decisions: List<DecisionRecordV1> = trajectory.decisions,
    ): TrajectoryV1 {
        val withoutId = TrajectoryV1(
            trajectoryId = "d".repeat(64),
            episodeMetadata = metadata,
            decisions = decisions,
        )
        return withoutId.copy(trajectoryId = withoutId.recomputeTrajectoryId())
    }

    fun resultForJson(root: JsonObject): TrajectoryValidationResult =
        TrajectoryV1Json.decodeAndValidate(encodeRoot(root))

    fun requireRejected(result: TrajectoryValidationResult): TrajectoryValidationResult.Rejected =
        result.shouldBeInstanceOf<TrajectoryValidationResult.Rejected>()

    fun containsKey(element: JsonElement, key: String): Boolean = when (element) {
        is JsonObject -> element.entries.any { (currentKey, value) ->
            currentKey == key || containsKey(value, key)
        }
        is JsonArray -> element.any { containsKey(it, key) }
        else -> false
    }

    test("one action trajectory validates and round-trips through strict JSON") {
        val trajectory = validTrajectory()

        val result = TrajectoryV1Validator.validate(trajectory)
        val validated = result.shouldBeInstanceOf<TrajectoryValidationResult.Valid>()
        validated.episode.trajectory shouldBe trajectory

        val encoded = TrajectoryV1Json.encode(trajectory)
        TrajectoryV1Json.decode(encoded) shouldBe trajectory
        TrajectoryV1Validator.validate(TrajectoryV1Json.decode(encoded))
            .shouldBeInstanceOf<TrajectoryValidationResult.Valid>()
    }

    test("folded and structured semantic responses validate as complete durable choices") {
        val trajectory = trajectoryOf(
            listOf(foldedFixture(), targetsFixture(), modeFixture(), cardSelectionFixture(), orderingFixture()),
        )

        TrajectoryV1Validator.validate(trajectory)
            .shouldBeInstanceOf<TrajectoryValidationResult.Valid>()
        trajectory.decisions.map { it.chosenSemanticResponse?.response?.getValue("type") }
            .forEach { it shouldNotBe null }
    }

    test("accepted action and structured domain families validate through the same A3 membership authority") {
        val trajectory = trajectoryOf(familyFixtures())

        TrajectoryV1Validator.validate(trajectory)
            .shouldBeInstanceOf<TrajectoryValidationResult.Valid>()
        trajectory.decisions.map { it.decisionKind }.toSet() shouldBe setOf(
            SemanticDecisionKindV1.PRIORITY,
            SemanticDecisionKindV1.YES_NO,
            SemanticDecisionKindV1.CHOOSE_TARGETS,
            SemanticDecisionKindV1.CHOOSE_MODE,
            SemanticDecisionKindV1.SELECT_CARDS,
            SemanticDecisionKindV1.ORDER_OBJECTS,
            SemanticDecisionKindV1.DISTRIBUTE,
            SemanticDecisionKindV1.CHOOSE_REPLACEMENT,
            SemanticDecisionKindV1.COMBAT_RESOLUTION,
        )
    }

    test("complete interrupted episodes are contract-valid without terminal facts or reward") {
        val trajectory = trajectoryOf(
            listOf(actionFixture()),
            closure = EpisodeClosureV1.Interrupted(
                stepCount = 1,
                reason = com.wingedsheep.gym.EpisodeInterruptionReason.HORIZON_REACHED,
            ),
        )

        TrajectoryV1Validator.validate(trajectory)
            .shouldBeInstanceOf<TrajectoryValidationResult.Valid>()
        val encoded = TrajectoryV1Json.encode(trajectory)
        encoded.contains("reward").shouldBeFalse()
        val encodedRoot = A3SemanticJson.strictJson.parseToJsonElement(encoded).jsonObject
        encodedRoot.getValue("episodeMetadata").jsonObject
            .getValue("closure").jsonObject.containsKey("winnerId").shouldBeFalse()
    }

    test("failed episodes are quarantine-eligible and never validated episodes") {
        val trajectory = trajectoryOf(
            listOf(actionFixture()),
            closure = EpisodeClosureV1.Failed(
                stepCount = 1,
                reason = com.wingedsheep.gym.EpisodeFailureReason.ENGINE_EXCEPTION,
            ),
        )

        val result = TrajectoryV1Validator.validate(trajectory)
        val quarantine = result.shouldBeInstanceOf<TrajectoryValidationResult.QuarantineEligible>()
        quarantine.status shouldBe TrajectoryValidationResult.Status.QUARANTINE_ELIGIBLE
        TrajectoryV1Json.decodeAndValidate(TrajectoryV1Json.encode(trajectory))
            .shouldBeInstanceOf<TrajectoryValidationResult.QuarantineEligible>()
    }

    test("policy provenance changes collection identity but not semantic episode or decision identity") {
        val first = trajectoryOf(listOf(actionFixture()))
        val second = trajectoryOf(
            listOf(actionFixture()),
            policy = defaultPolicy().copy(
                behaviorPolicyIdentity = "different-behavior@v1",
                opponentPolicyIdentity = "different-opponent@v1",
                policySeed = 9901L,
            ),
        )

        first.semanticEpisodeId shouldBe second.semanticEpisodeId
        first.collectionJobId shouldNotBe second.collectionJobId
        first.decisions.single().semanticDecisionId shouldBe second.decisions.single().semanticDecisionId
        TrajectoryV1Validator.validate(first).shouldBeInstanceOf<TrajectoryValidationResult.Valid>()
        TrajectoryV1Validator.validate(second).shouldBeInstanceOf<TrajectoryValidationResult.Valid>()
    }

    test("proof and replay-link metadata do not redefine semantic episode identity") {
        val trajectory = validTrajectory()
        val link = trajectory.compactReplayLink.copy(replayContentIdentity = "d".repeat(64))
        val changedLink = trajectory.episodeMetadata.copy(compactReplayLink = link)

        changedLink.recomputeSemanticEpisodeId() shouldBe trajectory.semanticEpisodeId
        changedLink.recomputeCollectionJobId() shouldBe trajectory.collectionJobId
    }

    test("runtime decision routing identities do not enter durable trajectory content") {
        val first = trajectoryOf(listOf(foldedFixture("runtime-decision-a")))
        val second = trajectoryOf(listOf(foldedFixture("runtime-decision-b")))

        first shouldBe second
        first.semanticEpisodeId shouldBe second.semanticEpisodeId
        first.collectionJobId shouldBe second.collectionJobId
        first.trajectoryId shouldBe second.trajectoryId
    }

    test("durable JSON contains complete public values but no transient routing or internal state") {
        val trajectory = trajectoryOf(listOf(foldedFixture(), targetsFixture()))
        val element = A3SemanticJson.strictJson.parseToJsonElement(TrajectoryV1Json.encode(trajectory))

        listOf(
            "gameState",
            "actionId",
            "decisionId",
            "abilityId",
            "projectionGeneration",
            "envId",
            "rawAction",
            "pendingDecisionInternal",
            "rawGameAction",
            "prefix",
            "reward",
        ).forEach { forbidden -> containsKey(element, forbidden).shouldBeFalse() }
        containsKey(element, "completeLegalDomain").shouldBeTrue()
        containsKey(element, "candidateDomainDigest").shouldBeTrue()
        containsKey(element, "observationBefore").shouldBeTrue()
        containsKey(element, "chosenSemanticResponse").shouldBeTrue()
    }

    test("dataset metadata and manifest contracts are deterministic and storage-neutral") {
        val metadata = DatasetMetadataV1(maxShardBytes = 1_000_000L, maxEpisodesPerShard = 2)
        val shard = DatasetShardMetadataV1(
            shardOrdinal = 0,
            contentReference = "shard-0000.ndjson",
            contentDigest = "e".repeat(64),
            byteCount = 123,
            episodeCount = 1,
        )
        val episode = validTrajectory()
        val index = DatasetEpisodeIndexV1(
            episodeOrdinal = 0,
            semanticEpisodeId = episode.semanticEpisodeId,
            collectionJobId = episode.collectionJobId,
            trajectoryId = episode.trajectoryId,
            shardOrdinal = 0,
            decisionCount = episode.decisions.size,
            closureKind = episode.closure.kind,
        )
        val counts = DatasetCountsV1(
            episodeCount = 1,
            decisionCount = episode.decisions.size,
            gameTerminalCount = 1,
            interruptedCount = 0,
            failedCount = 0,
        )
        val placeholder = DatasetManifestV1(
            datasetId = "f".repeat(64),
            metadata = metadata,
            shards = listOf(shard),
            episodes = listOf(index),
            counts = counts,
            manifestContentDigest = "0".repeat(64),
        )
        val manifest = placeholder.copy(datasetId = placeholder.recomputeDatasetId()).let { identified ->
            identified.copy(manifestContentDigest = identified.recomputeManifestContentDigest())
        }

        manifest.datasetId shouldBe manifest.recomputeDatasetId()
        manifest.manifestContentDigest shouldBe manifest.recomputeManifestContentDigest()
        A3SemanticJson.strictJson.decodeFromString(
            DatasetManifestV1.serializer(),
            A3SemanticJson.strictJson.encodeToString(DatasetManifestV1.serializer(), manifest),
        ) shouldBe manifest
    }

    test("candidate-domain digest is binding and the complete domain is never replaced by a digest") {
        val trajectory = validTrajectory()
        val record = trajectory.decisions.single()
        val wrongDigest = record.candidateDomainDigest.copy(value = "f".repeat(64))
        val result = TrajectoryV1Validator.validate(
            reidentified(trajectory, decisions = listOf(record.copy(candidateDomainDigest = wrongDigest))),
        )

        requireRejected(result).reason shouldBe TrajectoryValidationReason.CANDIDATE_DOMAIN_DIGEST_MISMATCH

        val root = rootJson(trajectory)
        val digestOnlyDomain = buildJsonObject {
            put("version", 1)
            put("schemaIdentity", com.wingedsheep.gym.contract.COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY)
            put("kind", "ACTION_CANDIDATES")
        }
        val digestOnlyRoot = updateDecision(root, 0) {
            replaceJsonValue(it, "completeLegalDomain", digestOnlyDomain)
        }
        requireRejected(resultForJson(digestOnlyRoot)).reason shouldBe TrajectoryValidationReason.DIGEST_ONLY_DOMAIN
    }

    test("A3 structured membership is closed at A5 against the stored public domain") {
        val trajectory = trajectoryOf(listOf(targetsFixture()))
        val root = rootJson(trajectory)
        val decision = root.getValue("decisions").jsonArray.single().jsonObject
        val chosen = decision.getValue("chosenSemanticResponse").jsonObject
        val response = chosen.getValue("response").jsonObject
        val selectedTargets = response.getValue("selectedTargets").jsonObject
        val outside = JsonArray(listOf(JsonPrimitive("outside-domain")))
        val outsideResponse = replaceJsonValue(
            response,
            "selectedTargets",
            replaceJsonValue(selectedTargets, "0", outside),
        )
        val outsideChosen = replaceJsonValue(chosen, "response", outsideResponse)
        val outsideDecision = replaceJsonValue(decision, "chosenSemanticResponse", outsideChosen)

        requireRejected(resultForJson(updateDecision(root, 0) { outsideDecision })).reason shouldBe
            TrajectoryValidationReason.CHOSEN_NOT_IN_DOMAIN
        TrajectoryV1Validator.validate(trajectory)
            .shouldBeInstanceOf<TrajectoryValidationResult.Valid>()
    }

    test("missing, duplicated, or malformed decision payloads reject at the typed boundary") {
        val actionRoot = rootJson(validTrajectory())
        val actionDecision = actionRoot.getValue("decisions").jsonArray.single().jsonObject
        val chosenAction = actionDecision.getValue("chosenSemanticAction").jsonObject
        val outsideCandidate = replaceJsonValue(
            chosenAction.getValue("candidate").jsonObject,
            "kind",
            JsonPrimitive("outside-stored-domain"),
        )
        val outsideAction = replaceJsonValue(chosenAction, "candidate", outsideCandidate)
        requireRejected(
            resultForJson(updateDecision(actionRoot, 0) {
                replaceJsonValue(it, "chosenSemanticAction", outsideAction)
            }),
        ).reason shouldBe TrajectoryValidationReason.CHOSEN_NOT_IN_DOMAIN

        val foldedRoot = rootJson(trajectoryOf(listOf(foldedFixture())))
        val foldedDecision = foldedRoot.getValue("decisions").jsonArray.single().jsonObject
        val chosenResponse = foldedDecision.getValue("chosenSemanticResponse")
        requireRejected(
            resultForJson(updateDecision(foldedRoot, 0) {
                replaceJsonValue(it, "chosenSemanticAction", chosenAction)
            }),
        ).reason shouldBe TrajectoryValidationReason.SCHEMA_MISMATCH
        requireRejected(
            resultForJson(updateDecision(foldedRoot, 0) {
                removeJsonValue(it, "chosenSemanticResponse")
            }),
        ).reason shouldBe TrajectoryValidationReason.SCHEMA_MISMATCH
        requireRejected(
            resultForJson(updateDecision(foldedRoot, 0) {
                replaceJsonValue(it, "chosenSemanticResponse", removeJsonValue(chosenResponse.jsonObject, "response"))
            }),
        ).reason shouldBe TrajectoryValidationReason.SCHEMA_MISMATCH

        val targetRoot = rootJson(trajectoryOf(listOf(targetsFixture())))
        val targetDecision = targetRoot.getValue("decisions").jsonArray.single().jsonObject
        val targetChosen = targetDecision.getValue("chosenSemanticResponse").jsonObject
        val targetResponse = targetChosen.getValue("response").jsonObject
        requireRejected(
            resultForJson(updateDecision(targetRoot, 0) {
                replaceJsonValue(
                    it,
                    "chosenSemanticResponse",
                    replaceJsonValue(
                        targetChosen,
                        "response",
                        removeJsonValue(targetResponse, "selectedTargets"),
                    ),
                )
            }),
        ).reason shouldBe TrajectoryValidationReason.SCHEMA_MISMATCH

        requireRejected(
            resultForJson(updateDecision(actionRoot, 0) {
                removeJsonValue(it, "observationBefore")
            }),
        ).reason shouldBe TrajectoryValidationReason.SCHEMA_MISMATCH
        requireRejected(
            resultForJson(updateDecision(actionRoot, 0) {
                removeJsonValue(it, "completeLegalDomain")
            }),
        ).reason shouldBe TrajectoryValidationReason.SCHEMA_MISMATCH
        requireRejected(
            resultForJson(updateDecision(actionRoot, 0) {
                removeJsonValue(it, "candidateDomainDigest")
            }),
        ).reason shouldBe TrajectoryValidationReason.SCHEMA_MISMATCH
    }

    test("replay coordinates and domain structure are not repaired") {
        val root = rootJson(validTrajectory())
        val badCoordinate = updateDecision(root, 0) {
            replaceJsonValue(
                replaceJsonValue(it, "replayActionIndex", JsonPrimitive(9)),
                "replayFrameIndex",
                JsonPrimitive(9),
            )
        }
        requireRejected(resultForJson(badCoordinate)).reason shouldBe
            TrajectoryValidationReason.BAD_REPLAY_COORDINATE

        val domain = root.getValue("decisions").jsonArray.single().jsonObject
            .getValue("completeLegalDomain").jsonObject
        val candidates = domain.getValue("candidates").jsonArray
        val duplicateDomain = replaceJsonValue(domain, "candidates", JsonArray(candidates + candidates.single()))
        requireRejected(
            resultForJson(updateDecision(root, 0) {
                replaceJsonValue(it, "completeLegalDomain", duplicateDomain)
            }),
        ).reason shouldBe TrajectoryValidationReason.SCHEMA_MISMATCH

        val futureDomain = replaceJsonValue(domain, "version", JsonPrimitive(2))
        requireRejected(
            resultForJson(updateDecision(root, 0) {
                replaceJsonValue(it, "completeLegalDomain", futureDomain)
            }),
        ).reason shouldBe TrajectoryValidationReason.SCHEMA_MISMATCH

        val duplicate = trajectoryOf(listOf(actionFixture(), foldedFixture()))
        requireRejected(
            TrajectoryV1Validator.validate(
                reidentified(
                    duplicate,
                    decisions = listOf(duplicate.decisions[0], duplicate.decisions[1].copy(decisionIndex = 0)),
                ),
            ),
        ).reason shouldBe TrajectoryValidationReason.NON_CONTIGUOUS_DECISION_INDEX
    }

    test("legacy automatic payment and malformed observation identity cannot become trusted choices") {
        val payment = trajectoryOf(listOf(familyFixtures()[1]))
        val root = rootJson(payment)
        val decision = root.getValue("decisions").jsonArray.single().jsonObject
        val chosen = decision.getValue("chosenSemanticAction").jsonObject
        val payload = chosen.getValue("choicePayload").jsonObject
        val autoPay = buildJsonObject { put("type", "AutoPay") }
        val autoPayChosen = replaceJsonValue(
            chosen,
            "choicePayload",
            replaceJsonValue(payload, "paymentStrategy", autoPay),
        )
        requireRejected(
            resultForJson(updateDecision(root, 0) {
                replaceJsonValue(it, "chosenSemanticAction", autoPayChosen)
            }),
        ).reason shouldBe TrajectoryValidationReason.SCHEMA_MISMATCH

        val observation = decision.getValue("observationBefore").jsonObject
        val malformedObservation = replaceJsonValue(observation, "observationDigest", JsonPrimitive("f".repeat(64)))
        requireRejected(
            resultForJson(updateDecision(root, 0) {
                replaceJsonValue(it, "observationBefore", malformedObservation)
            }),
        ).reason shouldBe TrajectoryValidationReason.SEMANTIC_DECISION_IDENTITY_MISMATCH
    }

    test("sequence and identity mismatches reject without repairing producer data") {
        val trajectory = trajectoryOf(listOf(actionFixture(), foldedFixture()))
        val first = trajectory.decisions[0]
        val second = trajectory.decisions[1]

        requireRejected(
            TrajectoryV1Validator.validate(
                reidentified(trajectory, decisions = listOf(first, second.copy(decisionIndex = 4))),
            ),
        ).reason shouldBe TrajectoryValidationReason.NON_CONTIGUOUS_DECISION_INDEX

        requireRejected(
            TrajectoryV1Validator.validate(
                reidentified(
                    trajectory,
                    decisions = listOf(first, second.copy(semanticDecisionId = first.semanticDecisionId)),
                ),
            ),
        ).reason shouldBe TrajectoryValidationReason.SEMANTIC_DECISION_IDENTITY_MISMATCH

        requireRejected(
            TrajectoryV1Validator.validate(trajectory.copy(trajectoryId = "f".repeat(64))),
        ).reason shouldBe TrajectoryValidationReason.TRAJECTORY_IDENTITY_MISMATCH
    }

    test("metadata identities and closure facts are recomputed and cross-checked") {
        val trajectory = validTrajectory()
        val metadata = trajectory.episodeMetadata

        requireRejected(
            TrajectoryV1Validator.validate(
                reidentified(trajectory, metadata = metadata.copy(semanticEpisodeId = "f".repeat(64))),
            ),
        ).reason shouldBe TrajectoryValidationReason.SEMANTIC_EPISODE_IDENTITY_MISMATCH

        requireRejected(
            TrajectoryV1Validator.validate(
                reidentified(trajectory, metadata = metadata.copy(collectionJobId = "f".repeat(64))),
            ),
        ).reason shouldBe TrajectoryValidationReason.COLLECTION_JOB_IDENTITY_MISMATCH

        val terminalMismatch = metadata.copy(
            closure = EpisodeClosureV1.GameTerminal(
                stepCount = 1,
                winnerId = EntityId("e0"),
                reason = GameEndReason.DRAW,
            ),
        )
        requireRejected(TrajectoryV1Validator.validate(reidentified(trajectory, terminalMismatch))).reason shouldBe
            TrajectoryValidationReason.CLOSURE_MISMATCH
    }

    test("terminal facts cannot be fabricated on an interrupted prefix") {
        val trajectory = validTrajectory()
        val terminalObservation = trajectory.decisions.single().observation.copy(
            terminated = true,
            winnerId = EntityId("e0"),
        )
        val record = trajectory.decisions.single().copy(observationBefore = terminalObservation)
        val interrupted = reidentified(
            trajectory,
            metadata = trajectory.episodeMetadata.copy(
                closure = EpisodeClosureV1.Interrupted(
                    stepCount = 1,
                    reason = com.wingedsheep.gym.EpisodeInterruptionReason.CALLER_CANCELLED,
                ),
            ),
            decisions = listOf(record),
        )

        requireRejected(TrajectoryV1Validator.validate(interrupted)).reason shouldBe
            TrajectoryValidationReason.CLOSURE_MISMATCH
    }

    test("strict decoding rejects future versions, malformed components, and internal-field injection") {
        val trajectory = validTrajectory()
        val root = rootJson(trajectory)

        requireRejected(resultForJson(replaceJsonValue(root, "version", JsonPrimitive(2)))).reason shouldBe
            TrajectoryValidationReason.UNKNOWN_VERSION
        requireRejected(
            resultForJson(updateNested(root, "episodeMetadata", "version", JsonPrimitive(2))),
        ).reason shouldBe TrajectoryValidationReason.SCHEMA_MISMATCH

        val missingObservation = updateDecision(root, 0) {
            removeJsonValue(it, "observationBefore")
        }
        requireRejected(resultForJson(missingObservation)).reason shouldBe TrajectoryValidationReason.SCHEMA_MISMATCH

        val missingChosen = updateDecision(root, 0) {
            removeJsonValue(it, "chosenSemanticAction")
        }
        requireRejected(resultForJson(missingChosen)).reason shouldBe TrajectoryValidationReason.SCHEMA_MISMATCH

        listOf(
            "gameState",
            "actionId",
            "decisionId",
            "abilityId",
            "projectionGeneration",
            "envId",
            "rawAction",
            "pendingDecisionInternal",
        ).forEach { forbidden ->
            val injected = replaceJsonValue(root, forbidden, buildJsonObject { put("secret", true) })
            requireRejected(resultForJson(injected)).reason shouldBe
                TrajectoryValidationReason.PRIVACY_INTERNAL_FIELD_REJECTION
        }

        val unknownField = replaceJsonValue(root, "futureField", JsonPrimitive(true))
        requireRejected(resultForJson(unknownField)).reason shouldBe TrajectoryValidationReason.SCHEMA_MISMATCH
    }
})
