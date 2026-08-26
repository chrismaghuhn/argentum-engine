package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.mechanics.mana.ManaPool
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.combat.AttackedThisCombatComponent
import com.wingedsheep.engine.state.components.combat.AttackersDeclaredThisCombatComponent
import com.wingedsheep.engine.state.components.combat.GoadedComponent
import com.wingedsheep.engine.state.components.combat.MustAttackPlayerComponent
import com.wingedsheep.engine.state.components.combat.MustAttackThisTurnComponent
import com.wingedsheep.engine.state.components.combat.PlayerAttackedThisTurnComponent
import com.wingedsheep.engine.state.components.combat.PlayerAttackersThisTurnComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.mechanics.combat.rules.AttackCheckContext
import com.wingedsheep.engine.mechanics.combat.rules.AttackDefenderRule
import com.wingedsheep.engine.mechanics.combat.rules.AttackRestrictionRule
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaSymbol
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AttackerCountLimit
import com.wingedsheep.sdk.scripting.CantAttackUnlessCoAttacker
import com.wingedsheep.sdk.scripting.MustAttack
import com.wingedsheep.sdk.scripting.filters.unified.Scope
import com.wingedsheep.engine.mechanics.battle.Battles
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.legalactions.AttackDeclarationDomainUnsupportedReason
import com.wingedsheep.engine.legalactions.AttackDeclarationDomainValidator
import com.wingedsheep.engine.legalactions.AttackDeclarationRejection
import com.wingedsheep.engine.legalactions.RulesAttackBandConstraints
import com.wingedsheep.engine.legalactions.RulesAttackDeclarationDomain
import com.wingedsheep.engine.legalactions.RulesAttackDeclarationDomainResult
import com.wingedsheep.engine.legalactions.RulesCoAttackerRequirement

/**
 * Handles the declare attackers step of combat.
 *
 * Responsibilities:
 * - Validating individual attackers via [AttackRestrictionRule] and [AttackDefenderRule]
 * - Must-attack requirements (Taunt, Walking Desecration, Grand Melee)
 * - Attack taxes (Ghostly Prison, Windborn Muse, Whipgrass Entangler)
 * - Applying attacker components and tapping
 */
internal class AttackPhaseManager(
    private val cardRegistry: CardRegistry,
    private val attackRestrictionRules: List<AttackRestrictionRule>,
    private val attackDefenderRules: List<AttackDefenderRule>,
    private val manaAbilitySideEffectExecutor: com.wingedsheep.engine.mechanics.mana.ManaAbilitySideEffectExecutor,
) {

    private val predicateEvaluator = PredicateEvaluator()
    private val conditionEvaluator = com.wingedsheep.engine.handlers.ConditionEvaluator()

    /**
     * Validate and declare attackers.
     *
     * @param attackers Map of attacker entity ID to defender (player or planeswalker)
     * @param bands Optional band groupings (CR 702.22). Each set is one band of attackers.
     */
    fun declareAttackers(
        state: GameState,
        attackingPlayer: EntityId,
        attackers: Map<EntityId, EntityId>,
        bands: List<Set<EntityId>> = emptyList()
    ): ExecutionResult {
        val declaration = DeclareAttackers(attackingPlayer, attackers, bands)
        val validation = validateDeclarationBeforeTax(state, declaration)
        if (validation != null) {
            return ExecutionResult.error(state, validation)
        }

        val projected = state.projectedState

        // Calculate (but don't pay) the attack tax. If non-zero, pause for the attacking
        // player to confirm before we tap any of their mana — otherwise auto-tapping the
        // pool would steal sources they were saving for instants/post-combat plays.
        val totalTax = calculateTotalAttackTax(state, attackers, projected)
        if (totalTax > 0) {
            return pauseForAttackTaxConfirmation(state, attackingPlayer, attackers, totalTax, bands)
        }

        return commitAttackDeclaration(state, attackingPlayer, attackers, projected, taxEvents = emptyList(), bands = bands)
    }

    /**
     * Validate every non-monetary attacker-declaration requirement in the same order used by
     * execution. Attack tax is intentionally outside this seam: it is a later explicit payment
     * decision after the declaration itself has been accepted.
     */
    internal fun validateDeclarationBeforeTax(
        state: GameState,
        declaration: DeclareAttackers,
    ): String? {
        val attackingPlayer = declaration.playerId
        val attackers = declaration.attackers
        val projected = state.projectedState
        val attackingTeam = state.teamOf(attackingPlayer)
        val opponents = state.activePlayers.filter { it !in attackingTeam }

        val bandValidation = validateBands(state, attackers, declaration.bands, projected)
        if (bandValidation != null) return bandValidation

        if (declaration.bands.isNotEmpty() &&
            CombatAttackerOrder.canonicalizeBands(state, declaration.bands) == null
        ) {
            return "Attack bands cannot be deterministically ordered"
        }

        for ((attackerId, defenderId) in attackers) {
            val attackerValidation = validateAttacker(state, attackingPlayer, attackerId)
            if (attackerValidation != null) return attackerValidation

            val defenderValidation = validateAttackDefender(
                state = state,
                projected = projected,
                attackingPlayer = attackingPlayer,
                attackingTeam = attackingTeam,
                opponents = opponents,
                attackerId = attackerId,
                defenderId = defenderId,
            )
            if (defenderValidation != null) return defenderValidation
        }

        validateCoAttackerRequirements(state, projected, attackers.keys)?.let { return it }
        validateGlobalAttackerCount(state, attackers.keys)?.let { return it }
        validateMustAttackRequirements(state, attackingPlayer, attackers)?.let { return it }
        validateMustAttackThisTurnRequirements(state, attackingPlayer, attackers)?.let { return it }
        validateProjectedMustAttackRequirements(state, attackingPlayer, attackers)?.let { return it }
        validateGoadedRequirements(state, attackingPlayer, attackers, projected, opponents)?.let { return it }

        return null
    }

    /**
     * Apply the post-tax commitment for a declared attack: stamp [AttackingComponent],
     * tap attackers (unless vigilance), mark tracking components, and emit the
     * [AttackersDeclaredEvent]. Callable both from the synchronous (no-tax) path in
     * [declareAttackers] and from [com.wingedsheep.engine.handlers.continuations.CombatTaxContinuationResumer]
     * after the player confirms and the tax is paid.
     *
     * @param taxEvents Events from the tax payment (auto-tap [TappedEvent]s, etc.) to emit
     *   before the [AttackersDeclaredEvent].
     * @param bands Validated band groupings (CR 702.22); each attacker in a band is stamped
     *   with a shared [AttackingComponent.bandId].
     */
    internal fun commitAttackDeclaration(
        state: GameState,
        attackingPlayer: EntityId,
        attackers: Map<EntityId, EntityId>,
        projected: ProjectedState,
        taxEvents: List<com.wingedsheep.engine.core.GameEvent>,
        bands: List<Set<EntityId>> = emptyList()
    ): ExecutionResult {
        // Assign each band a shared id, then map every banded attacker to it (CR 702.22). The
        // declaration was already validated before any mutation; repeat only the pure ordering
        // computation here because the tax continuation calls this method later with the paid
        // state. Never use EntityId/UUID or collection iteration as the band identity authority.
        val canonicalBands = CombatAttackerOrder.canonicalizeBands(state, bands)
            ?: return ExecutionResult.error(state, "Attack bands cannot be deterministically ordered")
        val firstBandOrdinal = if (canonicalBands.isEmpty()) {
            0L
        } else {
            CombatAttackerOrder.nextBandOrdinal(state)
                ?: return ExecutionResult.error(state, "Attack band ordinal space is exhausted")
        }
        val bandIdByAttacker: Map<EntityId, String> = canonicalBands
            .flatMapIndexed { index, band ->
                band.map { attackerId ->
                    attackerId to CombatAttackerOrder.bandId(firstBandOrdinal + index.toLong())
                }
            }
            .toMap()

        var newState = state
        val tapEvents = mutableListOf<TappedEvent>()
        for ((attackerId, defenderId) in attackers) {
            val hasVigilance = projected.hasKeyword(attackerId, Keyword.VIGILANCE)
            newState = newState.updateEntity(attackerId) { container ->
                container.with(
                    CombatDefenders.attackingComponentFor(
                        state = state,
                        projected = projected,
                        defenderId = defenderId,
                        bandId = bandIdByAttacker[attackerId],
                    )
                )
                    .with(AttackedThisCombatComponent)
            }
            // Non-vigilance attackers tap as a turn-based action; route through the tap atom so the
            // TappedEvent fires "becomes tapped" triggers (it was open-coded and once dropped here).
            if (!hasVigilance) {
                val (tappedState, event) = tap(newState, attackerId)
                newState = tappedState
                event?.let(tapEvents::add)
            }
        }

        // Resolve each attacker's defending player (CR 508.5/508.6): the defender entity is
        // either a player directly, or a planeswalker/battle whose controller is the defending
        // player. Record the set so "did player X attack player Y this turn?" can be answered
        // after combat (Faramir, Prince of Ithilien).
        val defendingPlayers: Set<EntityId> =
            attackers.values.mapNotNullTo(mutableSetOf()) { CombatDefenders.defendingPlayerOf(state, it) }

        // Attackers seen earlier this turn (across prior combat phases) — read before the per-turn
        // set is unioned below so we can flag which of these attackers are attacking for the *first
        // time this turn*. Backs AttackPredicate.FirstTimeEachTurn; this fact can't be derived after
        // the union (the just-declared attacker would already be a member).
        val previousAttackersThisTurn = state.getEntity(attackingPlayer)
            ?.get<PlayerAttackersThisTurnComponent>()?.attackerIds ?: emptySet()
        val firstTimeAttackers = attackers.keys - previousAttackersThisTurn

        // Attackers whose declared defender is a *player* (CR 508.1), not a planeswalker or
        // battle. Backs AttackPredicate.DefenderIsPlayer ("attacks an opponent"); the defender
        // kind is fixed at declaration and this aggregate fact is stamped here. The complete
        // attacker-to-target snapshot is emitted below for per-player trigger grouping.
        // `defenderId in activePlayers` is the current player-identity domain; turnOrder retains
        // departed seats for historical replay/state inspection.
        val attackersAgainstPlayer = attackers.filterValues { it in state.activePlayers }.keys

        newState = newState.updateEntity(attackingPlayer) { container ->
            var updated = container.with(AttackersDeclaredThisCombatComponent)
            if (attackers.isNotEmpty()) {
                updated = updated.with(PlayerAttackedThisTurnComponent)
                val previous = container.get<PlayerAttackersThisTurnComponent>()?.attackerIds ?: emptySet()
                updated = updated.with(PlayerAttackersThisTurnComponent(previous + attackers.keys))
                if (defendingPlayers.isNotEmpty()) {
                    val previousDefenders = container
                        .get<com.wingedsheep.engine.state.components.combat.PlayerAttackedPlayersThisTurnComponent>()
                        ?.defendingPlayerIds ?: emptySet()
                    updated = updated.with(
                        com.wingedsheep.engine.state.components.combat.PlayerAttackedPlayersThisTurnComponent(
                            previousDefenders + defendingPlayers
                        )
                    )
                }
            }
            updated
        }

        // Event collection order is part of replay/trigger determinism. Do not leak the
        // iteration order of the caller's declaration map into any serialized attack-event field.
        val canonicalAttackerIds = attackers.keys.sortedBy { it.value }
        val canonicalFirstTimeAttackers = linkedSetOf<EntityId>().apply {
            canonicalAttackerIds.filterTo(this) { it in firstTimeAttackers }
        }
        val canonicalAttackersAgainstPlayer = linkedSetOf<EntityId>().apply {
            canonicalAttackerIds.filterTo(this) { it in attackersAgainstPlayer }
        }
        val attackerNames = canonicalAttackerIds.map {
            state.getEntity(it)?.get<CardComponent>()?.name ?: "Creature"
        }
        val declaredAttacks = attackers.entries
            .sortedWith(compareBy({ it.key.value }, { it.value.value }))
            .map { (attackerId, defenderId) ->
                com.wingedsheep.engine.core.DeclaredAttack(
                    attackerId = attackerId,
                    defenderId = defenderId,
                    defendingPlayerId = CombatDefenders.defendingPlayerOf(state, defenderId),
                )
            }
        return ExecutionResult.success(
            newState,
            taxEvents + tapEvents + listOf(
                AttackersDeclaredEvent(
                    attackerNames = attackerNames,
                    attackingPlayerId = attackingPlayer,
                    attackers = canonicalAttackerIds,
                    firstTimeAttackers = canonicalFirstTimeAttackers,
                    attackersAgainstPlayer = canonicalAttackersAgainstPlayer,
                    declaredAttacks = declaredAttacks,
                )
            )
        )
    }

    private fun pauseForAttackTaxConfirmation(
        state: GameState,
        attackingPlayer: EntityId,
        attackers: Map<EntityId, EntityId>,
        totalTax: Int,
        bands: List<Set<EntityId>> = emptyList(),
    ): ExecutionResult {
        val manaCost = com.wingedsheep.sdk.core.ManaCost(
            List(totalTax) { com.wingedsheep.sdk.core.ManaSymbol.generic(1) }
        )
        val manaSolver = com.wingedsheep.engine.mechanics.mana.ManaSolver(cardRegistry)
        val sources = manaSolver.findAvailableManaSources(state, attackingPlayer)
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
        val solution = manaSolver.solve(state, attackingPlayer, manaCost)
        val autoPaySuggestion = solution?.sources?.map { it.entityId } ?: emptyList()

        val decisionId = java.util.UUID.randomUUID().toString()
        val attackerNames = attackers.keys.mapNotNull { state.getEntity(it)?.get<CardComponent>()?.name }
        val attackerListing = when (attackerNames.size) {
            0 -> "your attackers"
            1 -> attackerNames.single()
            else -> attackerNames.dropLast(1).joinToString(", ") + " and " + attackerNames.last()
        }
        val decision = com.wingedsheep.engine.core.SelectManaSourcesDecision(
            id = decisionId,
            playerId = attackingPlayer,
            prompt = "Pay {$totalTax} to attack with $attackerListing",
            context = com.wingedsheep.engine.core.DecisionContext(
                sourceId = null,
                sourceName = "Attack tax",
                phase = com.wingedsheep.engine.core.DecisionPhase.COMBAT,
            ),
            availableSources = sourceOptions,
            requiredCost = manaCost.toString(),
            autoPaySuggestion = autoPaySuggestion,
            canDecline = true,
        )
        val continuation = com.wingedsheep.engine.core.AttackTaxManaSelectionContinuation(
            decisionId = decisionId,
            attackingPlayer = attackingPlayer,
            attackers = attackers,
            manaCost = manaCost,
            availableSources = sourceOptions,
            autoPaySuggestion = autoPaySuggestion,
            bands = bands,
        )
        return ExecutionResult.paused(
            state.withPendingDecision(decision).pushContinuation(continuation),
            decision,
        )
    }

    /**
     * Check if a creature passes all per-creature attack restrictions.
     * Does NOT check per-defender restrictions.
     */
    fun isValidAttacker(state: GameState, attackerId: EntityId, attackingPlayer: EntityId): Boolean {
        val projected = state.projectedState
        val ctx = AttackCheckContext(state, projected, attackerId, attackingPlayer, cardRegistry)
        return attackRestrictionRules.all { it.check(ctx) == null }
    }

    /**
     * Check if a creature is restricted from attacking all opponents by per-defender rules.
     * Returns true if the creature cannot attack any opponent.
     */
    fun isRestrictedFromAllDefenders(state: GameState, attackerId: EntityId, attackingPlayer: EntityId): Boolean {
        val projected = state.projectedState
        val ctx = AttackCheckContext(state, projected, attackerId, attackingPlayer, cardRegistry)
        return attackDefenderRules.any { it.restrictsAllDefenders(ctx) }
    }

    // =========================================================================
    // Private validation
    // =========================================================================

    /**
     * Validate that a creature can attack.
     * Delegates to registered [AttackRestrictionRule] instances.
     */
    private fun validateAttacker(
        state: GameState,
        attackingPlayer: EntityId,
        attackerId: EntityId
    ): String? {
        state.getEntity(attackerId) ?: return "Attacker not found: $attackerId"
        state.getEntity(attackerId)?.get<CardComponent>() ?: return "Not a card: $attackerId"

        val ctx = AttackCheckContext(
            state = state,
            projected = state.projectedState,
            attackerId = attackerId,
            attackingPlayer = attackingPlayer,
            cardRegistry = cardRegistry
        )
        for (rule in attackRestrictionRules) {
            val error = rule.check(ctx)
            if (error != null) return error
        }
        return null
    }

    /** Validate the target kind, battlefield presence, and all Rules-owned defender rules. */
    private fun validateAttackDefender(
        state: GameState,
        projected: ProjectedState,
        attackingPlayer: EntityId,
        attackingTeam: List<EntityId>,
        opponents: List<EntityId>,
        attackerId: EntityId,
        defenderId: EntityId,
    ): String? {
        // An attacker may target an opponent player, a planeswalker controlled by an opponent, or
        // a battle protected by an opponent. A battle is keyed off its protector, never its
        // controller, which is what lets a player attack a Siege they control.
        if (defenderId !in opponents) {
            val isAttackableBattle = projected.isBattle(defenderId) &&
                Battles.canBeAttackedBy(state, defenderId, attackingPlayer, opponents.toSet())
            val isAttackablePlaneswalker = projected.isPlaneswalker(defenderId) &&
                projected.getController(defenderId) !in attackingTeam
            if (!isAttackableBattle && !isAttackablePlaneswalker) {
                return "Invalid attack target: must be an opponent, their planeswalker, or a battle they protect"
            }
            if (defenderId !in state.getBattlefield()) {
                return "Attacked permanent is not on the battlefield"
            }
        }

        val ctx = AttackCheckContext(state, projected, attackerId, attackingPlayer, cardRegistry)
        for (rule in attackDefenderRules) {
            rule.check(ctx, defenderId)?.let { return it }
        }
        return null
    }

    /**
     * Validate "can't attack unless [X] also attacks" restrictions ([CantAttackUnlessCoAttacker]).
     *
     * For each proposed attacker carrying the restriction, at least one *other* attacker in the
     * same declaration must match the restriction's filter (evaluated with projected state so
     * color/type-changing effects are honored). Self never counts as its own co-attacker.
     */
    private fun validateCoAttackerRequirements(
        state: GameState,
        projected: ProjectedState,
        attackerIds: Set<EntityId>
    ): String? {
        for (attackerId in attackerIds) {
            val cardComponent = state.getEntity(attackerId)?.get<CardComponent>() ?: continue
            val resolvedRequirements = resolveConcreteCoAttackerRequirements(
                state = state,
                projected = projected,
                attackingPlayer = state.projectedState.getController(attackerId) ?: attackerId,
                candidateAttackers = attackerIds,
            )[attackerId].orEmpty()
            for ((index, requirement) in resolvedRequirements.withIndex()) {
                if (requirement.anyOf.none { it in attackerIds }) {
                    val restriction = getCoAttackerRestrictions(state, attackerId).getOrNull(index)
                    val description = restriction?.description ?: "can't attack unless a qualifying creature also attacks"
                    return "${cardComponent.name} $description"
                }
            }
        }
        return null
    }

    /**
     * Resolve every self-scoped co-attacker filter into concrete candidate IDs. The public helper
     * is used while publishing the certificate; the private overload lets execution reuse the
     * exact same projected PredicateEvaluator semantics for the submitted set.
     */
    internal fun getConcreteCoAttackerRequirements(
        state: GameState,
        attackingPlayer: EntityId,
    ): Map<EntityId, List<RulesCoAttackerRequirement>> =
        resolveConcreteCoAttackerRequirements(
            state = state,
            projected = state.projectedState,
            attackingPlayer = attackingPlayer,
            candidateAttackers = getAttackDeclarationCandidateAttackers(state, attackingPlayer),
        )

    private fun resolveConcreteCoAttackerRequirements(
        state: GameState,
        projected: ProjectedState,
        attackingPlayer: EntityId,
        candidateAttackers: Collection<EntityId>,
    ): Map<EntityId, List<RulesCoAttackerRequirement>> {
        val candidates = candidateAttackers.toSet()
        return candidates.sortedBy(EntityId::value).associateWith { attackerId ->
            val context = PredicateContext(controllerId = projected.getController(attackerId) ?: attackerId)
            getCoAttackerRestrictions(state, attackerId).map { restriction ->
                RulesCoAttackerRequirement(
                    anyOf = candidates
                        .asSequence()
                        .filter { otherId ->
                            otherId != attackerId &&
                                predicateEvaluator.matches(state, projected, otherId, restriction.coAttackerFilter, context)
                        }
                        .sortedBy(EntityId::value)
                        .toList(),
                )
            }
        }.filterValues { it.isNotEmpty() }
    }

    private fun getCoAttackerRestrictions(
        state: GameState,
        attackerId: EntityId,
    ): List<CantAttackUnlessCoAttacker> {
        val cardComponent = state.getEntity(attackerId)?.get<CardComponent>() ?: return emptyList()
        // Tokens have no CardDefinition, so their restrictions arrive via grantedStaticAbilities.
        // Union both sources so the printed and granted forms share one resolution path.
        val printed = cardRegistry.getCard(cardComponent.cardDefinitionId)
            ?.staticAbilities.orEmpty()
        val granted = state.grantedStaticAbilities
            .filter { it.entityId == attackerId }
            .map { it.ability }
        return (printed + granted)
            .filterIsInstance<CantAttackUnlessCoAttacker>()
            .filter { it.filter.scope is Scope.Self }
    }

    /**
     * Validate global attacker-count caps. While any permanent with [AttackerCountLimit] is on
     * the battlefield (e.g. Dueling Grounds), the total number of declared attackers across all
     * players may not exceed the smallest such cap. Returns an error message when violated.
     */
    private fun validateGlobalAttackerCount(
        state: GameState,
        attackerIds: Set<EntityId>
    ): String? {
        val cap = getGlobalAttackerCapWithDescription(state)
        if (cap != null && attackerIds.size > cap.first) {
            return cap.second
        }
        return null
    }

    /** The smallest active Rules-owned attacker-count cap, if one is present. */
    internal fun getGlobalAttackerCap(state: GameState, _attackingPlayer: EntityId): Int? =
        getGlobalAttackerCapWithDescription(state)?.first

    private fun getGlobalAttackerCapWithDescription(state: GameState): Pair<Int, String>? {
        var cap: Pair<Int, String>? = null
        for (permId in state.getBattlefield()) {
            val cardComponent = state.getEntity(permId)?.get<CardComponent>() ?: continue
            val cardDef = cardRegistry.getCard(cardComponent.cardDefinitionId) ?: continue
            for (ability in cardDef.staticAbilities.filterIsInstance<AttackerCountLimit>()) {
                val currentCap = cap
                if (currentCap == null || ability.maxAttackers < currentCap.first) {
                    cap = ability.maxAttackers to ability.description
                }
            }
        }
        return cap
    }

    /**
     * Validate band declarations per CR 702.22c:
     * - Each band must have at least two creatures.
     * - Each creature in a band must also be one of the declared attackers.
     * - All creatures in a band must attack the same defender.
     * - At most one creature in a band may lack the [Keyword.BANDING] keyword.
     * - A creature may appear in at most one band.
     *
     * Returns an error message if any constraint is violated, or null when valid (including
     * the common no-bands case).
     */
    private fun validateBands(
        state: GameState,
        attackers: Map<EntityId, EntityId>,
        bands: List<Set<EntityId>>,
        projected: ProjectedState,
    ): String? {
        if (bands.isEmpty()) return null

        val seen = mutableSetOf<EntityId>()
        for (band in bands) {
            if (band.size < 2) {
                return "A band must contain at least two creatures"
            }
            var nonBandingCount = 0
            var sharedDefender: EntityId? = null
            for (creatureId in band) {
                if (creatureId in seen) {
                    val name = state.getEntity(creatureId)?.get<CardComponent>()?.name ?: "Creature"
                    return "$name cannot be in more than one band"
                }
                seen += creatureId

                val defenderId = attackers[creatureId]
                    ?: run {
                        val name = state.getEntity(creatureId)?.get<CardComponent>()?.name ?: "Creature"
                        return "$name is in a band but is not declared as an attacker"
                    }

                if (sharedDefender == null) {
                    sharedDefender = defenderId
                } else if (sharedDefender != defenderId) {
                    return "All creatures in a band must attack the same defender"
                }

                if (!projected.hasKeyword(creatureId, Keyword.BANDING)) {
                    nonBandingCount += 1
                    if (nonBandingCount > 1) {
                        return "A band may contain at most one creature without banding"
                    }
                }
            }
        }
        return null
    }

    /**
     * Validate "must attack" requirements (Taunt effect).
     */
    private fun validateMustAttackRequirements(
        state: GameState,
        attackingPlayer: EntityId,
        attackers: Map<EntityId, EntityId>
    ): String? {
        val mustAttack = state.getEntity(attackingPlayer)?.get<MustAttackPlayerComponent>()
            ?: return null

        if (!mustAttack.activeThisTurn) {
            return null
        }

        val requiredDefender = mustAttack.defenderId
        val validAttackers = getValidAttackers(state, attackingPlayer)

        for (attackerId in validAttackers) {
            if (attackerId !in attackers.keys) {
                val cardName = state.getEntity(attackerId)?.get<CardComponent>()?.name ?: "Creature"
                return "$cardName must attack this turn (Taunt)"
            }
        }

        for ((attackerId, defenderId) in attackers) {
            if (defenderId != requiredDefender) {
                val cardName = state.getEntity(attackerId)?.get<CardComponent>()?.name ?: "Creature"
                val defenderName = state.getEntity(requiredDefender)?.get<CardComponent>()?.name ?: "that player"
                return "$cardName must attack $defenderName (Taunt)"
            }
        }

        return null
    }

    /**
     * Validate "must attack this turn" requirements for individual creatures.
     */
    private fun validateMustAttackThisTurnRequirements(
        state: GameState,
        attackingPlayer: EntityId,
        attackers: Map<EntityId, EntityId>
    ): String? {
        val validAttackers = getValidAttackers(state, attackingPlayer)

        for (attackerId in validAttackers) {
            val container = state.getEntity(attackerId) ?: continue
            if (!container.has<MustAttackThisTurnComponent>()) continue

            if (attackerId !in attackers.keys) {
                val cardName = container.get<CardComponent>()?.name ?: "Creature"
                return "$cardName must attack this turn"
            }
        }

        return null
    }

    /**
     * Validate goaded-creature requirements (CR 701.15b–c).
     *
     * Per CR 701.15b a goaded creature has two combat requirements:
     *   1. It attacks each combat if able. We enforce this here by requiring every
     *      valid attacker that carries [GoadedComponent] to appear in [attackers]
     *      — same shape as the must-attack-this-turn check, just keyed off the
     *      component instead.
     *   2. It attacks a player other than each of its goaders if able. CR 701.15b
     *      phrases this requirement in terms of a *player* — not a planeswalker — so
     *      the "if able" lookup considers only opponent players, not their
     *      planeswalkers. CR 701.15c stacks the requirement per goader. If every
     *      opponent player the creature could legally attack is a goader, the
     *      requirement is unsatisfiable, so the creature may attack a goader (and
     *      must attack something, per requirement 1).
     *
     * The chosen defender may itself be a planeswalker; [defenderControllerOf] maps
     * it to its controller so attacking a goader's planeswalker is still caught as
     * "attacking the goader" when an unaffected player was available.
     */
    private fun validateGoadedRequirements(
        state: GameState,
        attackingPlayer: EntityId,
        attackers: Map<EntityId, EntityId>,
        projected: ProjectedState,
        opponents: List<EntityId>
    ): String? {
        val validAttackers = getValidAttackers(state, attackingPlayer)

        for (attackerId in validAttackers) {
            val goaded = state.getEntity(attackerId)?.get<GoadedComponent>() ?: continue
            val cardName = state.getEntity(attackerId)?.get<CardComponent>()?.name ?: "Creature"

            if (attackerId !in attackers.keys) {
                return "$cardName is goaded and must attack this combat if able"
            }

            // Is there a non-goader *player* this creature could legally attack right
            // now (honoring per-defender restrictions)? Per CR 701.15b the "attack a
            // player other than the goader" requirement only ever points at players,
            // so planeswalkers are not part of this alternative pool.
            val ctx = com.wingedsheep.engine.mechanics.combat.rules.AttackCheckContext(
                state, projected, attackerId, attackingPlayer, cardRegistry
            )
            val hasNonGoaderPlayer = opponents.any { playerId ->
                playerId !in goaded.goaderIds &&
                    attackDefenderRules.all { rule -> rule.check(ctx, playerId) == null }
            }
            if (!hasNonGoaderPlayer) continue

            val chosenDefenderId = attackers[attackerId] ?: continue
            val chosenDefenderController = defenderControllerOf(state, projected, chosenDefenderId)
            if (chosenDefenderController in goaded.goaderIds) {
                val goaderName = state.getEntity(chosenDefenderController)?.get<PlayerComponent>()?.name
                    ?: "their goader"
                return "$cardName is goaded and must attack a player other than $goaderName if able"
            }
        }

        return null
    }

    /**
     * The player an attack aimed at [defenderId] is really aimed at: the player themselves, a
     * planeswalker's controller, or — for a battle — its protector rather than its controller
     * (CR 310.9d).
     */
    private fun defenderControllerOf(
        state: GameState,
        projected: ProjectedState,
        defenderId: EntityId
    ): EntityId {
        if (state.getEntity(defenderId)?.has<LifeTotalComponent>() == true) return defenderId
        Battles.protectorOf(state, defenderId)?.let { return it }
        return projected.getController(defenderId) ?: defenderId
    }

    /**
     * Whether [attackerId] is required to attack this combat by a "must attack" static — either the
     * projected one (printed `MustAttack`: Valley Dasher, Grand Melee) or an entity-scoped
     * `MustAttack` granted at runtime and stored in [GameState.grantedStaticAbilities] (Carnage's
     * reanimated target: "attacks each combat if able"). Granted statics never reach projection, so
     * they must be consulted here at the point of use, alongside the projected value.
     */
    private fun mustAttackThisCombat(state: GameState, attackerId: EntityId): Boolean {
        if (state.projectedState.mustAttack(attackerId)) return true
        return state.grantedStaticAbilities.any {
            it.entityId == attackerId && it.ability is MustAttack &&
                it.ability.filter.scope is Scope.Self
        }
    }

    /**
     * Validate projected "must attack" requirements (e.g., from Grand Melee).
     */
    private fun validateProjectedMustAttackRequirements(
        state: GameState,
        attackingPlayer: EntityId,
        attackers: Map<EntityId, EntityId>
    ): String? {
        val validAttackers = getValidAttackers(state, attackingPlayer)

        for (attackerId in validAttackers) {
            if (!mustAttackThisCombat(state, attackerId)) continue

            if (attackerId !in attackers.keys) {
                val cardName = state.getEntity(attackerId)?.get<CardComponent>()?.name ?: "Creature"
                return "$cardName must attack this combat if able"
            }
        }

        return null
    }

    /**
     * Get all creatures that pass attacker-independent restrictions for the public declaration
     * domain. Defender-dependent legality is resolved by [getAttackDeclarationDomain] so a legal
     * Battle-only attacker cannot disappear merely because the legacy all-player/planeswalker
     * helper does not enumerate Battles.
     */
    internal fun getAttackDeclarationCandidateAttackers(
        state: GameState,
        playerId: EntityId,
    ): List<EntityId> {
        val projected = state.projectedState

        return state.getBattlefield().filter { entityId ->
            state.getEntity(entityId)?.get<CardComponent>() ?: return@filter false

            val ctx = AttackCheckContext(state, projected, entityId, playerId, cardRegistry)

            if (attackRestrictionRules.any { it.check(ctx) != null }) return@filter false

            true
        }
    }

    /**
     * Preserve the legacy valid-attacker semantics used by existing MustAttack/Goad checks.
     * This deliberately remains separate from the public declaration candidate universe, whose
     * defender relation is complete over players, planeswalkers, and Battles.
     */
    private fun getValidAttackers(state: GameState, playerId: EntityId): List<EntityId> =
        state.getBattlefield().filter { entityId ->
            isValidAttacker(state, entityId, playerId) &&
                !isRestrictedFromAllDefenders(state, entityId, playerId)
        }

    /**
     * Build the complete Rules-owned certificate used by the DeclareAttackers legal action.
     * Every field is resolved from the same state and Rules predicates that execute a declaration;
     * an unrepresentable shape returns typed unsupported rather than a weakened certificate.
     */
    internal fun getAttackDeclarationDomain(
        state: GameState,
        attackingPlayer: EntityId,
    ): RulesAttackDeclarationDomainResult {
        val projected = state.projectedState
        val attackingTeam = state.teamOf(attackingPlayer)
        val opponents = state.activePlayers.filter { it !in attackingTeam }
        val candidateAttackers = getAttackDeclarationCandidateAttackers(state, attackingPlayer)
        val candidateDefenders = CombatDefenders
            .getAttackDeclarationCertificateCandidateDefenders(state, attackingPlayer)
        val mustAttackPlayer = state.getEntity(attackingPlayer)
            ?.get<MustAttackPlayerComponent>()
            ?.takeIf { it.activeThisTurn }
            ?.defenderId

        val relation = candidateAttackers
            .sortedBy(EntityId::value)
            .associateWith { attackerId ->
                var legalDefenders = candidateDefenders.filter { defenderId ->
                    validateAttackDefender(
                        state = state,
                        projected = projected,
                        attackingPlayer = attackingPlayer,
                        attackingTeam = attackingTeam,
                        opponents = opponents,
                        attackerId = attackerId,
                        defenderId = defenderId,
                    ) == null
                }

                // MustAttackPlayerComponent/Taunt constrains the defender only for Rules shapes
                // that actually name one. Generic MustAttack remains a mandatory-attacker
                // constraint and must not be generalized into a defender choice.
                if (mustAttackPlayer != null) {
                    legalDefenders = legalDefenders.filter { it == mustAttackPlayer }
                }

                val goaded = state.getEntity(attackerId)?.get<GoadedComponent>()
                if (goaded != null) {
                    val context = AttackCheckContext(
                        state,
                        projected,
                        attackerId,
                        attackingPlayer,
                        cardRegistry,
                    )
                    val hasNonGoaderPlayer = opponents.any { playerId ->
                        playerId !in goaded.goaderIds &&
                            attackDefenderRules.all { rule -> rule.check(context, playerId) == null }
                    }
                    if (hasNonGoaderPlayer) {
                        legalDefenders = legalDefenders.filter { defenderId ->
                            defenderControllerOf(state, projected, defenderId) !in goaded.goaderIds
                        }
                    }
                }

                legalDefenders.sortedBy(EntityId::value)
            }
            .filterValues { it.isNotEmpty() }

        val mandatoryAttackers = getMandatoryAttackers(state, attackingPlayer)
            .distinct()
            .sortedBy(EntityId::value)
        if (mandatoryAttackers.any { it !in relation }) {
            return RulesAttackDeclarationDomainResult.Unsupported(
                AttackDeclarationDomainUnsupportedReason.INCOMPLETE_DECLARATION_CONSTRAINTS,
            )
        }

        val resolvedCoAttackers = getConcreteCoAttackerRequirements(state, attackingPlayer)
            .filterKeys { it in relation }
            .mapValues { (_, requirements) ->
                requirements
                    .map { requirement ->
                        RulesCoAttackerRequirement(
                            anyOf = requirement.anyOf
                                .filter { it in relation }
                                .distinct()
                                .sortedBy(EntityId::value),
                        )
                    }
                    .sortedBy { requirement ->
                        requirement.anyOf.joinToString(separator = "\u0000") { it.value }
                    }
            }
        if (resolvedCoAttackers.values.any { requirements -> requirements.any { it.anyOf.isEmpty() } }) {
            return RulesAttackDeclarationDomainResult.Unsupported(
                AttackDeclarationDomainUnsupportedReason.UNRESOLVED_CO_ATTACKER_REQUIREMENTS,
            )
        }

        val bandingAttackersByDefender = mutableMapOf<EntityId, MutableList<EntityId>>()
        val nonBandingAttackersByDefender = mutableMapOf<EntityId, MutableList<EntityId>>()
        for ((attackerId, defenders) in relation) {
            for (defenderId in defenders) {
                val destination = if (projected.hasKeyword(attackerId, Keyword.BANDING)) {
                    bandingAttackersByDefender
                } else {
                    nonBandingAttackersByDefender
                }
                destination.getOrPut(defenderId) { mutableListOf() }.add(attackerId)
            }
        }

        val domain = RulesAttackDeclarationDomain(
            attackerToDefenders = relation,
            mandatoryAttackers = mandatoryAttackers,
            canDeclareZeroAttackers = validateDeclarationBeforeTax(
                state,
                DeclareAttackers(attackingPlayer, emptyMap()),
            ) == null,
            maxAttackers = getGlobalAttackerCap(state, attackingPlayer),
            coAttackerRequirements = resolvedCoAttackers,
            bandConstraints = RulesAttackBandConstraints(
                bandingAttackersByDefender = bandingAttackersByDefender
                    .mapValues { (_, attackers) -> attackers.sortedBy(EntityId::value) }
                    .toSortedEntityIdMap(),
                nonBandingAttackersByDefender = nonBandingAttackersByDefender
                    .mapValues { (_, attackers) -> attackers.sortedBy(EntityId::value) }
                    .toSortedEntityIdMap(),
            ),
        )

        // This is a structural guard only. Zero-attacker legality above is derived directly from
        // Rules; this check prevents a future builder edit from registering malformed data.
        val structuralCheck = AttackDeclarationDomainValidator.validate(
            domain,
            DeclareAttackers(attackingPlayer, emptyMap()),
        )
        if (structuralCheck is com.wingedsheep.engine.legalactions.AttackDeclarationValidationResult.Rejected &&
            structuralCheck.reason == AttackDeclarationRejection.MALFORMED_CERTIFICATE
        ) {
            return RulesAttackDeclarationDomainResult.Unsupported(
                AttackDeclarationDomainUnsupportedReason.INCOMPLETE_BAND_CONSTRAINTS,
            )
        }

        return RulesAttackDeclarationDomainResult.Supported(domain)
    }

    /**
     * Get creatures that must attack this combat (for UI pre-selection).
     * Includes creatures required by MustAttackPlayerComponent, MustAttackThisTurnComponent,
     * and projected mustAttack (e.g., from static MustAttack ability like Valley Dasher).
     */
    fun getMandatoryAttackers(state: GameState, attackingPlayer: EntityId): List<EntityId> {
        val validAttackers = getValidAttackers(state, attackingPlayer)
        val mandatory = mutableSetOf<EntityId>()

        // 1. MustAttackPlayerComponent (Taunt effect) — all valid attackers must attack
        val mustAttackPlayer = state.getEntity(attackingPlayer)?.get<MustAttackPlayerComponent>()
        if (mustAttackPlayer != null && mustAttackPlayer.activeThisTurn) {
            mandatory.addAll(validAttackers)
        }

        // 2. MustAttackThisTurnComponent (Walking Desecration) — individual creatures
        for (attackerId in validAttackers) {
            val container = state.getEntity(attackerId) ?: continue
            if (container.has<MustAttackThisTurnComponent>()) {
                mandatory.add(attackerId)
            }
        }

        // 3. Projected mustAttack (static ability like Valley Dasher, Grand Melee) or a granted
        // entity-scoped MustAttack (Carnage's reanimated target), which never reaches projection.
        for (attackerId in validAttackers) {
            if (mustAttackThisCombat(state, attackerId)) {
                mandatory.add(attackerId)
            }
        }

        // 4. GoadedComponent (CR 701.15b) — individual creatures
        for (attackerId in validAttackers) {
            val container = state.getEntity(attackerId) ?: continue
            if (container.has<GoadedComponent>()) {
                mandatory.add(attackerId)
            }
        }

        return mandatory.toList()
    }

    // =========================================================================
    // Attack Taxes
    // =========================================================================

    /**
     * Compute the total generic-mana attack tax owed for [attackers] without paying it.
     * Used by [declareAttackers] to decide whether to pause for player confirmation
     * before tapping any mana. Shared with the AI via [CombatTaxes], which prices a
     * proposed attack before proposing it.
     */
    internal fun calculateTotalAttackTax(
        state: GameState,
        attackers: Map<EntityId, EntityId>,
        projected: ProjectedState
    ): Int = CombatTaxes.attackTax(state, cardRegistry, attackers, projected)

    private fun Map<EntityId, List<EntityId>>.toSortedEntityIdMap(): Map<EntityId, List<EntityId>> =
        entries.sortedBy { (entityId, _) -> entityId.value }.associate { (entityId, ids) ->
            entityId to ids
        }
}
