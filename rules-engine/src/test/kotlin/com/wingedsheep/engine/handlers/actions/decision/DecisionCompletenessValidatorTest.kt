package com.wingedsheep.engine.handlers.actions.decision

import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DistributeDecision
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.AssignDamageDecision
import com.wingedsheep.engine.core.DamageAssignmentResponse
import com.wingedsheep.engine.core.ManaSourceOption
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SplitPilesDecision
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull

class DecisionCompletenessValidatorTest : FunSpec({
    val chooser = EntityId.of("chooser")
    val first = EntityId.of("first")
    val second = EntityId.of("second")
    val unknown = EntityId.of("unknown")
    val context = DecisionContext()

    test("card selection rejects duplicate entity IDs") {
        val decision = SelectCardsDecision(
            id = "cards",
            playerId = chooser,
            prompt = "Choose two",
            context = context,
            options = listOf(first, second),
            minSelections = 2,
            maxSelections = 2
        )

        DecisionValidators.validate(
            decision,
            CardsSelectedResponse("cards", listOf(first, first))
        ).shouldNotBeNull()
    }

    test("restriction-bearing card selection fails closed without current state") {
        val decision = SelectCardsDecision(
            id = "restricted-cards",
            playerId = chooser,
            prompt = "Choose distinct names",
            context = context,
            options = listOf(first, second),
            minSelections = 1,
            maxSelections = 2,
            onePerCardName = true
        )

        DecisionValidators.validate(
            decision,
            CardsSelectedResponse("restricted-cards", listOf(first, second))
        ).shouldNotBeNull()
    }

    test("target response must cover every required target slot") {
        val decision = ChooseTargetsDecision(
            id = "targets",
            playerId = chooser,
            prompt = "Choose both",
            context = context,
            targetRequirements = listOf(
                TargetRequirementInfo(0, "first"),
                TargetRequirementInfo(1, "second")
            ),
            legalTargets = mapOf(0 to listOf(first), 1 to listOf(second))
        )

        DecisionValidators.validate(
            decision,
            TargetsResponse("targets", mapOf(0 to listOf(first)))
        ).shouldNotBeNull()
    }

    test("ordinary modal choice rejects repeated modes") {
        val decision = ChooseModeDecision(
            id = "modes",
            playerId = chooser,
            prompt = "Choose two modes",
            context = context,
            modes = listOf(
                com.wingedsheep.engine.core.ModeOption(0, "A"),
                com.wingedsheep.engine.core.ModeOption(1, "B")
            ),
            minModes = 2,
            maxModes = 2
        )

        DecisionValidators.validate(
            decision,
            ModesChosenResponse("modes", listOf(0, 0))
        ).shouldNotBeNull()
    }

    test("distribution rejects negative amounts") {
        val decision = DistributeDecision(
            id = "distribution",
            playerId = chooser,
            prompt = "Distribute",
            context = context,
            totalAmount = 1,
            targets = listOf(first),
            allowPartial = true
        )

        DecisionValidators.validate(
            decision,
            DistributionResponse("distribution", mapOf(first to -1))
        ).shouldNotBeNull()
    }

    test("manual mana payment rejects sources outside the advertised domain") {
        val decision = SelectManaSourcesDecision(
            id = "mana",
            playerId = chooser,
            prompt = "Pay",
            context = context,
            availableSources = listOf(
                ManaSourceOption(first, "Forest", setOf(Color.GREEN), producesColorless = false)
            ),
            requiredCost = "{G}",
            autoPaySuggestion = listOf(first)
        )

        DecisionValidators.validate(
            decision,
            ManaSourcesSelectedResponse("mana", selectedSources = listOf(unknown))
        ).shouldNotBeNull()
    }

    test("object ordering rejects duplicates and extra entries") {
        val decision = OrderObjectsDecision(
            id = "order",
            playerId = chooser,
            prompt = "Order",
            context = context,
            objects = listOf(first, second)
        )

        DecisionValidators.validate(
            decision,
            OrderedResponse("order", listOf(first, first, second))
        ).shouldNotBeNull()
    }

    test("pile split rejects repeated cards across piles") {
        val decision = SplitPilesDecision(
            id = "piles",
            playerId = chooser,
            prompt = "Split",
            context = context,
            cards = listOf(first, second),
            numberOfPiles = 2
        )

        DecisionValidators.validate(
            decision,
            PilesSplitResponse("piles", listOf(listOf(first, first), listOf(second)))
        ).shouldNotBeNull()
    }

    test("library search enforces its advertised minimum and distinctness") {
        val decision = SearchLibraryDecision(
            id = "search",
            playerId = chooser,
            prompt = "Search",
            context = context,
            options = listOf(first, second),
            minSelections = 1,
            maxSelections = 2,
            cards = mapOf(
                first to SearchCardInfo("A", "", ""),
                second to SearchCardInfo("B", "", "")
            ),
            filterDescription = "a card"
        )

        DecisionValidators.validate(
            decision,
            CardsSelectedResponse("search", emptyList())
        ).shouldNotBeNull()
        DecisionValidators.validate(
            decision,
            CardsSelectedResponse("search", listOf(first, first))
        ).shouldNotBeNull()
    }

    test("legacy damage assignment rejects negative amounts") {
        val decision = AssignDamageDecision(
            id = "damage",
            playerId = chooser,
            prompt = "Assign",
            context = context,
            attackerId = first,
            availablePower = 2,
            orderedTargets = listOf(second),
            defenderId = null,
            minimumAssignments = emptyMap(),
            defaultAssignments = emptyMap(),
            hasTrample = false,
            hasDeathtouch = false
        )

        DecisionValidators.validate(
            decision,
            DamageAssignmentResponse("damage", mapOf(second to -1))
        ).shouldNotBeNull()
    }
})
