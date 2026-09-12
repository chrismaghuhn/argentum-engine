package com.wingedsheep.gym.trainer.learner

import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.core.AtomicManaCostUnitV1
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.InitialPoolBucketV1
import com.wingedsheep.engine.core.PaymentCostKindV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.EpisodeInterruptionReason
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.CandidateDomainDigestV1
import com.wingedsheep.gym.contract.ChosenSemanticActionV1
import com.wingedsheep.gym.contract.CardSelectionDomain
import com.wingedsheep.gym.contract.CombatAttackerDomain
import com.wingedsheep.gym.contract.CombatBlockerDomain
import com.wingedsheep.gym.contract.CombatDamageDirection
import com.wingedsheep.gym.contract.CombatDefenderDomain
import com.wingedsheep.gym.contract.CombatTargetKind
import com.wingedsheep.gym.contract.ConditionalSelectionMinimumDomain
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.CombatResolutionDomain
import com.wingedsheep.gym.contract.DecisionShape
import com.wingedsheep.gym.contract.DistributionDomain
import com.wingedsheep.gym.contract.EntityFeatures
import com.wingedsheep.gym.contract.ManaPoolView
import com.wingedsheep.gym.contract.ModeSelectionDomain
import com.wingedsheep.gym.contract.ModeOptionDomain
import com.wingedsheep.gym.contract.ManaSourcesDomain
import com.wingedsheep.gym.contract.OrderingDomain
import com.wingedsheep.gym.contract.PaymentActivationSupportKindV1
import com.wingedsheep.gym.contract.PaymentDeterministicNonManaCostKindV1
import com.wingedsheep.gym.contract.PaymentSourceActivationDomainV2
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.PlayerObservationPendingDecisionV1
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.gym.contract.PlayerView
import com.wingedsheep.gym.contract.ReorderLibraryDomain
import com.wingedsheep.gym.contract.ReplacementDomain
import com.wingedsheep.gym.contract.SchemaHash
import com.wingedsheep.gym.contract.SearchLibraryDomain
import com.wingedsheep.gym.contract.SemanticDecisionKindV1
import com.wingedsheep.gym.contract.StackItemKind
import com.wingedsheep.gym.contract.StackItemView
import com.wingedsheep.gym.contract.StructuredCardInfo
import com.wingedsheep.gym.contract.SplitPilesDomain
import com.wingedsheep.gym.contract.StructuredDecisionDomain
import com.wingedsheep.gym.contract.TargetRequirementDomain
import com.wingedsheep.gym.contract.TargetsDomain
import com.wingedsheep.gym.contract.BudgetModalDomain
import com.wingedsheep.gym.contract.PaymentDomainV5
import com.wingedsheep.gym.contract.ZoneView
import com.wingedsheep.gym.trainer.trajectory.CompactReplayLinkV1
import com.wingedsheep.gym.trainer.trajectory.DecisionRecordV1
import com.wingedsheep.gym.trainer.trajectory.EpisodeMetadataV1
import com.wingedsheep.gym.trainer.trajectory.EnvironmentIdentityV1
import com.wingedsheep.gym.trainer.trajectory.PolicyProvenanceV1
import com.wingedsheep.gym.trainer.trajectory.RosterSeatV1
import com.wingedsheep.gym.trainer.trajectory.SemanticDecisionIdV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

class C1ModelFacingProjectionV1Test : FunSpec({
    test("changing only the chosen action leaves canonical input bytes unchanged") {
        val first = fixture(chosenCandidateIndex = 0)
        val second = fixture(chosenCandidateIndex = 1)
        val context = C1ProjectionContext(
            datasetId = "b".repeat(64),
            sourceManifestContentDigest = "c".repeat(64),
        )

        val firstSample = C1ModelFacingProjectionV1.project(
            trajectory = first.trajectory,
            record = first.record,
            context = context,
            partition = C1DatasetPartition.TRAIN,
        )
        val secondSample = C1ModelFacingProjectionV1.project(
            trajectory = second.trajectory,
            record = second.record,
            context = context,
            partition = C1DatasetPartition.TRAIN,
        )

        A3SemanticJson.canonicalJson(firstSample.input) shouldBe
            A3SemanticJson.canonicalJson(secondSample.input)
        A3SemanticJson.canonicalJson(
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1DerivedTargetChannel.serializer(),
                firstSample.target,
            ),
        ) shouldNotBe A3SemanticJson.canonicalJson(
            A3SemanticJson.strictJson.encodeToJsonElement(
                C1DerivedTargetChannel.serializer(),
                secondSample.target,
            ),
        )
    }

    test("rejects a decision record owned by another trajectory") {
        val owner = fixture(chosenCandidateIndex = 0)
        val foreign = fixture(chosenCandidateIndex = 1)

        shouldThrow<IllegalArgumentException> {
            C1ModelFacingProjectionV1.project(
                trajectory = owner.trajectory,
                record = foreign.record,
                context = C1ProjectionContext("b".repeat(64), "c".repeat(64)),
                partition = C1DatasetPartition.TRAIN,
            )
        }
    }

    test("keeps raw IDs out of input and feature views") {
        val source = fixture(chosenCandidateIndex = 0)
        val sample = C1ModelFacingProjectionV1.project(
            trajectory = source.trajectory,
            record = source.record,
            context = C1ProjectionContext("b".repeat(64), "c".repeat(64)),
            partition = C1DatasetPartition.TRAIN,
        )
        val input = A3SemanticJson.canonicalJson(sample.input)

        input shouldNotContain "entity-raw-source"
        input shouldNotContain "actionId"
        input shouldNotContain "decisionId"
        input shouldNotContain "policySeed"
        input shouldNotContain "collectionJobId"
        input shouldNotContain "trajectoryId"
        input shouldNotContain C1_MODEL_FACING_CONTRACT_IDENTITY
        sample.binding.completeLegalDomain.toString() shouldBe sample.binding.completeLegalDomain.toString()
    }

    test("retains every flat candidate and exact selected binding") {
        val source = fixture(chosenCandidateIndex = 1)
        val sample = C1ModelFacingProjectionV1.project(
            trajectory = source.trajectory,
            record = source.record,
            context = C1ProjectionContext("b".repeat(64), "c".repeat(64)),
            partition = C1DatasetPartition.TRAIN,
        )
        val candidates = sample.binding.completeLegalDomain["candidates"]
            ?.let { requireNotNull(it) }
            ?: error("missing complete candidates")

        candidates.toString().contains("entity-raw-source") shouldBe true
        sample.binding.sourceBindingOrdinals shouldBe listOf(0, 1)
        sample.binding.semanticTieDiscriminators.keys shouldBe setOf("0", "1")
        A3SemanticJson.canonicalJson(sample.target.chosenSemanticAction!!).also { chosen ->
            A3SemanticJson.canonicalJson(sample.binding.selectedExactSourceBinding) shouldBe chosen
        }
    }

    test("filters raw action semantics without breaking symmetric candidates") {
        val candidates = listOf(
            candidate(
                kind = "PlayLand",
                sourceEntityId = "entity-a",
                actionSemantics = buildJsonObject {
                    put("type", "PlayLand")
                    put("playerId", "self")
                    put("cardId", "entity-a")
                    put("targets", buildJsonArray {
                        add(buildJsonObject { put("targetId", "target-a") })
                    })
                },
            ),
            candidate(
                kind = "PlayLand",
                sourceEntityId = "entity-b",
                actionSemantics = buildJsonObject {
                    put("type", "PlayLand")
                    put("playerId", "self")
                    put("cardId", "entity-b")
                    put("targets", buildJsonArray {
                        add(buildJsonObject { put("targetId", "target-b") })
                    })
                },
            ),
        )
        val first = fixture(0, candidateList = candidates)
        val second = fixture(1, candidateList = candidates)
        val context = C1ProjectionContext("b".repeat(64), "c".repeat(64))
        val firstSample = C1ModelFacingProjectionV1.project(
            first.trajectory,
            first.record,
            context,
            C1DatasetPartition.TRAIN,
        )
        val secondSample = C1ModelFacingProjectionV1.project(
            second.trajectory,
            second.record,
            context,
            C1DatasetPartition.TRAIN,
        )

        A3SemanticJson.canonicalJson(firstSample.input) shouldBe
            A3SemanticJson.canonicalJson(secondSample.input)
        A3SemanticJson.canonicalJson(firstSample.input) shouldNotContain "cardId"
        A3SemanticJson.canonicalJson(firstSample.input) shouldNotContain "player-a"
        A3SemanticJson.canonicalJson(firstSample.input) shouldNotContain "target-a"
        firstSample.binding.entityAliasBindings.any { it.sourceEntityId == "entity-a" } shouldBe true
        secondSample.binding.entityAliasBindings.any { it.sourceEntityId == "entity-b" } shouldBe true
        firstSample.binding.semanticTieDiscriminators shouldBe emptyMap()
    }

    test("uses one injective inverse alias table across observation and domain relations") {
        val self = EntityId("self")
        val opponent = EntityId("opponent")
        val source = fixture(
            chosenCandidateIndex = 0,
            candidateList = listOf(
                candidate(
                    kind = "PlayLand",
                    sourceEntityId = "visible-card",
                    targetEntityIds = listOf("visible-card"),
                ),
            ),
            observationOverride = richObservation(self, opponent),
        )
        val sample = C1ModelFacingProjectionV1.project(
            source.trajectory,
            source.record,
            C1ProjectionContext("b".repeat(64), "c".repeat(64)),
            C1DatasetPartition.TRAIN,
        )
        val alias = sample.binding.entityAliasBindings
            .single { it.sourceEntityId == "visible-card" }
            .alias
        sample.binding.entityAliasBindings.map { it.alias }.distinct().size shouldBe
            sample.binding.entityAliasBindings.size
        val input = A3SemanticJson.canonicalJson(sample.input)
        input shouldContain "\"entityAlias\":\"$alias\""
        input shouldContain "\"targetEntityAliases\":[\"$alias\"]"
        val opponentAlias = sample.binding.entityAliasBindings
            .single { it.sourceEntityId == opponent.value }
            .alias
        input shouldContain "\"entityAlias\":\"$opponentAlias\""
        input shouldContain "\"targetAliases\":[\"$opponentAlias\"]"
        input shouldNotContain "visible-card"
    }

    test("accepts current ActivateAbility and folded semantic producer fields") {
        val activate = fixture(
            chosenCandidateIndex = 0,
            candidateList = listOf(
                candidate(
                    kind = "ActivateAbility",
                    sourceEntityId = "ability-source",
                    actionSemantics = buildJsonObject {
                        put("type", "ActivateAbility")
                        put("playerId", "self")
                        put("sourceId", "ability-source")
                        put("abilityKey", buildJsonObject {
                            put("origin", "printed")
                            put("ordinal", 0)
                            put("cardDefinitionId", "CARD_DEF")
                            put("ability", buildJsonObject { put("type", "binding-only") })
                        })
                    },
                ),
            ),
        )
        val activateSample = C1ModelFacingProjectionV1.project(
            activate.trajectory,
            activate.record,
            C1ProjectionContext("b".repeat(64), "c".repeat(64)),
            C1DatasetPartition.TRAIN,
        )
        val activateInput = A3SemanticJson.canonicalJson(activateSample.input)
        activateInput shouldContain "abilityKey"
        activateInput shouldContain "ordinalRelation"
        activateInput shouldContain "cardDefinitionId"
        activateInput shouldNotContain "binding-only"
        activateInput shouldNotContain "ability-source"

        val folded = fixture(
            chosenCandidateIndex = 0,
            candidateList = listOf(
                candidate(
                    kind = "DECISION",
                    sourceEntityId = "folded-source",
                    actionSemantics = buildJsonObject {
                        put("type", "OptionChosenResponse")
                        put("optionIndex", 1)
                        put("optionMetadata", buildJsonObject {
                            put("id", "metadata-id")
                            put("description", "presentation")
                            put("iconKey", "icon")
                            put("triggeringPlayerId", "opponent")
                        })
                    },
                ),
            ),
        )
        val foldedSample = C1ModelFacingProjectionV1.project(
            folded.trajectory,
            folded.record,
            C1ProjectionContext("b".repeat(64), "c".repeat(64)),
            C1DatasetPartition.TRAIN,
        )
        val foldedInput = A3SemanticJson.canonicalJson(foldedSample.input)
        foldedInput shouldContain "optionIndex"
        foldedInput shouldContain "triggeringPlayerRole"
        foldedInput shouldNotContain "metadata-id"
        foldedInput shouldNotContain "presentation"
    }

    test("retains fixed CastSpell mode and target-slot semantics") {
        val candidates = listOf(
            candidate(
                kind = "CastSpellMode",
                sourceEntityId = "spell-source",
                actionSemantics = buildJsonObject {
                    put("type", "CastSpell")
                    put("playerId", "self")
                    put("cardId", "spell-source")
                    put("chosenModes", buildJsonArray {
                        add(JsonPrimitive(0))
                        add(JsonPrimitive(1))
                    })
                    put("modeTargetsOrdered", buildJsonArray {
                        add(buildJsonArray {
                            add(buildJsonObject {
                                put("type", "Permanent")
                                put("entityId", "target-a")
                            })
                        })
                        add(buildJsonArray {
                            add(buildJsonObject {
                                put("type", "Permanent")
                                put("entityId", "target-b")
                            })
                        })
                    })
                    put("graveyardLifeCost", 2)
                },
            ),
            candidate(
                kind = "CastSpellMode",
                sourceEntityId = "spell-source",
                actionSemantics = buildJsonObject {
                    put("type", "CastSpell")
                    put("playerId", "self")
                    put("cardId", "spell-source")
                    put("chosenModes", buildJsonArray {
                        add(JsonPrimitive(1))
                        add(JsonPrimitive(0))
                    })
                    put("modeTargetsOrdered", buildJsonArray {
                        add(buildJsonArray {
                            add(buildJsonObject {
                                put("type", "Permanent")
                                put("entityId", "target-b")
                            })
                        })
                        add(buildJsonArray {
                            add(buildJsonObject {
                                put("type", "Permanent")
                                put("entityId", "target-a")
                            })
                        })
                    })
                    put("graveyardLifeCost", 2)
                },
            ),
        )
        val source = fixture(chosenCandidateIndex = 0, candidateList = candidates)
        val sample = C1ModelFacingProjectionV1.project(
            source.trajectory,
            source.record,
            C1ProjectionContext("b".repeat(64), "c".repeat(64)),
            C1DatasetPartition.TRAIN,
        )
        val input = A3SemanticJson.canonicalJson(sample.input)
        input shouldContain "modeSlots"
        input shouldContain "modeTargetSlots"
        input shouldContain "graveyardLifeCost"
        input shouldContain "\"modeIndex\":0"
        input shouldContain "\"modeIndex\":1"
        input shouldNotContain "target-a"
        input shouldNotContain "target-b"
    }

    test("rejects unknown and non-inert action-semantic fields") {
        val invalidSemantics = listOf(
            buildJsonObject {
                put("type", "PlayLand")
                put("preResolvedWebSlingReturnedManaValue", 7)
            },
            buildJsonObject {
                put("type", "PlayLand")
                put("futureInternalMarker", true)
            },
        )

        invalidSemantics.forEach { semantics ->
            val source = fixture(
                chosenCandidateIndex = 0,
                candidateList = listOf(
                    candidate(
                        kind = "PlayLand",
                        sourceEntityId = "entity-source",
                        actionSemantics = semantics,
                    ),
                ),
            )
            shouldThrow<IllegalArgumentException> {
                C1ModelFacingProjectionV1.project(
                    source.trajectory,
                    source.record,
                    C1ProjectionContext("b".repeat(64), "c".repeat(64)),
                    C1DatasetPartition.TRAIN,
                )
            }
        }
    }

    test("rejects terminal observations and unknown player identities") {
        val self = EntityId("self")
        val opponent = EntityId("opponent")
        val terminal = fixture(
            chosenCandidateIndex = 0,
            observationOverride = richObservation(self, opponent).copy(terminated = true),
        )
        shouldThrow<IllegalArgumentException> {
            C1ModelFacingProjectionV1.project(
                terminal.trajectory,
                terminal.record,
                C1ProjectionContext("b".repeat(64), "c".repeat(64)),
                C1DatasetPartition.TRAIN,
            )
        }

        val unknownPlayer = fixture(
            chosenCandidateIndex = 0,
            observationOverride = richObservation(self, opponent).copy(
                activePlayerId = EntityId("unknown-player"),
            ),
        )
        shouldThrow<IllegalArgumentException> {
            C1ModelFacingProjectionV1.project(
                unknownPlayer.trajectory,
                unknownPlayer.record,
                C1ProjectionContext("b".repeat(64), "c".repeat(64)),
                C1DatasetPartition.TRAIN,
            )
        }
    }

    test("projects observation feature groups without raw entity IDs") {
        val self = EntityId("self")
        val opponent = EntityId("opponent")
        val source = fixture(
            chosenCandidateIndex = 0,
            observationOverride = richObservation(self, opponent),
        )
        val sample = C1ModelFacingProjectionV1.project(
            source.trajectory,
            source.record,
            C1ProjectionContext("b".repeat(64), "c".repeat(64)),
            C1DatasetPartition.TRAIN,
        )
        val input = A3SemanticJson.canonicalJson(sample.input)

        input shouldContain "players"
        input shouldContain "zones"
        input shouldContain "stack"
        input shouldContain "pendingDecision"
        input shouldContain "lifeTotal"
        input shouldContain "manaPool"
        input shouldContain "cardDefinitionId"
        input shouldContain "oracleText"
        input shouldContain "counters"
        input shouldNotContain "visible-card"
        input shouldNotContain "stack-object"
    }

    test("omits generated face-down placeholder names") {
        val self = EntityId("self")
        val opponent = EntityId("opponent")
        val observation = richObservation(self, opponent).copy(
            zones = richObservation(self, opponent).zones.map { zone ->
                zone.copy(
                    cards = zone.cards.map { card ->
                        card.copy(
                            cardDefinitionId = null,
                            name = "Face-down permanent",
                            faceDown = true,
                        )
                    },
                )
            },
        )
        val source = fixture(chosenCandidateIndex = 0, observationOverride = observation)
        val sample = C1ModelFacingProjectionV1.project(
            source.trajectory,
            source.record,
            C1ProjectionContext("b".repeat(64), "c".repeat(64)),
            C1DatasetPartition.TRAIN,
        )
        val input = A3SemanticJson.canonicalJson(sample.input)
        input shouldContain "\"faceDown\":true"
        input shouldNotContain "Face-down permanent"
    }

    test("retains semantic candidate domain constraints in input") {
        val source = fixture(
            chosenCandidateIndex = 0,
            candidateList = listOf(
                candidate(
                    kind = "PlayLand",
                    sourceEntityId = "entity-raw-source",
                    targetDomain = minimalTargetDomain(),
                    availableManaColors = listOf("RED"),
                    targetEntityIds = listOf("entity-target"),
                    validSacrificeTargets = listOf("entity-sacrifice"),
                ),
            ),
        )
        val sample = C1ModelFacingProjectionV1.project(
            source.trajectory,
            source.record,
            C1ProjectionContext("b".repeat(64), "c".repeat(64)),
            C1DatasetPartition.TRAIN,
        )
        val input = A3SemanticJson.canonicalJson(sample.input)

        input shouldContain "targetDomain"
        input shouldContain "minTargets"
        input shouldContain "maxTargets"
        input shouldContain "availableManaColors"
        input shouldContain "targetEntityAliases"
        input shouldContain "validSacrificeTargetsAliases"
        input shouldNotContain "entity-target"
        input shouldNotContain "entity-sacrifice"
    }

    test("represents all twelve structured domain variants") {
        val domains: List<StructuredDecisionDomain> = listOf(
            TargetsDomain(requirements = emptyList(), canCancel = true),
            CardSelectionDomain(
                options = emptyList(),
                minSelections = 0,
                maxSelections = 0,
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
            ModeSelectionDomain(
                modes = listOf(ModeOptionDomain(index = 0, text = "presentation", available = true)),
                minModes = 1,
                maxModes = 1,
            ),
            DistributionDomain(
                totalAmount = 0,
                targets = emptyList(),
                minPerTarget = 0,
                maxPerTarget = emptyMap(),
                allowPartial = true,
            ),
            OrderingDomain(objects = emptyList()),
            SplitPilesDomain(
                cards = emptyList(),
                numberOfPiles = 1,
                pileLabels = listOf("pile"),
            ),
            SearchLibraryDomain(
                options = emptyList(),
                minSelections = 0,
                maxSelections = 0,
                cards = emptyMap(),
                filterDescription = "",
            ),
            ReorderLibraryDomain(cards = emptyList(), cardInfo = emptyMap()),
            CombatResolutionDomain(
                firstStrike = false,
                attackers = emptyList(),
                blockers = emptyList(),
                defenders = emptyList(),
                edges = emptyList(),
                coChooserId = null,
            ),
            ManaSourcesDomain(
                paymentDomain = PaymentDomainV5(
                    requiredCost = "",
                    outerAtomicCostUnits = emptyList(),
                    initialPoolBuckets = emptyList(),
                    sourceActivationOptions = emptyList(),
                ),
                canDecline = true,
            ),
            ReplacementDomain(
                fromOptions = emptyList(),
                toOptions = emptyList(),
                fromMetadata = emptyList(),
                toMetadata = emptyList(),
                allowedToByFrom = emptyList(),
                defaultFromIndex = null,
            ),
            BudgetModalDomain(budget = 0, modes = emptyList()),
        )
        val expectedTypes = listOf(
            "targets",
            "card-selection",
            "mode-selection",
            "distribution",
            "ordering",
            "split-piles",
            "search-library",
            "reorder-library",
            "combat-resolution",
            "mana-sources",
            "replacement",
            "budget-modal",
        )

        domains.zip(expectedTypes).forEach { (domain, expectedType) ->
            val representation = C1ModelFacingProjectionV1
                .projectStructuredDomainRepresentation(domain)
            representation["type"]?.jsonPrimitive?.content shouldBe expectedType
            representation["version"]?.jsonPrimitive?.content?.toInt() shouldBe domain.version
            when (expectedType) {
                "targets" -> representation["requirements"] shouldNotBe null
                "card-selection" -> representation["optionsAliases"] shouldNotBe null
                "mode-selection" -> {
                    representation["modes"] shouldNotBe null
                    representation.toString() shouldNotContain "presentation"
                }
                "distribution" -> representation["targetsAliases"] shouldNotBe null
                "ordering" -> representation["objectsAliases"] shouldNotBe null
                "split-piles" -> representation["cardsAliases"] shouldNotBe null
                "search-library" -> representation["optionsAliases"] shouldNotBe null
                "reorder-library" -> representation["cardsAliases"] shouldNotBe null
                "combat-resolution" -> representation["edges"] shouldNotBe null
                "mana-sources" -> representation["paymentDomain"] shouldNotBe null
                "replacement" -> representation["fromOptions"] shouldNotBe null
                "budget-modal" -> representation["modes"] shouldNotBe null
            }
        }
    }

    test("preserves nonempty membership and relation structure for reachable domains") {
        val first = EntityId("structured-first")
        val second = EntityId("structured-second")
        val third = EntityId("structured-third")
        val cardInfo = StructuredCardInfo(
            name = "Visible Card",
            manaCost = "{1}{R}",
            typeLine = "Creature",
            colors = listOf("RED"),
            power = 2,
        )
        val representations = listOf(
            C1ModelFacingProjectionV1.projectStructuredDomainRepresentation(
                TargetsDomain(
                    requirements = listOf(
                        TargetRequirementDomain(
                            index = 0,
                            description = "target",
                            minTargets = 1,
                            maxTargets = 2,
                            candidates = listOf(first, second),
                            targetZone = "BATTLEFIELD",
                            mustDifferFromEarlier = false,
                            sameController = true,
                            sameOwner = false,
                            sameCreatureType = false,
                            sameCardType = true,
                            totalManaValueAtMost = 5,
                            differentNames = true,
                            xConstrainsManaValue = false,
                            xConstrainsManaValueExactly = false,
                            xConstrainsPower = false,
                            xConstrainsCount = false,
                        ),
                    ),
                    canCancel = false,
                ),
            ),
            C1ModelFacingProjectionV1.projectStructuredDomainRepresentation(
                CardSelectionDomain(
                    options = listOf(first, second),
                    minSelections = 1,
                    maxSelections = 2,
                    ordered = true,
                    cardInfo = mapOf(first to cardInfo, second to cardInfo),
                    useTargetingUI = true,
                    selectedLabel = "Select",
                    remainderLabel = null,
                    nonSelectableOptions = listOf(third),
                    onePerCardType = false,
                    onePerColor = true,
                    availableColors = listOf("RED"),
                    onePerCardName = false,
                    onePerBasicLandType = false,
                    onePerPower = false,
                    maxTotalManaValue = 5,
                    minTotalManaValue = 1,
                    maxTotalPower = 4,
                    conditionalMinimums = listOf(
                        ConditionalSelectionMinimumDomain(
                            requiredSelections = 2,
                            minimumSelections = 1,
                            matchingOptions = listOf(first),
                            requiredMatches = 1,
                            description = null,
                        ),
                    ),
                ),
            ),
            C1ModelFacingProjectionV1.projectStructuredDomainRepresentation(
                DistributionDomain(
                    totalAmount = 3,
                    targets = listOf(first, second),
                    minPerTarget = 1,
                    maxPerTarget = mapOf(first to 2, second to 3),
                    allowPartial = false,
                ),
            ),
            C1ModelFacingProjectionV1.projectStructuredDomainRepresentation(
                OrderingDomain(
                    objects = listOf(second, first),
                    cardInfo = mapOf(first to cardInfo, second to cardInfo),
                    objectLabels = mapOf(first to "first", second to "second"),
                ),
            ),
            C1ModelFacingProjectionV1.projectStructuredDomainRepresentation(
                SearchLibraryDomain(
                    options = listOf(first),
                    minSelections = 0,
                    maxSelections = 1,
                    cards = mapOf(first to cardInfo),
                    filterDescription = "creatures",
                ),
            ),
            C1ModelFacingProjectionV1.projectStructuredDomainRepresentation(
                ReorderLibraryDomain(
                    cards = listOf(second, first),
                    cardInfo = mapOf(first to cardInfo, second to cardInfo),
                ),
            ),
        )

        representations.forEach { representation ->
            val text = A3SemanticJson.canonicalJson(representation)
            text shouldNotContain first.value
            text shouldNotContain second.value
            text shouldNotContain third.value
        }
        representations[0]["requirements"].toString() shouldContain "candidatesAliases"
        representations[1]["optionsAliases"].toString() shouldContain "entity-0"
        representations[1]["conditionalMinimums"].toString() shouldContain "matchingOptionsAliases"
        representations[1].toString() shouldNotContain "useTargetingUI"
        representations[2]["targetsAliases"] shouldNotBe null
        representations[3]["objectsAliases"].toString() shouldContain "entity-0"
        representations[3]["objectLabels"] shouldNotBe null
        representations[4]["optionsAliases"] shouldNotBe null
        representations[5]["cardsAliases"].toString() shouldContain "entity-0"
    }

    test("preserves nonempty current combat and mana domain relations") {
        val attacker = EntityId("combat-attacker")
        val blocker = EntityId("combat-blocker")
        val defender = EntityId("combat-defender")
        val chooser = EntityId("combat-chooser")
        val combat = C1ModelFacingProjectionV1.projectStructuredDomainRepresentation(
            CombatResolutionDomain(
                firstStrike = true,
                attackers = listOf(
                    CombatAttackerDomain(
                        id = attacker,
                        name = "Attacker",
                        power = 3,
                        toughness = 3,
                        hasTrample = true,
                        hasDeathtouch = false,
                        hasFirstStrike = false,
                        hasDoubleStrike = false,
                        dealsDamageThisStep = true,
                        bandId = null,
                        attackedDefenderId = defender,
                        blockedByIds = listOf(blocker),
                        markedDamage = 0,
                    ),
                ),
                blockers = listOf(
                    CombatBlockerDomain(
                        id = blocker,
                        name = "Blocker",
                        power = 2,
                        toughness = 2,
                        hasDeathtouch = false,
                        hasFirstStrike = false,
                        hasDoubleStrike = false,
                        dealsDamageThisStep = true,
                        blockedAttackerIds = listOf(attacker),
                        markedDamage = 0,
                    ),
                ),
                defenders = listOf(
                    CombatDefenderDomain(
                        id = defender,
                        kind = CombatTargetKind.PLAYER,
                        name = "Defender",
                        lifeOrLoyaltyOrDefense = 20,
                    ),
                ),
                edges = listOf(
                    com.wingedsheep.gym.contract.CombatDamageEdgeDomain(
                        id = "combat-edge",
                        sourceId = attacker,
                        targetId = blocker,
                        direction = CombatDamageDirection.ATTACKER_TO_BLOCKER,
                        amount = 3,
                        maximum = 3,
                        lethal = 2,
                        isTrampleDrain = false,
                        editableBy = chooser,
                    ),
                ),
                coChooserId = chooser,
            ),
            C1SampleRelationTable.standalonePlayers(chooser, defender),
        )
        val mana = C1ModelFacingProjectionV1.projectStructuredDomainRepresentation(
            ManaSourcesDomain(
                paymentDomain = PaymentDomainV5(
                    requiredCost = "{R}",
                    outerAtomicCostUnits = listOf(
                        AtomicManaCostUnitV1(
                            symbolIndex = 0,
                            unitIndexWithinSymbol = 0,
                            kind = PaymentCostKindV1.COLORED,
                            allowedColors = setOf(PaymentManaColor.RED),
                        ),
                    ),
                    initialPoolBuckets = listOf(
                        InitialPoolBucketV1(
                            key = InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.RED),
                            availableAmount = 1,
                        ),
                    ),
                    sourceActivationOptions = listOf(
                        PaymentSourceActivationDomainV2(
                            sourceId = EntityId("raw-mana-source-id"),
                            sourceName = "Mana Source",
                            manaAbilityKey = "mana-source-key",
                            productionChoices = listOf(ProductionChoice(PaymentManaColor.GREEN)),
                            atomicActivationManaCostUnits = emptyList(),
                            activationSupportKind = PaymentActivationSupportKindV1.FixedManaAndTapSelf,
                            deterministicNonManaCosts = listOf(
                                PaymentDeterministicNonManaCostKindV1.TapSelf,
                            ),
                            activationCostOrderOptions = listOf(
                                listOf(ActivationCostComponentRefV1.DeterministicNonManaComponent(0)),
                            ),
                        ),
                    ),
                ),
                canDecline = false,
            ),
        )

        val combatText = A3SemanticJson.canonicalJson(combat)
        val manaText = A3SemanticJson.canonicalJson(mana)
        listOf(
            attacker.value,
            blocker.value,
            defender.value,
            chooser.value,
            "raw-mana-source-id",
        ).forEach { raw ->
            combatText shouldNotContain raw
            manaText shouldNotContain raw
        }
        combat["attackers"] shouldNotBe null
        combat["blockers"] shouldNotBe null
        combat["defenders"] shouldNotBe null
        combat["edges"] shouldNotBe null
        combat.toString() shouldContain "editableByRole"
        combat.toString() shouldContain "coChooserRole"
        combat.toString() shouldNotContain "\"name\":\"Attacker\""
        combat.toString() shouldNotContain "\"name\":\"Blocker\""
        combat.toString() shouldNotContain "\"name\":\"Defender\""
        mana["paymentDomain"] shouldNotBe null
        mana["paymentDomain"].toString() shouldContain "sourceActivationOptions"
        mana["paymentDomain"].toString() shouldContain "initialPoolBuckets"
        mana.toString() shouldNotContain "mana-source-key"
    }
})

private data class ProjectionFixture(
    val trajectory: TrajectoryV1,
    val record: DecisionRecordV1,
)

private fun fixture(
    chosenCandidateIndex: Int,
    candidateList: List<JsonObject>? = null,
    observationOverride: PlayerObservationV1? = null,
): ProjectionFixture {
    val self = EntityId("self")
    val opponent = EntityId("opponent")
    val observation = observationOverride ?: PlayerObservationV1(
        wireSchemaHash = SchemaHash.CURRENT,
        perspectivePlayerId = self,
        agentToAct = self,
        turnNumber = 3,
        phase = Phase.PRECOMBAT_MAIN,
        step = Step.PRECOMBAT_MAIN,
        activePlayerId = self,
        priorityPlayerId = self,
        players = listOf(
            PlayerView(
                id = self,
                name = "SELF",
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
                id = opponent,
                name = "OPPONENT",
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
        pendingDecision = null,
        terminated = false,
        truncated = false,
        winnerId = null,
        observationDigest = "0".repeat(64),
    )
    val candidates = candidateList ?: listOf(
        candidate(kind = "PassPriority", sourceEntityId = "entity-raw-source"),
        candidate(kind = "PlayLand", sourceEntityId = "entity-second-source"),
    )
    val domain = CompleteLegalDomainV1(
        kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
        candidates = candidates,
    )
    val chosen = ChosenSemanticActionV1.from(
        domain = domain,
        candidate = candidates[chosenCandidateIndex],
    )
    val record = DecisionRecordV1(
        decisionIndex = 0,
        replayActionIndex = 0,
        replayFrameIndex = 0,
        perspectivePlayerId = self,
        decisionKind = SemanticDecisionKindV1.PRIORITY,
        semanticDecisionId = SemanticDecisionIdV1(value = "1".repeat(64)),
        observationBefore = observation,
        completeLegalDomain = domain,
        candidateDomainDigest = CandidateDomainDigestV1.from(domain),
        chosenSemanticAction = chosen,
    )
    val metadata = episodeMetadata(self, opponent)
    val trajectoryBase = TrajectoryV1(
        trajectoryId = "0".repeat(64),
        episodeMetadata = metadata,
        decisions = listOf(record),
    )
    val trajectory = trajectoryBase.copy(trajectoryId = trajectoryBase.recomputeTrajectoryId())
    return ProjectionFixture(trajectory = trajectory, record = record)
}

private fun candidate(
    kind: String,
    sourceEntityId: String,
    actionSemantics: JsonObject = buildJsonObject { put("type", kind) },
    targetDomain: JsonObject? = null,
    availableManaColors: List<String>? = null,
    targetEntityIds: List<String> = emptyList(),
    validSacrificeTargets: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("kind", kind)
    put("affordable", true)
    put("sourceEntityId", sourceEntityId)
    put("targetEntityIds", buildJsonArray {
        targetEntityIds.forEach { add(JsonPrimitive(it)) }
    })
    put("manaCost", JsonNull)
    put("hasXCost", false)
    put("maxAffordableX", JsonNull)
    put("minTargets", 0)
    put("maxTargets", 0)
    put("validSacrificeTargets", buildJsonArray {
        validSacrificeTargets.forEach { add(JsonPrimitive(it)) }
    })
    put("sacrificeCount", 0)
    put("sacrificeMinCount", 0)
    put("sacrificeMaxCount", 0)
    put("requiresDamageDistribution", false)
    put("isManaAbility", false)
    put("requiresStructuredAction", false)
    put("requiredPayloadFields", buildJsonArray { })
    put("actionSemantics", actionSemantics)
    put("isDecisionOption", false)
    targetDomain?.let { put("targetDomain", it) }
    availableManaColors?.let { colors ->
        put("availableManaColors", buildJsonArray { colors.forEach { add(JsonPrimitive(it)) } })
    }
}

private fun minimalTargetDomain(): JsonObject = buildJsonObject {
    put("version", 1)
    put("composition", "FIXED")
    put(
        "requirements",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("index", 0)
                    put("minTargets", 0)
                    put("maxTargets", 1)
                    put("candidates", buildJsonArray { add(JsonPrimitive("entity-target")) })
                    put("targetZone", JsonNull)
                    put("mustDifferFromEarlier", false)
                    put("sameController", false)
                    put("sameOwner", false)
                    put("sameCreatureType", false)
                    put("sameCardType", false)
                    put("totalManaValueAtMost", JsonNull)
                    put("differentNames", false)
                    put("xConstrainsManaValue", false)
                    put("xConstrainsManaValueExactly", false)
                    put("xConstrainsPower", false)
                    put("xConstrainsCount", false)
                },
            )
        },
    )
}

private fun richObservation(self: EntityId, opponent: EntityId): PlayerObservationV1 = PlayerObservationV1(
    wireSchemaHash = SchemaHash.CURRENT,
    perspectivePlayerId = self,
    agentToAct = self,
    turnNumber = 3,
    phase = Phase.PRECOMBAT_MAIN,
    step = Step.PRECOMBAT_MAIN,
    activePlayerId = self,
    priorityPlayerId = self,
    players = listOf(
        PlayerView(
            id = self,
            name = "SELF-NAME",
            lifeTotal = 20,
            handSize = 2,
            librarySize = 30,
            graveyardSize = 1,
            exileSize = 0,
            manaPool = ManaPoolView(red = 2),
            isPerspective = true,
            isActive = true,
            hasPriority = true,
            hasLost = false,
        ),
        PlayerView(
            id = opponent,
            name = "OPPONENT-NAME",
            lifeTotal = 18,
            handSize = 4,
            librarySize = 28,
            graveyardSize = 2,
            exileSize = 0,
            manaPool = ManaPoolView(blue = 1),
            isPerspective = false,
            isActive = false,
            hasPriority = false,
            hasLost = false,
        ),
    ),
    zones = listOf(
        ZoneView(
            ownerId = self,
            zoneType = Zone.BATTLEFIELD,
            hidden = false,
            size = 1,
            cards = listOf(
                EntityFeatures(
                    entityId = EntityId("visible-card"),
                    cardDefinitionId = "CARD_DEF",
                    name = "Visible Card",
                    zone = Zone.BATTLEFIELD,
                    ownerId = self,
                    controllerId = self,
                    types = setOf("CREATURE"),
                    subtypes = setOf("WIZARD"),
                    colors = setOf("RED"),
                    keywords = setOf("HASTE"),
                    manaCost = "{1}{R}",
                    manaValue = 2,
                    oracleText = "When this enters, draw a card.",
                    power = 2,
                    toughness = 2,
                    tapped = true,
                    damageMarked = 1,
                    counters = mapOf("+1/+1" to 1),
                ),
            ),
        ),
    ),
    stack = listOf(
        StackItemView(
            entityId = EntityId("stack-object"),
            controllerId = self,
            sourceEntityId = EntityId("visible-card"),
            name = "Stack Spell",
            kind = StackItemKind.SPELL,
            oracleText = "Draw a card.",
            targets = listOf(opponent),
        ),
    ),
    pendingDecision = PlayerObservationPendingDecisionV1(
        kind = PendingDecisionKind.YES_NO,
        playerId = self,
        sourceEntityId = EntityId("visible-card"),
        triggeringEntityId = null,
        requiresStructuredResponse = false,
        shape = DecisionShape(),
    ),
    terminated = false,
    truncated = false,
    winnerId = null,
    observationDigest = "0".repeat(64),
)

private fun episodeMetadata(self: EntityId, opponent: EntityId): EpisodeMetadataV1 {
    val roster = listOf(
        RosterSeatV1(
            seatIndex = 0,
            playerId = self,
            role = "SELF",
            deckIdentity = "deck-self",
        ),
        RosterSeatV1(
            seatIndex = 1,
            playerId = opponent,
            role = "OPPONENT",
            deckIdentity = "deck-opponent",
        ),
    )
    val environment = EnvironmentIdentityV1(
        engineCommit = "a".repeat(40),
        cardDefinitionIdentity = "cards-v1",
        akiriDeckIdentity = "akiri-v1",
        chevillDeckIdentity = "chevill-v1",
        format = "COMMANDER",
        attackMode = "MULTIPLE",
        startingHandSize = 0,
        skipMulligans = true,
        useHandSmoother = false,
        roster = roster,
        startingPlayer = self,
        actualEngineSeed = 1L,
    )
    val policy = PolicyProvenanceV1(
        behaviorPolicyIdentity = "behavior-v1",
        opponentPolicyIdentity = "opponent-v1",
        behaviorPolicyRole = "EXTERNAL_CONTROLLER",
        opponentPolicyRole = "EXTERNAL_CONTROLLER",
        policyRngIdentity = "explicit-seed/kotlin-policy-state-v1",
        policySeed = 1L,
        policySourceIdentity = "b".repeat(64),
    )
    val replay = CompactReplayLinkV1(
        replayContentIdentity = "c".repeat(64),
        replayActionCount = 1,
    )
    val closure = EpisodeClosureV1.Interrupted(
        stepCount = 1,
        reason = EpisodeInterruptionReason.HORIZON_REACHED,
    )
    val base = EpisodeMetadataV1(
        semanticEpisodeId = "0".repeat(64),
        collectionJobId = "0".repeat(64),
        environmentIdentity = environment,
        policyProvenance = policy,
        compactReplayLink = replay,
        closure = closure,
    )
    val semantic = base.copy(semanticEpisodeId = base.recomputeSemanticEpisodeId())
    return semantic.copy(collectionJobId = semantic.recomputeCollectionJobId())
}
