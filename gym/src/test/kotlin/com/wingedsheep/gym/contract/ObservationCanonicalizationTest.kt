package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.AtomicManaCostUnitV1
import com.wingedsheep.engine.core.PaymentCostKindV1
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val HIDDEN_HAND_FIXTURE_SEED = 70L

class ObservationCanonicalizationTest : FunSpec({

    fun registry(): CardRegistry = CardRegistry().also {
        it.register(PortalSet.cards)
        it.register(PortalSet.basicLands)
    }

    fun environment(seed: Long? = null): GameEnvironment {
        val env = GameEnvironment.create(registry())
        env.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20))
                ),
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = seed,
            )
        )
        return env
    }

    fun exactJsonStringPaths(
        element: JsonElement,
        expected: String,
        path: String = "$"
    ): List<String> = when (element) {
        is JsonObject -> element.entries.flatMap { (key, value) ->
            val keyPath = "$path.<key:$key>"
            val keyMatch = if (key == expected) listOf(keyPath) else emptyList()
            keyMatch + exactJsonStringPaths(value, expected, "$path.$key")
        }

        is JsonArray -> element.mapIndexed { index, child ->
            exactJsonStringPaths(child, expected, "$path[$index]")
        }.flatten()

        is JsonPrimitive -> if (element.content == expected) listOf(path) else emptyList()
    }

    fun assertNoExactJsonStringReference(
        serialized: String,
        expected: String,
        label: String,
    ) {
        val paths = exactJsonStringPaths(Json.parseToJsonElement(serialized), expected)
        if (paths.isNotEmpty()) {
            throw AssertionError(
                "$label contains exact JSON string '$expected' at ${paths.joinToString()}",
            )
        }
    }

    fun observation(env: GameEnvironment): TrainingObservation =
        ObservationBuilder(cardRegistry = registry()).build(env.state, env.playerIds.first(), env.legalActions())
            .observation as TrainingObservation

    fun targetRequirement(
        index: Int,
        description: String = "requirement-$index",
        minTargets: Int = 1,
        maxTargets: Int = 1,
        candidates: List<EntityId> = listOf(EntityId("candidate-$index")),
        targetZone: String? = "BATTLEFIELD",
        mustDifferFromEarlier: Boolean = false,
        sameController: Boolean = false,
        sameOwner: Boolean = false,
        sameCreatureType: Boolean = false,
        sameCardType: Boolean = false,
        totalManaValueAtMost: Int? = null,
        differentNames: Boolean = false,
        xConstrainsManaValue: Boolean = false,
        xConstrainsManaValueExactly: Boolean = false,
        xConstrainsPower: Boolean = false,
        xConstrainsCount: Boolean = false,
    ) = TargetRequirementDomain(
        index = index,
        description = description,
        minTargets = minTargets,
        maxTargets = maxTargets,
        candidates = candidates,
        targetZone = targetZone,
        mustDifferFromEarlier = mustDifferFromEarlier,
        sameController = sameController,
        sameOwner = sameOwner,
        sameCreatureType = sameCreatureType,
        sameCardType = sameCardType,
        totalManaValueAtMost = totalManaValueAtMost,
        differentNames = differentNames,
        xConstrainsManaValue = xConstrainsManaValue,
        xConstrainsManaValueExactly = xConstrainsManaValueExactly,
        xConstrainsPower = xConstrainsPower,
        xConstrainsCount = xConstrainsCount,
    )

    fun withActionTargetDomain(
        base: TrainingObservation,
        domain: ActionTargetDomainV1?,
    ): TrainingObservation = base.copy(
        legalActions = listOf(
            base.legalActions.first().copy(
                actionId = 9000,
                description = "action target presentation",
                targetEntityIds = emptyList(),
                targetDomain = domain,
                minTargets = 0,
                maxTargets = 0,
            )
        )
    )

    fun paymentDomain(
        requiredCost: String,
        outerAtomicCostUnits: List<AtomicManaCostUnitV1> = emptyList(),
    ) = PaymentDomainV5(
        requiredCost = requiredCost,
        outerAtomicCostUnits = outerAtomicCostUnits,
        initialPoolBuckets = emptyList(),
        sourceActivationOptions = emptyList(),
    )

    fun withTargetPaymentDomain(
        base: TrainingObservation,
        domain: TargetPaymentDomainV1?,
    ): TrainingObservation = base.copy(
        legalActions = listOf(
            base.legalActions.first().copy(
                actionId = 9001,
                description = "target payment presentation",
                targetPaymentDomain = domain,
            )
        )
    )

    fun targetBindingOrder(observation: TrainingObservation): List<String> =
        Json.parseToJsonElement(ObservationCanonicalizer.wireJson(observation))
            .jsonObject["legalActions"]!!
            .jsonArray
            .single()
            .jsonObject["targetPaymentDomain"]!!
            .jsonObject["targetBindings"]!!
            .jsonArray
            .map { it.jsonObject["target"]!!.jsonPrimitive.content }

    fun withAttackDeclarationDomain(
        base: TrainingObservation,
        domain: AttackDeclarationDomainV2?,
    ): TrainingObservation = base.copy(
        legalActions = listOf(
            base.legalActions.first().copy(
                actionId = 9001,
                attackDeclarationDomain = domain,
                requiresStructuredAction = true,
                requiredPayloadFields = listOf("attackers", "bands"),
            )
        )
    )

    test("wire JSON retains transport IDs while semantic JSON excludes them") {
        val base = observation(environment())
        val transportVariant = base.copy(
            legalActions = base.legalActions.mapIndexed { index, action ->
                action.copy(
                    actionId = index + 17,
                    description = "presentation variant $index"
                )
            }
        )

        ObservationCanonicalizer.wireJson(base) shouldNotBe
            ObservationCanonicalizer.wireJson(transportVariant)
        ObservationCanonicalizer.semanticJson(base) shouldBe
            ObservationCanonicalizer.semanticJson(transportVariant)
        StateDigest.compute(base) shouldBe StateDigest.compute(transportVariant)
    }

    test("structured legal-action changes affect semantic identity without using description") {
        val base = observation(environment())
        val first = base.legalActions.first()
        val structuredVariant = base.copy(
            legalActions = listOf(
                first.copy(
                    affordable = !first.affordable,
                    description = "the same presentation text"
                )
            ) + base.legalActions.drop(1)
        )

        ObservationCanonicalizer.semanticJson(base) shouldNotBe
            ObservationCanonicalizer.semanticJson(structuredVariant)
        StateDigest.compute(base) shouldNotBe StateDigest.compute(structuredVariant)
    }

    test("required payload fields are ordered wire and digest semantics") {
        val base = observation(environment())
        val first = base.legalActions.first()
        val withFields = base.copy(
            legalActions = listOf(
                first.copy(
                    requiresStructuredAction = true,
                    requiredPayloadFields = listOf("paymentStrategy", "additionalCostPayment"),
                )
            ) + base.legalActions.drop(1)
        )
        val reordered = withFields.copy(
            legalActions = listOf(
                withFields.legalActions.first().copy(
                    requiredPayloadFields = listOf("additionalCostPayment", "paymentStrategy"),
                )
            ) + withFields.legalActions.drop(1)
        )

        ObservationCanonicalizer.wireJson(withFields) shouldContain "\"requiredPayloadFields\""
        ObservationCanonicalizer.semanticJson(withFields) shouldNotBe
            ObservationCanonicalizer.semanticJson(reordered)
        StateDigest.compute(withFields) shouldNotBe StateDigest.compute(reordered)
    }

    test("target payment domain participates in wire and semantic action identity") {
        val base = observation(environment())
        val targetPaymentDomain = TargetPaymentDomainV1(
            targetBindings = listOf(
                TargetPaymentBindingV1(
                    target = EntityId("target-a"),
                    affordable = true,
                    paymentDomain = paymentDomain("{0}"),
                ),
            ),
        )
        val withDomain = withTargetPaymentDomain(base, targetPaymentDomain)

        ObservationCanonicalizer.wireJson(withDomain) shouldContain "\"targetPaymentDomain\""
        ObservationCanonicalizer.semanticJson(withDomain) shouldContain "\"targetPaymentDomain\""
        ObservationCanonicalizer.semanticJson(withDomain) shouldContain "\"requiredCost\":\"{0}\""
    }

    test("target payment bindings preserve producer order and nested V5 changes the digest") {
        val base = observation(environment())
        val targetA = EntityId("target-a")
        val targetB = EntityId("target-b")
        val genericOne = AtomicManaCostUnitV1(
            symbolIndex = 0,
            unitIndexWithinSymbol = 0,
            kind = PaymentCostKindV1.GENERIC,
        )

        fun relation(order: List<EntityId>, requiredCost: String = "{0}") =
            TargetPaymentDomainV1(
                targetBindings = order.map { target ->
                    TargetPaymentBindingV1(
                        target = target,
                        affordable = target == targetA,
                        paymentDomain = paymentDomain(
                            requiredCost = requiredCost,
                            outerAtomicCostUnits = if (requiredCost == "{1}") listOf(genericOne) else emptyList(),
                        ),
                    )
                },
            )

        val producerOrder = withTargetPaymentDomain(base, relation(listOf(targetA, targetB)))
        val reversedInput = withTargetPaymentDomain(base, relation(listOf(targetB, targetA)))
        targetBindingOrder(producerOrder) shouldBe listOf("target-a", "target-b")
        targetBindingOrder(reversedInput) shouldBe listOf("target-b", "target-a")

        val nestedPaymentVariant = withTargetPaymentDomain(
            base,
            relation(listOf(targetA, targetB), requiredCost = "{1}"),
        )
        ObservationCanonicalizer.semanticJson(producerOrder) shouldNotBe
            ObservationCanonicalizer.semanticJson(nestedPaymentVariant)
        StateDigest.compute(producerOrder) shouldNotBe StateDigest.compute(nestedPaymentVariant)
    }

    test("action target domains are present on the wire and candidates canonicalize by EntityId") {
        val base = observation(environment())
        val first = withActionTargetDomain(
            base,
            ActionTargetDomainV1(
                requirements = listOf(
                    targetRequirement(
                        index = 0,
                        candidates = listOf(EntityId("zeta"), EntityId("alpha")),
                    )
                )
            )
        )
        val equivalentIterationOrder = withActionTargetDomain(
            base,
            ActionTargetDomainV1(
                requirements = listOf(
                    targetRequirement(
                        index = 0,
                        candidates = listOf(EntityId("alpha"), EntityId("zeta")),
                    )
                )
            )
        )

        ObservationCanonicalizer.wireJson(first) shouldBe
            ObservationCanonicalizer.wireJson(equivalentIterationOrder)
        ObservationCanonicalizer.semanticJson(first) shouldBe
            ObservationCanonicalizer.semanticJson(equivalentIterationOrder)
        StateDigest.compute(first) shouldBe StateDigest.compute(equivalentIterationOrder)

        val wire = ObservationCanonicalizer.wireJson(first)
        wire shouldContain "\"targetDomain\""
        wire shouldContain "\"composition\":\"FIXED\""
        wire shouldContain "\"version\":1"
    }

    test("action target semantic identity excludes descriptions but binds every legal-domain field") {
        val base = observation(environment())
        val requirement = targetRequirement(index = 0)
        val domain = ActionTargetDomainV1(requirements = listOf(requirement))
        val withDomain = withActionTargetDomain(base, domain)
        val withPresentationVariant = withActionTargetDomain(
            base,
            ActionTargetDomainV1(
                requirements = listOf(requirement.copy(description = "different presentation text"))
            )
        )

        ObservationCanonicalizer.wireJson(withDomain) shouldNotBe
            ObservationCanonicalizer.wireJson(withPresentationVariant)
        ObservationCanonicalizer.semanticJson(withDomain) shouldBe
            ObservationCanonicalizer.semanticJson(withPresentationVariant)
        StateDigest.compute(withDomain) shouldBe StateDigest.compute(withPresentationVariant)

        val baselineDigest = StateDigest.compute(withDomain)
        val variants = listOf(
            requirement.copy(minTargets = 0),
            requirement.copy(maxTargets = 2),
            requirement.copy(candidates = listOf(EntityId("different-candidate"))),
            requirement.copy(targetZone = "STACK"),
            requirement.copy(mustDifferFromEarlier = true),
            requirement.copy(sameController = true),
            requirement.copy(sameOwner = true),
            requirement.copy(sameCreatureType = true),
            requirement.copy(sameCardType = true),
            requirement.copy(totalManaValueAtMost = 4),
            requirement.copy(differentNames = true),
            requirement.copy(xConstrainsManaValue = true),
            requirement.copy(xConstrainsManaValueExactly = true),
            requirement.copy(xConstrainsPower = true),
            requirement.copy(xConstrainsCount = true),
        )

        variants.forEach { variant ->
            StateDigest.compute(
                withActionTargetDomain(base, ActionTargetDomainV1(requirements = listOf(variant)))
            ) shouldNotBe baselineDigest
        }

        val reorderedRequirements = withActionTargetDomain(
            base,
            ActionTargetDomainV1(
                requirements = listOf(
                    requirement.copy(index = 0),
                    requirement.copy(index = 1, candidates = listOf(EntityId("second"))),
                )
            )
        )
        val reversedRequirements = reorderedRequirements.copy(
            legalActions = reorderedRequirements.legalActions.map { action ->
                action.copy(
                    targetDomain = ActionTargetDomainV1(
                        requirements = action.targetDomain!!.requirements.reversed()
                    )
                )
            }
        )
        StateDigest.compute(reorderedRequirements) shouldNotBe StateDigest.compute(reversedRequirements)

        val missingDomain = withActionTargetDomain(base, null)
        StateDigest.compute(withDomain) shouldNotBe StateDigest.compute(missingDomain)
    }

    test("attack declaration domains are canonical semantic legal-action identity") {
        val base = observation(environment())
        val attackerA = EntityId("attacker-a")
        val attackerB = EntityId("attacker-b")
        val defenderA = EntityId("defender-a")
        val defenderB = EntityId("defender-b")
        val domain = AttackDeclarationDomainV2(
            attackerOrder = listOf(attackerB, attackerA),
            attackerToDefenders = linkedMapOf(
                attackerB to listOf(defenderA, defenderB),
                attackerA to listOf(defenderA),
            ),
            mandatoryAttackers = listOf(attackerB, attackerA),
            canDeclareZeroAttackers = false,
            maxAttackers = 2,
            coAttackerRequirements = linkedMapOf(
                attackerB to listOf(AttackCoAttackerRequirementV1(listOf(attackerA))),
            ),
            bandConstraints = AttackBandConstraintsV1(
                bandingAttackersByDefender = linkedMapOf(defenderA to listOf(attackerB)),
                nonBandingAttackersByDefender = linkedMapOf(
                    defenderB to listOf(attackerB),
                    defenderA to listOf(attackerA),
                ),
            ),
        )
        val equivalentIterationOrder = domain.copy(
            attackerToDefenders = linkedMapOf(
                attackerA to listOf(defenderA),
                attackerB to listOf(defenderA, defenderB),
            ),
            bandConstraints = AttackBandConstraintsV1(
                bandingAttackersByDefender = linkedMapOf(defenderA to listOf(attackerB)),
                nonBandingAttackersByDefender = linkedMapOf(
                    defenderA to listOf(attackerA),
                    defenderB to listOf(attackerB),
                ),
            ),
        )
        val first = withAttackDeclarationDomain(base, domain)
        val reordered = withAttackDeclarationDomain(base, equivalentIterationOrder)

        ObservationCanonicalizer.semanticJson(first) shouldBe
            ObservationCanonicalizer.semanticJson(reordered)
        StateDigest.compute(first) shouldBe StateDigest.compute(reordered)
        first.legalActions.single().requiredPayloadFields shouldBe listOf("attackers", "bands")

        val wire = ObservationCanonicalizer.wireJson(first)
        wire shouldContain "\"attackDeclarationDomain\""
        wire shouldContain "\"attackerOrder\""
        wire shouldContain "\"attackerToDefenders\""
        wire shouldContain "\"coAttackerRequirements\""
        wire shouldContain "\"bandConstraints\""

        val baselineDigest = StateDigest.compute(first)
        listOf(
            domain.copy(
                attackerToDefenders = domain.attackerToDefenders +
                    (attackerA to listOf(defenderB)),
            ),
            domain.copy(mandatoryAttackers = listOf(attackerA)),
            domain.copy(canDeclareZeroAttackers = true),
            domain.copy(maxAttackers = 1),
            domain.copy(coAttackerRequirements = emptyMap()),
            domain.copy(
                bandConstraints = domain.bandConstraints.copy(
                    nonBandingAttackersByDefender = mapOf(defenderA to listOf(attackerA, attackerB)),
                )
            ),
        ).forEach { variant ->
            StateDigest.compute(withAttackDeclarationDomain(base, variant)) shouldNotBe baselineDigest
        }
    }

    test("attack candidate sequence is not erased from semantic identity") {
        val base = observation(environment())
        val attackerA = EntityId("attacker-a")
        val attackerB = EntityId("attacker-b")
        val defenderA = EntityId("defender-a")
        val defenderB = EntityId("defender-b")
        val first = AttackDeclarationDomainV2(
            attackerOrder = listOf(attackerB, attackerA),
            attackerToDefenders = linkedMapOf(
                attackerB to listOf(defenderA, defenderB),
                attackerA to listOf(defenderA),
            ),
            mandatoryAttackers = listOf(attackerB, attackerA),
            canDeclareZeroAttackers = false,
            maxAttackers = 2,
            coAttackerRequirements = emptyMap(),
            bandConstraints = AttackBandConstraintsV1(
                bandingAttackersByDefender = emptyMap(),
                nonBandingAttackersByDefender = linkedMapOf(
                    defenderB to listOf(attackerB),
                    defenderA to listOf(attackerB, attackerA),
                ),
            ),
        )
        val reversed = first.copy(
            attackerOrder = listOf(attackerA, attackerB),
            attackerToDefenders = linkedMapOf(
                attackerA to listOf(defenderA),
                attackerB to listOf(defenderA, defenderB),
            ),
            mandatoryAttackers = listOf(attackerA, attackerB),
            bandConstraints = first.bandConstraints.copy(
                nonBandingAttackersByDefender = linkedMapOf(
                    defenderB to listOf(attackerB),
                    defenderA to listOf(attackerA, attackerB),
                ),
            ),
        )

        ObservationCanonicalizer.semanticJson(withAttackDeclarationDomain(base, first)) shouldNotBe
            ObservationCanonicalizer.semanticJson(withAttackDeclarationDomain(base, reversed))
    }

    test("unknown future action target versions fail before canonicalization") {
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val encoded = json.encodeToString(ActionTargetDomainV1.serializer(), ActionTargetDomainV1())
        val unknownVersion = encoded.replace("\"version\":1", "\"version\":99")
        unknownVersion shouldNotBe encoded

        shouldThrow<IllegalArgumentException> {
            json.decodeFromString<ActionTargetDomainV1>(unknownVersion)
        }
    }

    test("set and map insertion order does not change canonical wire JSON") {
        val base = observation(environment())
        val cardZoneIndex = base.zones.indexOfFirst { it.cards.isNotEmpty() }
        val cardIndex = base.zones[cardZoneIndex].cards.indexOfFirst { true }
        val card = base.zones[cardZoneIndex].cards[cardIndex]
        val reordered = card.copy(
            types = linkedSetOf("TYPE_B", "TYPE_A"),
            subtypes = linkedSetOf("SUBTYPE_B", "SUBTYPE_A"),
            colors = linkedSetOf("COLOR_B", "COLOR_A"),
            keywords = linkedSetOf("KEYWORD_B", "KEYWORD_A"),
            counters = linkedMapOf("counter-b" to 2, "counter-a" to 1)
        )
        val sameSetDifferentInsertionOrder = reordered.copy(
            types = linkedSetOf("TYPE_A", "TYPE_B"),
            subtypes = linkedSetOf("SUBTYPE_A", "SUBTYPE_B"),
            colors = linkedSetOf("COLOR_A", "COLOR_B"),
            keywords = linkedSetOf("KEYWORD_A", "KEYWORD_B"),
            counters = linkedMapOf("counter-a" to 1, "counter-b" to 2)
        )
        fun withCard(replacement: EntityFeatures): TrainingObservation = base.copy(
            zones = base.zones.mapIndexed { index, zone ->
                if (index != cardZoneIndex) zone else zone.copy(
                    cards = zone.cards.mapIndexed { nestedIndex, nested ->
                        if (nestedIndex == cardIndex) replacement else nested
                    }
                )
            }
        )

        ObservationCanonicalizer.wireJson(withCard(reordered)) shouldBe
            ObservationCanonicalizer.wireJson(withCard(sameSetDifferentInsertionOrder))
    }

    test("unordered attachments and legal target candidates are canonicalized as sets") {
        val base = observation(environment())
        val cardZoneIndex = base.zones.indexOfFirst { it.cards.isNotEmpty() }
        val cardIndex = base.zones[cardZoneIndex].cards.indexOfFirst { true }
        val card = base.zones[cardZoneIndex].cards[cardIndex]
        val attachmentA = EntityId("attachment-a")
        val attachmentB = EntityId("attachment-b")

        fun withAttachments(attachments: List<EntityId>): TrainingObservation = base.copy(
            zones = base.zones.mapIndexed { index, zone ->
                if (index != cardZoneIndex) zone else zone.copy(
                    cards = zone.cards.mapIndexed { nestedIndex, nested ->
                        if (nestedIndex == cardIndex) nested.copy(attachments = attachments) else nested
                    }
                )
            }
        )

        ObservationCanonicalizer.wireJson(withAttachments(listOf(attachmentA, attachmentB))) shouldBe
            ObservationCanonicalizer.wireJson(withAttachments(listOf(attachmentB, attachmentA)))

        val firstTarget = EntityId("candidate-a")
        val secondTarget = EntityId("candidate-b")
        val withTargetsA = base.copy(
            legalActions = listOf(
                base.legalActions.first().copy(targetEntityIds = listOf(firstTarget, secondTarget))
            )
        )
        val withTargetsB = withTargetsA.copy(
            legalActions = listOf(
                withTargetsA.legalActions.first().copy(targetEntityIds = listOf(secondTarget, firstTarget))
            )
        )

        ObservationCanonicalizer.semanticJson(withTargetsA) shouldBe
            ObservationCanonicalizer.semanticJson(withTargetsB)
        StateDigest.compute(withTargetsA) shouldBe StateDigest.compute(withTargetsB)
    }

    test("structured action target slot order remains semantic") {
        val base = observation(environment())
        val first = EntityId("target-slot-a")
        val second = EntityId("target-slot-b")

        fun withTargetOrder(order: List<EntityId>): TrainingObservation = base.copy(
            legalActions = listOf(
                base.legalActions.first().copy(
                    actionSemantics = buildJsonObject {
                        put("targets", buildJsonArray {
                            order.forEach { add(JsonPrimitive(it.value)) }
                        })
                    }
                )
            )
        )

        ObservationCanonicalizer.semanticJson(withTargetOrder(listOf(first, second))) shouldNotBe
            ObservationCanonicalizer.semanticJson(withTargetOrder(listOf(second, first)))
    }

    test("presentation-only structured-domain drift does not change the semantic digest") {
        val base = observation(environment())
        val candidate = EntityId("domain-card")

        fun withPresentation(
            imageUri: String?,
            useTargetingUI: Boolean,
            selectedLabel: String?
        ): TrainingObservation {
            val domain = CardSelectionDomain(
                options = listOf(candidate),
                minSelections = 1,
                maxSelections = 1,
                ordered = false,
                cardInfo = mapOf(
                    candidate to StructuredCardInfo(
                        name = "Domain Card",
                        manaCost = "{1}",
                        typeLine = "Creature",
                        imageUri = imageUri,
                        colors = listOf("R"),
                        power = 1
                    )
                ),
                useTargetingUI = useTargetingUI,
                selectedLabel = selectedLabel,
                remainderLabel = "Keep",
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
                conditionalMinimums = emptyList()
            )
            return base.copy(
                pendingDecision = PendingDecisionView(
                    decisionId = "routing-id",
                    kind = PendingDecisionKind.SELECT_CARDS,
                    playerId = base.perspectivePlayerId,
                    prompt = "presentation prompt",
                    requiresStructuredResponse = true,
                    structuredDomain = domain
                )
            )
        }

        val first = withPresentation("https://cdn.example/one.png", false, "Select")
        val presentationVariant = withPresentation("https://cdn.example/two.png", true, "Choose")

        ObservationCanonicalizer.wireJson(first) shouldNotBe ObservationCanonicalizer.wireJson(presentationVariant)
        StateDigest.compute(first) shouldBe StateDigest.compute(presentationVariant)
    }

    test("structured-domain candidate and constraint drift changes the semantic digest") {
        val base = observation(environment())
        val first = EntityId("domain-card-a")
        val second = EntityId("domain-card-b")

        fun withDomain(options: List<EntityId>, maxSelections: Int): TrainingObservation = base.copy(
            pendingDecision = PendingDecisionView(
                decisionId = "routing-id",
                kind = PendingDecisionKind.SELECT_CARDS,
                playerId = base.perspectivePlayerId,
                prompt = "presentation prompt",
                structuredDomain = CardSelectionDomain(
                    options = options,
                    minSelections = 1,
                    maxSelections = maxSelections,
                    ordered = false,
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
                    conditionalMinimums = emptyList()
                )
            )
        )

        StateDigest.compute(withDomain(listOf(first), 1)) shouldNotBe
            StateDigest.compute(withDomain(listOf(first, second), 1))
        StateDigest.compute(withDomain(listOf(first), 1)) shouldNotBe
            StateDigest.compute(withDomain(listOf(first), 2))
    }

    test("split-pile labels are presentation-only in the semantic digest") {
        val base = observation(environment())
        val card = EntityId("split-pile-card")

        fun withLabels(labels: List<String>): TrainingObservation = base.copy(
            pendingDecision = PendingDecisionView(
                decisionId = "routing-id",
                kind = PendingDecisionKind.SPLIT_PILES,
                playerId = base.perspectivePlayerId,
                prompt = "presentation prompt",
                structuredDomain = SplitPilesDomain(
                    cards = listOf(card),
                    numberOfPiles = 2,
                    pileLabels = labels
                )
            )
        )

        val first = withLabels(listOf("Keep", "Discard"))
        val presentationVariant = withLabels(listOf("Top", "Bottom"))

        ObservationCanonicalizer.wireJson(first) shouldNotBe
            ObservationCanonicalizer.wireJson(presentationVariant)
        StateDigest.compute(first) shouldBe StateDigest.compute(presentationVariant)
    }

    test("rules-significant stack order remains observable") {
        val base = observation(environment())
        val lower = StackItemView(
            entityId = EntityId("stack-lower"),
            controllerId = base.perspectivePlayerId,
            sourceEntityId = EntityId("source-lower"),
            name = "Lower",
            kind = StackItemKind.SPELL
        )
        val upper = lower.copy(
            entityId = EntityId("stack-upper"),
            sourceEntityId = EntityId("source-upper"),
            name = "Upper"
        )
        val ordered = base.copy(stack = listOf(lower, upper))
        val reversed = base.copy(stack = listOf(upper, lower))

        ObservationCanonicalizer.semanticJson(ordered) shouldNotBe
            ObservationCanonicalizer.semanticJson(reversed)
    }

    test("seeded hidden-hand fixture has stable entity placement") {
        val first = environment(seed = HIDDEN_HAND_FIXTURE_SEED)
        val second = environment(seed = HIDDEN_HAND_FIXTURE_SEED)

        first.playerIds shouldBe second.playerIds
        first.state shouldBe second.state
        first.state.getHand(first.playerIds[1]).first() shouldBe
            second.state.getHand(second.playerIds[1]).first()
    }

    test("hidden hand identity is absent from both canonical forms") {
        val env = environment(seed = HIDDEN_HAND_FIXTURE_SEED)
        val opponent = env.playerIds[1]
        val hiddenId = env.state.getHand(opponent).first()
        val replacement = CardEntityFactory
            .create(registry().requireCard("Raging Goblin"), opponent)
            .get<com.wingedsheep.engine.state.components.identity.CardComponent>()
        val pairedState = env.state.copy(
            entities = env.state.entities + (
                hiddenId to checkNotNull(env.state.entities[hiddenId]).with(checkNotNull(replacement))
                )
        )
        val maskedA = ObservationBuilder(cardRegistry = registry()).build(env.state, env.playerIds[0], emptyList())
            .observation as TrainingObservation
        val maskedB = ObservationBuilder(cardRegistry = registry()).build(pairedState, env.playerIds[0], emptyList())
            .observation as TrainingObservation

        val wireA = ObservationCanonicalizer.wireJson(maskedA)
        val wireB = ObservationCanonicalizer.wireJson(maskedB)
        val semanticA = ObservationCanonicalizer.semanticJson(maskedA)
        val semanticB = ObservationCanonicalizer.semanticJson(maskedB)

        listOf(
            "wireA" to wireA,
            "wireB" to wireB,
            "semanticA" to semanticA,
            "semanticB" to semanticB,
        ).forEach { (label, serialized) ->
            assertNoExactJsonStringReference(serialized, hiddenId.value, label)
            assertNoExactJsonStringReference(serialized, "Raging Goblin", label)
        }

        semanticA shouldBe semanticB
        maskedA.stateDigest shouldBe maskedB.stateDigest
        StateDigest.compute(maskedA) shouldBe StateDigest.compute(maskedB)
    }

    test("structural hidden-identity assertion detects exact references, not substrings") {
        val hiddenId = EntityId("e2")
        val decoy = buildJsonObject {
            put("visibleEntityId", "e20")
        }

        assertNoExactJsonStringReference(decoy.toString(), hiddenId.value, "decoy")

        val leaked = buildJsonObject {
            put("zones", buildJsonArray {
                add(buildJsonObject {
                    put("cards", buildJsonArray {
                        add(buildJsonObject {
                            put("entityId", hiddenId.value)
                            put("attachments", buildJsonArray { add(JsonPrimitive(hiddenId.value)) })
                        })
                    })
                })
            })
            put("stack", buildJsonArray {
                add(buildJsonObject { put("sourceEntityId", hiddenId.value) })
            })
            put("legalActions", buildJsonArray {
                add(buildJsonObject {
                    put("sourceEntityId", hiddenId.value)
                    put("targetEntityIds", buildJsonArray { add(JsonPrimitive(hiddenId.value)) })
                    put("targetDomain", buildJsonObject {
                        put("requirements", buildJsonArray {
                            add(buildJsonObject {
                                put("candidates", buildJsonArray { add(JsonPrimitive(hiddenId.value)) })
                            })
                        })
                    })
                })
            })
            put("pendingDecision", buildJsonObject {
                put("structuredDomain", buildJsonObject {
                    put("options", buildJsonArray { add(JsonPrimitive(hiddenId.value)) })
                })
            })
            put("diagnostics", buildJsonObject { put("entityId", hiddenId.value) })
        }

        val failure = shouldThrow<AssertionError> {
            assertNoExactJsonStringReference(leaked.toString(), hiddenId.value, "negative control")
        }
        failure.message shouldContain "$.zones[0].cards[0].entityId"
    }
})
