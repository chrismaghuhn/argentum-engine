package com.wingedsheep.gym

import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.ManaSpentEvent
import com.wingedsheep.engine.core.CounterUnlessPaysContinuation
import com.wingedsheep.engine.core.CounterUnlessPaysManaSelectionContinuation
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SourceActivationV2
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.player.PayOrSufferExecutor
import com.wingedsheep.engine.mechanics.cost.CostPaymentContext
import com.wingedsheep.engine.mechanics.cost.CostPaymentService
import com.wingedsheep.engine.mechanics.cost.PaymentResult
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidation
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidator
import com.wingedsheep.engine.mechanics.mana.SpellPaymentContext
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.ChosenSemanticResponseV1
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.ManaSourcesDomain
import com.wingedsheep.gym.contract.PaymentDomainV5
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.effects.PayOrSufferEffect
import com.wingedsheep.sdk.scripting.effects.WardCost
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Contract witness for the real pending-payment boundary that A8 characterization found.
 *
 * The setup reaches Rules through Mentor of the Meek; it does not construct a pending decision or
 * a payment domain by hand. Battlefield Forge is intentionally the witness because choosing its
 * red and white productions is a real player choice even when the outer {1} cost accepts either.
 */
class PendingManaPaymentDomainTest : ScenarioTestBase() {

    private val v3CombatFlyingBlocker = card("Pending V3 Combat Flying Blocker") {
        manaCost = "{0}"
        typeLine = "Creature — Bird"
        power = 1
        toughness = 1
        keywords(Keyword.FLYING)
    }

    private val v3CompositeWardBearer = card("Pending V3 Composite Ward Bearer") {
        manaCost = "{0}"
        typeLine = "Creature — Human"
        power = 1
        toughness = 1
        keywordAbility(KeywordAbility.wardComposite(WardCost.Mana("{2}"), WardCost.Life(2)))
    }

    init {
        test("locked Mentor pending payment publishes both Battlefield Forge productions in a complete V5 domain") {
            lockedAkiriCards().let { cards ->
                setOf("Mentor of the Meek", "Fervent Champion", "Battlefield Forge")
                    .all(cards::contains) shouldBe true
            }

            val witness = mentorPendingWitness()
            val sourceDomain = witness.sourceDomain
            val domainJson = A3SemanticJson.strictJson
                .encodeToJsonElement(ManaSourcesDomain.serializer(), sourceDomain)
                .jsonObject
            val paymentJson = domainJson["paymentDomain"]?.jsonObject
                ?: error("Pending mana domain has no complete V5 payment domain")
            val paymentDomain = A3SemanticJson.strictJson.decodeFromJsonElement(
                PaymentDomainV5.serializer(),
                paymentJson,
            )

            val forgeColors = paymentDomain.sourceActivationOptions
                .filter { it.sourceName == "Battlefield Forge" }
                .flatMap { it.productionChoices }
                .map { it.producedColor }
                .toSet()
            forgeColors.containsAll(setOf(PaymentManaColor.RED, PaymentManaColor.WHITE)) shouldBe true
        }

        test("locked Mentor payment executes the externally selected Battlefield Forge production") {
            val red = mentorPendingWitness()
            val white = mentorPendingWitness()
            val redPlan = forgePlan(red.paymentDomain, PaymentManaColor.RED)
            val whitePlan = forgePlan(white.paymentDomain, PaymentManaColor.WHITE)
            val redResponse = ManaSourcesSelectedResponse(red.decisionId, paymentPlan = redPlan)
            val whiteResponse = ManaSourcesSelectedResponse(white.decisionId, paymentPlan = whitePlan)

            val redChosen = ChosenSemanticResponseV1.from(
                CompleteLegalDomainV1.from(red.observation),
                redResponse,
            )
            val whiteChosen = ChosenSemanticResponseV1.from(
                CompleteLegalDomainV1.from(white.observation),
                whiteResponse,
            )
            redChosen.response.keys shouldBe setOf("type", "paymentPlan")
            redChosen.canonicalJson() shouldNotBe whiteChosen.canonicalJson()

            red.gym.submitDecision(redResponse)
            white.gym.submitDecision(whiteResponse)

            val redSpent = red.environment.lastStepEvents.filterIsInstance<ManaSpentEvent>().single()
            val whiteSpent = white.environment.lastStepEvents.filterIsInstance<ManaSpentEvent>().single()
            redSpent.red shouldBe 1
            redSpent.white shouldBe 0
            whiteSpent.red shouldBe 0
            whiteSpent.white shouldBe 1
        }

        test("pending payment membership rejects invalid production, incomplete allocation, and AutoPay") {
            val witness = mentorPendingWitness()
            val domain = CompleteLegalDomainV1.from(witness.observation)
            val plan = forgePlan(witness.paymentDomain, PaymentManaColor.RED)
            val activation = plan.activations.single()
            val invalidProduction = plan.copy(
                activations = listOf(
                    activation.copy(
                        productionChoice = activation.productionChoice.copy(
                            producedColor = PaymentManaColor.BLUE,
                        )
                    )
                )
            )

            shouldThrow<IllegalArgumentException> {
                ChosenSemanticResponseV1.from(
                    domain,
                    ManaSourcesSelectedResponse(
                        witness.decisionId,
                        paymentPlan = invalidProduction,
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                ChosenSemanticResponseV1.from(
                    domain,
                    ManaSourcesSelectedResponse(
                        witness.decisionId,
                        paymentPlan = plan.copy(outerAllocation = emptyList()),
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                ChosenSemanticResponseV1.from(
                    domain,
                    ManaSourcesSelectedResponse(
                        witness.decisionId,
                        paymentPlan = plan.copy(
                            activations = listOf(activation.copy(sourceId = com.wingedsheep.sdk.model.EntityId("outside-domain"))),
                        ),
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                ChosenSemanticResponseV1.from(
                    domain,
                    ManaSourcesSelectedResponse(witness.decisionId, autoPay = true),
                )
            }

            val before = witness.environment.state
            shouldThrow<IllegalArgumentException> {
                witness.environment.stepStrict(
                    SubmitDecision(
                        witness.observation.agentToAct ?: error("Missing pending-payment actor"),
                        ManaSourcesSelectedResponse(witness.decisionId, paymentPlan = invalidProduction),
                    )
                )
            }
            witness.environment.state shouldBe before
            shouldThrow<IllegalArgumentException> {
                witness.gym.submitDecision(ManaSourcesSelectedResponse(witness.decisionId, autoPay = true))
            }
            witness.environment.state shouldBe before
        }

        test("pending payment semantic identity excludes the live decision nonce") {
            val witness = mentorPendingWitness()
            val domain = CompleteLegalDomainV1.from(witness.observation)
            val plan = forgePlan(witness.paymentDomain, PaymentManaColor.RED)

            val first = ChosenSemanticResponseV1.from(
                domain,
                ManaSourcesSelectedResponse("first-live-decision", paymentPlan = plan),
            )
            val second = ChosenSemanticResponseV1.from(
                domain,
                ManaSourcesSelectedResponse("second-live-decision", paymentPlan = plan),
            )
            first.canonicalJson() shouldBe second.canonicalJson()
        }

        test("pending payment publishes and consumes an explicit initial-pool bucket deterministically") {
            val first = mentorPendingWitness(withFloatingRed = true)
            val second = mentorPendingWitness(withFloatingRed = true)
            val firstDomain = A3SemanticJson.strictJson.encodeToJsonElement(
                PaymentDomainV5.serializer(),
                first.paymentDomain,
            )
            val secondDomain = A3SemanticJson.strictJson.encodeToJsonElement(
                PaymentDomainV5.serializer(),
                second.paymentDomain,
            )
            A3SemanticJson.canonicalJson(firstDomain) shouldBe A3SemanticJson.canonicalJson(secondDomain)

            val plan = initialRedPoolPlan(first.paymentDomain)
            plan.activations shouldBe emptyList()
            ChosenSemanticResponseV1.from(
                CompleteLegalDomainV1.from(first.observation),
                ManaSourcesSelectedResponse(first.decisionId, paymentPlan = plan),
            ).response.keys shouldBe setOf("type", "paymentPlan")

            first.gym.submitDecision(ManaSourcesSelectedResponse(first.decisionId, paymentPlan = plan))
            val spent = first.environment.lastStepEvents.filterIsInstance<ManaSpentEvent>().single()
            spent.red shouldBe 1
            spent.white shouldBe 0
        }

        test("Ward pending payment reuses the same explicit V3 payment program") {
            val witness = wardPendingWitness()
            val plan = forgePlan(witness.paymentDomain, PaymentManaColor.WHITE)
            val response = ManaSourcesSelectedResponse(witness.decisionId, paymentPlan = plan)

            ChosenSemanticResponseV1.from(
                CompleteLegalDomainV1.from(witness.observation),
                response,
            ).response.keys shouldBe setOf("type", "paymentPlan")
            witness.gym.submitDecision(response)

            val spent = witness.environment.lastStepEvents.filterIsInstance<ManaSpentEvent>().single()
            spent.white shouldBe 1
            spent.red shouldBe 0
        }

        test("attack tax commits the declared attack after a public V3 payment") {
            val game = scenario()
                .withPlayers()
                .withFormat(Format.Commander())
                .withCardOnBattlefield(1, "Fervent Champion")
                .withCardOnBattlefield(1, "Battlefield Forge")
                .withCardOnBattlefield(2, "Archangel of Tithes")
                .withActivePlayer(1)
                .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                .withRngSeed(13L)
                .build()

            game.declareAttackers(mapOf("Fervent Champion" to 2)).error shouldBe null
            game.getPendingDecision().shouldBeInstanceOf<SelectManaSourcesDecision>()
            val fixture = gymForState(game.state, game.player1Id, game.player2Id, perspectivePlayerIndex = 0)
            val observation = fixture.gym.observe().observation.shouldBeInstanceOf<TrainingObservation>()
            val pending = observation.pendingDecision ?: error("Missing attack-tax payment decision")
            val domain = pending.structuredDomain.shouldBeInstanceOf<ManaSourcesDomain>()

            fixture.gym.submitDecision(
                ManaSourcesSelectedResponse(
                    pending.decisionId ?: error("Missing attack-tax decision ID"),
                    paymentPlan = sourcePlan(domain.paymentDomain, "Battlefield Forge", PaymentManaColor.RED),
                ),
            )

            fixture.environment.lastStepEvents.filterIsInstance<ManaSpentEvent>().single().red shouldBe 1
            battlefieldCardId(fixture.environment.state, game.player1Id, "Battlefield Forge")
                .let { fixture.environment.state.getEntity(it)!!.has<TappedComponent>() } shouldBe true
        }

        test("block tax commits the declared block after a public V3 payment") {
            cardRegistry.register(v3CombatFlyingBlocker)
            val game = scenario()
                .withPlayers()
                .withFormat(Format.Commander())
                .withCardOnBattlefield(1, "Archangel of Tithes")
                .withCardOnBattlefield(2, v3CombatFlyingBlocker.name)
                .withCardOnBattlefield(2, "Battlefield Forge")
                .withActivePlayer(1)
                .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                .withRngSeed(14L)
                .build()

            game.declareAttackers(mapOf("Archangel of Tithes" to 2)).error shouldBe null
            game.passUntilPhase(Phase.COMBAT, Step.DECLARE_BLOCKERS)
            game.declareBlockers(mapOf(v3CombatFlyingBlocker.name to listOf("Archangel of Tithes"))).error shouldBe null
            game.getPendingDecision().shouldBeInstanceOf<SelectManaSourcesDecision>()
            val fixture = gymForState(game.state, game.player1Id, game.player2Id, perspectivePlayerIndex = 1)
            val observation = fixture.gym.observe().observation.shouldBeInstanceOf<TrainingObservation>()
            val pending = observation.pendingDecision ?: error("Missing block-tax payment decision")
            val domain = pending.structuredDomain.shouldBeInstanceOf<ManaSourcesDomain>()

            fixture.gym.submitDecision(
                ManaSourcesSelectedResponse(
                    pending.decisionId ?: error("Missing block-tax decision ID"),
                    paymentPlan = sourcePlan(domain.paymentDomain, "Battlefield Forge", PaymentManaColor.WHITE),
                ),
            )

            fixture.environment.lastStepEvents.filterIsInstance<ManaSpentEvent>().single().white shouldBe 1
            battlefieldCardId(fixture.environment.state, game.player2Id, "Battlefield Forge")
                .let { fixture.environment.state.getEntity(it)!!.has<TappedComponent>() } shouldBe true
        }

        test("generic counter-unless payment opens an explicit unpaid branch before the V3 domain") {
            val game = scenario()
                .withPlayers()
                .withFormat(Format.Commander())
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .withRngSeed(11L)
                .build()
            val prompt = YesNoDecision(
                id = "counter-unless-yes",
                playerId = game.player1Id,
                prompt = "Pay {1}?",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
            )
            val state = game.state.copy(pendingDecision = prompt).pushContinuation(
                CounterUnlessPaysContinuation(
                    decisionId = prompt.id,
                    payingPlayerId = game.player1Id,
                    spellEntityId = com.wingedsheep.sdk.model.EntityId("counter-unless-spell"),
                    manaCost = com.wingedsheep.sdk.core.ManaCost.parse("{1}"),
                    sourceId = null,
                    sourceName = "Counter unless pays",
                ),
            )
            val environment = GameEnvironment.create(
                cardRegistry = cardRegistry,
                executionMode = GameEnvironmentMode.TRUSTED,
            )
            environment.restore(state, listOf(game.player1Id, game.player2Id))
            val gym = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = com.wingedsheep.gym.contract.ObservationBuilder(cardRegistry = cardRegistry),
            )

            gym.submitDecision(YesNoResponse(prompt.id, true))
            val pending = environment.state.pendingDecision.shouldBeInstanceOf<SelectManaSourcesDecision>()
            pending.canDecline shouldBe true
            val observation = gym.observe().observation.shouldBeInstanceOf<TrainingObservation>()
            val domain = observation.pendingDecision!!.structuredDomain.shouldBeInstanceOf<ManaSourcesDomain>()
            domain.canDecline shouldBe true
            ChosenSemanticResponseV1.from(
                CompleteLegalDomainV1.from(observation),
                ManaSourcesSelectedResponse(pending.id, declined = true),
            ).response["declined"].toString() shouldBe "true"
        }

        test("CostPayment paid follow-up runs after a public V3 payment") {
            val game = scenario()
                .withPlayers()
                .withFormat(Format.Commander())
                .withCardOnBattlefield(1, "Goblin Guide")
                .withCardOnBattlefield(1, "Forest")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .withRngSeed(15L)
                .build()
            val sourceId = game.findPermanent("Goblin Guide") ?: error("Missing cost-payment source")
            val pending = CostPaymentService(EngineServices(cardRegistry)).pay(
                state = game.state,
                payerId = game.player1Id,
                cost = Costs.pay.Mana("{G}"),
                sourceId = sourceId,
                ctx = CostPaymentContext(onPaid = Effects.GainLife(1)),
            ).shouldBeInstanceOf<PaymentResult.Pending>()
            game.state = pending.state
            game.submitDecision(YesNoResponse(pending.pendingDecision.id, true)).error shouldBe null
            game.getPendingDecision().shouldBeInstanceOf<SelectManaSourcesDecision>()

            val fixture = gymForState(game.state, game.player1Id, game.player2Id, perspectivePlayerIndex = 0)
            val observation = fixture.gym.observe().observation.shouldBeInstanceOf<TrainingObservation>()
            val pendingView = observation.pendingDecision ?: error("Missing cost-payment source decision")
            val domain = pendingView.structuredDomain.shouldBeInstanceOf<ManaSourcesDomain>()
            fixture.gym.submitDecision(
                ManaSourcesSelectedResponse(
                    pendingView.decisionId ?: error("Missing cost-payment decision ID"),
                    paymentPlan = sourcePlan(domain.paymentDomain, "Forest", PaymentManaColor.GREEN),
                ),
            )

            fixture.environment.lastStepEvents.filterIsInstance<ManaSpentEvent>().single().green shouldBe 1
            fixture.environment.state.getEntity(game.player1Id)!!.get<LifeTotalComponent>()!!.life shouldBe 21
        }

        test("PayOrSuffer paid branch runs after a public V3 payment") {
            val game = scenario()
                .withPlayers()
                .withFormat(Format.Commander())
                .withCardOnBattlefield(1, "Goblin Guide")
                .withCardOnBattlefield(1, "Forest")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .withRngSeed(16L)
                .build()
            val sourceId = game.findPermanent("Goblin Guide") ?: error("Missing pay-or-suffer source")
            val result = PayOrSufferExecutor(cardRegistry).execute(
                state = game.state,
                effect = PayOrSufferEffect(
                    cost = Costs.pay.Mana("{G}"),
                    suffer = Effects.LoseLife(7),
                ),
                context = EffectContext(sourceId = sourceId, controllerId = game.player1Id),
            )
            game.state = result.state
            val yes = result.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
            game.submitDecision(YesNoResponse(yes.id, true)).error shouldBe null
            game.getPendingDecision().shouldBeInstanceOf<SelectManaSourcesDecision>()

            val fixture = gymForState(game.state, game.player1Id, game.player2Id, perspectivePlayerIndex = 0)
            val observation = fixture.gym.observe().observation.shouldBeInstanceOf<TrainingObservation>()
            val pending = observation.pendingDecision ?: error("Missing pay-or-suffer source decision")
            val domain = pending.structuredDomain.shouldBeInstanceOf<ManaSourcesDomain>()
            fixture.gym.submitDecision(
                ManaSourcesSelectedResponse(
                    pending.decisionId ?: error("Missing pay-or-suffer decision ID"),
                    paymentPlan = sourcePlan(domain.paymentDomain, "Forest", PaymentManaColor.GREEN),
                ),
            )

            fixture.environment.lastStepEvents.filterIsInstance<ManaSpentEvent>().single().green shouldBe 1
            fixture.environment.state.getEntity(game.player1Id)!!.get<LifeTotalComponent>()!!.life shouldBe 20
        }

        test("targeted triggered may-pay source window fails closed before a V3 domain") {
            val game = scenario()
                .withPlayers()
                .withFormat(Format.Commander())
                .withCardOnBattlefield(1, "Frenzied Goblin")
                .withCardOnBattlefield(1, "Mountain")
                .withCardOnBattlefield(2, "Grizzly Bears")
                .withActivePlayer(1)
                .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                .withRngSeed(17L)
                .build()

            game.declareAttackers(mapOf("Frenzied Goblin" to 2)).error shouldBe null
            game.resolveStack()
            val yes = game.getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
            game.submitDecision(YesNoResponse(yes.id, true)).error shouldBe null
            game.getPendingDecision().shouldBeInstanceOf<SelectManaSourcesDecision>().canDecline shouldBe false

            val fixture = gymForState(game.state, game.player1Id, game.player2Id, perspectivePlayerIndex = 0)
            shouldThrow<UnsupportedPathFailure> { fixture.gym.observe() }
        }

        test("Waterbend with a tap-to-reduce choice fails closed instead of publishing a partial payment domain") {
            val game = scenario()
                .withPlayers()
                .withFormat(Format.Commander())
                .withCardOnBattlefield(1, "Mentor of the Meek")
                .withCardOnBattlefield(1, "Fervent Champion")
                .withCardInHand(1, "Waterbending Lesson")
                .withCardInLibrary(1, "Plains")
                .withCardInLibrary(1, "Mountain")
                .withCardInLibrary(1, "Island")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .withRngSeed(10L)
                .build()
            game.state = game.state.updateEntity(game.player1Id) { container ->
                container.with(ManaPoolComponent(blue = 4))
            }
            game.castSpell(1, "Waterbending Lesson").error shouldBe null
            game.resolveStack()
            val pending = game.getPendingDecision().shouldBeInstanceOf<SelectManaSourcesDecision>()
            pending.waterbendPermanents.isNotEmpty() shouldBe true

            val environment = GameEnvironment.create(
                cardRegistry = cardRegistry,
                executionMode = GameEnvironmentMode.TRUSTED,
            )
            environment.restore(game.state, listOf(game.player1Id, game.player2Id))
            val gym = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = com.wingedsheep.gym.contract.ObservationBuilder(cardRegistry = cardRegistry),
            )
            shouldThrow<UnsupportedPathFailure> { gym.observe() }

            // The same unsupported shape must be rejected by Rules before a direct legacy client
            // can partially execute a V3 payment behind the Gym boundary.
            val before = game.state
            game.submitDecision(
                ManaSourcesSelectedResponse(pending.id, paymentPlan = PaymentPlanV3()),
            ).error shouldBe "Explicit V3 pending payment does not support Waterbend cost reduction"
            game.state shouldBe before
        }

        test("Rules rejects a valid pain-land V3 plan for an actual composite Ward before any partial payment") {
            cardRegistry.register(v3CompositeWardBearer)
            val game = scenario()
                .withPlayers()
                .withFormat(Format.Commander())
                .withCardOnBattlefield(1, "Battlefield Forge")
                .withCardOnBattlefield(1, "Battlefield Forge")
                .withCardInHand(1, "Lightning Bolt")
                .withCardOnBattlefield(2, v3CompositeWardBearer.name)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .withRngSeed(12L)
                .build()
            // Cast without consuming either pain land; the following payment would otherwise be
            // a valid V3 `{2}` program that deals two damage before the remaining life cost fails.
            game.state = game.state.updateEntity(game.player1Id) { container ->
                container.with(ManaPoolComponent(red = 1))
            }
            val bearer = game.findPermanent(v3CompositeWardBearer.name) ?: error("Missing composite Ward bearer")
            game.castSpell(1, "Lightning Bolt", bearer).error shouldBe null
            game.resolveStack()
            val pending = game.getPendingDecision().shouldBeInstanceOf<SelectManaSourcesDecision>()
            pending.requiredCost shouldBe "{2}"
            game.state = game.state.updateEntity(game.player1Id) { container ->
                container.with(LifeTotalComponent(2))
            }
            val forgeIds = game.state.getBattlefield(game.player1Id).filter { id ->
                game.state.getEntity(id)?.get<CardComponent>()?.name == "Battlefield Forge"
            }
            forgeIds.size shouldBe 2
            val plan = twoForgeRedPlan(forgeIds)
            val before = game.state
            PaymentPlanValidator(ManaSolver(cardRegistry)).validateV3(
                state = before,
                playerId = game.player1Id,
                cost = com.wingedsheep.sdk.core.ManaCost.parse("{2}"),
                plan = plan,
                spellContext = SpellPaymentContext(),
            ).shouldBeInstanceOf<PaymentPlanValidation.AcceptedV3>()

            game.submitDecision(
                ManaSourcesSelectedResponse(pending.id, paymentPlan = plan),
            ).error shouldBe "Explicit V3 pending payment does not support Waterbend or composite Ward costs"
            game.state shouldBe before
        }
    }

    private data class GymFixture(
        val gym: GameGymEnv,
        val environment: GameEnvironment,
    )

    private fun gymForState(
        state: com.wingedsheep.engine.state.GameState,
        playerOne: com.wingedsheep.sdk.model.EntityId,
        playerTwo: com.wingedsheep.sdk.model.EntityId,
        perspectivePlayerIndex: Int,
    ): GymFixture {
        val environment = GameEnvironment.create(
            cardRegistry = cardRegistry,
            executionMode = GameEnvironmentMode.TRUSTED,
        )
        environment.restore(state, listOf(playerOne, playerTwo))
        return GymFixture(
            gym = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = perspectivePlayerIndex,
                observationBuilder = com.wingedsheep.gym.contract.ObservationBuilder(cardRegistry = cardRegistry),
            ),
            environment = environment,
        )
    }

    private fun battlefieldCardId(
        state: com.wingedsheep.engine.state.GameState,
        playerId: com.wingedsheep.sdk.model.EntityId,
        cardName: String,
    ): com.wingedsheep.sdk.model.EntityId = state.getBattlefield(playerId).single { id ->
        state.getEntity(id)?.get<CardComponent>()?.name == cardName
    }

    private data class MentorPendingWitness(
        val gym: GameGymEnv,
        val environment: GameEnvironment,
        val observation: TrainingObservation,
        val sourceDomain: ManaSourcesDomain,
        val decisionId: String,
    ) {
        val paymentDomain: PaymentDomainV5 get() = sourceDomain.paymentDomain
    }

    private fun mentorPendingWitness(withFloatingRed: Boolean = false): MentorPendingWitness {
        val game = scenario()
            .withPlayers()
            .withFormat(Format.Commander())
            .withCardOnBattlefield(1, "Mentor of the Meek")
            .withCardOnBattlefield(1, "Battlefield Forge")
            .withCardInHand(1, "Fervent Champion")
            .withCardInLibrary(1, "Plains")
            .withActivePlayer(1)
            .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
            .withRngSeed(8L)
            .build()
        game.state = game.state.updateEntity(game.player1Id) { container ->
            container.with(ManaPoolComponent(red = 1))
        }

        game.castSpell(1, "Fervent Champion").error shouldBe null
        game.resolveStack()
        game.getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
        game.answerYesNo(true).error shouldBe null
        game.getPendingDecision().shouldBeInstanceOf<SelectManaSourcesDecision>()
        if (withFloatingRed) {
            // Represents mana the payer floated through the existing CR 605.3a payment window
            // before confirming this still-pending decision. The plan below selects it only from
            // the subsequently published public initial-pool bucket.
            game.state = game.state.updateEntity(game.player1Id) { container ->
                container.with(ManaPoolComponent(red = 1))
            }
        }

        val environment = GameEnvironment.create(
            cardRegistry = cardRegistry,
            executionMode = GameEnvironmentMode.TRUSTED,
        )
        environment.restore(
            state = game.state,
            playerIds = listOf(game.player1Id, game.player2Id),
        )
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = com.wingedsheep.gym.contract.ObservationBuilder(cardRegistry = cardRegistry),
        )
        val observation = gym.observe().observation.shouldBeInstanceOf<TrainingObservation>()
        val pending = observation.pendingDecision ?: error("Mentor payment did not reach Gym")
        return MentorPendingWitness(
            gym = gym,
            environment = environment,
            observation = observation,
            sourceDomain = pending.structuredDomain.shouldBeInstanceOf<ManaSourcesDomain>(),
            decisionId = pending.decisionId ?: error("Mentor payment has no live decision ID"),
        )
    }

    private fun wardPendingWitness(): MentorPendingWitness {
        val game = scenario()
            .withPlayers()
            .withFormat(Format.Commander())
            .withCardOnBattlefield(2, "Twining Twins")
            .withCardOnBattlefield(1, "Battlefield Forge")
            .withCardInHand(1, "Lightning Bolt")
            .withActivePlayer(1)
            .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
            .withRngSeed(9L)
            .build()
        game.state = game.state.updateEntity(game.player1Id) { container ->
            container.with(ManaPoolComponent(red = 1))
        }
        val target = game.findPermanent("Twining Twins") ?: error("Missing Ward witness")
        game.castSpell(1, "Lightning Bolt", target).error shouldBe null
        game.resolveStack()
        game.getPendingDecision().shouldBeInstanceOf<SelectManaSourcesDecision>()

        val environment = GameEnvironment.create(
            cardRegistry = cardRegistry,
            executionMode = GameEnvironmentMode.TRUSTED,
        )
        environment.restore(
            state = game.state,
            playerIds = listOf(game.player1Id, game.player2Id),
        )
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = com.wingedsheep.gym.contract.ObservationBuilder(cardRegistry = cardRegistry),
        )
        val observation = gym.observe().observation.shouldBeInstanceOf<TrainingObservation>()
        val pending = observation.pendingDecision ?: error("Ward payment did not reach Gym")
        return MentorPendingWitness(
            gym = gym,
            environment = environment,
            observation = observation,
            sourceDomain = pending.structuredDomain.shouldBeInstanceOf<ManaSourcesDomain>(),
            decisionId = pending.decisionId ?: error("Ward payment has no live decision ID"),
        )
    }

    private fun sourcePlan(
        domain: PaymentDomainV5,
        sourceName: String,
        color: PaymentManaColor,
    ): PaymentPlanV3 {
        val source = domain.sourceActivationOptions.singleOrNull { option ->
            option.sourceName == sourceName &&
                option.productionChoices.any { it.producedColor == color }
        } ?: error("$sourceName cannot produce $color in the public payment domain")
        val production = source.productionChoices.single { it.producedColor == color }
        return PaymentPlanV3(
            activations = listOf(
                SourceActivationV2(
                    sourceId = source.sourceId,
                    manaAbilityKey = source.manaAbilityKey,
                    productionChoice = production,
                    activationCostOrder = source.activationCostOrderOptions.single(),
                )
            ),
            outerAllocation = domain.outerAtomicCostUnits.map { unit ->
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(
                        symbolIndex = unit.symbolIndex,
                        unitIndexWithinSymbol = unit.unitIndexWithinSymbol,
                    ),
                    resource = ManaResourceRefV1.ActivationOutputUnit(0, 0),
                )
            },
        )
    }

    private fun forgePlan(domain: PaymentDomainV5, color: PaymentManaColor): PaymentPlanV3 =
        sourcePlan(domain, "Battlefield Forge", color)

    private fun twoForgeRedPlan(
        forgeIds: List<com.wingedsheep.sdk.model.EntityId>,
    ): PaymentPlanV3 {
        require(forgeIds.size == 2) { "Composite Ward witness requires exactly two Battlefield Forges" }
        val template = mentorPendingWitness().paymentDomain.sourceActivationOptions.singleOrNull { option ->
            option.sourceName == "Battlefield Forge" &&
                option.productionChoices.any { it.producedColor == PaymentManaColor.RED }
        } ?: error("Battlefield Forge red production is not public in the locked Mentor witness")
        val production = template.productionChoices.single { it.producedColor == PaymentManaColor.RED }
        return PaymentPlanV3(
            activations = forgeIds.map { sourceId ->
                SourceActivationV2(
                    sourceId = sourceId,
                    manaAbilityKey = template.manaAbilityKey,
                    productionChoice = production,
                    activationCostOrder = template.activationCostOrderOptions.single(),
                )
            },
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(symbolIndex = 0, unitIndexWithinSymbol = 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(activationIndex = 0, outputIndex = 0),
                ),
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(symbolIndex = 0, unitIndexWithinSymbol = 1),
                    resource = ManaResourceRefV1.ActivationOutputUnit(activationIndex = 1, outputIndex = 0),
                ),
            ),
        )
    }

    private fun initialRedPoolPlan(domain: PaymentDomainV5): PaymentPlanV3 {
        val bucket = domain.initialPoolBuckets.single { entry ->
            (entry.key as? com.wingedsheep.engine.core.InitialPoolBucketKeyV1.UnrestrictedPoolBucket)
                ?.color == PaymentManaColor.RED
        }
        return PaymentPlanV3(
            outerAllocation = domain.outerAtomicCostUnits.map { unit ->
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(
                        symbolIndex = unit.symbolIndex,
                        unitIndexWithinSymbol = unit.unitIndexWithinSymbol,
                    ),
                    resource = ManaResourceRefV1.InitialPoolResource(bucket.key),
                )
            },
        )
    }

    private fun lockedAkiriCards(): Set<String> {
        val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .first { Files.isDirectory(it.resolve("docs/ml/curriculum")) }
        return Files.readAllLines(repositoryRoot.resolve("docs/ml/curriculum/akiri-v0.1.txt"))
            .asSequence()
            .filter { it.matches(Regex("^\\d{3}\\t.*")) }
            .map { it.substringAfterLast('\t') }
            .toSet()
    }
}
