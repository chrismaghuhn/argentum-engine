package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.mechanics.layers.ContinuousEffectSourceComponent
import com.wingedsheep.engine.mechanics.layers.Modification
import com.wingedsheep.engine.mechanics.layers.SerializableModification
import com.wingedsheep.engine.mechanics.mana.ManaPool
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.combat.BlockedComponent
import com.wingedsheep.engine.state.components.combat.BlockedThisCombatComponent
import com.wingedsheep.engine.state.components.combat.BlockersDeclaredThisCombatComponent
import com.wingedsheep.engine.state.components.combat.BlockingComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.mechanics.combat.rules.BlockCheckContext
import com.wingedsheep.engine.mechanics.combat.rules.BlockEvasionRule
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.ManaSymbol
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.DynamicAmountEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.sdk.scripting.BlockerCountLimit
import com.wingedsheep.sdk.scripting.CanBlockAnyNumber
import com.wingedsheep.sdk.scripting.CanBlockAdditionalForCreatureGroup
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.MustBeBlocked
import com.wingedsheep.sdk.scripting.MustBlock
import com.wingedsheep.sdk.scripting.CantBeBlockedByMoreThan
import com.wingedsheep.sdk.scripting.CantBeBlockedByFewerThan
import com.wingedsheep.sdk.scripting.CantBlock
import com.wingedsheep.sdk.scripting.CantBlockUnless
import com.wingedsheep.sdk.scripting.CantBlockUnlessCoBlocker
import com.wingedsheep.sdk.scripting.StaticAbility
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.filters.unified.Scope
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.engine.legalactions.BlockerDeclarationDomainValidator
import com.wingedsheep.engine.legalactions.BlockerDeclarationDomainUnsupportedReason
import com.wingedsheep.engine.legalactions.RulesBlockRequirement
import com.wingedsheep.engine.legalactions.RulesBlockerDeclarationDomain
import com.wingedsheep.engine.legalactions.RulesBlockerDeclarationDomainResult
import com.wingedsheep.engine.legalactions.RulesCoBlockerRequirement
import java.util.UUID

/**
 * Handles the declare blockers step of combat.
 *
 * Responsibilities:
 * - Validating individual blockers (creature eligibility, evasion, can't block)
 * - Menace requirements
 * - Must-be-blocked requirements (Alluring Scent, Taunting Elf)
 * - Provoke requirements
 * - Projected must-block requirements (Grand Melee)
 * - Block taxes (Whipgrass Entangler)
 * - Persisting unordered block relations for the combat-damage assignment board
 * - Mandatory blocker assignment queries
 */
internal class BlockPhaseManager(
    private val cardRegistry: CardRegistry,
    private val blockEvasionRules: List<BlockEvasionRule>,
    private val manaAbilitySideEffectExecutor: com.wingedsheep.engine.mechanics.mana.ManaAbilitySideEffectExecutor,
) {
    private val conditionEvaluator = ConditionEvaluator()
    private val predicateEvaluator = PredicateEvaluator()

    /**
     * Validate and declare blockers.
     *
     * @param blockers Map of blocker entity ID to list of attackers being blocked
     */
    fun declareBlockers(
        state: GameState,
        blockingPlayer: EntityId,
        blockers: Map<EntityId, List<EntityId>>
    ): ExecutionResult {
        val blockerDomain = getBlockerDeclarationDomain(state, blockingPlayer)
        if (blockerDomain is RulesBlockerDeclarationDomainResult.Supported) {
            when (val validation = BlockerDeclarationDomainValidator.validate(
                blockerDomain.domain,
                DeclareBlockers(blockingPlayer, blockers),
            )) {
                com.wingedsheep.engine.legalactions.BlockerDeclarationValidationResult.Accepted -> Unit
                is com.wingedsheep.engine.legalactions.BlockerDeclarationValidationResult.Rejected ->
                    return ExecutionResult.error(
                        state,
                        blockerDomainRejectionMessage(
                            state = state,
                            blockingPlayer = blockingPlayer,
                            domain = blockerDomain.domain,
                            blockers = blockers,
                            reason = validation.reason,
                        ),
                    )
            }
        }

        // Validate each blocker
        for ((blockerId, attackerIds) in blockers) {
            val validation = validateBlocker(state, blockingPlayer, blockerId, attackerIds)
            if (validation != null) {
                return ExecutionResult.error(state, validation)
            }
        }

        // The complete certificate above owns all currently represented declaration-wide
        // restrictions/requirements. The legacy checks remain below only for a certificate that
        // could not be produced; that path is fail-closed at the public Gym boundary and retains
        // direct engine compatibility for callers outside Gym.
        if (blockerDomain is RulesBlockerDeclarationDomainResult.Unsupported) {
            val legacyValidation = validateLegacyDeclarationConstraints(state, blockingPlayer, blockers)
            if (legacyValidation != null) return ExecutionResult.error(state, legacyValidation)
        }

        // Calculate (but don't pay) the block tax. If non-zero, pause for the blocking
        // player to confirm — same reasoning as attack taxes: don't tap their mana
        // without consent.
        val projected = state.projectedState
        val totalBlockTax = CombatTaxes.blockTax(state, cardRegistry, blockers.keys, projected)
        if (totalBlockTax > 0) {
            return pauseForBlockTaxConfirmation(state, blockingPlayer, blockers, totalBlockTax)
        }

        return commitBlockDeclaration(state, blockingPlayer, blockers, taxEvents = emptyList())
    }

    /**
     * Resolve the complete 509.1a-c blocker certificate for one defending player.
     *
     * This is the only producer for the blocker declaration domain. Pairwise edges are resolved
     * through the same evasion/restriction machinery used by [validateBlocker], while the
     * declaration-wide fields are resolved from the current Rules state. If a stable public order
     * or exact requirement threshold cannot be produced, the whole domain is unsupported.
     */
    internal fun getBlockerDeclarationDomain(
        state: GameState,
        blockingPlayer: EntityId,
    ): RulesBlockerDeclarationDomainResult {
        val attackerIds = state.getBattlefield()
            .filter { state.getEntity(it)?.has<AttackingComponent>() == true }
        val attackerOrder = canonicalCombatOrder(state, attackerIds)
            ?: return RulesBlockerDeclarationDomainResult.Unsupported(
                BlockerDeclarationDomainUnsupportedReason.CANONICAL_ORDER_UNAVAILABLE,
            )

        val potentialBlockers = findPotentialBlockers(state, blockingPlayer)
        val relationByBlocker = LinkedHashMap<EntityId, List<EntityId>>()
        for (blockerId in potentialBlockers) {
            val legalAttackers = attackerOrder.filter { attackerId ->
                canDeclareBlockPair(state, blockingPlayer, blockerId, attackerId)
            }
            if (legalAttackers.isNotEmpty()) relationByBlocker[blockerId] = legalAttackers
        }
        val blockerOrder = canonicalCombatOrder(state, relationByBlocker.keys.toList())
            ?: return RulesBlockerDeclarationDomainResult.Unsupported(
                BlockerDeclarationDomainUnsupportedReason.CANONICAL_ORDER_UNAVAILABLE,
            )
        val relation = blockerOrder.associateWith { relationByBlocker.getValue(it) }
        val projected = state.projectedState
        val requirementRelation = blockerOrder.associateWith { blockerId ->
            if (CombatTaxes.blockTax(state, cardRegistry, setOf(blockerId), projected) == 0) {
                relation.getValue(blockerId)
            } else {
                emptyList()
            }
        }

        val maxAttackersByBlocker = blockerOrder.associateWith { blockerId ->
            maxAttackersForBlocker(state, blockerId, relation.getValue(blockerId).size)
        }
        val minBlockersByAttacker = attackerOrder.mapNotNull { attackerId ->
            minBlockersForAttacker(state, attackerId)?.let { attackerId to it }
        }.toMap()
        val maxBlockersByAttacker = attackerOrder.mapNotNull { attackerId ->
            maxBlockersForAttacker(state, attackerId)?.let { attackerId to it }
        }.toMap()
        val coBlockerRequirements = LinkedHashMap<EntityId, List<RulesCoBlockerRequirement>>()
        for (blockerId in blockerOrder) {
            val resolved = coBlockerRequirementsFor(state, blockerOrder, blockerId)
                ?: return RulesBlockerDeclarationDomainResult.Unsupported(
                    BlockerDeclarationDomainUnsupportedReason.INCOMPLETE_DECLARATION_CONSTRAINTS,
                )
            if (resolved.isNotEmpty()) coBlockerRequirements[blockerId] = resolved
        }
        val requirements = resolveBlockRequirements(
            state = state,
            attackerOrder = attackerOrder,
            blockerOrder = blockerOrder,
            relation = relation,
        ) ?: return RulesBlockerDeclarationDomainResult.Unsupported(
            BlockerDeclarationDomainUnsupportedReason.CANONICAL_ORDER_UNAVAILABLE,
        )

        val globalMaxBlockers = globalMaxBlockersFor(state)
        blockerDomainUnsupportedReason(
            state = state,
            blockingPlayer = blockingPlayer,
            globalMaxBlockers = globalMaxBlockers,
            minBlockersByAttacker = minBlockersByAttacker,
            maxBlockersByAttacker = maxBlockersByAttacker,
            coBlockerRequirements = coBlockerRequirements,
            requirements = requirements,
        )?.let { reason ->
            return RulesBlockerDeclarationDomainResult.Unsupported(reason)
        }

        val certificateWithoutThreshold = RulesBlockerDeclarationDomain(
            blockerOrder = blockerOrder,
            attackerOrder = attackerOrder,
            blockerToAttackers = relation,
            maxAttackersByBlocker = maxAttackersByBlocker,
            minBlockersByAttacker = minBlockersByAttacker,
            maxBlockersByAttacker = maxBlockersByAttacker,
            globalMaxBlockers = globalMaxBlockers,
            coBlockerRequirements = coBlockerRequirements,
            requirements = requirements,
            minimumSatisfiedRequirementCount = 0,
            canDeclareZeroBlockers = false,
            requirementBlockerToAttackers = requirementRelation,
        )
        val threshold = BlockerDeclarationDomainValidator.maximumSatisfiedRequirementCount(
            certificateWithoutThreshold,
        ) ?: return RulesBlockerDeclarationDomainResult.Unsupported(
            com.wingedsheep.engine.legalactions.BlockerDeclarationDomainUnsupportedReason.EXACT_REQUIREMENT_THRESHOLD_UNAVAILABLE,
        )
        val certificate = certificateWithoutThreshold.copy(
            minimumSatisfiedRequirementCount = threshold,
            canDeclareZeroBlockers =
                BlockerDeclarationDomainValidator.satisfiesDeclarationConstraints(
                    certificateWithoutThreshold,
                    emptyMap(),
                ) && BlockerDeclarationDomainValidator.satisfiedRequirementCountWithoutBlockingCosts(
                    certificateWithoutThreshold,
                    emptyMap(),
                ) >= threshold,
        )
        return RulesBlockerDeclarationDomainResult.Supported(certificate)
    }

    /**
     * Guard the certificate boundary against blocker semantics that the current resolved fields do
     * not faithfully carry. This is deliberately conservative: a shape that the existing Rules
     * validator can observe but this certificate cannot express makes the whole public domain
     * unsupported. It is not a second acceptance algorithm.
     */
    private fun blockerDomainUnsupportedReason(
        state: GameState,
        blockingPlayer: EntityId,
        globalMaxBlockers: Int?,
        minBlockersByAttacker: Map<EntityId, Int>,
        maxBlockersByAttacker: Map<EntityId, Int>,
        coBlockerRequirements: Map<EntityId, List<RulesCoBlockerRequirement>>,
        requirements: List<RulesBlockRequirement>,
    ): BlockerDeclarationDomainUnsupportedReason? {
        val declarationWideConstraintPresent =
            globalMaxBlockers != null ||
                minBlockersByAttacker.isNotEmpty() ||
                maxBlockersByAttacker.isNotEmpty() ||
                coBlockerRequirements.isNotEmpty() ||
                requirements.isNotEmpty()

        // A per-player action cannot certify a combat-wide cap or a team-wide 509.1c optimum when
        // another defending player may contribute blockers to the same declaration.
        if (CombatDefenders.defendingPlayers(state).size > 1 && declarationWideConstraintPresent) {
            return BlockerDeclarationDomainUnsupportedReason.INCOMPLETE_DECLARATION_CONSTRAINTS
        }
        if (state.sharedTurnTeam(blockingPlayer).size > 1 && declarationWideConstraintPresent) {
            return BlockerDeclarationDomainUnsupportedReason.INCOMPLETE_DECLARATION_CONSTRAINTS
        }

        for (sourceId in state.getBattlefield()) {
            val container = state.getEntity(sourceId) ?: continue
            // CR 708.2a: the printed identity and abilities of a face-down permanent are not
            // available to the defender and cannot change this public certificate.
            if (container.has<FaceDownComponent>()) continue
            val card = container.get<CardComponent>() ?: continue
            val statics = cardRegistry.getCard(card.cardDefinitionId)?.staticAbilities.orEmpty()
            for (ability in statics) {
                val active = activeStaticAbility(state, sourceId, ability) ?: continue
                val resolved = active.first
                val conditional = active.second
                when (resolved) {
                    is CanBlockAnyNumber -> {
                        if (conditional || resolved.filter.scope !is Scope.Self) return unsupportedConstraint()
                    }
                    is CanBlockAdditionalForCreatureGroup -> Unit // projected and counted by Rules
                    is CantBeBlockedByFewerThan -> {
                        if (conditional || resolved.filter.scope !is Scope.Self) return unsupportedConstraint()
                    }
                    is CantBeBlockedByMoreThan -> {
                        if (resolved.filter.scope !is Scope.Self) return unsupportedConstraint()
                    }
                    is CantBlockUnless -> {
                        if (conditional || resolved.filter.scope !is Scope.Self) return unsupportedConstraint()
                    }
                    is CantBlockUnlessCoBlocker -> {
                        if (conditional || resolved.filter.scope !is Scope.Self) return unsupportedConstraint()
                    }
                    is BlockerCountLimit -> {
                        if (conditional) return unsupportedConstraint()
                    }
                    is MustBeBlocked -> Unit // resolved by attackersWithMustBeBlockedStatic
                    is MustBlock -> Unit // resolved through ProjectedState; multiplicity is audited below
                    else -> Unit
                }
            }
        }

        // Granted statics do not pass through the ordinary static-ability projection. Only the
        // two direct, self-scoped forms that the existing final Rules checks already consume are
        // representable here; every other granted blocker constraint fails closed.
        for (grant in state.grantedStaticAbilities) {
            val target = state.getEntity(grant.entityId) ?: continue
            if (grant.entityId !in state.getBattlefield() || target.has<FaceDownComponent>()) continue
            val active = activeStaticAbility(state, grant.entityId, grant.ability) ?: continue
            val resolved = active.first
            when (resolved) {
                is CantBeBlockedByMoreThan -> {
                    if (active.second || resolved.filter.scope !is Scope.Self) return unsupportedConstraint()
                }
                is CantBlockUnlessCoBlocker -> {
                    if (active.second || resolved.filter.scope !is Scope.Self) return unsupportedConstraint()
                }
                is CanBlockAnyNumber,
                is CanBlockAdditionalForCreatureGroup,
                is CantBeBlockedByFewerThan,
                is CantBlockUnless,
                is BlockerCountLimit,
                is MustBeBlocked,
                is MustBlock -> return unsupportedConstraint()
                else -> Unit
            }
        }

        if (hasUnrepresentedMustBlockMultiplicity(state, blockingPlayer)) {
            return BlockerDeclarationDomainUnsupportedReason.INCOMPLETE_DECLARATION_CONSTRAINTS
        }
        return null
    }

    private fun unsupportedConstraint(): BlockerDeclarationDomainUnsupportedReason =
        BlockerDeclarationDomainUnsupportedReason.UNSUPPORTED_RULE_OR_MECHANIC

    private fun activeStaticAbility(
        state: GameState,
        sourceId: EntityId,
        ability: StaticAbility,
    ): Pair<StaticAbility, Boolean>? {
        if (ability !is ConditionalStaticAbility) return ability to false
        val controllerId = state.projectedState.getController(sourceId) ?: return null
        return if (conditionEvaluator.evaluate(
                state,
                ability.condition,
                EffectContext(sourceId = sourceId, controllerId = controllerId),
            )
        ) {
            ability.ability to true
        } else {
            null
        }
    }

    /**
     * [ProjectedState.mustBlock] is a Boolean, while CR 509.1c counts requirement instances. If
     * more than one active Rules source can set that Boolean for a blocker, the current projection
     * has already lost the instance multiplicity and cannot be a complete public certificate.
     */
    private fun hasUnrepresentedMustBlockMultiplicity(
        state: GameState,
        blockingPlayer: EntityId,
    ): Boolean {
        val projected = state.projectedState
        val potentialBlockers = findPotentialBlockers(state, blockingPlayer)
        if (potentialBlockers.none { projected.mustBlock(it) }) return false

        val staticEffectCount = state.getBattlefield().sumOf { sourceId ->
            val source = state.getEntity(sourceId) ?: return@sumOf 0
            if (source.has<FaceDownComponent>()) return@sumOf 0
            source.get<ContinuousEffectSourceComponent>()?.effects.orEmpty().count { effect ->
                effect.modification is Modification.SetMustBlock &&
                    activeContinuousEffect(state, sourceId, effect.sourceCondition)
            }
        }
        val floatingEffectCount = state.floatingEffects.count { floating ->
            floating.effect.modification is SerializableModification.SetMustBlock &&
                activeFloatingEffect(state, floating)
        }
        return staticEffectCount + floatingEffectCount != 1
    }

    private fun activeContinuousEffect(
        state: GameState,
        sourceId: EntityId,
        condition: com.wingedsheep.sdk.scripting.conditions.Condition?,
    ): Boolean {
        if (condition == null) return true
        val controllerId = state.projectedState.getController(sourceId) ?: return false
        return conditionEvaluator.evaluate(
            state,
            condition,
            EffectContext(sourceId = sourceId, controllerId = controllerId),
        )
    }

    private fun activeFloatingEffect(
        state: GameState,
        floating: com.wingedsheep.engine.mechanics.layers.ActiveFloatingEffect,
    ): Boolean {
        val condition = floating.effect.sourceCondition ?: return true
        return conditionEvaluator.evaluate(
            state,
            condition,
            EffectContext(
                sourceId = floating.sourceId ?: floating.id,
                controllerId = floating.controllerId,
            ),
        )
    }

    private fun canDeclareBlockPair(
        state: GameState,
        blockingPlayer: EntityId,
        blockerId: EntityId,
        attackerId: EntityId,
    ): Boolean {
        val container = state.getEntity(blockerId) ?: return false
        val card = container.get<CardComponent>() ?: return false
        val projected = state.projectedState
        if (!projected.isCreature(blockerId) || projected.getController(blockerId) != blockingPlayer) return false
        if (container.has<TappedComponent>() || container.has<BlockingComponent>()) return false
        val faceDown = container.has<FaceDownComponent>()
        if (!faceDown && validateCantBlock(card) != null) return false
        if (projected.cantBlock(blockerId)) return false
        if (!faceDown && validateCantBlockUnless(state, blockerId, blockingPlayer, projected) != null) return false

        val attacking = state.getEntity(attackerId)?.get<AttackingComponent>() ?: return false
        if (CombatDefenders.defendingPlayerOf(state, attacking) !in state.sharedTurnTeam(blockingPlayer)) return false
        return canCreatureBlockAttacker(state, blockerId, attackerId, blockingPlayer, projected)
    }

    private fun maxAttackersForBlocker(
        state: GameState,
        blockerId: EntityId,
        candidateCount: Int,
    ): Int {
        val container = state.getEntity(blockerId) ?: return 0
        val card = container.get<CardComponent>() ?: return 0
        val canBlockAny = !container.has<FaceDownComponent>() &&
            cardRegistry.getCard(card.cardDefinitionId)?.staticAbilities?.any { it is CanBlockAnyNumber } == true
        return if (canBlockAny) candidateCount else {
            (1 + state.projectedState.getAdditionalBlockCount(blockerId)).coerceAtMost(candidateCount)
        }
    }

    private fun minBlockersForAttacker(state: GameState, attackerId: EntityId): Int? {
        val container = state.getEntity(attackerId) ?: return null
        if (container.has<FaceDownComponent>()) return null
        var minimum = if (state.projectedState.hasKeyword(attackerId, Keyword.MENACE)) 2 else 0
        val card = container.get<CardComponent>() ?: return null
        val printed = cardRegistry.getCard(card.cardDefinitionId)?.staticAbilities.orEmpty()
            .filterIsInstance<com.wingedsheep.sdk.scripting.CantBeBlockedByFewerThan>()
            .filter { it.filter.scope is com.wingedsheep.sdk.scripting.filters.unified.Scope.Self }
        minimum = maxOf(minimum, printed.maxOfOrNull { it.minBlockers } ?: 0)
        return minimum.takeIf { it > 0 }
    }

    private fun maxBlockersForAttacker(state: GameState, attackerId: EntityId): Int? {
        val container = state.getEntity(attackerId) ?: return null
        if (container.has<FaceDownComponent>()) return null
        val card = container.get<CardComponent>() ?: return null
        val cardDef = cardRegistry.getCard(card.cardDefinitionId)
        val attackerController = state.projectedState.getController(attackerId)
        val printedLimit = cardDef?.staticAbilities
            ?.mapNotNull { ability ->
                val unwrapped = if (ability is ConditionalStaticAbility) ability.ability else ability
                if (unwrapped !is CantBeBlockedByMoreThan ||
                    unwrapped.filter.scope !is com.wingedsheep.sdk.scripting.filters.unified.Scope.Self
                ) return@mapNotNull null
                if (ability is ConditionalStaticAbility) {
                    if (attackerController == null || !conditionEvaluator.evaluate(
                            state,
                            ability.condition,
                            EffectContext(sourceId = attackerId, controllerId = attackerController),
                        )
                    ) return@mapNotNull null
                }
                unwrapped.maxBlockers
            }
            ?.minOrNull()
        val grantedLimit = state.grantedStaticAbilities
            .filter { it.entityId == attackerId }
            .map { it.ability }
            .filterIsInstance<CantBeBlockedByMoreThan>()
            .filter { it.filter.scope is com.wingedsheep.sdk.scripting.filters.unified.Scope.Self }
            .minOfOrNull { it.maxBlockers }
        val flagLimit = if (state.projectedState.hasKeyword(
                attackerId,
                com.wingedsheep.sdk.core.AbilityFlag.CANT_BE_BLOCKED_BY_MORE_THAN_ONE,
            )
        ) 1 else null
        return listOfNotNull(printedLimit, grantedLimit, flagLimit).minOrNull()
    }

    private fun globalMaxBlockersFor(state: GameState): Int? = state.getBattlefield()
        .asSequence()
        // CR 708.2a: a face-down permanent has no printed abilities. Reading the underlying
        // definition here would make a hidden opponent permanent change the public certificate.
        .filter { state.getEntity(it)?.has<FaceDownComponent>() != true }
        .mapNotNull { state.getEntity(it)?.get<CardComponent>() }
        .flatMap { cardRegistry.getCard(it.cardDefinitionId)?.staticAbilities.orEmpty().asSequence() }
        .filterIsInstance<BlockerCountLimit>()
        .map { it.maxBlockers }
        .minOrNull()

    private fun coBlockerRequirementsFor(
        state: GameState,
        blockerOrder: List<EntityId>,
        blockerId: EntityId,
    ): List<RulesCoBlockerRequirement>? {
        if (state.getEntity(blockerId)?.has<FaceDownComponent>() == true) return emptyList()
        val card = state.getEntity(blockerId)?.get<CardComponent>() ?: return emptyList()
        val printed = cardRegistry.getCard(card.cardDefinitionId)?.staticAbilities.orEmpty()
        val granted = state.grantedStaticAbilities.filter { it.entityId == blockerId }.map { it.ability }
        val restrictions = (printed + granted)
            .filterIsInstance<CantBlockUnlessCoBlocker>()
            .filter { it.filter.scope is Scope.Self }
        val blockerRanks = blockerOrder.withIndex().associate { it.value to it.index }
        val requirements = restrictions.map { restriction ->
            RulesCoBlockerRequirement(
                eligibleCoBlockers = blockerOrder.filter { otherId ->
                    otherId != blockerId && predicateEvaluator.matches(
                        state,
                        state.projectedState,
                        otherId,
                        restriction.coBlockerFilter,
                        PredicateContext(controllerId = state.projectedState.getController(blockerId) ?: blockerId),
                    )
                }
            )
        }.sortedWith(compareBy { requirement ->
            requirement.eligibleCoBlockers.joinToString(",") { blockerRanks.getValue(it).toString() }
        })
        return requirements.takeUnless { it.any { requirement -> requirement.eligibleCoBlockers.isEmpty() } }
    }

    private fun resolveBlockRequirements(
        state: GameState,
        attackerOrder: List<EntityId>,
        blockerOrder: List<EntityId>,
        relation: Map<EntityId, List<EntityId>>,
    ): List<RulesBlockRequirement>? {
        val requirements = mutableListOf<RulesBlockRequirement>()
        val attackerSet = attackerOrder.toSet()
        val mustBeBlockedByAllOccurrences = mutableListOf<EntityId>()

        for (floatingEffect in state.floatingEffects) {
            when (val modification = floatingEffect.effect.modification) {
                is SerializableModification.MustBlockSpecificAttacker -> {
                    val relevantBlockers = floatingEffect.effect.affectedEntities
                        .filter { blockerId ->
                            blockerId in relation && modification.attackerId in relation.getValue(blockerId)
                        }
                    for (blockerId in canonicalOccurrences(blockerOrder, relevantBlockers) ?: return null) {
                        if (blockerId in relation && modification.attackerId in relation.getValue(blockerId)) {
                            requirements += RulesBlockRequirement.BlockSpecific(blockerId, modification.attackerId)
                        }
                    }
                }

                is SerializableModification.MustBeBlockedIfAble -> {
                    val relevantAttackers = floatingEffect.effect.affectedEntities.filter { it in attackerSet }
                    for (attackerId in canonicalOccurrences(attackerOrder, relevantAttackers) ?: return null) {
                        if (attackerId in attackerSet) {
                            requirements += RulesBlockRequirement.AttackerMustBeBlockedIfAble(attackerId)
                        }
                    }
                }

                is SerializableModification.MustBeBlockedByAll -> {
                    val relevantAttackers = floatingEffect.effect.affectedEntities.filter { it in attackerSet }
                    for (attackerId in canonicalOccurrences(attackerOrder, relevantAttackers) ?: return null) {
                        if (attackerId in attackerSet) mustBeBlockedByAllOccurrences += attackerId
                    }
                }

                else -> Unit
            }
        }

        canonicalOccurrences(
            attackerOrder,
            attackersWithMustBeBlockedStatic(state, allCreatures = false).filter { it in attackerSet },
        )?.forEach { requirements += RulesBlockRequirement.AttackerMustBeBlockedIfAble(it) }
            ?: return null
        canonicalOccurrences(
            attackerOrder,
            attackersWithMustBeBlockedStatic(state, allCreatures = true).filter { it in attackerSet },
        )?.forEach { mustBeBlockedByAllOccurrences += it }
            ?: return null

        // A Lure-style effect resolves to one 509.1c requirement for each blocker able to block
        // each affected attacker. A blocker may satisfy several such instances only when its
        // resolved max-attacker capacity permits assigning all of those attackers. Keeping these
        // as BlockOneOf instances (including repeated singleton instances) preserves the source
        // multiplicity without turning the effect into an unconditional attacker-level hard pin.
        for (attackerId in mustBeBlockedByAllOccurrences) {
            for (blockerId in blockerOrder) {
                if (attackerId in relation.getValue(blockerId)) {
                    requirements += RulesBlockRequirement.BlockOneOf(blockerId, listOf(attackerId))
                }
            }
        }

        val projected = state.projectedState
        for (blockerId in blockerOrder) {
            if (projected.mustBlock(blockerId) && relation.getValue(blockerId).isNotEmpty()) {
                requirements += RulesBlockRequirement.BlockerMustBlockIfAble(blockerId)
            }
        }

        val blockerRanks = blockerOrder.withIndex().associate { it.value to it.index }
        val attackerRanks = attackerOrder.withIndex().associate { it.value to it.index }
        return requirements.sortedWith(compareBy<RulesBlockRequirement> {
            when (it) {
                is RulesBlockRequirement.BlockSpecific -> 0
                is RulesBlockRequirement.BlockOneOf -> 1
                is RulesBlockRequirement.AttackerMustBeBlockedIfAble -> 2
                is RulesBlockRequirement.AttackerMustBeBlockedByAll -> 3
                is RulesBlockRequirement.BlockerMustBlockIfAble -> 4
            }
        }.thenBy {
            when (it) {
                is RulesBlockRequirement.BlockSpecific -> blockerRanks.getValue(it.blockerId)
                is RulesBlockRequirement.BlockOneOf -> blockerRanks.getValue(it.blockerId)
                is RulesBlockRequirement.AttackerMustBeBlockedIfAble -> attackerRanks.getValue(it.attackerId)
                is RulesBlockRequirement.AttackerMustBeBlockedByAll -> attackerRanks.getValue(it.attackerId)
                is RulesBlockRequirement.BlockerMustBlockIfAble -> blockerRanks.getValue(it.blockerId)
            }
        }.thenBy {
            when (it) {
                is RulesBlockRequirement.BlockSpecific -> attackerRanks.getValue(it.attackerId)
                is RulesBlockRequirement.BlockOneOf -> it.attackerIds.map(attackerRanks::getValue)
                    .joinToString(",")
                else -> ""
            }
        })
    }

    /**
     * Canonicalize a multiset of already relevant battlefield entities without collapsing equal
     * occurrences. The public order is producer-owned; this helper is not an EntityId sort.
     */
    private fun canonicalOccurrences(
        order: List<EntityId>,
        occurrences: List<EntityId>,
    ): List<EntityId>? {
        val counts = occurrences.groupingBy { it }.eachCount()
        if (counts.keys.any { it !in order }) return null
        return order.flatMap { entityId ->
            List(counts.getOrDefault(entityId, 0)) { entityId }
        }
    }

    private fun canonicalCombatOrder(state: GameState, entityIds: List<EntityId>): List<EntityId>? {
        val ranked = entityIds.map { entityId ->
            val rank = state.objectIdentityStamps[entityId]
                ?: state.getEntity(entityId)?.get<BattlefieldEntryTimestampComponent>()?.timestamp
                ?: return null
            entityId to rank
        }
        if (ranked.map { it.second }.size != ranked.map { it.second }.toSet().size) return null
        return ranked.sortedBy { it.second }.map { it.first }
    }

    private fun validateLegacyDeclarationConstraints(
        state: GameState,
        blockingPlayer: EntityId,
        blockers: Map<EntityId, List<EntityId>>,
    ): String? = listOf(
        validateMenaceRequirements(state, blockers),
        validateMinBlockersRequirements(state, blockers),
        validateMaxBlockersRequirements(state, blockers),
        validateGlobalBlockerCount(state, blockers.keys),
        validateCoBlockerRequirements(state, state.projectedState, blockers.keys),
        validateMustBeBlockedRequirements(state, blockingPlayer, blockers),
        validateProvokeRequirements(state, blockingPlayer, blockers),
        validateProjectedMustBlockRequirements(state, blockingPlayer, blockers),
    ).firstOrNull()

    /**
     * Preserve the direct engine's useful restriction wording after the certificate has rejected
     * a pair. The certificate remains the acceptance authority. The existing pairwise Rules
     * validator is consulted only to render its established diagnostic, never to accept or reject
     * the declaration a second time.
     */
    private fun blockerDomainRejectionMessage(
        state: GameState,
        blockingPlayer: EntityId,
        domain: com.wingedsheep.engine.legalactions.RulesBlockerDeclarationDomain,
        blockers: Map<EntityId, List<EntityId>>,
        reason: com.wingedsheep.engine.legalactions.BlockerDeclarationRejection,
    ): String {
        if (reason == com.wingedsheep.engine.legalactions.BlockerDeclarationRejection.UNKNOWN_BLOCKER ||
            reason == com.wingedsheep.engine.legalactions.BlockerDeclarationRejection.INVALID_ATTACKER_FOR_BLOCKER
        ) {
            val pairwiseDiagnostic = blockers.asSequence()
                .filter { (blockerId, _) -> state.getEntity(blockerId) != null }
                .mapNotNull { (blockerId, attackerIds) ->
                    // This is diagnostic-only: the certificate has already made the acceptance
                    // decision. Reusing the existing stateful checker preserves direct Rules API
                    // error wording without introducing a second Gym legality algorithm.
                    validateBlocker(state, blockingPlayer, blockerId, attackerIds)
                }
                .firstOrNull()
            if (pairwiseDiagnostic != null) return pairwiseDiagnostic
        }

        val detail = when (reason) {
            com.wingedsheep.engine.legalactions.BlockerDeclarationRejection.ZERO_BLOCKERS_FORBIDDEN ->
                "at least one creature must block when able"

            com.wingedsheep.engine.legalactions.BlockerDeclarationRejection.MAX_BLOCKERS_EXCEEDED -> {
                val counts = blockers.values.flatten().groupingBy { it }.eachCount()
                val violation = domain.attackerOrder.firstNotNullOfOrNull { attackerId ->
                    val count = counts.getOrDefault(attackerId, 0)
                    val limit = domain.maxBlockersByAttacker[attackerId] ?: return@firstNotNullOfOrNull null
                    if (count > limit) attackerId to limit else null
                }
                violation?.second?.let { limit ->
                    if (limit == 1) "more than one creature" else "more than $limit creatures"
                }
            }

            else -> null
        }
        val suffix = detail?.let { ": $it" }.orEmpty()
        return "Blocker declaration is outside the Rules-owned domain: ${reason.name}$suffix"
    }

    /**
     * Apply the post-tax commitment for a declared block: stamp [BlockingComponent] /
     * [BlockedComponent], mark the blockers-declared tracking component, emit the
     * [BlockersDeclaredEvent]. Modern combat damage does not queue a blocker-order or
     * attacker-order decision here.
     *
     * Callable from the synchronous (no-tax) path in [declareBlockers] and from
     * [com.wingedsheep.engine.handlers.continuations.CombatTaxContinuationResumer] after
     * the player confirms the tax.
     */
    internal fun commitBlockDeclaration(
        state: GameState,
        blockingPlayer: EntityId,
        blockers: Map<EntityId, List<EntityId>>,
        taxEvents: List<com.wingedsheep.engine.core.GameEvent>,
    ): ExecutionResult {
        // CR 702.22h: blocking any member of an attacking band blocks the whole band — a blocker
        // assigned to one band member is treated as blocking every member. Expand the declared
        // assignments before stamping so the rest of combat (the damage board) sees the
        // full bipartite picture.
        val bandMembers = collectBands(state)
        val expandedBlockers: Map<EntityId, List<EntityId>> = blockers.mapValues { (_, attackerIds) ->
            val expanded = LinkedHashSet<EntityId>()
            for (attackerId in attackerIds) {
                expanded += attackerId
                val bandId = state.getEntity(attackerId)?.get<AttackingComponent>()?.bandId
                if (bandId != null) expanded += bandMembers[bandId] ?: emptySet()
            }
            expanded.toList()
        }

        var newState = state
        // Capture legendary-ness of every combatant *now* (at block declaration), so the
        // "blocked or was blocked by a legendary creature this turn" marker (You Cannot Pass!)
        // reflects the pairing-time status even if a legendary partner later leaves or loses
        // legendary-ness (CR: the predicate looks at combat history).
        val projected = state.projectedState
        for ((blockerId, attackerIds) in expandedBlockers) {
            newState = newState.updateEntity(blockerId) { container ->
                container.with(BlockingComponent(attackerIds))
                    .with(BlockedThisCombatComponent)
            }

            // Mark attackers as blocked
            for (attackerId in attackerIds) {
                newState = newState.updateEntity(attackerId) { container ->
                    val existing = container.get<BlockedComponent>()?.blockerIds ?: emptyList()
                    container.with(BlockedComponent(existing + blockerId))
                }
            }

            // Stamp the "paired with a legendary in combat this turn" marker on each side
            // whose partner is legendary.
            val blockerIsLegendary = projected.isLegendary(blockerId)
            for (attackerId in attackerIds) {
                if (projected.isLegendary(attackerId)) {
                    newState = newState.updateEntity(blockerId) { container ->
                        container.with(com.wingedsheep.engine.state.components.combat.BlockedOrWasBlockedByLegendaryThisTurnComponent)
                    }
                }
                if (blockerIsLegendary) {
                    newState = newState.updateEntity(attackerId) { container ->
                        container.with(com.wingedsheep.engine.state.components.combat.BlockedOrWasBlockedByLegendaryThisTurnComponent)
                    }
                }
            }
        }

        // Mark that blockers have been declared this combat (even if empty)
        newState = newState.updateEntity(blockingPlayer) { container ->
            container.with(BlockersDeclaredThisCombatComponent)
        }

        val blockerNameMap = expandedBlockers.keys.associateWith { state.getEntity(it)?.get<CardComponent>()?.name ?: "Creature" }
        val attackerNameMap = expandedBlockers.values.flatten().distinct().associateWith { state.getEntity(it)?.get<CardComponent>()?.name ?: "Creature" }
        val blockersEvent = BlockersDeclaredEvent(expandedBlockers, blockerNameMap, attackerNameMap)
        val blockTaxEvents = taxEvents

        // Damage-assignment order (CR 510.1c/d) is no longer collected in a standalone
        // OrderObjectsDecision pre-step. The combat resolution board consumes the unordered
        // BlockedComponent/BlockingComponent relations and collects the complete split directly.
        // No pause occurs here.
        return ExecutionResult.success(
            newState,
            blockTaxEvents + blockersEvent
        )
    }

    /**
     * Collect the current attacking bands, keyed by [AttackingComponent.bandId]. Used to expand
     * declared block assignments so a blocker on one band member blocks the whole band (CR 702.22h).
     */
    private fun collectBands(state: GameState): Map<String, Set<EntityId>> {
        val result = mutableMapOf<String, MutableSet<EntityId>>()
        for ((entityId, container) in state.entities) {
            val bandId = container.get<AttackingComponent>()?.bandId ?: continue
            result.getOrPut(bandId) { mutableSetOf() }.add(entityId)
        }
        return result
    }

    /**
     * Check if a creature can legally block at least one of the current attackers.
     */
    fun canCreatureBlockAnyAttacker(state: GameState, blockerId: EntityId, blockingPlayer: EntityId): Boolean {
        val blockerContainer = state.getEntity(blockerId) ?: return false
        val blockerCard = blockerContainer.get<CardComponent>() ?: return false

        val isFaceDown = blockerContainer.has<FaceDownComponent>()
        if (!isFaceDown && hasCantBlockAbility(blockerCard)) return false

        val projected = state.projectedState

        if (projected.cantBlock(blockerId)) return false

        if (!isFaceDown && hasCantBlockUnlessRestriction(state, blockerId, blockingPlayer, projected)) return false

        val attackers = state.entities.filter { (_, container) -> container.has<AttackingComponent>() }.keys

        return attackers.any { attackerId ->
            canCreatureBlockAttacker(state, blockerId, attackerId, blockingPlayer, projected)
        }
    }

    /**
     * Compute mandatory blocker assignments from floating effects.
     * Returns a map of blocker → list of attackers it must block.
     */
    fun getMandatoryBlockerAssignments(state: GameState, blockingPlayer: EntityId): Map<EntityId, List<EntityId>> {
        val projected = state.projectedState
        val potentialBlockers = findPotentialBlockers(state, blockingPlayer)
        val result = mutableMapOf<EntityId, MutableList<EntityId>>()

        // 1. MustBlockSpecificAttacker (Provoke)
        val provokeConstraints = state.floatingEffects
            .filter { it.effect.modification is SerializableModification.MustBlockSpecificAttacker }
            .flatMap { floatingEffect ->
                val modification = floatingEffect.effect.modification as SerializableModification.MustBlockSpecificAttacker
                floatingEffect.effect.affectedEntities.map { blockerId ->
                    blockerId to modification.attackerId
                }
            }

        for ((blockerId, attackerId) in provokeConstraints) {
            if (blockerId !in potentialBlockers) continue
            val controller = projected.getController(blockerId)
            if (controller != blockingPlayer) continue
            val attackerContainer = state.getEntity(attackerId) ?: continue
            if (!attackerContainer.has<AttackingComponent>()) continue
            if (!canCreatureBlockAttacker(state, blockerId, attackerId, blockingPlayer, projected)) continue
            result.getOrPut(blockerId) { mutableListOf() }.add(attackerId)
        }

        // 2. MustBeBlockedByAll (Taunting Elf, Alluring Scent)
        val mustBeBlockedAttackers = findMustBeBlockedAttackers(state)
        for (attackerId in mustBeBlockedAttackers) {
            for (blockerId in potentialBlockers) {
                if (canCreatureBlockAttacker(state, blockerId, attackerId, blockingPlayer, projected)) {
                    result.getOrPut(blockerId) { mutableListOf() }.add(attackerId)
                }
            }
        }

        return result.filterValues { it.isNotEmpty() }
    }

    // =========================================================================
    // Blocker Validation
    // =========================================================================

    /**
     * Validate that a creature can block.
     */
    private fun validateBlocker(
        state: GameState,
        blockingPlayer: EntityId,
        blockerId: EntityId,
        attackerIds: List<EntityId>
    ): String? {
        val container = state.getEntity(blockerId)
            ?: return "Blocker not found: $blockerId"

        val cardComponent = container.get<CardComponent>()
            ?: return "Not a card: $blockerId"

        val projected = state.projectedState

        if (!projected.isCreature(blockerId)) {
            return "Only creatures can block: ${cardComponent.name}"
        }
        val controller = projected.getController(blockerId)
        if (controller != blockingPlayer) {
            return "You don't control ${cardComponent.name}"
        }

        if (container.has<TappedComponent>()) {
            return "${cardComponent.name} is tapped and cannot block"
        }

        if (container.has<BlockingComponent>()) {
            return "${cardComponent.name} is already blocking"
        }

        val isFaceDown = container.has<FaceDownComponent>()
        if (!isFaceDown) {
            val cantBlockValidation = validateCantBlock(cardComponent)
            if (cantBlockValidation != null) {
                return cantBlockValidation
            }
        }

        if (projected.cantBlock(blockerId)) {
            return "${cardComponent.name} can't block"
        }

        if (!isFaceDown) {
            val cantBlockUnlessError = validateCantBlockUnless(state, blockerId, blockingPlayer, projected)
            if (cantBlockUnlessError != null) return cantBlockUnlessError
        }

        if (attackerIds.size > 1) {
            val canBlockAny = if (!isFaceDown) {
                val cardDef = cardRegistry.getCard(cardComponent.cardDefinitionId)
                cardDef?.staticAbilities?.any { it is CanBlockAnyNumber } == true
            } else false
            if (!canBlockAny) {
                val additionalBlocks = projected.getAdditionalBlockCount(blockerId)
                val maxBlocks = 1 + additionalBlocks
                if (attackerIds.size > maxBlocks) {
                    val countText = if (maxBlocks == 1) "one creature" else "$maxBlocks creatures"
                    return "${cardComponent.name} can only block $countText"
                }
            }
        }

        // Check each attacker
        for (attackerId in attackerIds) {
            // CR 509.1b / 805.10d: a creature can only block an attacker that is attacking its
            // controller (or a planeswalker/battle its controller protects). Under shared team turns
            // (Two-Headed Giant) the defending team blocks as one, so a creature may block an attacker
            // aimed at any teammate; without shared team turns (Team vs. Team — CR 808, non-team
            // games) sharedTurnTeam is a singleton, so you can only block attackers aimed at you.
            val attacking = state.getEntity(attackerId)?.get<AttackingComponent>()
                ?: return "${cardComponent.name} can't block: ${attackerId.value} isn't attacking"
            val attackedDefender = CombatDefenders.defendingPlayerOf(state, attacking)
            if (attackedDefender !in state.sharedTurnTeam(blockingPlayer)) {
                return "${cardComponent.name} can't block a creature attacking another player"
            }

            val evasionValidation = validateCanBlock(state, blockerId, attackerId, blockingPlayer)
            if (evasionValidation != null) {
                return evasionValidation
            }
        }

        return null
    }

    /**
     * Validate that a blocker can block a specific attacker (evasion abilities).
     * Delegates to registered [BlockEvasionRule] instances.
     */
    private fun validateCanBlock(
        state: GameState,
        blockerId: EntityId,
        attackerId: EntityId,
        blockingPlayer: EntityId
    ): String? {
        state.getEntity(attackerId) ?: return "Attacker not found: $attackerId"
        state.getEntity(attackerId)?.get<CardComponent>() ?: return "Not a card: $attackerId"

        val ctx = BlockCheckContext(
            state = state,
            projected = state.projectedState,
            attackerId = attackerId,
            blockerId = blockerId,
            blockingPlayer = blockingPlayer,
            cardRegistry = cardRegistry
        )
        for (rule in blockEvasionRules) {
            val error = rule.check(ctx)
            if (error != null) return error
        }
        return null
    }

    /**
     * Check if a creature has "can't block" ability (e.g., Craven Giant, Jungle Lion).
     */
    private fun validateCantBlock(blockerCard: CardComponent): String? {
        val cardDef = cardRegistry.getCard(blockerCard.cardDefinitionId) ?: return null
        val cantBlockAbility = cardDef.staticAbilities.filterIsInstance<CantBlock>().firstOrNull()
            ?: return null

        if (cantBlockAbility.filter.scope is com.wingedsheep.sdk.scripting.filters.unified.Scope.Self) {
            return "${blockerCard.name} can't block"
        }

        return null
    }

    /**
     * Check if a creature has "can't block" ability.
     * Returns true if the creature cannot block.
     */
    private fun hasCantBlockAbility(blockerCard: CardComponent): Boolean {
        val cardDef = cardRegistry.getCard(blockerCard.cardDefinitionId) ?: return false
        val cantBlockAbility = cardDef.staticAbilities.filterIsInstance<CantBlock>().firstOrNull()
            ?: return false

        return cantBlockAbility.filter.scope is com.wingedsheep.sdk.scripting.filters.unified.Scope.Self
    }

    /**
     * Check if a creature can legally block an attacker.
     * Delegates to registered [BlockEvasionRule] instances for evasion checks,
     * plus blocker-level restrictions (can't block, face-down abilities).
     */
    private fun canCreatureBlockAttacker(
        state: GameState,
        blockerId: EntityId,
        attackerId: EntityId,
        blockingPlayer: EntityId,
        projected: ProjectedState
    ): Boolean {
        val blockerContainer = state.getEntity(blockerId) ?: return false
        state.getEntity(attackerId) ?: return false

        val blockerCard = blockerContainer.get<CardComponent>() ?: return false

        val isFaceDown = blockerContainer.has<FaceDownComponent>()
        if (!isFaceDown && hasCantBlockAbility(blockerCard)) {
            return false
        }

        if (projected.cantBlock(blockerId)) {
            return false
        }

        val ctx = BlockCheckContext(
            state = state,
            projected = projected,
            attackerId = attackerId,
            blockerId = blockerId,
            blockingPlayer = blockingPlayer,
            cardRegistry = cardRegistry
        )
        return blockEvasionRules.all { it.check(ctx) == null }
    }

    // =========================================================================
    // Menace
    // =========================================================================

    /**
     * Validate menace requirements (must be blocked by 2+ creatures).
     */
    private fun validateMenaceRequirements(
        state: GameState,
        blockers: Map<EntityId, List<EntityId>>
    ): String? {
        val attackerToBlockers = mutableMapOf<EntityId, MutableList<EntityId>>()
        for ((blockerId, attackerIds) in blockers) {
            for (attackerId in attackerIds) {
                attackerToBlockers.getOrPut(attackerId) { mutableListOf() }.add(blockerId)
            }
        }

        val projected = state.projectedState

        for ((attackerId, blockerList) in attackerToBlockers) {
            val attackerContainer = state.getEntity(attackerId) ?: continue
            val attackerCard = attackerContainer.get<CardComponent>() ?: continue

            if (projected.hasKeyword(attackerId, Keyword.MENACE)) {
                if (blockerList.size < 2) {
                    return "${attackerCard.name} has menace and must be blocked by 2 or more creatures"
                }
            }
        }

        return null
    }

    /**
     * Validate "can't be blocked except by N or more creatures" ([CantBeBlockedByFewerThan]).
     * Generalizes menace: an attacker carrying the static may be left unblocked, but if blocked it
     * must have at least [CantBeBlockedByFewerThan.minBlockers] blockers.
     */
    private fun validateMinBlockersRequirements(
        state: GameState,
        blockers: Map<EntityId, List<EntityId>>
    ): String? {
        val attackerToBlockers = mutableMapOf<EntityId, MutableList<EntityId>>()
        for ((blockerId, attackerIds) in blockers) {
            for (attackerId in attackerIds) {
                attackerToBlockers.getOrPut(attackerId) { mutableListOf() }.add(blockerId)
            }
        }

        for ((attackerId, blockerList) in attackerToBlockers) {
            if (blockerList.isEmpty()) continue
            val attackerContainer = state.getEntity(attackerId) ?: continue
            if (attackerContainer.has<FaceDownComponent>()) continue
            val attackerCard = attackerContainer.get<CardComponent>() ?: continue
            val cardDef = cardRegistry.getCard(attackerCard.cardDefinitionId) ?: continue

            val minBlockers = cardDef.staticAbilities
                .filterIsInstance<com.wingedsheep.sdk.scripting.CantBeBlockedByFewerThan>()
                .filter { it.filter.scope is com.wingedsheep.sdk.scripting.filters.unified.Scope.Self }
                .maxOfOrNull { it.minBlockers } ?: continue

            if (blockerList.size < minBlockers) {
                return "${attackerCard.name} can't be blocked except by $minBlockers or more creatures"
            }
        }

        return null
    }

    /**
     * Validate `CantBeBlockedByMoreThan` restrictions (CR 509.1b).
     * Each attacker with this static ability caps the number of creatures that may block it.
     */
    private fun validateMaxBlockersRequirements(
        state: GameState,
        blockers: Map<EntityId, List<EntityId>>
    ): String? {
        val attackerToBlockerCount = mutableMapOf<EntityId, Int>()
        for (attackerIds in blockers.values) {
            for (attackerId in attackerIds) {
                attackerToBlockerCount.merge(attackerId, 1, Int::plus)
            }
        }

        for ((attackerId, count) in attackerToBlockerCount) {
            val attackerContainer = state.getEntity(attackerId) ?: continue
            if (attackerContainer.has<FaceDownComponent>()) continue
            val attackerCard = attackerContainer.get<CardComponent>() ?: continue
            val cardDef = cardRegistry.getCard(attackerCard.cardDefinitionId)

            // Printed "can't be blocked by more than N", including the conditional form
            // (Akawalli's descend-8 "can't be blocked by more than one creature") — unwrap a
            // ConditionalStaticAbility and honor it only while its condition currently holds,
            // mirroring the MustBeBlocked handling in attackersWithMustBeBlockedStatic. cardDef
            // may be null for tokens/copies without a registered definition — the granted forms
            // below still apply.
            val attackerController = state.projectedState.getController(attackerId)
            val staticLimit = cardDef?.staticAbilities
                ?.mapNotNull { ability ->
                    val unwrapped = if (ability is ConditionalStaticAbility) ability.ability else ability
                    if (unwrapped !is CantBeBlockedByMoreThan) return@mapNotNull null
                    if (unwrapped.filter.scope !is com.wingedsheep.sdk.scripting.filters.unified.Scope.Self) {
                        return@mapNotNull null
                    }
                    if (ability is ConditionalStaticAbility) {
                        if (attackerController == null) return@mapNotNull null
                        if (!conditionEvaluator.evaluate(
                                state,
                                ability.condition,
                                EffectContext(sourceId = attackerId, controllerId = attackerController)
                            )
                        ) return@mapNotNull null
                    }
                    unwrapped.maxBlockers
                }
                ?.minOrNull()
            // Granted static-ability form: e.g. Full Steam Ahead grants CantBeBlockedByMoreThan(1)
            // until end of turn via grantedStaticAbilities.
            val grantedLimit = state.grantedStaticAbilities
                .filter { it.entityId == attackerId }
                .map { it.ability }
                .filterIsInstance<CantBeBlockedByMoreThan>()
                .filter { it.filter.scope is com.wingedsheep.sdk.scripting.filters.unified.Scope.Self }
                .minOfOrNull { it.maxBlockers }
            // Granted (floating) flag form (CR 509.1b): a temporary "can't be blocked by more than one
            // creature" via Effects.GrantKeyword(AbilityFlag.CANT_BE_BLOCKED_BY_MORE_THAN_ONE) caps at 1.
            val flagLimit = if (
                state.projectedState.hasKeyword(
                    attackerId,
                    com.wingedsheep.sdk.core.AbilityFlag.CANT_BE_BLOCKED_BY_MORE_THAN_ONE
                )
            ) 1 else null
            val limit = listOfNotNull(staticLimit, grantedLimit, flagLimit).minOrNull() ?: continue

            if (count > limit) {
                val countText = if (limit == 1) "more than one creature" else "more than $limit creatures"
                return "${attackerCard.name} can't be blocked by $countText"
            }
        }
        return null
    }

    /**
     * Validate global blocker-count caps. While any permanent with [BlockerCountLimit] is on the
     * battlefield (e.g. Dueling Grounds), the total number of distinct blocking creatures across
     * all players may not exceed the smallest such cap. Returns an error message when violated.
     */
    private fun validateGlobalBlockerCount(
        state: GameState,
        blockerIds: Set<EntityId>
    ): String? {
        var cap: Int? = null
        var capDescription = ""
        for (permId in state.getBattlefield()) {
            // CR 708.2a: a face-down permanent has no printed abilities. In particular, a hidden
            // opponent permanent must not alter the authoritative declaration result.
            if (state.getEntity(permId)?.has<FaceDownComponent>() == true) continue
            val cardComponent = state.getEntity(permId)?.get<CardComponent>() ?: continue
            val cardDef = cardRegistry.getCard(cardComponent.cardDefinitionId) ?: continue
            for (ability in cardDef.staticAbilities.filterIsInstance<BlockerCountLimit>()) {
                if (cap == null || ability.maxBlockers < cap) {
                    cap = ability.maxBlockers
                    capDescription = ability.description
                }
            }
        }
        if (cap != null && blockerIds.size > cap) {
            return capDescription
        }
        return null
    }

    /**
     * Validate "can't block unless [X] also blocks" restrictions ([CantBlockUnlessCoBlocker], CR
     * 509.1b). The blocking sibling of [com.wingedsheep.engine.mechanics.combat.AttackPhaseManager]'s
     * co-attacker check.
     *
     * For each proposed blocker carrying the restriction, at least one *other* blocker in the same
     * declaration must match the restriction's filter (evaluated with projected state so
     * color/type-changing effects are honored). The co-blocker need not block the same attacker —
     * it just has to be declared as a blocker this combat. Self never counts as its own co-blocker.
     *
     * Restrictions are read from both the card definition (printed) and grantedStaticAbilities, so
     * the form arrives on a token without a CardDefinition (Toby's Beast token — "This token can't
     * attack or block alone").
     */
    private fun validateCoBlockerRequirements(
        state: GameState,
        projected: ProjectedState,
        blockerIds: Set<EntityId>
    ): String? {
        for (blockerId in blockerIds) {
            val cardComponent = state.getEntity(blockerId)?.get<CardComponent>() ?: continue
            if (state.getEntity(blockerId)?.has<FaceDownComponent>() == true) continue
            val printed = cardRegistry.getCard(cardComponent.cardDefinitionId)
                ?.staticAbilities.orEmpty()
            val granted = state.grantedStaticAbilities
                .filter { it.entityId == blockerId }
                .map { it.ability }
            val restrictions = (printed + granted)
                .filterIsInstance<CantBlockUnlessCoBlocker>()
                .filter { it.filter.scope is Scope.Self }
            for (restriction in restrictions) {
                val context = PredicateContext(controllerId = projected.getController(blockerId) ?: blockerId)
                val satisfied = blockerIds.any { otherId ->
                    otherId != blockerId &&
                        predicateEvaluator.matches(state, projected, otherId, restriction.coBlockerFilter, context)
                }
                if (!satisfied) {
                    return "${cardComponent.name} ${restriction.description}"
                }
            }
        }
        return null
    }

    // =========================================================================
    // Must Be Blocked Requirements
    // =========================================================================

    /**
     * Validate "must be blocked" requirements.
     * Handles both "must be blocked by all" (Lure) and "must be blocked if able" (Gaea's Protector).
     */
    private fun validateMustBeBlockedRequirements(
        state: GameState,
        blockingPlayer: EntityId,
        blockers: Map<EntityId, List<EntityId>>
    ): String? {
        val projected = state.projectedState
        val potentialBlockers = findPotentialBlockers(state, blockingPlayer)

        // Build reverse map: attacker → set of blockers assigned to it
        val attackerToBlockers = mutableMapOf<EntityId, MutableSet<EntityId>>()
        for ((blockerId, attackerIds) in blockers) {
            for (attackerId in attackerIds) {
                attackerToBlockers.getOrPut(attackerId) { mutableSetOf() }.add(blockerId)
            }
        }

        // 1. "Must be blocked by all" (Lure/Taunting Elf): every blocker that CAN block it MUST block it
        val mustBeBlockedByAllAttackers = findMustBeBlockedAttackers(state)
        if (mustBeBlockedByAllAttackers.isNotEmpty()) {
            val blockerToAttackers = blockers.mapValues { it.value.toSet() }

            for (blockerId in potentialBlockers) {
                val canBlockThese = mustBeBlockedByAllAttackers.filter { attackerId ->
                    canCreatureBlockAttacker(state, blockerId, attackerId, blockingPlayer, projected)
                }

                if (canBlockThese.isEmpty()) {
                    continue
                }

                val actuallyBlocking = blockerToAttackers[blockerId] ?: emptySet()
                val blockingMustBeBlocked = actuallyBlocking.intersect(mustBeBlockedByAllAttackers.toSet())

                if (blockingMustBeBlocked.isEmpty()) {
                    val blockerCard = state.getEntity(blockerId)?.get<CardComponent>()
                    val blockerName = blockerCard?.name ?: "Creature"

                    val attackerNames = canBlockThese.mapNotNull { attackerId ->
                        state.getEntity(attackerId)?.get<CardComponent>()?.name
                    }

                    return if (canBlockThese.size == 1) {
                        "$blockerName must block ${attackerNames.first()}"
                    } else {
                        "$blockerName must block one of: ${attackerNames.joinToString(", ")}"
                    }
                }
            }
        }

        // 2. "Must be blocked if able" (Gaea's Protector): at least one creature must block it.
        // Rule 509.1c: the declaration is illegal if the number of requirements being obeyed is
        // fewer than the maximum number that could be obeyed. That maximum is a maximum bipartite
        // matching between the must-be-blocked attackers and the blockers hypothetically free to
        // cover them: a provoke-pinned blocker is only free for its pinned attacker, and a blocker
        // that can block a Lure-style attacker is claimed by that requirement (section 1 forces it
        // there). Per-pair blocking restrictions go through canCreatureBlockAttacker; declaration-
        // wide restrictions (e.g. can't-block-alone) are not modelled, so the computed maximum can
        // only over-count in those corners — never rejecting more than 509.1c would.
        val mustBeBlockedIfAbleAttackers = findMustBeBlockedIfAbleAttackers(state)
        if (mustBeBlockedIfAbleAttackers.isNotEmpty()) {
            val provokePinnedAttackers = state.floatingEffects
                .filter { it.effect.modification is SerializableModification.MustBlockSpecificAttacker }
                .flatMap { floatingEffect ->
                    val modification =
                        floatingEffect.effect.modification as SerializableModification.MustBlockSpecificAttacker
                    floatingEffect.effect.affectedEntities.map { it to modification.attackerId }
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }
            val lureClaimedBlockers = potentialBlockers.filter { blockerId ->
                mustBeBlockedByAllAttackers.any { attackerId ->
                    canCreatureBlockAttacker(state, blockerId, attackerId, blockingPlayer, projected)
                }
            }.toSet()

            fun canHypotheticallyBlock(blockerId: EntityId, attackerId: EntityId): Boolean {
                if (blockerId in lureClaimedBlockers) return false
                provokePinnedAttackers[blockerId]?.let { pins -> if (attackerId !in pins) return false }
                return canCreatureBlockAttacker(state, blockerId, attackerId, blockingPlayer, projected)
            }

            // Maximum bipartite matching (attackers ↔ hypothetically-free blockers): its size is
            // the most requirements that could be simultaneously obeyed. Shared Kuhn's routine.
            val matchedAttackerOfBlocker = com.wingedsheep.engine.mechanics.BipartiteMatching
                .maximumMatching(mustBeBlockedIfAbleAttackers, potentialBlockers) { attackerId, blockerId ->
                    canHypotheticallyBlock(blockerId, attackerId)
                }
            val maxSatisfiable = matchedAttackerOfBlocker.size
            val satisfied = mustBeBlockedIfAbleAttackers.count { !attackerToBlockers[it].isNullOrEmpty() }

            if (satisfied < maxSatisfiable) {
                val matchedAttackers = matchedAttackerOfBlocker.values.toSet()
                val culpritId = mustBeBlockedIfAbleAttackers.first {
                    it in matchedAttackers && attackerToBlockers[it].isNullOrEmpty()
                }
                val attackerName = state.getEntity(culpritId)?.get<CardComponent>()?.name ?: "Creature"
                return "$attackerName must be blocked if able"
            }
        }

        return null
    }

    /**
     * Validate provoke "must block specific attacker" requirements.
     */
    private fun validateProvokeRequirements(
        state: GameState,
        blockingPlayer: EntityId,
        blockers: Map<EntityId, List<EntityId>>
    ): String? {
        val projected = state.projectedState

        val provokeConstraints = state.floatingEffects
            .filter { it.effect.modification is SerializableModification.MustBlockSpecificAttacker }
            .flatMap { floatingEffect ->
                val modification = floatingEffect.effect.modification as SerializableModification.MustBlockSpecificAttacker
                floatingEffect.effect.affectedEntities.map { blockerId ->
                    blockerId to modification.attackerId
                }
            }

        for ((blockerId, attackerId) in provokeConstraints) {
            val controller = projected.getController(blockerId)
            if (controller != blockingPlayer) continue

            val blockerContainer = state.getEntity(blockerId) ?: continue
            if (blockerId !in state.getBattlefield()) continue
            if (blockerContainer.has<TappedComponent>()) continue

            val attackerContainer = state.getEntity(attackerId) ?: continue
            if (!attackerContainer.has<AttackingComponent>()) continue

            if (!canCreatureBlockAttacker(state, blockerId, attackerId, blockingPlayer, projected)) continue

            val actuallyBlocking = blockers[blockerId] ?: emptyList()
            if (attackerId !in actuallyBlocking) {
                val blockerName = blockerContainer.get<CardComponent>()?.name ?: "Creature"
                val attackerName = attackerContainer.get<CardComponent>()?.name ?: "creature"
                return "$blockerName must block $attackerName (provoke)"
            }
        }

        return null
    }

    /**
     * Validate projected "must block" requirements (e.g., from Grand Melee).
     */
    private fun validateProjectedMustBlockRequirements(
        state: GameState,
        blockingPlayer: EntityId,
        blockers: Map<EntityId, List<EntityId>>
    ): String? {
        val projected = state.projectedState
        val potentialBlockers = findPotentialBlockers(state, blockingPlayer)

        for (blockerId in potentialBlockers) {
            if (!projected.mustBlock(blockerId)) continue

            val attackers = state.findEntitiesWith<AttackingComponent>().map { it.first }
            val canBlockAny = attackers.any { attackerId ->
                canCreatureBlockAttacker(state, blockerId, attackerId, blockingPlayer, projected)
            }

            if (!canBlockAny) continue

            if (blockerId !in blockers.keys) {
                val cardName = state.getEntity(blockerId)?.get<CardComponent>()?.name ?: "Creature"
                return "$cardName must block this combat if able"
            }
        }

        return null
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Find all attackers that have "must be blocked by all" requirement active (Lure effects).
     */
    private fun findMustBeBlockedAttackers(state: GameState): List<EntityId> {
        val attackers = state.findEntitiesWith<AttackingComponent>().map { it.first }.toSet()

        val fromFloating = state.floatingEffects
            .filter { floatingEffect ->
                floatingEffect.effect.modification is SerializableModification.MustBeBlockedByAll
            }
            .flatMap { floatingEffect ->
                floatingEffect.effect.affectedEntities.filter { it in attackers }
            }
        return (fromFloating + attackersWithMustBeBlockedStatic(state, allCreatures = true)).distinct()
    }

    /**
     * Attackers that carry a [MustBeBlocked] static ability (matching [allCreatures]), including the
     * conditional form (e.g. Frodo Baggins: gated on `SourceIsRingBearer`). The gating condition is
     * evaluated with the attacker as the source.
     */
    private fun attackersWithMustBeBlockedStatic(state: GameState, allCreatures: Boolean): List<EntityId> {
        val attackers = state.findEntitiesWith<AttackingComponent>().map { it.first }
        if (attackers.isEmpty()) return emptyList()
        val projected = state.projectedState
        val attackerSet = attackers.toSet()
        val result = mutableListOf<EntityId>()

        // (a) An attacker's own source-scoped MustBeBlocked static (filter == null), including the
        // conditional form (Frodo Baggins, gated on SourceIsRingBearer).
        for (attackerId in attackers) {
            // A face-down attacker has only its face-down characteristics; its hidden printed
            // MustBeBlocked ability cannot contribute to the defender's public domain.
            if (state.getEntity(attackerId)?.has<FaceDownComponent>() == true) continue
            val cardName = state.getEntity(attackerId)?.get<CardComponent>()?.cardDefinitionId ?: continue
            val statics = cardRegistry.getCard(cardName)?.staticAbilities.orEmpty()
            for (ability in statics) {
                val unwrapped = if (ability is ConditionalStaticAbility) ability.ability else ability
                if (unwrapped !is MustBeBlocked || unwrapped.filter != null || unwrapped.allCreatures != allCreatures) {
                    continue
                }
                val active = if (ability is ConditionalStaticAbility) {
                    val controller = projected.getController(attackerId) ?: continue
                    conditionEvaluator.evaluate(
                        state,
                        ability.condition,
                        EffectContext(sourceId = attackerId, controllerId = controller)
                    )
                } else true
                if (active) result.add(attackerId)
            }
        }

        // (b) A battlefield permanent projecting MustBeBlocked onto a *different* creature via a
        // filter - e.g. an Equipment granting "equipped creature ... must be blocked if able"
        // (The Masamune, filter = GroupFilter.attachedCreature()). The filter is resolved relative
        // to the permanent carrying the static.
        for (sourceId in state.getBattlefield()) {
            val container = state.getEntity(sourceId) ?: continue
            if (container.has<FaceDownComponent>()) continue
            val cardName = container.get<CardComponent>()?.cardDefinitionId ?: continue
            val statics = cardRegistry.getCard(cardName)?.staticAbilities.orEmpty()
            for (ability in statics) {
                val unwrapped = if (ability is ConditionalStaticAbility) ability.ability else ability
                if (unwrapped !is MustBeBlocked || unwrapped.allCreatures != allCreatures) continue
                val filter = unwrapped.filter ?: continue
                val controller = projected.getController(sourceId) ?: continue
                if (ability is ConditionalStaticAbility &&
                    !conditionEvaluator.evaluate(
                        state, ability.condition, EffectContext(sourceId = sourceId, controllerId = controller)
                    )
                ) continue
                result.addAll(
                    resolveFilteredMustBeBlockedAttackers(state, projected, sourceId, controller, filter, attackerSet)
                )
            }
        }

        return result.toList()
    }

    /**
     * Resolve which declared attackers a filtered [MustBeBlocked] static (carried by [sourceId])
     * applies to. Source-relative scopes resolve against [sourceId]: `AttachedTo` -> the creature it
     * is attached to (equipped creature), `Self` -> the source, `Specific` -> the bound entity;
     * `Battlefield` matches every attacker against the base filter. Only attackers pass, and each
     * must also satisfy the base filter (evaluated with the static's source as context).
     */
    private fun resolveFilteredMustBeBlockedAttackers(
        state: GameState,
        projected: ProjectedState,
        sourceId: EntityId,
        controllerId: EntityId,
        filter: GroupFilter,
        attackerSet: Set<EntityId>,
    ): List<EntityId> {
        val candidates: List<EntityId> = when (val scope = filter.scope) {
            is Scope.AttachedTo -> listOfNotNull(state.getEntity(sourceId)?.get<AttachedToComponent>()?.targetId)
            is Scope.Self -> listOf(sourceId)
            is Scope.SoulbondPair ->
                com.wingedsheep.engine.mechanics.SoulbondPairing.pairOf(state, sourceId).toList()
            is Scope.Specific -> listOf(scope.entityId)
            is Scope.Battlefield -> attackerSet.toList()
        }
        return candidates.filter { id ->
            id in attackerSet &&
                predicateEvaluator.matches(
                    state, projected, id, filter.baseFilter,
                    PredicateContext(sourceId = sourceId, controllerId = controllerId)
                )
        }
    }

    /**
     * Find all attackers that have "must be blocked if able" requirement active.
     * These only require at least one blocker, not all.
     */
    private fun findMustBeBlockedIfAbleAttackers(state: GameState): List<EntityId> {
        val attackers = state.findEntitiesWith<AttackingComponent>().map { it.first }.toSet()

        val fromFloating = state.floatingEffects
            .filter { floatingEffect ->
                floatingEffect.effect.modification is SerializableModification.MustBeBlockedIfAble
            }
            .flatMap { floatingEffect ->
                floatingEffect.effect.affectedEntities.filter { it in attackers }
            }
        return (fromFloating + attackersWithMustBeBlockedStatic(state, allCreatures = false)).distinct()
    }

    /**
     * Find all potential blockers (untapped creatures controlled by the blocking player).
     */
    private fun findPotentialBlockers(state: GameState, blockingPlayer: EntityId): List<EntityId> {
        val projected = state.projectedState
        return state.getBattlefield()
            .filter { entityId ->
                val container = state.getEntity(entityId) ?: return@filter false
                container.get<CardComponent>() ?: return@filter false
                val controller = projected.getController(entityId)

                projected.isCreature(entityId) &&
                    controller == blockingPlayer &&
                    !container.has<TappedComponent>()
            }
    }

    // =========================================================================
    // CantBlockUnless
    // =========================================================================

    /**
     * Validate CantBlockUnless restrictions for a blocker.
     */
    private fun validateCantBlockUnless(
        state: GameState,
        blockerId: EntityId,
        blockingPlayer: EntityId,
        projected: ProjectedState
    ): String? {
        val container = state.getEntity(blockerId) ?: return null
        if (container.has<FaceDownComponent>()) return null
        val cardComponent = container.get<CardComponent>() ?: return null
        val cardDef = cardRegistry.getCard(cardComponent.cardDefinitionId) ?: return null

        val restriction = cardDef.staticAbilities
            .filterIsInstance<CantBlockUnless>()
            .firstOrNull { it.filter.scope is com.wingedsheep.sdk.scripting.filters.unified.Scope.Self } ?: return null

        val attackers = state.entities.filter { (_, c) -> c.has<AttackingComponent>() }
        if (attackers.isEmpty()) return null

        val anyAttacker = attackers.keys.first()
        val attackingPlayer = projected.getController(anyAttacker) ?: return null

        val effectContext = EffectContext(
            sourceId = blockerId,
            controllerId = blockingPlayer,
        )
        if (!conditionEvaluator.evaluate(state, restriction.condition, effectContext)) {
            return "${cardComponent.name} ${restriction.description}"
        }

        return null
    }

    /**
     * Check if a creature has a CantBlockUnless restriction.
     */
    private fun hasCantBlockUnlessRestriction(
        state: GameState,
        blockerId: EntityId,
        blockingPlayer: EntityId,
        projected: ProjectedState
    ): Boolean {
        val container = state.getEntity(blockerId) ?: return false
        if (container.has<FaceDownComponent>()) return false
        val cardComponent = container.get<CardComponent>() ?: return false
        val cardDef = cardRegistry.getCard(cardComponent.cardDefinitionId) ?: return false

        val restriction = cardDef.staticAbilities
            .filterIsInstance<CantBlockUnless>()
            .firstOrNull { it.filter.scope is com.wingedsheep.sdk.scripting.filters.unified.Scope.Self } ?: return false

        val attackers = state.entities.filter { (_, c) -> c.has<AttackingComponent>() }
        if (attackers.isEmpty()) return false

        val anyAttacker = attackers.keys.first()
        val attackingPlayer = projected.getController(anyAttacker) ?: return false

        val effectContext = EffectContext(
            sourceId = blockerId,
            controllerId = blockingPlayer,
        )
        return !conditionEvaluator.evaluate(state, restriction.condition, effectContext)
    }

    // =========================================================================
    // Block Taxes
    // =========================================================================

    private fun pauseForBlockTaxConfirmation(
        state: GameState,
        blockingPlayer: EntityId,
        blockers: Map<EntityId, List<EntityId>>,
        totalTax: Int,
    ): ExecutionResult {
        val manaCost = com.wingedsheep.sdk.core.ManaCost(
            List(totalTax) { com.wingedsheep.sdk.core.ManaSymbol.generic(1) }
        )
        val manaSolver = com.wingedsheep.engine.mechanics.mana.ManaSolver(cardRegistry)
        val sources = manaSolver.findAvailableManaSources(state, blockingPlayer)
        val sourceOptions = sources.map { source ->
            com.wingedsheep.engine.core.ManaSourceOption(
                entityId = source.entityId,
                name = source.name,
                producesColors = source.producesColors,
                producesColorless = source.producesColorless,
                requiresSacrifice = source.requiresSacrifice,
                requiresTappingAnotherPermanent = source.tapPermanentsSubCost != null,
                manaAbilityId = source.manaAbilityFor(source.producesColors.firstOrNull())?.id,
            )
        }
        val solution = manaSolver.solve(state, blockingPlayer, manaCost)
        val autoPaySuggestion = solution?.sources?.map { it.entityId } ?: emptyList()

        val decisionId = java.util.UUID.randomUUID().toString()
        val decision = com.wingedsheep.engine.core.SelectManaSourcesDecision(
            id = decisionId,
            playerId = blockingPlayer,
            prompt = "Pay {$totalTax} to block with the declared creatures",
            context = com.wingedsheep.engine.core.DecisionContext(
                sourceId = null,
                sourceName = "Block tax",
                phase = com.wingedsheep.engine.core.DecisionPhase.COMBAT,
            ),
            availableSources = sourceOptions,
            requiredCost = manaCost.toString(),
            autoPaySuggestion = autoPaySuggestion,
            canDecline = true,
        )
        val continuation = com.wingedsheep.engine.core.BlockTaxManaSelectionContinuation(
            decisionId = decisionId,
            blockingPlayer = blockingPlayer,
            blockers = blockers,
            manaCost = manaCost,
            availableSources = sourceOptions,
            autoPaySuggestion = autoPaySuggestion,
        )
        return ExecutionResult.paused(
            state.withPendingDecision(decision).pushContinuation(continuation),
            decision,
        )
    }
}
