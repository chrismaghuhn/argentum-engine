package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.TargetDomainSupport
import com.wingedsheep.engine.legalactions.TargetInfo
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.GameGymEnv
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.targets.TargetCreature
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject

class ActionTargetDomainContractTest : FunSpec({

    fun requirement(
        index: Int,
        minTargets: Int,
        maxTargets: Int,
        xConstrainsCount: Boolean = false,
        candidates: List<EntityId> = listOf(EntityId("candidate-$index")),
    ) = TargetInfo(
        index = index,
        description = "requirement $index",
        minTargets = minTargets,
        maxTargets = maxTargets,
        validTargets = candidates,
        xConstrainsCount = xConstrainsCount,
    )

    fun action(
        player: EntityId = EntityId("player"),
        requirements: List<TargetInfo> = emptyList(),
        requiresTargets: Boolean = requirements.isNotEmpty(),
        support: TargetDomainSupport = TargetDomainSupport.SUPPORTED,
    ) = LegalAction(
        action = CastSpell(player, EntityId("spell")),
        actionType = "CastSpell",
        description = "test action",
        requiresTargets = requiresTargets,
        targetRequirements = requirements,
        targetDomainSupport = support,
    )

    fun allCandidatesAreAddressable(@Suppress("UNUSED_PARAMETER") entityId: EntityId): Boolean = true

    test("targetless fixed domain accepts only an empty payload") {
        val certification = TargetPayloadPartition.certify(emptyList())
            .shouldBeInstanceOf<TargetPayloadPartition.Certification.Supported>()

        certification.partition(0) shouldBe TargetPayloadPartition.PayloadPartition.Accepted(emptyList())
        certification.partition(1) shouldBe TargetPayloadPartition.PayloadPartition.Rejected(
            TargetPayloadPartition.UnsupportedReason.PAYLOAD_LENGTH_OUT_OF_RANGE,
        )
    }

    test("one optional requirement accepts zero or one target") {
        val certification = TargetPayloadPartition.certify(listOf(requirement(0, 0, 1)))
            .shouldBeInstanceOf<TargetPayloadPartition.Certification.Supported>()

        certification.partition(0) shouldBe TargetPayloadPartition.PayloadPartition.Accepted(listOf(0))
        certification.partition(1) shouldBe TargetPayloadPartition.PayloadPartition.Accepted(listOf(1))
    }

    test("one requirement may have any finite resolved cardinality") {
        val certification = TargetPayloadPartition.certify(listOf(requirement(0, 1, 3)))
            .shouldBeInstanceOf<TargetPayloadPartition.Certification.Supported>()

        certification.partition(1) shouldBe TargetPayloadPartition.PayloadPartition.Accepted(listOf(1))
        certification.partition(2) shouldBe TargetPayloadPartition.PayloadPartition.Accepted(listOf(2))
        certification.partition(3) shouldBe TargetPayloadPartition.PayloadPartition.Accepted(listOf(3))
    }

    test("fixed Bite Down and Brass Squire shaped payloads preserve slot order") {
        val shapes = listOf(
            "Bite Down" to listOf(requirement(0, 1, 1), requirement(1, 1, 1)),
            "Brass Squire" to listOf(requirement(0, 1, 1), requirement(1, 1, 1)),
        )
        val slot0 = ChosenTarget.Permanent(EntityId("slot-0"))
        val slot1 = ChosenTarget.Permanent(EntityId("slot-1"))

        shapes.forEach { (_, requirements) ->
            val certification = TargetPayloadPartition.certify(requirements)
                .shouldBeInstanceOf<TargetPayloadPartition.Certification.Supported>()
            val partition = certification.partition(2)
                .shouldBeInstanceOf<TargetPayloadPartition.PayloadPartition.Accepted>()

            partition.counts shouldBe listOf(1, 1)
            val payload = listOf(slot0, slot1)
            payload.take(partition.counts[0]) shouldBe listOf(slot0)
            payload.drop(partition.counts[0]) shouldBe listOf(slot1)
        }
    }

    test("two variable requirements are rejected when total lengths collide") {
        TargetPayloadPartition.certify(
            listOf(requirement(0, 0, 1), requirement(1, 1, 2)),
        ) shouldBe TargetPayloadPartition.Certification.Unsupported(
            TargetPayloadPartition.UnsupportedReason.AMBIGUOUS_FLAT_PARTITION,
        )
    }

    test("unresolved X cardinality is rejected without using the placeholder max") {
        TargetPayloadPartition.certify(listOf(requirement(0, 0, 1, xConstrainsCount = true))) shouldBe
            TargetPayloadPartition.Certification.Unsupported(
                TargetPayloadPartition.UnsupportedReason.UNRESOLVED_X,
            )
    }

    test("invalid cardinality is rejected") {
        TargetPayloadPartition.certify(listOf(requirement(0, 2, 1))) shouldBe
            TargetPayloadPartition.Certification.Unsupported(
                TargetPayloadPartition.UnsupportedReason.INVALID_CARDINALITY,
            )
    }

    test("a mandatory domain with too few candidates fails closed") {
        val result = ActionTargetDomainMapper.map(
            action(
                requirements = listOf(
                    requirement(
                        index = 0,
                        minTargets = 2,
                        maxTargets = 2,
                        candidates = listOf(EntityId("only-candidate")),
                    ),
                ),
            ),
            ::allCandidatesAreAddressable,
        ).shouldBeInstanceOf<ActionTargetDomainMapper.Result.Unsupported>()

        result.diagnostic.code shouldBe DiagnosticCode.ACTION_TARGET_DOMAIN_UNSUPPORTED
        result.diagnostic.semanticCode shouldBe "ACTION_TARGET_DOMAIN_UNSUPPORTED"
    }

    test("maps the fixed action domain in semantic requirement order with canonical candidates") {
        val result = ActionTargetDomainMapper.map(
            action(
                requirements = listOf(
                    requirement(
                        index = 0,
                        minTargets = 1,
                        maxTargets = 1,
                        candidates = listOf(EntityId("zeta"), EntityId("alpha")),
                    ),
                    requirement(
                        index = 1,
                        minTargets = 1,
                        maxTargets = 1,
                        candidates = listOf(EntityId("delta"), EntityId("beta")),
                    ),
                ),
            ),
            ::allCandidatesAreAddressable,
        ).shouldBeInstanceOf<ActionTargetDomainMapper.Result.Supported>()

        result.domain.version shouldBe ACTION_TARGET_DOMAIN_VERSION
        result.domain.composition shouldBe ActionTargetComposition.FIXED
        result.domain.requirements.map { it.index } shouldBe listOf(0, 1)
        result.domain.requirements[0].candidates shouldBe listOf(EntityId("alpha"), EntityId("zeta"))
        result.domain.requirements[1].candidates shouldBe listOf(EntityId("beta"), EntityId("delta"))

        val reordered = ActionTargetDomainMapper.map(
            action(
                requirements = listOf(
                    requirement(
                        index = 0,
                        minTargets = 1,
                        maxTargets = 1,
                        candidates = listOf(EntityId("alpha"), EntityId("zeta")),
                    ),
                    requirement(
                        index = 1,
                        minTargets = 1,
                        maxTargets = 1,
                        candidates = listOf(EntityId("beta"), EntityId("delta")),
                    ),
                ),
            ),
            ::allCandidatesAreAddressable,
        ).shouldBeInstanceOf<ActionTargetDomainMapper.Result.Supported>()
        reordered.domain shouldBe result.domain
    }

    test("unsupported action projection maps to one stable target-domain diagnostic") {
        val result = ActionTargetDomainMapper.map(
            action(
                requirements = listOf(requirement(0, 1, 1)),
                support = TargetDomainSupport.UNSUPPORTED(
                    com.wingedsheep.engine.legalactions.TargetDomainUnsupportedReason.INCOMPLETE_SEMANTICS,
                ),
            ),
            ::allCandidatesAreAddressable,
        ).shouldBeInstanceOf<ActionTargetDomainMapper.Result.Unsupported>()

        result.diagnostic.code shouldBe DiagnosticCode.ACTION_TARGET_DOMAIN_UNSUPPORTED
        result.diagnostic.semanticCode shouldBe "ACTION_TARGET_DOMAIN_UNSUPPORTED"
    }

    test("unaddressable candidates are withheld instead of filtered into a lossy domain") {
        val result = ActionTargetDomainMapper.map(
            action(requirements = listOf(requirement(0, 1, 1))),
            { false },
        ).shouldBeInstanceOf<ActionTargetDomainMapper.Result.Unsupported>()

        result.diagnostic.code shouldBe DiagnosticCode.ACTION_TARGET_DOMAIN_UNSUPPORTED
    }

    test("invalid V1 version is rejected instead of becoming a legacy flat domain") {
        shouldThrow<IllegalArgumentException> {
            ActionTargetDomainV1(version = ACTION_TARGET_DOMAIN_VERSION + 1)
        }
    }

    test("a target-bearing action without canonical requirements cannot use legacy flat fields") {
        val action = LegalAction(
            action = CastSpell(EntityId("player"), EntityId("spell")),
            actionType = "CastSpell",
            description = "targeted spell",
            requiresTargets = true,
            validTargets = listOf(EntityId("legacy-candidate")),
            minTargets = 1,
            targetCount = 1,
        )

        ActionPayloadRequirements.requiredPayloadFields(action) shouldBe setOf("targets")
        shouldThrow<IllegalArgumentException> {
            ActionPayloadRequirements.requireTargetDomainSupported(action)
        }
    }

    test("targetless actions do not require a target payload") {
        val action = LegalAction(
            action = CastSpell(EntityId("player"), EntityId("spell")),
            actionType = "CastSpell",
            description = "targetless spell",
            requiresTargets = false,
            targetRequirements = emptyList(),
            targetDomainSupport = TargetDomainSupport.SUPPORTED,
        )

        ActionPayloadRequirements.requiredPayloadFields(action).shouldBeEmpty()
        ActionPayloadRequirements.missingRequiredFields(action, buildJsonObject {}) shouldBe emptyList()
        ActionPayloadRequirements.requireTargetDomainSupported(action)
    }

    test("targetless submitted CastSpell rejects an extra target payload") {
        val player = EntityId("player")
        val targetless = LegalAction(
            action = CastSpell(player, EntityId("spell")),
            actionType = "CastSpell",
            description = "targetless spell",
            requiresTargets = false,
            targetRequirements = emptyList(),
            targetDomainSupport = TargetDomainSupport.SUPPORTED,
        )
        val submitted = CastSpell(
            playerId = player,
            cardId = EntityId("spell"),
            targets = listOf(ChosenTarget.Player(EntityId("opponent"))),
        )

        shouldThrow<IllegalArgumentException> {
            ActionPayloadRequirements.requireTargetPayloadPartition(targetless, submitted)
        }.message shouldContain "PAYLOAD_LENGTH_OUT_OF_RANGE"
    }

    test("submitted fixed multi-target payload rejects a malformed length") {
        val player = EntityId("player")
        val fixedMultiTarget = LegalAction(
            action = CastSpell(player, EntityId("spell")),
            actionType = "CastSpell",
            description = "fixed multi-target spell",
            requiresTargets = true,
            targetRequirements = listOf(requirement(0, 1, 1), requirement(1, 1, 1)),
            targetDomainSupport = TargetDomainSupport.SUPPORTED,
        )
        val submitted = CastSpell(
            playerId = player,
            cardId = EntityId("spell"),
            targets = listOf(ChosenTarget.Player(EntityId("opponent"))),
        )

        shouldThrow<IllegalArgumentException> {
            ActionPayloadRequirements.requireTargetPayloadPartition(fixedMultiTarget, submitted)
        }.message shouldContain "PAYLOAD_LENGTH_OUT_OF_RANGE"
    }

    test("strict registry execution rejects an unsupported target-domain action before processing") {
        val unsupported = action(
            requirements = listOf(requirement(0, 1, 1)),
            support = TargetDomainSupport.UNSUPPORTED(
                com.wingedsheep.engine.legalactions.TargetDomainUnsupportedReason.INCOMPLETE_SEMANTICS,
            ),
        )
        val resolved = ActionRegistry.ofLegalActions(listOf(unsupported))
            .resolve(0)
            .shouldBeInstanceOf<ResolvedAction.Legal>()

        shouldThrow<IllegalArgumentException> {
            ActionPayloadRequirements.requireTargetDomainSupported(resolved.legalAction)
        }
    }

    test("GameGymEnv fails the whole observation when one enumerated target shape is unsupported") {
        val ambiguousTargetSpell = card("Gym Ambiguous Target Spell") {
            manaCost = "{R}"
            typeLine = "Instant"
            spell {
                val first = target("up to one target creature", TargetCreature(optional = true))
                val second = target("up to one other target creature", TargetCreature(optional = true))
                effect = Effects.Tap(first).then(Effects.Tap(second))
            }
        }
        val cardRegistry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
            register(ambiguousTargetSpell)
        }
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of("Mountain" to 1, ambiguousTargetSpell.name to 1),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 1)),
                ),
                startingHandSize = 2,
                skipMulligans = true,
                startingPlayerIndex = 0,
            ),
        )
        var land = environment.legalActions().firstOrNull { it.actionType == "PlayLand" }
        var setupSteps = 0
        while (land == null && setupSteps++ < 20) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            land = environment.legalActions().firstOrNull { it.actionType == "PlayLand" }
        }
        environment.step(checkNotNull(land).action)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )

        val failure = shouldThrow<UnsupportedPathFailure> { gym.observe() }
        failure.diagnostics.map { it.code } shouldBe listOf(DiagnosticCode.ACTION_TARGET_DOMAIN_UNSUPPORTED)
        environment.diagnostics.events.map { it.code } shouldBe
            listOf(DiagnosticCode.ACTION_TARGET_DOMAIN_UNSUPPORTED)
    }

    test("builder uses one supported sequence for views and registry while normalizing flat fields") {
        val cardRegistry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
        }
        val environment = com.wingedsheep.gym.GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 1)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 1)),
                ),
                startingHandSize = 0,
                skipMulligans = true,
                startingPlayerIndex = 0,
            ),
        )
        val player = environment.playerIds.first()
        val targetless = action(player = player, requirements = emptyList(), requiresTargets = false)
        val supportedSingle = action(
            player = player,
            requirements = listOf(
                requirement(
                    index = 0,
                    minTargets = 1,
                    maxTargets = 1,
                    candidates = listOf(environment.playerIds[1]),
                ),
            ),
        )
        val unsupported = action(
            player = player,
            requirements = listOf(requirement(0, 1, 1)),
            support = TargetDomainSupport.UNSUPPORTED(
                com.wingedsheep.engine.legalactions.TargetDomainUnsupportedReason.INCOMPLETE_SEMANTICS,
            ),
        )

        val result = ObservationBuilder(cardRegistry = cardRegistry).build(
            environment.state,
            player,
            listOf(targetless, unsupported, supportedSingle),
        )
        val observation = result.observation as TrainingObservation

        result.diagnostics.map { it.code } shouldBe listOf(DiagnosticCode.ACTION_TARGET_DOMAIN_UNSUPPORTED)
        observation.legalActions.map { it.actionId } shouldBe listOf(0, 1)
        observation.legalActions[0].targetDomain?.requirements.shouldBeEmpty()
        observation.legalActions[0].targetEntityIds.shouldBeEmpty()
        observation.legalActions[0].minTargets shouldBe 0
        observation.legalActions[0].maxTargets shouldBe 0
        observation.legalActions[1].targetEntityIds shouldBe listOf(environment.playerIds[1])
        observation.legalActions[1].minTargets shouldBe 1
        observation.legalActions[1].maxTargets shouldBe 1
        result.registry.legalActions.map { it.first } shouldBe listOf(0, 1)
        result.registry.legalActions.map { it.second } shouldBe listOf(targetless, supportedSingle)
    }
})
