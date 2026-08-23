package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.BudgetModalDecision
import com.wingedsheep.engine.core.BudgetModeOption
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.ChooseReplacementDecision
import com.wingedsheep.engine.core.DamageEdge
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DamageEdgeDirection
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.DistributeDecision
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ManaSourceOption
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.ModeOption
import com.wingedsheep.engine.core.OptionMetadata
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.ResolutionAttacker
import com.wingedsheep.engine.core.ResolutionBlocker
import com.wingedsheep.engine.core.ResolutionDefender
import com.wingedsheep.engine.core.ResolutionTargetKind
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SplitPilesDecision
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.WaterbendPermanentChoice
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.GameGymEnv
import com.wingedsheep.gym.service.SnapshotCodec
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.targets.TargetPlayer
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Contract coverage for the perspective-safe structured decision domains. */
class StructuredDecisionDomainTest : FunSpec({

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
                    PlayerConfig("Bob", Deck.of("Mountain" to 20))
                ),
                skipMulligans = true,
                startingPlayerIndex = 0
            )
        )
        return env
    }

    fun observation(
        env: GameEnvironment,
        decision: com.wingedsheep.engine.core.PendingDecision,
        perspective: EntityId = env.playerIds.first()
    ): TrainingObservation = ObservationBuilder(cardRegistry = registry())
        .build(env.state.copy(pendingDecision = decision), perspective, emptyList())
        .observation as TrainingObservation

    fun gameWithPendingDecision(decision: com.wingedsheep.engine.core.PendingDecision): GameGymEnv {
        val env = environment()
        env.restore(
            env.state.copy(pendingDecision = decision),
            env.playerIds,
            env.stepCount,
            env.maxSteps
        )
        return GameGymEnv(
            environment = env,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = registry())
        )
    }

    fun pendingView(gym: GameGymEnv): PendingDecisionView =
        checkNotNull((gym.observe().observation as TrainingObservation).pendingDecision)

    fun decisionId(view: PendingDecisionView): String = checkNotNull(view.decisionId)

    fun submitAndRequireResolved(gym: GameGymEnv, response: com.wingedsheep.engine.core.DecisionResponse) {
        gym.submitDecision(response)
        (gym.observe().observation as TrainingObservation).pendingDecision shouldBe null
    }

    fun cardInfo(name: String) = SearchCardInfo(
        name = name,
        manaCost = "{1}",
        typeLine = "Creature",
        colors = listOf("R"),
        power = 1
    )

    test("actor receives complete domains for the V1 reachable structured families") {
        val env = environment()
        val owner = env.playerIds.first()
        val targetA = EntityId("target-a")
        val targetB = EntityId("target-b")
        val cardA = EntityId("card-a")
        val cardB = EntityId("card-b")
        val source = EntityId("source")

        val targets = ChooseTargetsDecision(
            id = "targets",
            playerId = owner,
            prompt = "Choose targets",
            context = DecisionContext(sourceId = source, phase = DecisionPhase.RESOLUTION),
            targetRequirements = listOf(
                TargetRequirementInfo(
                    index = 0,
                    description = "up to two targets",
                    minTargets = 0,
                    maxTargets = 2,
                    targetZone = "Graveyard",
                    mustDifferFromEarlier = true,
                    sameController = true,
                    sameOwner = true,
                    sameCreatureType = true,
                    sameCardType = true,
                    totalManaValueAtMost = 5,
                    differentNames = true,
                    xConstrainsManaValue = true,
                    xConstrainsManaValueExactly = true,
                    xConstrainsPower = true,
                    xConstrainsCount = true,
                )
            ),
            legalTargets = mapOf(0 to listOf(targetA, targetB)),
            canCancel = true
        )
        val targetDomain = observation(env, targets).pendingDecision!!.structuredDomain
            .shouldBeInstanceOf<TargetsDomain>()
        val targetRequirement = targetDomain.requirements.single()
        targetRequirement.candidates shouldBe listOf(targetA, targetB)
        targetRequirement.maxTargets shouldBe 2
        targetRequirement.targetZone shouldBe "Graveyard"
        targetRequirement.mustDifferFromEarlier shouldBe true
        targetRequirement.sameController shouldBe true
        targetRequirement.sameOwner shouldBe true
        targetRequirement.sameCreatureType shouldBe true
        targetRequirement.sameCardType shouldBe true
        targetRequirement.totalManaValueAtMost shouldBe 5
        targetRequirement.differentNames shouldBe true
        targetRequirement.xConstrainsManaValue shouldBe true
        targetRequirement.xConstrainsManaValueExactly shouldBe true
        targetRequirement.xConstrainsPower shouldBe true
        targetRequirement.xConstrainsCount shouldBe true
        targetDomain.canCancel shouldBe true

        val cards = SelectCardsDecision(
            id = "cards",
            playerId = owner,
            prompt = "Select cards",
            context = DecisionContext(sourceId = source, phase = DecisionPhase.RESOLUTION),
            options = listOf(cardA, cardB),
            minSelections = 0,
            maxSelections = 2,
            ordered = true,
            cardInfo = mapOf(cardA to cardInfo("Card A"), cardB to cardInfo("Card B")),
            useTargetingUI = true,
            selectedLabel = "Discard",
            remainderLabel = "Keep",
            nonSelectableOptions = listOf(EntityId("shown-only")),
            onePerCardType = true,
            onePerColor = true,
            availableColors = listOf("R", "W"),
            onePerCardName = true,
            onePerBasicLandType = true,
            onePerPower = true,
            maxTotalManaValue = 4,
            minTotalManaValue = 1,
            maxTotalPower = 3
        )
        val cardDomain = observation(env, cards).pendingDecision!!.structuredDomain
            .shouldBeInstanceOf<CardSelectionDomain>()
        cardDomain.options shouldBe listOf(cardA, cardB)
        cardDomain.ordered shouldBe true
        cardDomain.maxTotalManaValue shouldBe 4
        cardDomain.cardInfo!![cardA]!!.name shouldBe "Card A"

        val ordered = OrderObjectsDecision(
            id = "order",
            playerId = owner,
            prompt = "Choose order",
            context = DecisionContext(phase = DecisionPhase.TRIGGER),
            objects = listOf(cardA, cardB),
            cardInfo = mapOf(cardA to cardInfo("Card A"), cardB to cardInfo("Card B")),
            objectLabels = mapOf(cardA to "A", cardB to "B")
        )
        val orderingDomain = observation(env, ordered).pendingDecision!!.structuredDomain
            .shouldBeInstanceOf<OrderingDomain>()
        orderingDomain.objects shouldBe listOf(cardA, cardB)
        orderingDomain.objectLabels!![cardB] shouldBe "B"

        val search = SearchLibraryDecision(
            id = "search",
            playerId = owner,
            prompt = "Search library",
            context = DecisionContext(sourceId = source, phase = DecisionPhase.RESOLUTION),
            options = listOf(cardA, cardB),
            minSelections = 0,
            maxSelections = 1,
            cards = mapOf(
                cardA to cardInfo("Card A"),
                cardB to cardInfo("Card B"),
                EntityId("outside-search-domain") to cardInfo("Unrelated Card")
            ),
            filterDescription = "a creature card"
        )
        val searchDomain = observation(env, search).pendingDecision!!.structuredDomain
            .shouldBeInstanceOf<SearchLibraryDomain>()
        searchDomain.options shouldBe listOf(cardA, cardB)
        searchDomain.cards[cardB]!!.name shouldBe "Card B"
        searchDomain.cards.containsKey(EntityId("outside-search-domain")) shouldBe false

        val reorder = ReorderLibraryDecision(
            id = "reorder",
            playerId = owner,
            prompt = "Put these cards back in any order",
            context = DecisionContext(sourceId = source, phase = DecisionPhase.RESOLUTION),
            cards = listOf(cardB, cardA),
            cardInfo = mapOf(cardA to cardInfo("Card A"), cardB to cardInfo("Card B"))
        )
        val reorderDomain = observation(env, reorder).pendingDecision!!.structuredDomain
            .shouldBeInstanceOf<ReorderLibraryDomain>()
        reorderDomain.cards shouldBe listOf(cardB, cardA)

        val attacker = EntityId("attacker")
        val blocker = EntityId("blocker")
        val defender = EntityId("defender")
        val edge = DamageEdge(
            id = "edge",
            sourceId = attacker,
            targetId = blocker,
            direction = DamageEdgeDirection.ATTACKER_TO_BLOCKER,
            amount = 2,
            maximum = 2,
            lethal = 2,
            isTrampleDrain = false,
            editableBy = owner
        )
        val combat = CombatResolutionDecision(
            id = "combat",
            playerId = owner,
            prompt = "Assign combat damage",
            context = DecisionContext(phase = DecisionPhase.COMBAT),
            firstStrike = false,
            attackers = listOf(
                ResolutionAttacker(
                    id = attacker,
                    name = "Attacker",
                    power = 2,
                    toughness = 2,
                    hasTrample = false,
                    hasDeathtouch = false,
                    hasFirstStrike = false,
                    hasDoubleStrike = false,
                    dealsDamageThisStep = true,
                    bandId = null,
                    attackedDefenderId = defender,
                    blockedByIds = listOf(blocker),
                    markedDamage = 0
                )
            ),
            blockers = listOf(
                ResolutionBlocker(
                    id = blocker,
                    name = "Blocker",
                    power = 1,
                    toughness = 2,
                    hasDeathtouch = false,
                    hasFirstStrike = false,
                    hasDoubleStrike = false,
                    dealsDamageThisStep = true,
                    blockedAttackerIds = listOf(attacker),
                    markedDamage = 0
                )
            ),
            defenders = listOf(
                ResolutionDefender(defender, ResolutionTargetKind.PLAYER, "Defender", 20)
            ),
            edges = listOf(edge),
            coChooserId = null
        )
        val combatDomain = observation(env, combat).pendingDecision!!.structuredDomain
            .shouldBeInstanceOf<CombatResolutionDomain>()
        combatDomain.edges.single().maximum shouldBe 2
        combatDomain.edges.single().editableBy shouldBe owner

        val mana = SelectManaSourcesDecision(
            id = "mana",
            playerId = owner,
            prompt = "Select mana sources",
            context = DecisionContext(phase = DecisionPhase.RESOLUTION),
            availableSources = listOf(
                ManaSourceOption(
                    entityId = source,
                    name = "Mountain",
                    producesColors = setOf(Color.RED),
                    producesColorless = false
                )
            ),
            requiredCost = "{1}",
            autoPaySuggestion = listOf(source),
            canDecline = true,
            waterbendPermanents = listOf(WaterbendPermanentChoice(source, "Mountain", false))
        )
        val manaDomain = observation(env, mana).pendingDecision!!.structuredDomain
            .shouldBeInstanceOf<ManaSourcesDomain>()
        manaDomain.availableSources.single().entityId shouldBe source
        manaDomain.requiredCost shouldBe "{1}"
        manaDomain.canDecline shouldBe true
    }

    test("pending target domains use an independent version while the global version stays V1") {
        val env = environment()
        val owner = env.playerIds.first()
        val domain = observation(
            env,
            ChooseTargetsDecision(
                id = "targets-version",
                playerId = owner,
                prompt = "Choose targets",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                targetRequirements = listOf(
                    TargetRequirementInfo(
                        index = 0,
                        description = "target",
                        minTargets = 1,
                        maxTargets = 1,
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
                ),
                legalTargets = mapOf(0 to listOf(EntityId("candidate")))
            )
        ).pendingDecision!!.structuredDomain.shouldBeInstanceOf<TargetsDomain>()

        STRUCTURED_DECISION_DOMAIN_VERSION shouldBe 1
        domain.version shouldBe 2
    }

    test("pending target atom serializes every semantic field explicitly") {
        val env = environment()
        val owner = env.playerIds.first()
        val domain = observation(
            env,
            ChooseTargetsDecision(
                id = "targets-atom-fields",
                playerId = owner,
                prompt = "Choose targets",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                targetRequirements = listOf(
                    TargetRequirementInfo(
                        index = 0,
                        description = "target",
                        minTargets = 1,
                        maxTargets = 1,
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
                ),
                legalTargets = mapOf(0 to listOf(EntityId("candidate")))
            )
        ).pendingDecision!!.structuredDomain.shouldBeInstanceOf<TargetsDomain>()

        val encoded = Json.encodeToJsonElement(
            TargetRequirementDomain.serializer(),
            domain.requirements.single()
        ).jsonObject
        listOf(
            "targetZone",
            "mustDifferFromEarlier",
            "sameController",
            "sameOwner",
            "sameCreatureType",
            "sameCardType",
            "totalManaValueAtMost",
            "differentNames",
            "xConstrainsManaValue",
            "xConstrainsManaValueExactly",
            "xConstrainsPower",
            "xConstrainsCount"
        ).forEach { field ->
            encoded[field] shouldNotBe null
        }
    }

    test("pending target requirements keep index order and canonicalize candidate IDs") {
        val env = environment()
        val owner = env.playerIds.first()
        val domain = observation(
            env,
            ChooseTargetsDecision(
                id = "targets-order",
                playerId = owner,
                prompt = "Choose targets",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                targetRequirements = listOf(
                    TargetRequirementInfo.fromRequirement(index = 2, requirement = TargetPlayer()),
                    TargetRequirementInfo.fromRequirement(index = 1, requirement = TargetPlayer()),
                ),
                legalTargets = mapOf(
                    2 to listOf(EntityId("z"), EntityId("a")),
                    1 to listOf(EntityId("m"), EntityId("b")),
                ),
            )
        ).pendingDecision!!.structuredDomain.shouldBeInstanceOf<TargetsDomain>()

        domain.requirements.map { it.index } shouldBe listOf(1, 2)
        domain.requirements.map { it.candidates } shouldBe listOf(
            listOf(EntityId("b"), EntityId("m")),
            listOf(EntityId("a"), EntityId("z")),
        )
    }

    test("pending target domain round-trips and rejects an unknown version") {
        val env = environment()
        val owner = env.playerIds.first()
        val decision = ChooseTargetsDecision(
            id = "targets-round-trip",
            playerId = owner,
            prompt = "Choose targets",
            context = DecisionContext(phase = DecisionPhase.RESOLUTION),
            targetRequirements = listOf(
                TargetRequirementInfo(
                    index = 0,
                    description = "target",
                    minTargets = 1,
                    maxTargets = 1,
                    targetZone = "Exile",
                    mustDifferFromEarlier = false,
                    sameController = true,
                    sameOwner = false,
                    sameCreatureType = false,
                    sameCardType = true,
                    totalManaValueAtMost = 4,
                    differentNames = true,
                    xConstrainsManaValue = true,
                    xConstrainsManaValueExactly = false,
                    xConstrainsPower = true,
                    xConstrainsCount = true,
                )
            ),
            legalTargets = mapOf(0 to listOf(EntityId("round-trip-candidate"))),
        )
        val domain = observation(env, decision).pendingDecision!!.structuredDomain
            .shouldBeInstanceOf<TargetsDomain>()

        val wireJson = Json { encodeDefaults = true }
        val encoded = wireJson.encodeToString(TargetsDomain.serializer(), domain)
        wireJson.decodeFromString<TargetsDomain>(encoded) shouldBe domain

        val unknownVersion = encoded.replace(Regex("\\\"version\\\"\\s*:\\s*2"), "\"version\":99")
        unknownVersion shouldNotBe encoded
        shouldThrow<IllegalArgumentException> {
            wireJson.decodeFromString<TargetsDomain>(unknownVersion)
        }
    }

    test("ChooseTargets domain constructs a response accepted by GameGymEnv and Rules") {
        val owner = environment().playerIds.first()
        val candidate = EntityId("target-candidate")
        val gym = gameWithPendingDecision(
            ChooseTargetsDecision(
                id = "targets-acceptance",
                playerId = owner,
                prompt = "Choose a target",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                targetRequirements = listOf(
                    TargetRequirementInfo(
                        index = 0,
                        description = "one target",
                        minTargets = 1,
                        maxTargets = 1,
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
                ),
                legalTargets = mapOf(0 to listOf(candidate))
            )
        )
        val view = pendingView(gym)
        val domain = view.structuredDomain.shouldBeInstanceOf<TargetsDomain>()
        val requirement = domain.requirements.single()

        submitAndRequireResolved(
            gym,
            TargetsResponse(
                decisionId(view),
                mapOf(requirement.index to listOf(requirement.candidates.single()))
            )
        )
    }

    test("multi-select card domain constructs a response accepted by GameGymEnv and Rules") {
        val owner = environment().playerIds.first()
        val gym = gameWithPendingDecision(
            SelectCardsDecision(
                id = "cards-acceptance",
                playerId = owner,
                prompt = "Select cards",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                options = listOf(EntityId("card-a"), EntityId("card-b")),
                minSelections = 1,
                maxSelections = 2,
                ordered = true
            )
        )
        val view = pendingView(gym)
        val domain = view.structuredDomain.shouldBeInstanceOf<CardSelectionDomain>()

        submitAndRequireResolved(
            gym,
            CardsSelectedResponse(decisionId(view), domain.options.take(domain.minSelections))
        )
    }

    test("OrderObjects domain constructs a response accepted by GameGymEnv and Rules") {
        val owner = environment().playerIds.first()
        val gym = gameWithPendingDecision(
            OrderObjectsDecision(
                id = "ordering-acceptance",
                playerId = owner,
                prompt = "Choose an order",
                context = DecisionContext(phase = DecisionPhase.TRIGGER),
                objects = listOf(EntityId("object-a"), EntityId("object-b"))
            )
        )
        val view = pendingView(gym)
        val domain = view.structuredDomain.shouldBeInstanceOf<OrderingDomain>()

        submitAndRequireResolved(gym, OrderedResponse(decisionId(view), domain.objects))
    }

    test("SearchLibrary domain constructs a response accepted by GameGymEnv and Rules") {
        val owner = environment().playerIds.first()
        val candidate = EntityId("search-candidate")
        val gym = gameWithPendingDecision(
            SearchLibraryDecision(
                id = "search-acceptance",
                playerId = owner,
                prompt = "Search",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                options = listOf(candidate),
                minSelections = 0,
                maxSelections = 1,
                cards = mapOf(candidate to cardInfo("Candidate")),
                filterDescription = "a card"
            )
        )
        val view = pendingView(gym)
        val domain = view.structuredDomain.shouldBeInstanceOf<SearchLibraryDomain>()

        submitAndRequireResolved(
            gym,
            CardsSelectedResponse(decisionId(view), domain.options.take(domain.maxSelections))
        )
    }

    test("ReorderLibrary domain constructs a response accepted by GameGymEnv and Rules") {
        val owner = environment().playerIds.first()
        val gym = gameWithPendingDecision(
            ReorderLibraryDecision(
                id = "reorder-acceptance",
                playerId = owner,
                prompt = "Reorder",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                cards = listOf(EntityId("top"), EntityId("bottom")),
                cardInfo = emptyMap()
            )
        )
        val view = pendingView(gym)
        val domain = view.structuredDomain.shouldBeInstanceOf<ReorderLibraryDomain>()

        submitAndRequireResolved(gym, OrderedResponse(decisionId(view), domain.cards.reversed()))
    }

    test("CombatResolution domain constructs a response accepted by GameGymEnv and Rules") {
        val owner = environment().playerIds.first()
        val attacker = EntityId("attacker-acceptance")
        val blocker = EntityId("blocker-acceptance")
        val defender = EntityId("defender-acceptance")
        val edge = DamageEdge(
            id = "edge-acceptance",
            sourceId = attacker,
            targetId = blocker,
            direction = DamageEdgeDirection.ATTACKER_TO_BLOCKER,
            amount = 2,
            maximum = 2,
            lethal = 2,
            isTrampleDrain = false,
            editableBy = owner
        )
        val gym = gameWithPendingDecision(
            CombatResolutionDecision(
                id = "combat-acceptance",
                playerId = owner,
                prompt = "Assign combat damage",
                context = DecisionContext(phase = DecisionPhase.COMBAT),
                firstStrike = false,
                attackers = listOf(
                    ResolutionAttacker(
                        attacker,
                        "Attacker",
                        2,
                        2,
                        false,
                        false,
                        false,
                        false,
                        true,
                        null,
                        defender,
                        listOf(blocker),
                        0
                    )
                ),
                blockers = listOf(
                    ResolutionBlocker(
                        id = blocker,
                        name = "Blocker",
                        power = 1,
                        toughness = 2,
                        hasDeathtouch = false,
                        hasFirstStrike = false,
                        hasDoubleStrike = false,
                        dealsDamageThisStep = true,
                        blockedAttackerIds = listOf(attacker),
                        markedDamage = 0
                    )
                ),
                defenders = listOf(ResolutionDefender(defender, ResolutionTargetKind.PLAYER, "Defender", 20)),
                edges = listOf(edge)
            )
        )
        val view = pendingView(gym)
        val domain = view.structuredDomain.shouldBeInstanceOf<CombatResolutionDomain>()

        submitAndRequireResolved(
            gym,
            CombatResolutionResponse(
                decisionId(view),
                domain.edges.map { DamageEdgeAmount(it.id, it.amount) }
            )
        )
    }

    test("SelectManaSources domain constructs a response accepted by GameGymEnv and Rules") {
        val owner = environment().playerIds.first()
        val source = EntityId("mana-source-acceptance")
        val gym = gameWithPendingDecision(
            SelectManaSourcesDecision(
                id = "mana-acceptance",
                playerId = owner,
                prompt = "Select mana sources",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                availableSources = listOf(
                    ManaSourceOption(source, "Mountain", setOf(Color.RED), false)
                ),
                requiredCost = "{1}",
                autoPaySuggestion = listOf(source)
            )
        )
        val view = pendingView(gym)
        val domain = view.structuredDomain.shouldBeInstanceOf<ManaSourcesDomain>()

        submitAndRequireResolved(
            gym,
            ManaSourcesSelectedResponse(
                decisionId(view),
                selectedSources = domain.availableSources.map { it.entityId }
            )
        )
    }

    test("Rules rejects responses with candidates outside the projected domain") {
        val owner = environment().playerIds.first()
        val candidate = EntityId("search-candidate")
        val gym = gameWithPendingDecision(
            SearchLibraryDecision(
                id = "search-reject-outside",
                playerId = owner,
                prompt = "Search",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                options = listOf(candidate),
                minSelections = 0,
                maxSelections = 1,
                cards = mapOf(candidate to cardInfo("Candidate")),
                filterDescription = "a card"
            )
        )
        val view = pendingView(gym)

        shouldThrow<IllegalArgumentException> {
            gym.submitDecision(
                CardsSelectedResponse(decisionId(view), listOf(EntityId("not-in-domain")))
            )
        }
    }

    test("Rules rejects responses that violate projected cardinality") {
        val owner = environment().playerIds.first()
        val firstCandidate = EntityId("target-cardinality-a")
        val secondCandidate = EntityId("target-cardinality-b")
        val gym = gameWithPendingDecision(
            ChooseTargetsDecision(
                id = "targets-reject-cardinality",
                playerId = owner,
                prompt = "Choose one target",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                targetRequirements = listOf(
                    TargetRequirementInfo(
                        index = 0,
                        description = "one target",
                        minTargets = 1,
                        maxTargets = 1,
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
                ),
                legalTargets = mapOf(0 to listOf(firstCandidate, secondCandidate))
            )
        )
        val view = pendingView(gym)
        val domain = view.structuredDomain.shouldBeInstanceOf<TargetsDomain>()
        val requirement = domain.requirements.single()

        shouldThrow<IllegalArgumentException> {
            gym.submitDecision(
                TargetsResponse(
                    decisionId(view),
                    mapOf(requirement.index to requirement.candidates.take(2))
                )
            )
        }
    }

    test("structured domains are actor-only and are absent from the opponent view") {
        val env = environment()
        val owner = env.playerIds.first()
        val opponent = env.playerIds[1]
        val decision = SearchLibraryDecision(
            id = "private-search",
            playerId = owner,
            prompt = "Search",
            context = DecisionContext(phase = DecisionPhase.RESOLUTION),
            options = listOf(EntityId("hidden-card")),
            minSelections = 0,
            maxSelections = 1,
            cards = mapOf(EntityId("hidden-card") to cardInfo("Hidden Card")),
            filterDescription = "a card"
        )

        val ownerView = observation(env, decision, owner)
        val opponentView = observation(env, decision, opponent)

        ownerView.pendingDecision!!.structuredDomain shouldNotBe null
        opponentView.pendingDecision!!.structuredDomain shouldBe null
        opponentView.pendingDecision.decisionId shouldBe null
        opponentView.pendingDecision.kind shouldBe PendingDecisionKind.GENERIC
        ObservationCanonicalizer.wireJson(opponentView).contains("hidden-card") shouldBe false
    }

    test("generic structured domains retain authoritative validator metadata") {
        val env = environment()
        val owner = env.playerIds.first()
        val first = EntityId("first")
        val second = EntityId("second")

        val modes = observation(
            env,
            ChooseModeDecision(
                id = "modes",
                playerId = owner,
                prompt = "Choose modes",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                modes = listOf(ModeOption(0, "Draw", true), ModeOption(1, "Discard", false)),
                minModes = 1,
                maxModes = 2
            )
        ).pendingDecision!!.structuredDomain.shouldBeInstanceOf<ModeSelectionDomain>()
        modes.modes.map { it.available } shouldBe listOf(true, false)
        modes.maxModes shouldBe 2

        val distribution = observation(
            env,
            DistributeDecision(
                id = "distribution",
                playerId = owner,
                prompt = "Distribute",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                totalAmount = 3,
                targets = listOf(second, first),
                minPerTarget = 1,
                maxPerTarget = mapOf(first to 2, second to 3, EntityId("unrelated") to 9),
                allowPartial = false
            )
        ).pendingDecision!!.structuredDomain.shouldBeInstanceOf<DistributionDomain>()
        distribution.targets shouldBe listOf(first, second)
        distribution.maxPerTarget.keys shouldBe setOf(first, second)

        val split = observation(
            env,
            SplitPilesDecision(
                id = "split",
                playerId = owner,
                prompt = "Split piles",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                cards = listOf(second, first),
                numberOfPiles = 3,
                pileLabels = listOf("One", "Two", "Three"),
                cardInfo = mapOf(first to cardInfo("First"), second to cardInfo("Second"))
            )
        ).pendingDecision!!.structuredDomain.shouldBeInstanceOf<SplitPilesDomain>()
        split.cards shouldBe listOf(first, second)
        split.numberOfPiles shouldBe 3

        val replacement = observation(
            env,
            ChooseReplacementDecision(
                id = "replacement",
                playerId = owner,
                prompt = "Choose replacement",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                fromOptions = listOf("red", "blue"),
                toOptions = listOf("green", "white"),
                fromMetadata = listOf(OptionMetadata(id = "red")),
                toMetadata = listOf(OptionMetadata(id = "green")),
                allowedToByFrom = listOf(listOf(0), listOf(1)),
                defaultFromIndex = 0
            )
        ).pendingDecision!!.structuredDomain.shouldBeInstanceOf<ReplacementDomain>()
        replacement.allowedToByFrom shouldBe listOf(listOf(0), listOf(1))
        replacement.defaultFromIndex shouldBe 0

        val budget = observation(
            env,
            BudgetModalDecision(
                id = "budget",
                playerId = owner,
                prompt = "Choose budget modes",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                budget = 3,
                modes = listOf(BudgetModeOption(1, "Draw"), BudgetModeOption(2, "Discard"))
            )
        ).pendingDecision!!.structuredDomain.shouldBeInstanceOf<BudgetModalDomain>()
        budget.budget shouldBe 3
        budget.modes.map { it.cost } shouldBe listOf(1, 2)
    }

    test("structured domains round-trip through the shared observation serializer") {
        val env = environment()
        val owner = env.playerIds.first()
        val source = EntityId("source")
        val candidate = EntityId("candidate")
        val decision = SearchLibraryDecision(
            id = "search-round-trip",
            playerId = owner,
            prompt = "Search",
            context = DecisionContext(sourceId = source, phase = DecisionPhase.RESOLUTION),
            options = listOf(candidate),
            minSelections = 0,
            maxSelections = 1,
            cards = mapOf(candidate to cardInfo("Candidate")),
            filterDescription = "a card"
        )
        val observation = observation(env, decision)
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
            allowStructuredMapKeys = true
        }

        val encoded = json.encodeToString(TrainingObservation.serializer(), observation)
        json.decodeFromString(TrainingObservation.serializer(), encoded) shouldBe observation
    }

    test("domain semantics affect the digest but routing and presentation do not") {
        val env = environment()
        val owner = env.playerIds.first()
        val source = EntityId("source")
        val candidate = EntityId("candidate")
        fun decision(id: String, prompt: String, candidateList: List<EntityId>) =
            SearchLibraryDecision(
                id = id,
                playerId = owner,
                prompt = prompt,
                context = DecisionContext(sourceId = source, sourceName = "Presentation", effectHint = "Hint"),
                options = candidateList,
                minSelections = 0,
                maxSelections = 1,
                cards = candidateList.associateWith { cardInfo(it.value) },
                filterDescription = "a card"
            )

        val base = observation(env, decision("id-a", "prompt-a", listOf(candidate)))
        val sameSemantics = observation(env, decision("id-b", "prompt-b", listOf(candidate)))
        val changedDomain = observation(
            env,
            decision("id-c", "prompt-c", listOf(candidate, EntityId("other")))
        )

        StateDigest.compute(base) shouldBe StateDigest.compute(sameSemantics)
        StateDigest.compute(base) shouldNotBe StateDigest.compute(changedDomain)
    }

    test("candidate sets are canonicalized while library order remains semantic") {
        val env = environment()
        val owner = env.playerIds.first()
        val first = EntityId("first")
        val second = EntityId("second")
        fun search(options: List<EntityId>) = SearchLibraryDecision(
            id = "search",
            playerId = owner,
            prompt = "Search",
            context = DecisionContext(phase = DecisionPhase.RESOLUTION),
            options = options,
            minSelections = 0,
            maxSelections = 1,
            cards = mapOf(first to cardInfo("First"), second to cardInfo("Second")),
            filterDescription = "a card"
        )

        val searchA = observation(env, search(listOf(first, second)))
        val searchB = observation(env, search(listOf(second, first)))
        StateDigest.compute(searchA) shouldBe StateDigest.compute(searchB)

        fun reorder(cards: List<EntityId>) = ReorderLibraryDecision(
            id = "reorder",
            playerId = owner,
            prompt = "Reorder",
            context = DecisionContext(phase = DecisionPhase.RESOLUTION),
            cards = cards,
            cardInfo = mapOf(first to cardInfo("First"), second to cardInfo("Second"))
        )
        val reorderA = observation(env, reorder(listOf(first, second)))
        val reorderB = observation(env, reorder(listOf(second, first)))
        StateDigest.compute(reorderA) shouldNotBe StateDigest.compute(reorderB)
    }

    test("opaque trigger-order handles do not become semantic identity") {
        val env = environment()
        val owner = env.playerIds.first()

        fun ordering(first: EntityId, second: EntityId): TrainingObservation = observation(
            env,
            OrderObjectsDecision(
                id = "order",
                playerId = owner,
                prompt = "Choose order",
                context = DecisionContext(phase = DecisionPhase.TRIGGER),
                objects = listOf(first, second),
                objectLabels = mapOf(first to "A trigger", second to "B trigger")
            )
        )

        val first = ordering(EntityId("trigger-order-object-0"), EntityId("trigger-order-object-1"))
        val second = ordering(EntityId("trigger-order-object-7"), EntityId("trigger-order-object-9"))
        StateDigest.compute(first) shouldBe StateDigest.compute(second)
    }

    test("advisory mana autopay suggestions do not change the domain digest") {
        val env = environment()
        val owner = env.playerIds.first()
        val sourceA = EntityId("source-a")
        val sourceB = EntityId("source-b")
        val source = ManaSourceOption(
            entityId = sourceA,
            name = "Mountain",
            producesColors = setOf(Color.RED),
            producesColorless = false
        )

        fun mana(suggestion: List<EntityId>, sources: List<ManaSourceOption> = listOf(source)) =
            SelectManaSourcesDecision(
                id = "mana",
                playerId = owner,
                prompt = "Select mana",
                context = DecisionContext(phase = DecisionPhase.RESOLUTION),
                availableSources = sources,
                requiredCost = "{1}",
                autoPaySuggestion = suggestion
            )

        val base = observation(env, mana(listOf(sourceA)))
        val differentSuggestion = observation(env, mana(listOf(sourceB)))
        val differentDomain = observation(
            env,
            mana(
                suggestion = listOf(sourceA),
                sources = listOf(
                    source,
                    ManaSourceOption(
                        entityId = sourceB,
                        name = "Island",
                        producesColors = setOf(Color.BLUE),
                        producesColorless = false
                    )
                )
            )
        )

        StateDigest.compute(base) shouldBe StateDigest.compute(differentSuggestion)
        StateDigest.compute(base) shouldNotBe StateDigest.compute(differentDomain)
    }

    test("fork and snapshot restore preserve structured decision semantics") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20))
                ),
                skipMulligans = true,
                startingPlayerIndex = 0
            )
        )
        val owner = environment.playerIds.first()
        val candidate = EntityId("snapshot-candidate")
        val decision = SearchLibraryDecision(
            id = "snapshot-search",
            playerId = owner,
            prompt = "Search",
            context = DecisionContext(phase = DecisionPhase.RESOLUTION),
            options = listOf(candidate),
            minSelections = 0,
            maxSelections = 1,
            cards = mapOf(candidate to cardInfo("Snapshot Candidate")),
            filterDescription = "a card"
        )
        val pendingState = environment.state.copy(pendingDecision = decision)
        environment.restore(pendingState, environment.playerIds, environment.stepCount, environment.maxSteps)

        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry)
        )
        val original = gym.observe().observation as TrainingObservation
        val originalDomain = original.pendingDecision!!.structuredDomain

        val forked = gym.fork()
        forked.observe().observation.stateDigest shouldBe original.stateDigest

        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)
        environment.restore(
            pendingState.copy(pendingDecision = null),
            environment.playerIds,
            environment.stepCount,
            environment.maxSteps
        )
        val restored = gym.restore(codec, handle).observation as TrainingObservation
        restored.stateDigest shouldBe original.stateDigest
        restored.pendingDecision!!.structuredDomain shouldBe originalDomain
    }
})
