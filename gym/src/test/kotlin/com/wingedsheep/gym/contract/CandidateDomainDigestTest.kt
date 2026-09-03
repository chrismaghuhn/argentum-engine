package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.core.AtomicManaCostUnitV1
import com.wingedsheep.engine.core.PaymentCostKindV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val FIXTURE_SEED = 70L

class CandidateDomainDigestTest : FunSpec({

    fun registry(): CardRegistry = CardRegistry().also {
        it.register(PortalSet.cards)
        it.register(PortalSet.basicLands)
    }

    fun environment(): GameEnvironment {
        val env = GameEnvironment.create(registry())
        env.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 2,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = FIXTURE_SEED,
            )
        )
        return env
    }

    fun observation(env: GameEnvironment): TrainingObservation = ObservationBuilder(
        cardRegistry = registry()
    ).build(
        env.state,
        env.playerIds.first(),
        env.legalActions(),
    ).observation as TrainingObservation

    fun structuredObservation(
        env: GameEnvironment,
        domain: StructuredDecisionDomain?,
        kind: PendingDecisionKind = PendingDecisionKind.REORDER_LIBRARY,
        shape: DecisionShape = DecisionShape(),
    ): TrainingObservation {
        val source = env.state.getHand(env.playerIds.first()).first()
        val trigger = env.state.getHand(env.playerIds.first()).last()
        val pending = PendingDecisionView(
            decisionId = "routing-decision",
            kind = kind,
            playerId = env.playerIds.first(),
            prompt = "presentation prompt",
            sourceEntityId = source,
            sourceName = "presentation source",
            triggeringEntityId = trigger,
            effectHint = "presentation hint",
            requiresStructuredResponse = true,
            shape = shape,
            structuredDomain = domain,
        )
        return observation(env).copy(
            pendingDecision = pending,
            legalActions = emptyList(),
        )
    }

    fun canonicalJson(domain: CompleteLegalDomainV1): String = domain.canonicalJson()

    fun semanticPayload(type: String): JsonObject = buildJsonObject {
        put("type", type)
        if (type == "ActivateAbility") {
            put("abilityKey", buildJsonObject {
                put("origin", "test")
                put("ordinal", 0)
            })
        }
    }

    fun semanticCandidate(action: LegalActionView): JsonObject =
        ObservationCanonicalizer.semanticActionFingerprint(action)

    fun replaceJsonValue(
        objectValue: JsonObject,
        key: String,
        value: JsonElement,
    ): JsonObject = buildJsonObject {
        objectValue.forEach { (existingKey, existingValue) ->
            put(existingKey, if (existingKey == key) value else existingValue)
        }
    }

    fun removeJsonValue(
        objectValue: JsonObject,
        key: String,
    ): JsonObject = buildJsonObject {
        objectValue.forEach { (existingKey, existingValue) ->
            if (existingKey != key) put(existingKey, existingValue)
        }
    }

    fun domainJson(candidate: JsonObject): JsonObject {
        val json = Json { encodeDefaults = true; explicitNulls = true; allowStructuredMapKeys = true }
        val domain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
            candidates = listOf(candidate),
        )
        return json.parseToJsonElement(
            json.encodeToString(CompleteLegalDomainV1.serializer(), domain)
        ).jsonObject
    }

    fun decodeDomain(domain: JsonObject): CompleteLegalDomainV1 {
        val json = Json { encodeDefaults = true; explicitNulls = true; allowStructuredMapKeys = true }
        return json.decodeFromString(CompleteLegalDomainV1.serializer(), domain.toString())
    }

    fun candidateWithNested(
        action: LegalActionView,
        nestedKey: String,
        mutation: (JsonObject) -> JsonObject,
    ): JsonObject {
        val candidate = semanticCandidate(action)
        val nested = requireNotNull(candidate[nestedKey]).jsonObject
        return replaceJsonValue(candidate, nestedKey, mutation(nested))
    }

    fun paymentDomain(requiredCost: String = "{0}"): PaymentDomainV5 = PaymentDomainV5(
        requiredCost = requiredCost,
        outerAtomicCostUnits = listOf(
            AtomicManaCostUnitV1(
                symbolIndex = 0,
                unitIndexWithinSymbol = 0,
                kind = PaymentCostKindV1.GENERIC,
            )
        ),
        initialPoolBuckets = emptyList(),
        sourceActivationOptions = emptyList(),
    )

    fun targetDomain(): ActionTargetDomainV1 = ActionTargetDomainV1(
        requirements = listOf(
            TargetRequirementDomain(
                index = 0,
                description = "presentation",
                minTargets = 1,
                maxTargets = 2,
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
            )
        )
    )

    fun attackDomain(): AttackDeclarationDomainV2 = AttackDeclarationDomainV2(
        attackerOrder = listOf(EntityId("attacker-a"), EntityId("attacker-b")),
        attackerToDefenders = linkedMapOf(
            EntityId("attacker-a") to listOf(EntityId("defender-a")),
            EntityId("attacker-b") to listOf(EntityId("defender-a"), EntityId("defender-b")),
        ),
        mandatoryAttackers = listOf(EntityId("attacker-a")),
        canDeclareZeroAttackers = false,
        maxAttackers = 2,
        coAttackerRequirements = emptyMap(),
        bandConstraints = AttackBandConstraintsV1(emptyMap(), emptyMap()),
    )

    fun blockerDomain(): BlockerDeclarationDomainV1 = BlockerDeclarationDomainV1(
        blockerOrder = listOf(EntityId("blocker-a"), EntityId("blocker-b")),
        attackerOrder = listOf(EntityId("attacker-a"), EntityId("attacker-b")),
        blockerToAttackers = linkedMapOf(
            EntityId("blocker-a") to listOf(EntityId("attacker-a")),
            EntityId("blocker-b") to listOf(EntityId("attacker-b")),
        ),
        maxAttackersByBlocker = linkedMapOf(
            EntityId("blocker-a") to 1,
            EntityId("blocker-b") to 1,
        ),
        minBlockersByAttacker = emptyMap(),
        maxBlockersByAttacker = emptyMap(),
        globalMaxBlockers = null,
        coBlockerRequirements = emptyMap(),
        requirements = emptyList(),
        minimumSatisfiedRequirementCount = 0,
        canDeclareZeroBlockers = true,
    )

    test("action IDs do not change an action candidate domain or its digest") {
        val env = environment()
        val base = observation(env)
        val changed = base.copy(
            legalActions = base.legalActions.mapIndexed { index, action ->
                action.copy(actionId = index + 1000)
            }
        )

        val baseDomain = CompleteLegalDomainV1.from(base)
        baseDomain.kind shouldBe CompleteLegalDomainKind.ACTION_CANDIDATES
        baseDomain.candidates.size shouldBe base.legalActions.size
        baseDomain shouldBe CompleteLegalDomainV1.from(changed)
        CandidateDomainDigestV1.from(base).value shouldBe CandidateDomainDigestV1.from(changed).value
        canonicalJson(baseDomain).contains("actionId").shouldBeFalse()
    }

    test("an empty action domain remains distinguishable from a missing domain") {
        val env = environment()
        val empty = observation(env).copy(legalActions = emptyList())
        val domain = CompleteLegalDomainV1.from(empty)

        domain.kind shouldBe CompleteLegalDomainKind.ACTION_CANDIDATES
        domain.candidates shouldBe emptyList()
        canonicalJson(domain).contains("candidates").shouldBeTrue()
    }

    test("the domain digest does not sort a producer-owned candidate sequence") {
        val env = environment()
        val source = observation(env)
        val firstCandidate = source.legalActions.first()
        val secondCandidate = firstCandidate.copy(
            actionId = firstCandidate.actionId + 1,
            affordable = !firstCandidate.affordable,
        )
        val first = source.copy(legalActions = listOf(firstCandidate, secondCandidate))
        val reordered = first.copy(legalActions = first.legalActions.reversed())
        val firstDomain = CompleteLegalDomainV1.from(first)
        val reorderedDomain = CompleteLegalDomainV1.from(reordered)

        firstDomain.candidates shouldNotBe reorderedDomain.candidates
        firstDomain.canonicalJson() shouldNotBe reorderedDomain.canonicalJson()
        CandidateDomainDigestV1.from(firstDomain).value shouldNotBe
            CandidateDomainDigestV1.from(reorderedDomain).value
    }

    test("producer-canonical unordered candidate fields are accepted and raw order is rejected") {
        val env = environment()
        val candidate = observation(env).legalActions.first()
        val canonical = observation(env).copy(
            legalActions = listOf(candidate.copy(targetEntityIds = listOf(EntityId("a"), EntityId("b"))))
        )
        CompleteLegalDomainV1.from(canonical)

        val noncanonical = observation(env).copy(
            legalActions = listOf(candidate.copy(targetEntityIds = listOf(EntityId("b"), EntityId("a"))))
        )

        shouldThrow<IllegalArgumentException> { CompleteLegalDomainV1.from(noncanonical) }
    }

    test("persisted candidates reject noncanonical producer arrays") {
        val action = LegalActionView(
            actionId = 99,
            kind = "CastSpell",
            description = "target",
            affordable = true,
            actionSemantics = semanticPayload("CastSpell"),
            targetEntityIds = listOf(EntityId("target-a"), EntityId("target-b")),
        )
        val persisted = domainJson(semanticCandidate(action))
        val candidate = persisted["candidates"]!!.jsonArray.single().jsonObject
        val noncanonicalCandidate = replaceJsonValue(
            candidate,
            "targetEntityIds",
            buildJsonArray {
                add(JsonPrimitive("target-b"))
                add(JsonPrimitive("target-a"))
            },
        )

        shouldThrow<IllegalArgumentException> {
            decodeDomain(replaceJsonValue(
                persisted,
                "candidates",
                buildJsonArray { add(noncanonicalCandidate) },
            ))
        }
    }

    test("durable candidates require semantic action payloads") {
        val env = environment()
        val sourceCandidate = observation(env).legalActions.first()
        val missing = removeJsonValue(semanticCandidate(sourceCandidate), "actionSemantics")

        shouldThrow<IllegalArgumentException> {
            CompleteLegalDomainV1(
                kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
                candidates = listOf(missing),
            )
        }
        CompleteLegalDomainV1.from(observation(env))
    }

    test("durable action semantics reject nested decision routing IDs") {
        val env = environment()
        val action = observation(env).legalActions.first().copy(
            actionSemantics = buildJsonObject {
                put("type", "CastSpell")
                put("nested", buildJsonObject { put("decisionId", "routing-id") })
            }
        )

        shouldThrow<IllegalArgumentException> {
            CompleteLegalDomainV1.from(observation(env).copy(legalActions = listOf(action)))
        }
    }

    test("durable action semantics reject nested runtime ability IDs") {
        val env = environment()
        val action = observation(env).legalActions.first().copy(
            actionSemantics = buildJsonObject {
                put("type", "CastSpell")
                put("nested", buildJsonObject { put("abilityId", "runtime-ability-id") })
            }
        )

        shouldThrow<IllegalArgumentException> {
            CompleteLegalDomainV1.from(observation(env).copy(legalActions = listOf(action)))
        }
    }

    test("required payload fields retain the shared canonical producer order") {
        val env = environment()
        val source = observation(env)
        val candidate = source.legalActions.first().copy(
            requiresStructuredAction = true,
            requiredPayloadFields = listOf("targets", "paymentStrategy"),
        )
        val canonical = source.copy(legalActions = listOf(candidate))
        val reversed = canonical.copy(
            legalActions = listOf(candidate.copy(
                requiredPayloadFields = listOf("paymentStrategy", "targets")
            ))
        )
        val duplicate = canonical.copy(
            legalActions = listOf(candidate.copy(
                requiredPayloadFields = listOf("targets", "targets")
            ))
        )

        CompleteLegalDomainV1.from(canonical)
        shouldThrow<IllegalArgumentException> { CompleteLegalDomainV1.from(reversed) }
        shouldThrow<IllegalArgumentException> { CompleteLegalDomainV1.from(duplicate) }
    }

    test("mode-selection producer order is validated at the durable boundary") {
        val env = environment()
        val domain = ModeSelectionDomain(
            modes = listOf(
                ModeOptionDomain(index = 0, text = "zero", available = true),
                ModeOptionDomain(index = 1, text = "one", available = true),
            ),
            minModes = 1,
            maxModes = 1,
        )
        val source = structuredObservation(env, domain, PendingDecisionKind.CHOOSE_MODE)
        val reversed = source.copy(
            pendingDecision = source.pendingDecision!!.copy(
                structuredDomain = domain.copy(modes = domain.modes.reversed())
            )
        )
        val duplicate = source.copy(
            pendingDecision = source.pendingDecision!!.copy(
                structuredDomain = domain.copy(
                    modes = listOf(domain.modes.first(), domain.modes.first())
                )
            )
        )

        CompleteLegalDomainV1.from(source)
        shouldThrow<IllegalArgumentException> { CompleteLegalDomainV1.from(reversed) }
        shouldThrow<IllegalArgumentException> { CompleteLegalDomainV1.from(duplicate) }
    }

    test("persisted action candidates reject an unknown target-domain version") {
        val action = LegalActionView(
            actionId = 100,
            kind = "CastSpell",
            description = "target",
            affordable = true,
            actionSemantics = semanticPayload("CastSpell"),
            targetEntityIds = listOf(EntityId("target-a"), EntityId("target-b")),
            targetDomain = targetDomain(),
        )
        val candidate = candidateWithNested(action, "targetDomain") {
            replaceJsonValue(it, "version", JsonPrimitive(ACTION_TARGET_DOMAIN_VERSION + 1))
        }

        shouldThrow<IllegalArgumentException> {
            decodeDomain(replaceJsonValue(
                domainJson(semanticCandidate(action)),
                "candidates",
                buildJsonArray { add(candidate) },
            ))
        }
    }

    test("persisted action candidates reject an unknown attack-domain version") {
        val action = LegalActionView(
            actionId = 101,
            kind = "DeclareAttackers",
            description = "attack",
            affordable = true,
            actionSemantics = semanticPayload("DeclareAttackers"),
            attackDeclarationDomain = attackDomain(),
        )
        val candidate = candidateWithNested(action, "attackDeclarationDomain") {
            replaceJsonValue(it, "version", JsonPrimitive(ATTACK_DECLARATION_DOMAIN_V2_VERSION + 1))
        }

        shouldThrow<IllegalArgumentException> {
            decodeDomain(replaceJsonValue(
                domainJson(semanticCandidate(action)),
                "candidates",
                buildJsonArray { add(candidate) },
            ))
        }
    }

    test("persisted action candidates reject an unknown blocker-domain version") {
        val action = LegalActionView(
            actionId = 102,
            kind = "DeclareBlockers",
            description = "block",
            affordable = true,
            actionSemantics = semanticPayload("DeclareBlockers"),
            blockerDeclarationDomain = blockerDomain(),
        )
        val candidate = candidateWithNested(action, "blockerDeclarationDomain") {
            replaceJsonValue(it, "version", JsonPrimitive(BLOCKER_DECLARATION_DOMAIN_VERSION + 1))
        }

        shouldThrow<IllegalArgumentException> {
            decodeDomain(replaceJsonValue(
                domainJson(semanticCandidate(action)),
                "candidates",
                buildJsonArray { add(candidate) },
            ))
        }
    }

    test("persisted action candidates reject an unknown PaymentDomainV5 version") {
        val action = LegalActionView(
            actionId = 103,
            kind = "CastSpell",
            description = "payment",
            affordable = true,
            actionSemantics = semanticPayload("CastSpell"),
            paymentDomain = paymentDomain(),
        )
        val candidate = candidateWithNested(action, "paymentDomain") {
            replaceJsonValue(it, "version", JsonPrimitive(PAYMENT_DOMAIN_V5_VERSION + 1))
        }

        shouldThrow<IllegalArgumentException> {
            decodeDomain(replaceJsonValue(
                domainJson(semanticCandidate(action)),
                "candidates",
                buildJsonArray { add(candidate) },
            ))
        }
    }

    test("persisted action candidates reject an unknown target-payment version") {
        val action = LegalActionView(
            actionId = 104,
            kind = "ActivateAbility",
            description = "target payment",
            affordable = true,
            actionSemantics = semanticPayload("ActivateAbility"),
            targetPaymentDomain = TargetPaymentDomainV1(
                targetBindings = listOf(
                    TargetPaymentBindingV1(
                        target = EntityId("target-a"),
                        affordable = true,
                        paymentDomain = paymentDomain(),
                    )
                )
            ),
        )
        val candidate = candidateWithNested(action, "targetPaymentDomain") {
            replaceJsonValue(it, "version", JsonPrimitive(TARGET_PAYMENT_DOMAIN_V1_VERSION + 1))
        }

        shouldThrow<IllegalArgumentException> {
            decodeDomain(replaceJsonValue(
                domainJson(semanticCandidate(action)),
                "candidates",
                buildJsonArray { add(candidate) },
            ))
        }
    }

    test("persisted attack candidates reject a missing required relation map") {
        val action = LegalActionView(
            actionId = 105,
            kind = "DeclareAttackers",
            description = "attack",
            affordable = true,
            actionSemantics = semanticPayload("DeclareAttackers"),
            attackDeclarationDomain = attackDomain(),
        )
        val candidate = candidateWithNested(action, "attackDeclarationDomain") {
            removeJsonValue(it, "attackerToDefenders")
        }

        shouldThrow<IllegalArgumentException> {
            decodeDomain(replaceJsonValue(
                domainJson(semanticCandidate(action)),
                "candidates",
                buildJsonArray { add(candidate) },
            ))
        }
    }

    test("persisted blocker candidates reject a missing required relation map") {
        val action = LegalActionView(
            actionId = 106,
            kind = "DeclareBlockers",
            description = "block",
            affordable = true,
            actionSemantics = semanticPayload("DeclareBlockers"),
            blockerDeclarationDomain = blockerDomain(),
        )
        val candidate = candidateWithNested(action, "blockerDeclarationDomain") {
            removeJsonValue(it, "blockerToAttackers")
        }

        shouldThrow<IllegalArgumentException> {
            decodeDomain(replaceJsonValue(
                domainJson(semanticCandidate(action)),
                "candidates",
                buildJsonArray { add(candidate) },
            ))
        }
    }

    test("persisted blocker candidates reject duplicate relation members") {
        val action = LegalActionView(
            actionId = 107,
            kind = "DeclareBlockers",
            description = "block",
            affordable = true,
            actionSemantics = semanticPayload("DeclareBlockers"),
            blockerDeclarationDomain = blockerDomain(),
        )
        val candidate = candidateWithNested(action, "blockerDeclarationDomain") { domain ->
            val relations = requireNotNull(domain["blockerToAttackers"]).jsonObject
            val malformedRelations = replaceJsonValue(
                relations,
                "blocker-a",
                buildJsonArray {
                    add(JsonPrimitive("attacker-a"))
                    add(JsonPrimitive("attacker-a"))
                },
            )
            replaceJsonValue(domain, "blockerToAttackers", malformedRelations)
        }

        shouldThrow<IllegalArgumentException> {
            decodeDomain(replaceJsonValue(
                domainJson(semanticCandidate(action)),
                "candidates",
                buildJsonArray { add(candidate) },
            ))
        }
    }

    test("target-domain semantic payload changes the digest and raw candidate order fails closed") {
        val env = environment()
        val action = LegalActionView(
            actionId = 108,
            kind = "CastSpell",
            description = "target",
            affordable = true,
            actionSemantics = semanticPayload("CastSpell"),
            targetEntityIds = listOf(EntityId("target-a"), EntityId("target-b")),
            targetDomain = targetDomain(),
        )
        val changed = action.copy(
            targetDomain = targetDomain().copy(
                requirements = listOf(targetDomain().requirements.single().copy(maxTargets = 1))
            )
        )
        val reversed = action.copy(
            targetDomain = targetDomain().copy(
                requirements = listOf(
                    targetDomain().requirements.single().copy(
                        candidates = listOf(EntityId("target-b"), EntityId("target-a"))
                    )
                )
            )
        )

        CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(action))).value shouldNotBe
            CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(changed))).value
        shouldThrow<IllegalArgumentException> {
            CompleteLegalDomainV1.from(observation(env).copy(legalActions = listOf(reversed)))
        }
    }

    test("attack-domain producer order is retained in the digest") {
        val env = environment()
        val action = LegalActionView(
            actionId = 109,
            kind = "DeclareAttackers",
            description = "attack",
            affordable = true,
            actionSemantics = semanticPayload("DeclareAttackers"),
            attackDeclarationDomain = attackDomain(),
        )
        val reversed = action.copy(
            attackDeclarationDomain = attackDomain().copy(
                attackerOrder = attackDomain().attackerOrder.reversed()
            )
        )

        CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(action))).value shouldNotBe
            CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(reversed))).value
    }

    test("blocker-domain producer order is retained in the digest") {
        val env = environment()
        val action = LegalActionView(
            actionId = 110,
            kind = "DeclareBlockers",
            description = "block",
            affordable = true,
            actionSemantics = semanticPayload("DeclareBlockers"),
            blockerDeclarationDomain = blockerDomain(),
        )
        val reversed = action.copy(
            blockerDeclarationDomain = blockerDomain().copy(
                blockerOrder = blockerDomain().blockerOrder.reversed(),
                blockerToAttackers = linkedMapOf(
                    EntityId("blocker-b") to listOf(EntityId("attacker-b")),
                    EntityId("blocker-a") to listOf(EntityId("attacker-a")),
                ),
                maxAttackersByBlocker = linkedMapOf(
                    EntityId("blocker-b") to 1,
                    EntityId("blocker-a") to 1,
                ),
            )
        )

        CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(action))).value shouldNotBe
            CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(reversed))).value
    }

    test("PaymentDomainV5 semantic payload is digest-bound") {
        val env = environment()
        val action = LegalActionView(
            actionId = 111,
            kind = "CastSpell",
            description = "payment",
            affordable = true,
            actionSemantics = semanticPayload("CastSpell"),
            paymentDomain = paymentDomain("{0}"),
        )
        val changed = action.copy(paymentDomain = paymentDomain("{1}"))

        CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(action))).value shouldNotBe
            CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(changed))).value
    }

    test("PaymentDomainV5 source-option producer order is retained") {
        val env = environment()
        fun source(sourceId: String, abilityKey: String): PaymentSourceActivationDomainV2 =
            PaymentSourceActivationDomainV2(
                sourceId = EntityId(sourceId),
                sourceName = sourceId,
                manaAbilityKey = abilityKey,
                productionChoices = listOf(ProductionChoice(PaymentManaColor.GREEN)),
                atomicActivationManaCostUnits = emptyList(),
                activationSupportKind = PaymentActivationSupportKindV1.FixedManaAndTapSelf,
                deterministicNonManaCosts = listOf(PaymentDeterministicNonManaCostKindV1.TapSelf),
                activationCostOrderOptions = listOf(
                    listOf(ActivationCostComponentRefV1.DeterministicNonManaComponent(0))
                ),
            )
        val domain = paymentDomain().copy(
            sourceActivationOptions = listOf(
                source("source-a", "ability-a"),
                source("source-b", "ability-b"),
            )
        )
        val action = LegalActionView(
            actionId = 115,
            kind = "CastSpell",
            description = "ordered payment sources",
            affordable = true,
            actionSemantics = semanticPayload("CastSpell"),
            paymentDomain = domain,
        )
        val reordered = action.copy(
            paymentDomain = domain.copy(sourceActivationOptions = domain.sourceActivationOptions.reversed())
        )

        CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(action))).value shouldNotBe
            CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(reordered))).value
    }

    test("target-payment binding payload is digest-bound") {
        val env = environment()
        val action = LegalActionView(
            actionId = 112,
            kind = "ActivateAbility",
            description = "target payment",
            affordable = true,
            actionSemantics = semanticPayload("ActivateAbility"),
            targetPaymentDomain = TargetPaymentDomainV1(
                targetBindings = listOf(
                    TargetPaymentBindingV1(
                        target = EntityId("target-a"),
                        affordable = true,
                        paymentDomain = paymentDomain(),
                    ),
                    TargetPaymentBindingV1(
                        target = EntityId("target-b"),
                        affordable = false,
                        paymentDomain = paymentDomain("{1}"),
                    )
                )
            ),
        )
        val changed = action.copy(
            targetPaymentDomain = TargetPaymentDomainV1(
                targetBindings = listOf(
                    TargetPaymentBindingV1(
                        target = EntityId("target-a"),
                        affordable = false,
                        paymentDomain = paymentDomain(),
                    ),
                    TargetPaymentBindingV1(
                        target = EntityId("target-b"),
                        affordable = false,
                        paymentDomain = paymentDomain("{1}"),
                    ),
                )
            )
        )
        val reordered = action.copy(
            targetPaymentDomain = TargetPaymentDomainV1(
                targetBindings = action.targetPaymentDomain!!.targetBindings.reversed()
            )
        )

        CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(action))).value shouldNotBe
            CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(changed))).value
        CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(action))).value shouldNotBe
            CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(reordered))).value
    }

    test("mana-color producer order is validated rather than repaired") {
        val env = environment()
        val canonical = observation(env).copy(
            legalActions = listOf(
                LegalActionView(
                    actionId = 113,
                    kind = "ActivateAbility",
                    description = "color",
                    affordable = true,
                    actionSemantics = semanticPayload("ActivateAbility"),
                    availableManaColors = listOf(Color.RED, Color.GREEN),
                )
            )
        )
        val noncanonical = canonical.copy(
            legalActions = listOf(canonical.legalActions.single().copy(
                availableManaColors = listOf(Color.GREEN, Color.RED)
            ))
        )

        CompleteLegalDomainV1.from(canonical)
        shouldThrow<IllegalArgumentException> { CompleteLegalDomainV1.from(noncanonical) }
    }

    test("X-cost semantic payload is digest-bound") {
        val env = environment()
        val action = LegalActionView(
            actionId = 114,
            kind = "CastSpell",
            description = "X spell",
            affordable = true,
            actionSemantics = semanticPayload("CastSpell"),
            hasXCost = true,
            maxAffordableX = 2,
        )
        val changed = action.copy(maxAffordableX = 3)

        CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(action))).value shouldNotBe
            CandidateDomainDigestV1.from(observation(env).copy(legalActions = listOf(changed))).value
    }

    test("card-selection domain retains semantic options and rejects raw noncanonical order") {
        val env = environment()
        val cardA = EntityId("card-a")
        val cardB = EntityId("card-b")
        val domain = CardSelectionDomain(
            options = listOf(cardA, cardB),
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
        )
        val source = structuredObservation(env, domain, PendingDecisionKind.SELECT_CARDS)
        val changed = source.copy(
            pendingDecision = source.pendingDecision!!.copy(
                structuredDomain = domain.copy(minSelections = 0)
            )
        )
        val reversed = source.copy(
            pendingDecision = source.pendingDecision!!.copy(
                structuredDomain = domain.copy(options = listOf(cardB, cardA))
            )
        )

        CandidateDomainDigestV1.from(source).value shouldNotBe CandidateDomainDigestV1.from(changed).value
        shouldThrow<IllegalArgumentException> { CompleteLegalDomainV1.from(reversed) }
    }

    test("combat-resolution domain semantic payload is digest-bound") {
        val env = environment()
        val attacker = CombatAttackerDomain(
            id = EntityId("attacker-a"),
            name = "attacker",
            power = 2,
            toughness = 2,
            hasTrample = false,
            hasDeathtouch = false,
            hasFirstStrike = false,
            hasDoubleStrike = false,
            dealsDamageThisStep = true,
            bandId = null,
            attackedDefenderId = EntityId("defender-a"),
            blockedByIds = emptyList(),
            markedDamage = 0,
        )
        val domain = CombatResolutionDomain(
            firstStrike = false,
            attackers = listOf(attacker, attacker.copy(id = EntityId("attacker-b"))),
            blockers = emptyList(),
            defenders = emptyList(),
            edges = emptyList(),
            coChooserId = null,
        )
        val source = structuredObservation(env, domain, PendingDecisionKind.COMBAT_RESOLUTION)
        val changed = source.copy(
            pendingDecision = source.pendingDecision!!.copy(
                structuredDomain = domain.copy(firstStrike = true)
            )
        )
        val reversed = source.copy(
            pendingDecision = source.pendingDecision!!.copy(
                structuredDomain = domain.copy(attackers = domain.attackers.reversed())
            )
        )

        CandidateDomainDigestV1.from(source).value shouldNotBe CandidateDomainDigestV1.from(changed).value
        shouldThrow<IllegalArgumentException> { CompleteLegalDomainV1.from(reversed) }
    }

    test("replacement domain relation payload is digest-bound") {
        val env = environment()
        val domain = ReplacementDomain(
            fromOptions = listOf("from-a", "from-b"),
            toOptions = listOf("to-a", "to-b"),
            fromMetadata = listOf(OptionMetadataDomain(null, null, null, null), OptionMetadataDomain(null, null, null, null)),
            toMetadata = listOf(OptionMetadataDomain(null, null, null, null), OptionMetadataDomain(null, null, null, null)),
            allowedToByFrom = listOf(listOf(0), listOf(1)),
            defaultFromIndex = 0,
        )
        val source = structuredObservation(env, domain, PendingDecisionKind.CHOOSE_REPLACEMENT)
        val changed = source.copy(
            pendingDecision = source.pendingDecision!!.copy(
                structuredDomain = domain.copy(allowedToByFrom = listOf(listOf(0, 1), listOf(1)))
            )
        )

        CandidateDomainDigestV1.from(source).value shouldNotBe CandidateDomainDigestV1.from(changed).value
    }

    test("Rules-significant structured producer order changes the domain digest") {
        val env = environment()
        val first = structuredObservation(
            env,
            ReorderLibraryDomain(
                cards = listOf(EntityId("top"), EntityId("bottom")),
                cardInfo = emptyMap(),
            )
        )
        val second = first.copy(
            pendingDecision = first.pendingDecision!!.copy(
                structuredDomain = ReorderLibraryDomain(
                    cards = listOf(EntityId("bottom"), EntityId("top")),
                    cardInfo = emptyMap(),
                )
            )
        )

        CandidateDomainDigestV1.from(first).value shouldNotBe CandidateDomainDigestV1.from(second).value
    }

    test("semantic candidate payload changes the domain digest") {
        val env = environment()
        val base = observation(env)
        val candidateIndex = base.legalActions.indexOfFirst { it.actionSemantics != null }
        candidateIndex shouldNotBe -1
        val candidate = base.legalActions[candidateIndex]
        val changedSemantics = buildJsonObject {
            checkNotNull(candidate.actionSemantics).forEach { (key, value) -> put(key, value) }
            put("type", JsonPrimitive("CandidatePayloadVariant"))
        }
        val changed = base.copy(
            legalActions = base.legalActions.mapIndexed { index, action ->
                if (index == candidateIndex) action.copy(actionSemantics = changedSemantics) else action
            }
        )

        CandidateDomainDigestV1.from(base).value shouldNotBe CandidateDomainDigestV1.from(changed).value
    }

    test("presentation-only candidate descriptions do not change the domain digest") {
        val env = environment()
        val base = observation(env)
        val changed = base.copy(
            legalActions = base.legalActions.map { it.copy(description = "different description") }
        )

        CandidateDomainDigestV1.from(base).value shouldBe CandidateDomainDigestV1.from(changed).value
    }

    test("duplicate semantic candidates are rejected instead of deduplicated") {
        val env = environment()
        val candidate = observation(env).legalActions.first()
        val duplicate = candidate.copy(actionId = candidate.actionId + 100)
        val observation = observation(env).copy(legalActions = listOf(candidate, duplicate))

        shouldThrow<IllegalArgumentException> { CompleteLegalDomainV1.from(observation) }
    }

    test("unknown complete-domain versions fail closed") {
        val env = environment()
        val domain = CompleteLegalDomainV1.from(observation(env))
        val json = Json { encodeDefaults = true; explicitNulls = true; allowStructuredMapKeys = true }
        val encoded = json.encodeToString(CompleteLegalDomainV1.serializer(), domain)
        val unknownVersion = buildJsonObject {
            json.parseToJsonElement(encoded).jsonObject.forEach { (key, value) -> put(key, value) }
            put("version", JsonPrimitive(COMPLETE_LEGAL_DOMAIN_VERSION + 1))
        }.toString()

        shouldThrow<IllegalArgumentException> {
            json.decodeFromString(CompleteLegalDomainV1.serializer(), unknownVersion)
        }
    }

    test("unknown candidate-digest versions fail closed") {
        val digest = CandidateDomainDigestV1(
            version = CANDIDATE_DOMAIN_DIGEST_VERSION,
            schemaIdentity = CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY,
            value = "0".repeat(64),
        )
        val json = Json { encodeDefaults = true; explicitNulls = true }
        val encoded = json.encodeToString(CandidateDomainDigestV1.serializer(), digest)
        val unknownVersion = buildJsonObject {
            json.parseToJsonElement(encoded).jsonObject.forEach { (key, value) -> put(key, value) }
            put("version", JsonPrimitive(CANDIDATE_DOMAIN_DIGEST_VERSION + 1))
        }.toString()

        shouldThrow<IllegalArgumentException> {
            json.decodeFromString(CandidateDomainDigestV1.serializer(), unknownVersion)
        }
    }

    test("structured-domain semantic changes alter the domain digest") {
        val env = environment()
        val first = structuredObservation(
            env,
            ModeSelectionDomain(
                modes = listOf(ModeOptionDomain(index = 0, text = "same", available = true)),
                minModes = 1,
                maxModes = 1,
            ),
            kind = PendingDecisionKind.CHOOSE_MODE,
        )
        val second = first.copy(
            pendingDecision = first.pendingDecision!!.copy(
                structuredDomain = ModeSelectionDomain(
                    modes = listOf(ModeOptionDomain(index = 0, text = "same", available = false)),
                    minModes = 1,
                    maxModes = 1,
                )
            )
        )

        CandidateDomainDigestV1.from(first).value shouldNotBe CandidateDomainDigestV1.from(second).value
    }

    test("missing structured domain for an acting structured decision fails closed") {
        val env = environment()
        val missing = structuredObservation(env, domain = null)

        shouldThrow<IllegalArgumentException> { CompleteLegalDomainV1.from(missing) }
    }

    test("folded decision options retain a complete candidate domain") {
        val env = environment()
        val pending = PendingDecisionView(
            decisionId = "folded-routing-id",
            kind = PendingDecisionKind.YES_NO,
            playerId = env.playerIds.first(),
            prompt = "presentation prompt",
            requiresStructuredResponse = false,
            shape = DecisionShape(),
        )
        val base = observation(env).copy(
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
        val domain = CompleteLegalDomainV1.from(base)

        domain.kind shouldBe CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS
        domain.candidates.size shouldBe 2
        canonicalJson(domain).contains("decisionId").shouldBeFalse()
    }

    test("structured decisions retain the typed public domain without legal-action duplication") {
        val env = environment()
        val source = structuredObservation(
            env,
            ModeSelectionDomain(
                modes = listOf(ModeOptionDomain(index = 0, text = "yes", available = true)),
                minModes = 1,
                maxModes = 1,
            ),
            kind = PendingDecisionKind.CHOOSE_MODE,
        )
        val domain = CompleteLegalDomainV1.from(source)

        domain.kind shouldBe CompleteLegalDomainKind.STRUCTURED_DECISION
        domain.structuredDomain.shouldNotBeNull()
        val canonical = canonicalJson(domain)
        canonical.contains("legalActions").shouldBeFalse()
        canonical.contains("text").shouldBeFalse()
        canonical.contains("description").shouldBeFalse()
        PlayerObservationV1.from(source).canonicalJson().contains("structuredDomain").shouldBeFalse()
    }

    test("existing StateDigest and TrainingObservation semantics remain unchanged") {
        val env = environment()
        val source = observation(env)

        StateDigest.compute(source) shouldBe source.stateDigest
        ObservationCanonicalizer.wireJson(source).contains("legalActions").shouldBeTrue()
    }
})
