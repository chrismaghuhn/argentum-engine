package com.wingedsheep.gym

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.TargetDomainSupport
import com.wingedsheep.engine.legalactions.TargetDomainUnsupportedReason
import com.wingedsheep.engine.legalactions.utils.TargetEnumerationUtils
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.gym.contract.ACTION_TARGET_DOMAIN_VERSION
import com.wingedsheep.gym.contract.ActionTargetComposition
import com.wingedsheep.gym.contract.ActionTargetDomainV1
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.ObservationResult
import com.wingedsheep.gym.contract.TargetPayloadPartition
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.ModalEffect
import com.wingedsheep.sdk.scripting.effects.GatedEffect
import com.wingedsheep.sdk.scripting.effects.ReflexiveTriggerEffect
import com.wingedsheep.sdk.scripting.targets.TargetChooser
import com.wingedsheep.sdk.scripting.targets.TargetOther
import com.wingedsheep.sdk.scripting.targets.TargetObject
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Task 8 evidence from the two locked curriculum artifacts.
 *
 * The action-selection portion of the real proofs consumes only the public target domain. State
 * surgery is limited to deterministic fixture setup; it is never used to choose a target or to
 * reconstruct a missing domain.
 */
class LockedSliceActionTargetDomainTest : FunSpec({

    val registry = lockedCatalogRegistry()

    test("the persisted locked decks resolve and expose the current target-shape census") {
        val akiri = readLockedDeck("akiri-v0.1.txt")
        val chevill = readLockedDeck("chevill-v0.1.txt")

        akiri shouldHaveSize 100
        chevill shouldHaveSize 100
        akiri shouldContain "Brass Squire"
        akiri shouldContain "Bonesplitter"
        chevill shouldContain "Bite Down"

        (akiri + chevill).toSet()
            .filterNot(registry::hasCard)
            .sorted() shouldBe emptyList()

        // Targetless and single mandatory examples come from the locked artifacts themselves.
        registry.requireCard("Fire Diamond").script.targetRequirements.shouldBeEmpty()
        registry.requireCard("Putrefy").script.targetRequirements shouldHaveSize 1

        val biteRequirements = registry.requireCard("Bite Down").script.targetRequirements
        biteRequirements.map { it.effectiveMinCount to it.count } shouldBe
            listOf(1 to 1, 1 to 1)

        val brassAbility = registry.requireCard("Brass Squire").script.activatedAbilities.single()
        brassAbility.targetRequirements.map { it.effectiveMinCount to it.count } shouldBe
            listOf(1 to 1, 1 to 1)

        val arm = registry.requireCard("Arm the Cathars")
        arm.script.targetRequirements.map { it.effectiveMinCount to it.count } shouldBe
            listOf(1 to 1, 0 to 1, 0 to 1)
        arm.script.targetRequirements.drop(1).forEach { it.shouldBeInstanceOf<TargetOther>() }

        val giantfall = registry.requireCard("Giantfall").script.spellEffect
            .shouldBeInstanceOf<ModalEffect>()
        giantfall.chooseCount shouldBe 1
        giantfall.modes.map { it.targetRequirements.size } shouldBe listOf(2, 1)

        val icyBlast = registry.requireCard("Icy Blast").script.targetRequirements.single()
            .shouldBeInstanceOf<TargetObject>()
        icyBlast.dynamicMaxCount shouldBe com.wingedsheep.sdk.scripting.values.DynamicAmount.XValue

        val renew = registry.requireCard("Rot-Curse Rakshasa").script.activatedAbilities
            .first { it.targetRequirements.isNotEmpty() }
        renew.targetRequirements.single().shouldBeInstanceOf<TargetObject>().dynamicMaxCount shouldBe
            com.wingedsheep.sdk.scripting.values.DynamicAmount.XValue
    }

    test("available repository architecture probes publish aggregate target relations") {
        val repositoryCards = listOf(
            "Behold the Sinister Six!" to
                "mtg-sets/2025/src/main/kotlin/com/wingedsheep/mtg/sets/definitions/spm/cards/BeholdTheSinisterSix.kt",
            "The Rise of Sozin" to
                "mtg-sets/2025/src/main/kotlin/com/wingedsheep/mtg/sets/definitions/tla/cards/TheRiseOfSozin.kt",
            "Secret Tunnel" to
                "mtg-sets/2025/src/main/kotlin/com/wingedsheep/mtg/sets/definitions/tla/cards/SecretTunnel.kt",
            "Giantfall" to
                "mtg-sets/2026/src/main/kotlin/com/wingedsheep/mtg/sets/definitions/ecl/cards/Giantfall.kt",
        )
        repositoryCards.forEach { (name, relativePath) ->
            registry.requireCard(name)
            Files.exists(repositoryRoot().resolve(relativePath)) shouldBe true
        }

        val behold = prepareRepositoryProbeGame(
            cardName = "Behold the Sinister Six!",
            firstHand = true,
            firstGraveyard = listOf(
                "Llanowar Elves",
                "Elvish Mystic",
                "Sram, Senior Edificer",
                "Brass Squire",
                "Viscera Seer",
                "Scavenging Ooze",
            ),
        )
        val beholdId = behold.cardIdsIn(Zone.HAND).single()
        val beholdLegalActions = behold.environment.legalActions()
        val beholdAction = beholdLegalActions.single { legal ->
            val action = legal.action
            action is CastSpell && action.cardId == beholdId
        }
        beholdAction.targetRequirements.single().differentNames shouldBe true
        beholdAction.targetRequirements.single().validTargets shouldHaveSize 6
        val beholdView = publicActionView(behold.environment, beholdLegalActions, beholdAction, registry)
        beholdView.targetDomain.shouldNotBeNull().requirements.single().let { requirement ->
            requirement.minTargets shouldBe 0
            requirement.maxTargets shouldBe 6
            requirement.differentNames shouldBe true
            requirement.targetZone shouldBe "Graveyard"
            requirement.candidates shouldHaveSize 6
        }

        val tunnel = prepareRepositoryProbeGame(
            cardName = "Secret Tunnel",
            firstBattlefield = listOf("Llanowar Elves", "Elvish Mystic"),
        )
        val tunnelId = tunnel.cardIdsIn(Zone.BATTLEFIELD).single()
        val tunnelLegalActions = tunnel.environment.legalActions()
        val tunnelAction = tunnelLegalActions.single { legal ->
            val action = legal.action
            action is ActivateAbility &&
                action.sourceId == tunnelId &&
                legal.targetRequirements.isNotEmpty()
        }
        tunnelAction.targetRequirements.single().sameCreatureType shouldBe true
        tunnelAction.targetRequirements.single().validTargets shouldHaveSize 2
        val tunnelView = publicActionView(tunnel.environment, tunnelLegalActions, tunnelAction, registry)
        tunnelView.targetDomain.shouldNotBeNull().requirements.single().let { requirement ->
            requirement.minTargets shouldBe 2
            requirement.maxTargets shouldBe 2
            requirement.sameCreatureType shouldBe true
            requirement.candidates shouldHaveSize 2
        }

        val sozin = registry.requireCard("The Rise of Sozin")
        val sozinProbe = prepareRepositoryProbeGame(cardName = "The Rise of Sozin", firstHand = true)
        val player = sozinProbe.environment.playerIds.first()
        val opponent = sozinProbe.environment.playerIds.last()
        val projection = TargetEnumerationUtils(PredicateEvaluator())
        val chapterTwo = sozin.sagaChapters.single { it.chapter == 2 }
        val chapterProjection = projection.buildTargetInfos(
            state = sozinProbe.environment.state,
            playerId = player,
            targetReqs = listOfNotNull(chapterTwo.targetRequirement) +
                chapterTwo.additionalTargetRequirements,
        )
        chapterProjection.support shouldBe TargetDomainSupport.SUPPORTED
        chapterProjection.infos.single().validTargets shouldBe listOf(opponent)

        // Fire Lord Sozin's aggregate mana-value cap is bound only after X is paid. The public
        // target seam therefore records the real unsupported state instead of inventing a cap.
        val sozinBack = sozin.backFace.shouldNotBeNull()
        val backGate = sozinBack.script.triggeredAbilities
            .first { it.effect is GatedEffect }
            .effect
            .shouldBeInstanceOf<GatedEffect>()
        val reflexive = backGate.then.shouldBeInstanceOf<ReflexiveTriggerEffect>()
        val backProjection = projection.buildTargetInfos(
            state = sozinProbe.environment.state,
            playerId = player,
            targetReqs = reflexive.reflexiveTargetRequirements,
        )
        backProjection.support shouldBe TargetDomainSupport.UNSUPPORTED(
            TargetDomainUnsupportedReason.UNRESOLVED_X,
        )

        val giantfall = prepareRepositoryProbeGame(
            cardName = "Giantfall",
            firstHand = true,
            firstBattlefield = listOf("Llanowar Elves"),
            secondBattlefield = listOf("Elvish Mystic", "Bonesplitter"),
        )
        val giantfallId = giantfall.cardIdsIn(Zone.HAND).single()
        val giantfallLegalActions = giantfall.environment.legalActions()
        val giantfallActions = giantfallLegalActions
            .filter { legal ->
                val action = legal.action
                action is CastSpell && action.cardId == giantfallId
            }
        giantfallActions.map { it.actionType } shouldBe listOf("CastSpellMode", "CastSpellMode")
        giantfallActions.map { (it.action as CastSpell).chosenModes } shouldBe
            listOf(listOf(0), listOf(1))
        giantfallActions.map { it.targetRequirements.size } shouldBe listOf(2, 1)
        val giantfallViews = giantfallActions.map {
            publicActionView(giantfall.environment, giantfallLegalActions, it, registry)
        }
        giantfallViews.map { it.actionSemantics?.get("chosenModes").toString() } shouldBe
            listOf("[0]", "[1]")
        giantfallViews.map { it.targetDomain.shouldNotBeNull().requirements.size } shouldBe listOf(2, 1)
    }

    test("real Bite Down consumes two public slots and is accepted by strict Gym execution") {
        val prepared = prepareLockedGame(
            firstDeck = readLockedDeck("chevill-v0.1.txt"),
            secondDeck = readLockedDeck("akiri-v0.1.txt"),
            firstBattlefield = listOf("Forest", "Forest", "Llanowar Elves"),
            firstHand = listOf("Bite Down"),
            secondBattlefield = listOf("Brass Squire"),
            seed = 8_001L,
        )
        val gym = GameGymEnv(
            environment = prepared.environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
        )

        val observed = gym.observe()
        val observation = observed.observation.shouldBeInstanceOf<TrainingObservation>()
        observed.diagnostics.shouldBeEmpty()
        val bite = observation.legalActions.single {
            it.kind == "CastSpell" && it.description == "Cast Bite Down"
        }
        val domain = bite.targetDomain.shouldNotBeNull()

        domain.version shouldBe ACTION_TARGET_DOMAIN_VERSION
        domain.composition shouldBe ActionTargetComposition.FIXED
        domain.requirements.map { it.index } shouldBe listOf(0, 1)
        domain.requirements.map { it.minTargets to it.maxTargets } shouldBe
            listOf(1 to 1, 1 to 1)
        domain.requirements[0].candidates shouldBe listOf(prepared.firstSource)
        domain.requirements[1].candidates shouldBe listOf(requireNotNull(prepared.secondRecipient))
        domain.requirements[0].candidates.intersect(domain.requirements[1].candidates.toSet())
            .shouldBeEmpty()

        // These IDs are selected from the public ActionTargetDomain only. The setup IDs are used
        // below solely to prove that the resulting engine targets preserved the two slot roles.
        val slot0 = domain.requirements[0].candidates.single()
        val slot1 = domain.requirements[1].candidates.single()
        val payload = bitePayload(bite, paymentPayload(bite), listOf(slot0, slot1))
        val before = prepared.environment.stepCount

        val after = gym.step(bite.actionId, payload)

        after.diagnostics.shouldBeEmpty()
        prepared.environment.stepCount shouldBe before + 1
        val stackId = prepared.environment.state.stack.last()
        prepared.environment.state.getEntity(stackId)?.get<TargetsComponent>()?.targets shouldBe
            listOf(ChosenTarget.Permanent(slot0), ChosenTarget.Permanent(slot1))
    }

    test("locked Bonesplitter cast uses canonical raw targetless cardinality") {
        val prepared = prepareLockedGame(
            firstDeck = readLockedDeck("akiri-v0.1.txt"),
            secondDeck = readLockedDeck("chevill-v0.1.txt"),
            firstBattlefield = listOf("Plains", "Plains"),
            firstHand = listOf("Bonesplitter"),
            seed = 8_006L,
        )
        val bonesplitterId = prepared.environment.state.getZone(
            prepared.environment.playerIds.first(),
            Zone.HAND,
        ).single { id -> stateCardName(prepared.environment, id) == "Bonesplitter" }

        val cast = prepared.environment.legalActions().single { legal ->
            (legal.action as? CastSpell)?.cardId == bonesplitterId
        }

        cast.targetRequirements shouldBe emptyList()
        cast.requiresTargets shouldBe false
        cast.minTargets shouldBe 0
        cast.targetCount shouldBe 0
    }

    test("locked Brass Squire publishes its target domain without an unaffordable Equip blocker") {
        val prepared = prepareLockedGame(
            firstDeck = readLockedDeck("akiri-v0.1.txt"),
            secondDeck = readLockedDeck("chevill-v0.1.txt"),
            firstBattlefield = listOf("Brass Squire", "Bonesplitter", "Sram, Senior Edificer"),
            seed = 8_002L,
        )

        val legalActions = prepared.environment.legalActions()
        val brassAction = legalActions.single { legal ->
            val action = legal.action as? ActivateAbility
            action != null && stateCardName(prepared.environment, action.sourceId) == "Brass Squire"
        }
        val brassGameAction = brassAction.action.shouldBeInstanceOf<ActivateAbility>()
        val brassRequirements = brassAction.targetRequirements
        brassRequirements.map { it.index } shouldBe listOf(0, 1)
        brassRequirements.map { it.minTargets to it.maxTargets } shouldBe
            listOf(1 to 1, 1 to 1)
        brassRequirements.map { it.targetChooser } shouldBe
            listOf(TargetChooser.Controller, TargetChooser.Controller)
        brassRequirements.map { it.description } shouldBe
            listOf("target Equipment you control", "target creature you control")
        brassRequirements[0].validTargets shouldBe listOf(prepared.firstEquipment)
        brassRequirements[1].validTargets.toSet() shouldBe
            setOf(brassGameAction.sourceId, prepared.firstCreature)
        brassRequirements.flatMap { it.validTargets }.forEach { targetId ->
            prepared.environment.state.projectedState.getController(targetId) shouldBe
                prepared.environment.playerIds.first()
        }

        // The real Rules actions are passed unchanged to ObservationBuilder. The unaffordable
        // Bonesplitter Equip placeholder remains visible but does not make the observation fatal.
        val observed = ObservationBuilder(cardRegistry = registry).build(
            state = prepared.environment.state,
            perspectivePlayerId = prepared.environment.playerIds.first(),
            legalActions = legalActions,
        )
        observed.diagnostics.shouldBeEmpty()
        val squire = publicActionViewForLegalAction(observed, brassAction)
            ?: error("Brass Squire legal action was not publicly published")
        squire.kind shouldBe "ActivateAbility"
        squire.sourceEntityId shouldBe brassGameAction.sourceId
        val gym = GameGymEnv(
            environment = prepared.environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
        )

        val gymObserved = gym.observe()
        gymObserved.diagnostics.shouldBeEmpty()
        val greyedEquip = gymObserved.observation.legalActions.single {
            it.kind == "ActivateAbility" && it.sourceEntityId == prepared.firstEquipment
        }
        greyedEquip.affordable shouldBe false
        greyedEquip.paymentDomain shouldBe null
        val domain = squire.targetDomain.shouldNotBeNull()

        domain.requirements.map { it.index } shouldBe listOf(0, 1)
        domain.requirements.map { it.minTargets to it.maxTargets } shouldBe
            listOf(1 to 1, 1 to 1)
        domain.requirements.map { it.description } shouldBe
            listOf("target Equipment you control", "target creature you control")
        domain.requirements[0].candidates shouldBe listOf(prepared.firstEquipment)
        domain.requirements[1].candidates.toSet() shouldBe
            setOf(brassGameAction.sourceId, prepared.firstCreature)
    }

    test("repository optional, modal, chooser, cross-slot, and X probes fail closed without lossy domains") {
        val environment = GameEnvironment.create(registry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Forest" to 2)),
                    PlayerConfig("Bob", Deck.of("Forest" to 2)),
                ),
                startingHandSize = 0,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 8_003L,
            ),
        )
        val player = environment.playerIds.first()
        val projection = TargetEnumerationUtils(PredicateEvaluator())

        // Real repository probe: Arm the Cathars must come from GameEnvironment.legalActions(),
        // and the public observation must reject its ambiguous flat target partition. The
        // synthetic checks below exercise the partition contract independently of that probe.
        val armProbe = prepareRepositoryProbeGame(
            cardName = "Arm the Cathars",
            firstHand = true,
            firstBattlefield = listOf("Llanowar Elves", "Elvish Mystic", "Sram, Senior Edificer"),
        )
        val armId = armProbe.cardIdsIn(Zone.HAND).single()
        val armLegalActions = armProbe.environment.legalActions()
        val armAction = armLegalActions.single { legal ->
            val action = legal.action
            action is CastSpell && action.cardId == armId
        }
        armAction.targetRequirements.map { it.index } shouldBe listOf(0, 1, 2)
        armAction.targetRequirements.map { it.minTargets to it.maxTargets } shouldBe
            listOf(1 to 1, 0 to 1, 0 to 1)
        val armObserved = ObservationBuilder(cardRegistry = registry).build(
            state = armProbe.environment.state,
            perspectivePlayerId = armProbe.playerId,
            legalActions = armLegalActions,
        )
        armObserved.diagnostics.map { it.code } shouldContain
            DiagnosticCode.ACTION_TARGET_DOMAIN_UNSUPPORTED
        armObserved.registry.legalActions.none { (_, registeredAction) ->
            registeredAction === armAction
        } shouldBe true
        publicActionViewForLegalAction(armObserved, armAction) shouldBe null

        val armProjection = projection.buildTargetInfos(
            state = environment.state,
            playerId = player,
            targetReqs = registry.requireCard("Arm the Cathars").script.targetRequirements,
        )
        armProjection.support shouldBe TargetDomainSupport.SUPPORTED
        armProjection.infos.drop(1).forEach { requirement ->
            requirement.minTargets shouldBe 0
            requirement.maxTargets shouldBe 1
            requirement.mustDifferFromEarlier shouldBe true
        }
        TargetPayloadPartition.certify(armProjection.infos) shouldBe
            TargetPayloadPartition.Certification.Unsupported(
                TargetPayloadPartition.UnsupportedReason.AMBIGUOUS_FLAT_PARTITION,
            )

        TargetPayloadPartition.certify(listOf(armProjection.infos[1]))
            .shouldBeInstanceOf<TargetPayloadPartition.Certification.Supported>()

        val icyProjection = projection.buildTargetInfos(
            state = environment.state,
            playerId = player,
            targetReqs = registry.requireCard("Icy Blast").script.targetRequirements,
        )
        icyProjection.support shouldBe TargetDomainSupport.UNSUPPORTED(
            TargetDomainUnsupportedReason.UNRESOLVED_X,
        )

        val renew = registry.requireCard("Rot-Curse Rakshasa").script.activatedAbilities
            .first { it.targetRequirements.isNotEmpty() }
        val renewProjection = projection.buildTargetInfos(
            state = environment.state,
            playerId = player,
            targetReqs = renew.targetRequirements,
        )
        renewProjection.support shouldBe TargetDomainSupport.UNSUPPORTED(
            TargetDomainUnsupportedReason.UNRESOLVED_X,
        )

        val chooserProjection = projection.buildTargetInfos(
            state = environment.state,
            playerId = player,
            targetReqs = registry.requireCard("Cuombajj Witches")
                .script.activatedAbilities.single().targetRequirements,
        )
        chooserProjection.support shouldBe TargetDomainSupport.UNSUPPORTED(
            TargetDomainUnsupportedReason.NON_CONTROLLER_CHOOSER,
        )
        chooserProjection.infos[1].targetChooser shouldBe TargetChooser.Opponent

        val giantfall = registry.requireCard("Giantfall").script.spellEffect
            .shouldBeInstanceOf<ModalEffect>()
        giantfall.chooseCount shouldBe 1
        giantfall.modes.map { it.targetRequirements.size } shouldBe listOf(2, 1)
        giantfall.modes.flatMap { it.targetRequirements }.size shouldBe 3
        // The mode union is intentionally a census fact, not a V1 action domain. Each selected
        // mode must be enumerated and published independently by the action-level route.
        giantfall.modes.map { mode -> mode.targetRequirements.map { it.description } }
            .toSet().size shouldBe 2
    }

    test("ATD-04 existing Gold Rush exposes one optional 0/1 slot and executes empty and one-target payloads") {
        // Gold Rush is an existing production card, but it is not part of either locked curriculum
        // artifact. This is architectural evidence for the single optional-slot contract only; it
        // deliberately does not change or extend the persisted Akiri/Chevill decks.
        fun execute(targetCount: Int) {
            val prepared = prepareGoldRushGame()
            val gym = GameGymEnv(
                environment = prepared.environment,
                perspectivePlayerIndex = 0,
                observationBuilder = ObservationBuilder(cardRegistry = registry),
            )

            val observed = gym.observe()
            val observation = observed.observation.shouldBeInstanceOf<TrainingObservation>()
            observed.diagnostics.shouldBeEmpty()
            val goldRush = observation.legalActions.single {
                it.kind == "CastSpell" && it.description == "Cast Gold Rush"
            }
            goldRush.minTargets shouldBe 0
            goldRush.maxTargets shouldBe 1
            val domain = goldRush.targetDomain.shouldNotBeNull()
            domain.composition shouldBe ActionTargetComposition.FIXED
            domain.requirements.map { it.index } shouldBe listOf(0)
            domain.requirements.map { it.minTargets to it.maxTargets } shouldBe listOf(0 to 1)
            domain.requirements.single().candidates shouldBe listOf(prepared.targetCreature)

            // The one-target choice is read from the public domain. The empty case intentionally
            // carries no target object at all; neither branch invents an ID outside the view.
            val publicTarget = domain.requirements.single().candidates.single()
            val targets = when (targetCount) {
                0 -> emptyList()
                1 -> listOf(publicTarget)
                else -> error("Unexpected ATD-04 target count: $targetCount")
            }
            val payload = bitePayload(goldRush, paymentPayload(goldRush), targets)
            val before = prepared.environment.stepCount

            val after = gym.step(goldRush.actionId, payload)

            after.diagnostics.shouldBeEmpty()
            prepared.environment.stepCount shouldBe before + 1
            val stackId = prepared.environment.state.stack.last()
            prepared.environment.state.getEntity(stackId)?.get<CardComponent>()?.name shouldBe "Gold Rush"
            prepared.environment.state.getEntity(stackId)?.get<TargetsComponent>()?.targets.orEmpty() shouldBe
                targets.map { ChosenTarget.Permanent(it) }
        }

        execute(targetCount = 0)
        execute(targetCount = 1)
    }

    test("ATD-06 locked Putrefy with no artifact or creature is absent from Rules and Gym candidates") {
        val prepared = prepareLockedGame(
            firstDeck = readLockedDeck("chevill-v0.1.txt"),
            secondDeck = readLockedDeck("akiri-v0.1.txt"),
            firstBattlefield = listOf("Forest", "Forest", "Swamp"),
            firstHand = listOf("Putrefy"),
            seed = 8_005L,
        )
        val environment = prepared.environment
        val player = environment.playerIds.first()
        val putrefyId = environment.state.getZone(player, Zone.HAND).single { id ->
            stateCardName(environment, id) == "Putrefy"
        }
        val putrefyRequirement = registry.requireCard("Putrefy").script.targetRequirements.single()

        // This is the real card shape, not a fabricated TargetRequirement: Putrefy requires one
        // artifact-or-creature target, while the fixture provides only three lands and enough
        // mana for {1}{B}{G}.
        putrefyRequirement.effectiveMinCount shouldBe 1
        putrefyRequirement.count shouldBe 1
        environment.state.getBattlefield(player)
            .mapNotNull { stateCardName(environment, it) }
            .sorted() shouldBe listOf("Forest", "Forest", "Swamp")

        val enumeratedPutrefy = environment.legalActions().filter { legalAction ->
            (legalAction.action as? CastSpell)?.cardId == putrefyId
        }
        enumeratedPutrefy shouldBe emptyList()

        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
        )
        val observed = gym.observe()
        val observation = observed.observation.shouldBeInstanceOf<TrainingObservation>()
        observed.diagnostics.shouldBeEmpty()
        observation.legalActions.any {
            it.kind == "CastSpell" && it.description.contains("Putrefy")
        } shouldBe false

        // Gym only accepts the current published action registry; a fabricated next ID cannot
        // rescue an action that Rules enumeration did not produce.
        val before = environment.stepCount
        val unavailableActionId = (observation.legalActions.maxOfOrNull { it.actionId } ?: -1) + 1
        shouldThrow<IllegalArgumentException> { gym.step(unavailableActionId) }
        environment.stepCount shouldBe before
    }

    test("persisted locked-card set records reachable CastSpell and ActivateAbility shapes") {
        val akiri = readLockedDeck("akiri-v0.1.txt")
        val chevill = readLockedDeck("chevill-v0.1.txt")
        val persistedNames = (akiri + chevill).toSortedSet()
        val artifactMembership = persistedNames.associateWith { name ->
            when {
                name in akiri && name in chevill -> "AKIRI+CHEVILL"
                name in akiri -> "AKIRI"
                else -> "CHEVILL"
            }
        }

        akiri shouldHaveSize 100
        chevill shouldHaveSize 100
        persistedNames shouldHaveSize 146
        artifactMembership.values.count { it == "AKIRI+CHEVILL" } shouldBe 3

        val rows = persistedNames.toList().flatMapIndexed { index, name ->
            enumerateLockedCardActions(
                cardName = name,
                artifactMembership = artifactMembership.getValue(name),
                registry = registry,
                seed = 8_100L + index,
            )
        }
        val orderedRows = rows.sortedWith(
            compareBy<LockedActionCensusRow> { it.cardName }
                .thenBy { it.actionKind }
                .thenBy { it.actionType }
                .thenBy { it.description }
                .thenBy { it.targetShape },
        )
        orderedRows.map { it.cardName }.toSet() shouldBe persistedNames
        orderedRows.groupBy { it.cardName }.values.all { it.isNotEmpty() } shouldBe true

        // The artifact files provide the exact 146-card denominator. Each row below is then
        // derived from real GameEnvironment.legalActions() for that card in a deterministic
        // witness fixture (three selected copies plus bounded target witnesses), not from
        // CardDefinition declarations. This is a controlled reachability census, not a full
        // 100-card gameplay rollout or Environment-V1 acceptance run.
        val table = orderedRows.joinToString("\n", prefix = "card|artifact|action|form|targets|public|payment\n") {
            it.render()
        }
        table.lineSequence().count() shouldBe orderedRows.size + 1
        println("LOCKED_DECK_ACTION_CENSUS\n$table")

        // These are totals over executable Rules actions, not CardDefinition declarations. The
        // no-action rows keep the persisted-deck denominator explicit; payment is intentionally
        // a separate status and is never repaired or folded into target-domain support.
        val rawShapeTotals = orderedRows.groupingBy { it.targetShape }.eachCount().toSortedMap()
        val publicShapeTotals = orderedRows.groupingBy { it.publicDomain }.eachCount().toSortedMap()
        orderedRows.size shouldBe 215
        orderedRows.count { it.actionKind == "NO_REACHABLE_ACTION" } shouldBe 0
        orderedRows.count { it.actionKind == "CastSpell" } shouldBe 134
        orderedRows.count { it.actionKind == "ActivateAbility" } shouldBe 81
        orderedRows.count {
            it.targetShape != "TARGETLESS" && it.targetShape != "NO_REACHABLE_ACTION"
        } shouldBe 55
        orderedRows.count { it.publicDomain != "NOT_PUBLISHED" } shouldBe 215
        // The deterministic activated-cost certificate now also covers fixed ordinary {0},
        // and the plain fixed-kicker rail closes Tear Asunder's kicked cast. Pure sacrifice
        // overlaps are now representable after the Rules mana-payment ordering fix, reducing the
        // unsupported count for Diabolic Intent and Plumb the Forbidden by two.
        orderedRows.count { it.paymentStatus == "PAYMENT_DOMAIN_UNSUPPORTED" } shouldBe 4
        orderedRows.count { it.paymentStatus == "SUPPORTED" } shouldBe 156
        orderedRows.single {
            it.cardName == "Diabolic Intent" &&
                it.actionKind == "CastSpell" &&
                it.actionType == "CastSpell" &&
                it.description == "Cast Diabolic Intent"
        }.paymentStatus shouldBe "SUPPORTED"
        orderedRows.single {
            it.cardName == "Plumb the Forbidden" &&
                it.actionKind == "CastSpell" &&
                it.actionType == "CastSpell" &&
                it.description == "Cast Plumb the Forbidden"
        }.paymentStatus shouldBe "SUPPORTED"
        orderedRows.count { it.paymentStatus == "NOT_APPLICABLE" } shouldBe 55
        rawShapeTotals shouldBe mapOf(
            "0:1-1@-[]#1" to 1,
            "0:1-1@-[]#10" to 5,
            "0:1-1@-[]#12" to 2,
            "0:1-1@-[]#1;1:1-1@-[]#5" to 1,
            "0:1-1@-[]#2" to 4,
            "0:1-1@-[]#20" to 3,
            "0:1-1@-[]#4" to 16,
            "0:1-1@-[]#4;1:1-1@-[]#4" to 1,
            "0:1-1@-[]#5" to 1,
            "0:1-1@-[]#6" to 6,
            "0:1-1@-[]#8" to 9,
            "0:1-1@-[]#9" to 1,
            "0:1-1@Graveyard[]#15" to 1,
            "0:1-1@Graveyard[]#6" to 1,
            "0:1-1@Graveyard[]#7" to 2,
            "0:1-1@Graveyard[]#8" to 1,
            "TARGETLESS" to 160,
        )
        publicShapeTotals shouldBe rawShapeTotals
    }
})

private data class LockedActionCensusRow(
    val cardName: String,
    val artifactMembership: String,
    val actionKind: String,
    val actionType: String,
    val description: String,
    val targetShape: String,
    val publicDomain: String,
    val paymentStatus: String,
) {
    fun render(): String = listOf(
        cardName,
        artifactMembership,
        actionKind,
        actionType,
        description,
        targetShape,
        publicDomain,
        paymentStatus,
    ).joinToString("|")
}

private data class LockedCensusPreparedGame(
    val environment: GameEnvironment,
    val playerId: EntityId,
    val selectedIds: Set<EntityId>,
)

private data class RepositoryProbeGame(
    val environment: GameEnvironment,
    val playerId: EntityId,
    val selectedId: EntityId,
) {
    fun cardIdsIn(zone: Zone): List<EntityId> = environment.state.getZone(playerId, zone)
        .filter { id -> stateCardName(environment, id) == stateCardName(environment, selectedId) }
}

private fun enumerateLockedCardActions(
    cardName: String,
    artifactMembership: String,
    registry: CardRegistry,
    seed: Long,
): List<LockedActionCensusRow> {
    registry.requireCard(cardName)
    val prepared = prepareLockedCensusGame(cardName, registry, seed)
    val allLegalActions = prepared.environment.legalActions()
    val legalActions = allLegalActions.filter { legal ->
        when (val action = legal.action) {
            is CastSpell -> action.cardId in prepared.selectedIds
            is ActivateAbility -> action.sourceId in prepared.selectedIds
            else -> false
        }
    }
    if (legalActions.isEmpty()) {
        return listOf(
            LockedActionCensusRow(
                cardName = cardName,
                artifactMembership = artifactMembership,
                actionKind = "NO_REACHABLE_ACTION",
                actionType = "-",
                description = "-",
                targetShape = "NO_REACHABLE_ACTION",
                publicDomain = "NOT_PUBLISHED",
                paymentStatus = "NOT_APPLICABLE",
            ),
        )
    }

    val observationBuilder = ObservationBuilder(cardRegistry = registry)
    val observed = observationBuilder.build(
        state = prepared.environment.state,
        perspectivePlayerId = prepared.playerId,
        legalActions = allLegalActions,
    )

    return legalActions.map { legal ->
        // The same LegalAction instance is retained by the ObservationBuilder registry. Resolve
        // its canonical ordered public action ID instead of matching presentation fields, because
        // mode/target/payment variants may share kind, description, and source.
        val publicView = publicActionViewForLegalAction(observed, legal)
        LockedActionCensusRow(
            cardName = cardName,
            artifactMembership = artifactMembership,
            actionKind = when (legal.action) {
                is CastSpell -> "CastSpell"
                is ActivateAbility -> "ActivateAbility"
                else -> error("Unreachable census action kind")
            },
            actionType = legal.actionType,
            description = legal.description,
            targetShape = renderTargetShape(legal.targetRequirements),
            publicDomain = publicView?.targetDomain?.let(::renderTargetShape)
                ?: "NOT_PUBLISHED",
            paymentStatus = when {
                legal.manaCostString == null -> "NOT_APPLICABLE"
                observationBuilder.paymentDomainFor(prepared.environment.state, legal) == null ->
                    "PAYMENT_DOMAIN_UNSUPPORTED"
                else -> "SUPPORTED"
            },
        )
    }
}

private fun renderTargetShape(requirements: List<com.wingedsheep.engine.legalactions.TargetInfo>): String {
    if (requirements.isEmpty()) return "TARGETLESS"
    return requirements.joinToString(";") { requirement ->
        val flags = buildList {
            if (requirement.mustDifferFromEarlier) add("different")
            if (requirement.sameController) add("sameController")
            if (requirement.sameOwner) add("sameOwner")
            if (requirement.sameCreatureType) add("sameCreatureType")
            if (requirement.sameCardType) add("sameCardType")
            if (requirement.differentNames) add("differentNames")
            requirement.totalManaValueAtMost?.let { add("totalMV<=$it") }
            if (requirement.xConstrainsManaValue) add("xManaValue")
            if (requirement.xConstrainsManaValueExactly) add("xManaValueExactly")
            if (requirement.xConstrainsPower) add("xPower")
            if (requirement.xConstrainsCount) add("xCount")
        }.joinToString(",", prefix = "[", postfix = "]")
        "${requirement.index}:${requirement.minTargets}-${requirement.maxTargets}" +
            "@${requirement.targetZone ?: "-"}$flags#${requirement.validTargets.size}"
    }
}

private fun renderTargetShape(domain: ActionTargetDomainV1): String {
    if (domain.requirements.isEmpty()) return "TARGETLESS"
    return domain.requirements.joinToString(";") { requirement ->
        val flags = buildList {
            if (requirement.mustDifferFromEarlier) add("different")
            if (requirement.sameController) add("sameController")
            if (requirement.sameOwner) add("sameOwner")
            if (requirement.sameCreatureType) add("sameCreatureType")
            if (requirement.sameCardType) add("sameCardType")
            if (requirement.differentNames) add("differentNames")
            requirement.totalManaValueAtMost?.let { add("totalMV<=$it") }
            if (requirement.xConstrainsManaValue) add("xManaValue")
            if (requirement.xConstrainsManaValueExactly) add("xManaValueExactly")
            if (requirement.xConstrainsPower) add("xPower")
            if (requirement.xConstrainsCount) add("xCount")
        }.joinToString(",", prefix = "[", postfix = "]")
        "${requirement.index}:${requirement.minTargets}-${requirement.maxTargets}" +
            "@${requirement.targetZone ?: "-"}$flags#${requirement.candidates.size}"
    }
}

private fun prepareLockedCensusGame(
    cardName: String,
    registry: CardRegistry,
    seed: Long,
): LockedCensusPreparedGame {
    val witnessDeck = CENSUS_BATTLEFIELD_WITNESSES + CENSUS_GRAVEYARD_WITNESSES
    val environment = GameEnvironment.create(registry)
    environment.reset(
        GameConfig(
            players = listOf(
                PlayerConfig("Census", Deck(listOf(cardName, cardName, cardName) + witnessDeck)),
                PlayerConfig("Opponent", Deck(witnessDeck)),
            ),
            startingHandSize = 0,
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = seed,
        ),
    )

    // Enter the priority window before moving fixture cards. This keeps cards with upkeep/ETB
    // triggers from creating an unrelated pending decision while the census state is assembled.
    advanceToPrecombatMain(environment, "locked census baseline")
    val player = environment.playerIds.first()
    val opponent = environment.playerIds.last()
    var state = environment.state
    val selectedIds = linkedSetOf<EntityId>()

    fun moveNamed(owner: EntityId, name: String, destination: Zone): EntityId {
        val library = ZoneKey(owner, Zone.LIBRARY)
        val definitionId = cardDefinitionId(registry.requireCard(name))
        val id = state.getZone(library).firstOrNull { candidate ->
            state.getEntity(candidate)?.get<CardComponent>()?.cardDefinitionId == definitionId
        } ?: error("Census fixture has no '$name' for $owner")
        state = state.moveToZone(id, library, ZoneKey(owner, destination))
        return id
    }

    selectedIds += moveNamed(player, cardName, Zone.HAND)
    selectedIds += moveNamed(player, cardName, Zone.BATTLEFIELD)
    selectedIds += moveNamed(player, cardName, Zone.GRAVEYARD)
    CENSUS_BATTLEFIELD_WITNESSES.forEach { name -> moveNamed(player, name, Zone.BATTLEFIELD) }
    CENSUS_GRAVEYARD_WITNESSES.forEach { name -> moveNamed(player, name, Zone.GRAVEYARD) }
    CENSUS_BATTLEFIELD_WITNESSES.forEach { name -> moveNamed(opponent, name, Zone.BATTLEFIELD) }
    CENSUS_GRAVEYARD_WITNESSES.forEach { name -> moveNamed(opponent, name, Zone.GRAVEYARD) }

    state = state.updateEntity(player) { container ->
        container.withComponent(
            ManaPoolComponent(
                white = 20,
                blue = 20,
                black = 20,
                red = 20,
                green = 20,
                colorless = 20,
            ),
        )
    }
    environment.restore(state, environment.playerIds, environment.stepCount)
    return LockedCensusPreparedGame(environment, player, selectedIds)
}

private val CENSUS_BATTLEFIELD_WITNESSES = listOf(
    "Forest",
    "Swamp",
    "Plains",
    "Mountain",
    "Llanowar Elves",
    "Elvish Mystic",
    "Sram, Senior Edificer",
    "Brass Squire",
    "Bonesplitter",
    "Guardian Project",
)

private val CENSUS_GRAVEYARD_WITNESSES = listOf(
    "Llanowar Elves",
    "Elvish Mystic",
    "Sram, Senior Edificer",
    "Brass Squire",
    "Viscera Seer",
    "Scavenging Ooze",
    "Bonesplitter",
)

private fun prepareRepositoryProbeGame(
    cardName: String,
    firstHand: Boolean = false,
    firstBattlefield: List<String> = emptyList(),
    firstGraveyard: List<String> = emptyList(),
    secondBattlefield: List<String> = emptyList(),
    secondGraveyard: List<String> = emptyList(),
): RepositoryProbeGame {
    val firstDeck = listOf(cardName) + firstBattlefield + firstGraveyard
    val secondDeck = listOf("Forest") + secondBattlefield + secondGraveyard
    val environment = GameEnvironment.create(lockedCatalogRegistry())
    environment.reset(
        GameConfig(
            players = listOf(
                PlayerConfig("Probe", Deck(firstDeck)),
                PlayerConfig("Opponent", Deck(secondDeck)),
            ),
            startingHandSize = 0,
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = 8_090L,
        ),
    )

    advanceToPrecombatMain(environment, "repository probe baseline")
    val player = environment.playerIds.first()
    val opponent = environment.playerIds.last()
    var state = environment.state

    fun moveNamed(owner: EntityId, name: String, destination: Zone): EntityId {
        val library = ZoneKey(owner, Zone.LIBRARY)
        val id = state.getZone(library).firstOrNull { candidate ->
            state.getEntity(candidate)?.get<CardComponent>()?.name == name
        } ?: error("Repository probe fixture has no '$name' for $owner")
        state = state.moveToZone(id, library, ZoneKey(owner, destination))
        return id
    }

    val selectedId = moveNamed(player, cardName, if (firstHand) Zone.HAND else Zone.BATTLEFIELD)
    firstBattlefield.forEach { name -> moveNamed(player, name, Zone.BATTLEFIELD) }
    firstGraveyard.forEach { name -> moveNamed(player, name, Zone.GRAVEYARD) }
    secondBattlefield.forEach { name -> moveNamed(opponent, name, Zone.BATTLEFIELD) }
    secondGraveyard.forEach { name -> moveNamed(opponent, name, Zone.GRAVEYARD) }
    state = state.updateEntity(player) { container ->
        container.withComponent(
            ManaPoolComponent(
                white = 20,
                blue = 20,
                black = 20,
                red = 20,
                green = 20,
                colorless = 20,
            ),
        )
    }
    environment.restore(state, environment.playerIds, environment.stepCount)
    return RepositoryProbeGame(environment, player, selectedId)
}

private fun publicActionView(
    environment: GameEnvironment,
    legalActions: List<LegalAction>,
    legalAction: LegalAction,
    registry: CardRegistry,
): LegalActionView {
    val result = ObservationBuilder(cardRegistry = registry).build(
        state = environment.state,
        perspectivePlayerId = environment.playerIds.first(),
        legalActions = legalActions,
    )
    return publicActionViewForLegalAction(result, legalAction)
        ?: error("Legal action was not published: ${legalAction.description}")
}

private fun publicActionViewForLegalAction(
    observed: ObservationResult,
    legalAction: LegalAction,
): LegalActionView? {
    val registryMatches = observed.registry.legalActions.filter { (_, registeredAction) ->
        registeredAction === legalAction
    }
    check(registryMatches.size <= 1) {
        "One LegalAction instance mapped to multiple public action IDs: ${legalAction.description}"
    }
    val actionId = registryMatches.singleOrNull()?.first ?: return null
    return observed.observation.shouldBeInstanceOf<TrainingObservation>().legalActions.single {
        it.actionId == actionId
    }
}

private fun advanceToPrecombatMain(environment: GameEnvironment, fixtureName: String) {
    var advances = 0
    while (environment.state.step != Step.PRECOMBAT_MAIN) {
        val pass = environment.legalActions().firstOrNull { it.action is PassPriority }
            ?: error("Expected PassPriority while preparing $fixtureName fixture: ${environment.state.step}")
        environment.step(pass.action)
        check(++advances < 20) { "$fixtureName fixture did not reach precombat main" }
    }
}

private data class LockedPreparedGame(
    val environment: GameEnvironment,
    val firstSource: EntityId,
    val secondRecipient: EntityId?,
    val firstEquipment: EntityId = EntityId("unused-equipment"),
    val firstCreature: EntityId = EntityId("unused-creature"),
)

private fun prepareLockedGame(
    firstDeck: List<String>,
    secondDeck: List<String>,
    firstBattlefield: List<String>,
    firstHand: List<String> = emptyList(),
    secondBattlefield: List<String> = emptyList(),
    seed: Long,
): LockedPreparedGame {
    val cardRegistry = lockedCatalogRegistry()
    val environment = GameEnvironment.create(cardRegistry)
    environment.reset(
        GameConfig(
            players = listOf(
                PlayerConfig("First", Deck(firstDeck)),
                PlayerConfig("Second", Deck(secondDeck)),
            ),
            startingHandSize = 0,
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = seed,
        ),
    )

    val first = environment.playerIds[0]
    val second = environment.playerIds[1]
    var state = environment.state

    fun moveNamed(player: EntityId, name: String, destination: Zone): EntityId {
        val library = ZoneKey(player, Zone.LIBRARY)
        val id = state.getZone(library).firstOrNull { candidate ->
            state.getEntity(candidate)?.get<CardComponent>()?.name == name
        } ?: error("Locked fixture has no '$name' for $player")
        state = state.moveToZone(id, library, ZoneKey(player, destination))
        return id
    }

    firstHand.forEach { moveNamed(first, it, Zone.HAND) }
    val firstIds = firstBattlefield.map { moveNamed(first, it, Zone.BATTLEFIELD) }
    val secondIds = secondBattlefield.map { moveNamed(second, it, Zone.BATTLEFIELD) }
    environment.restore(state, environment.playerIds, environment.stepCount)

    var advances = 0
    while (environment.state.step != Step.PRECOMBAT_MAIN) {
        val pass = environment.legalActions().firstOrNull { it.action is PassPriority }
            ?: error("Expected PassPriority while preparing locked fixture: ${environment.state.step}")
        environment.step(pass.action)
        check(++advances < 20) { "Locked fixture did not reach precombat main" }
    }

    val firstSource = firstIds.firstOrNull { id ->
        stateCardName(environment, id) == "Llanowar Elves"
    } ?: firstIds.firstOrNull { id -> stateCardName(environment, id) == "Brass Squire" }
        ?: EntityId("unused-source")
    val secondRecipient = secondIds.firstOrNull { id -> stateCardName(environment, id) == "Brass Squire" }
    val firstEquipment = firstIds.firstOrNull { id -> stateCardName(environment, id) == "Bonesplitter" }
        ?: EntityId("unused-equipment")
    val firstCreature = firstIds.firstOrNull { id -> stateCardName(environment, id) == "Sram, Senior Edificer" }
        ?: EntityId("unused-creature")
    return LockedPreparedGame(environment, firstSource, secondRecipient, firstEquipment, firstCreature)
}

private fun bitePayload(
    view: LegalActionView,
    payment: JsonObject?,
    targets: List<EntityId>,
): JsonObject = buildJsonObject {
    view.actionSemantics.shouldNotBeNull().forEach { (key, value) -> put(key, value) }
    payment?.forEach { (key, value) -> put(key, value) }
    put(
        "targets",
        buildJsonArray {
            targets.forEach { target ->
                add(buildJsonObject {
                    put("type", "Permanent")
                    put("entityId", target.value)
                })
            }
        },
    )
}

private fun paymentPayload(view: LegalActionView): JsonObject {
    val domain = view.paymentDomain ?: error("Expected a public payment domain for ${view.description}")
    val plan = paymentPlanV3FromPublic(domain)
        ?: error("Expected a complete public PaymentDomainV5 plan for ${view.description}")
    val json = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }
    return buildJsonObject {
        put(
            "paymentStrategy",
            json.encodeToJsonElement(
                PaymentStrategy.serializer(),
                PaymentStrategy.ExplicitV3(paymentPlan = plan),
            ),
        )
    }
}

private data class GoldRushPreparedGame(
    val environment: GameEnvironment,
    val targetCreature: EntityId,
)

private fun prepareGoldRushGame(): GoldRushPreparedGame {
    val environment = GameEnvironment.create(lockedCatalogRegistry())
    environment.reset(
        GameConfig(
            players = listOf(
                PlayerConfig(
                    "First",
                    Deck.of("Gold Rush" to 1, "Forest" to 4, "Llanowar Elves" to 1),
                ),
                PlayerConfig("Second", Deck.of("Forest" to 2)),
            ),
            startingHandSize = 0,
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = 8_004L,
        ),
    )

    val player = environment.playerIds.first()
    var state = environment.state

    fun moveNamed(name: String, destination: Zone): EntityId {
        val library = ZoneKey(player, Zone.LIBRARY)
        val id = state.getZone(library).firstOrNull { candidate ->
            stateCardName(environment, candidate) == name
        } ?: error("Repository fixture has no '$name' for $player")
        state = state.moveToZone(id, library, ZoneKey(player, destination))
        return id
    }

    moveNamed("Gold Rush", Zone.HAND)
    moveNamed("Forest", Zone.BATTLEFIELD)
    moveNamed("Forest", Zone.BATTLEFIELD)
    val targetCreature = moveNamed("Llanowar Elves", Zone.BATTLEFIELD)
    environment.restore(state, environment.playerIds, environment.stepCount)

    var advances = 0
    while (environment.state.step != Step.PRECOMBAT_MAIN) {
        val pass = environment.legalActions().firstOrNull { it.action is PassPriority }
            ?: error("Expected PassPriority while preparing Gold Rush fixture: ${environment.state.step}")
        environment.step(pass.action)
        check(++advances < 20) { "Gold Rush fixture did not reach precombat main" }
    }

    return GoldRushPreparedGame(environment, targetCreature)
}

private fun stateCardName(environment: GameEnvironment, id: EntityId): String? =
    environment.state.getEntity(id)?.get<CardComponent>()?.name

private fun cardDefinitionId(card: CardDefinition): String = card.metadata.collectorNumber?.let { collectorNumber ->
    if (card.setCode != null) {
        "${card.name}#${card.setCode}-$collectorNumber"
    } else {
        "${card.name}#$collectorNumber"
    }
} ?: card.name

private fun lockedCatalogRegistry(): CardRegistry = CardRegistry().apply {
    MtgSetCatalog.all.forEach { set ->
        register(set.cards.map { it.withSetCodeIfMissing(set.code) })
        register(set.basicLands.map { it.withSetCodeIfMissing(set.code) })
    }
}

private fun CardDefinition.withSetCodeIfMissing(code: String): CardDefinition =
    if (this.setCode == null) copy(setCode = code) else this

private fun readLockedDeck(fileName: String): List<String> {
    val path = repositoryRoot().resolve("docs/ml/curriculum").resolve(fileName)
    return Files.readAllLines(path)
        .asSequence()
        .filter { it.length >= 4 && it[0].isDigit() && it[1].isDigit() && it[2].isDigit() && it[3] == '\t' }
        .map { it.substringAfterLast('\t') }
        .toList()
}

private fun repositoryRoot(): Path {
    var candidate = Paths.get("").toAbsolutePath().normalize()
    while (!Files.exists(candidate.resolve("docs/ml/curriculum/akiri-v0.1.txt"))) {
        candidate = candidate.parent ?: error("Could not locate repository root from $candidate")
    }
    return candidate
}
