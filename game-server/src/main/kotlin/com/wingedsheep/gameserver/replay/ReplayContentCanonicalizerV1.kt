package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.gameserver.persistence.persistenceJson
import com.wingedsheep.gym.contract.REPLAY_CONTENT_IDENTITY_V1_SCHEMA_IDENTITY
import com.wingedsheep.gym.contract.ReplayContentIdentityV1
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardEntry
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.serialization.CardExporter
import com.wingedsheep.sdk.serialization.CardLoader
import com.wingedsheep.sdk.serialization.CardSerialization
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Game-server authority for the logical V1 CompactReplay content identity.
 *
 * Only replay inputs that can affect deterministic initialization or action execution are included:
 * the supported replay version, semantic setup, ordered actions, ordered yield mutations, and the
 * logical pinned card definitions. Checkpoints and composition-root closure are A4 proof evidence,
 * not replay content, and are intentionally absent.
 */
@OptIn(ExperimentalSerializationApi::class)
object ReplayContentCanonicalizerV1 {

    private const val SETUP_PATH = "setup"
    private const val ACTIONS_PATH = "actions"

    /**
     * The v5 action discriminator allow-list is deliberate. A newly added action must receive an
     * explicit replay-content audit instead of being silently included by generic serialization.
     */
    private val supportedActionTypes = setOf(
        "ActivateAbility",
        "BottomCards",
        "CastSpell",
        "ChooseManaColor",
        "Concede",
        "CrewVehicle",
        "CycleCard",
        "DeclareAttackers",
        "DeclareBlockers",
        "ForetellCard",
        "KeepHand",
        "OrderBlockers",
        "PassPriority",
        "PlayLand",
        "PlotCard",
        "SaddleMount",
        "SubmitDecision",
        "SuspendCardFromHand",
        "TakeMulligan",
        "TurnFaceUp",
        "TypecycleCard",
        "UnlockRoomDoor",
    )

    /** Decision response subtypes whose current V1 payloads are replay-supported. */
    private val supportedDecisionResponseTypes = setOf(
        "BatchYesNoResponse",
        "BudgetModalResponse",
        "CancelDecisionResponse",
        "CardsSelectedResponse",
        "ColorChosenResponse",
        "CombatResolutionResponse",
        "DamageAssignmentResponse",
        "DistributionResponse",
        "ManaSourcesSelectedResponse",
        "ModesChosenResponse",
        "NumberChosenResponse",
        "OptionChosenResponse",
        "OrderedResponse",
        "PilesSplitResponse",
        "ReplacementChosenResponse",
        "TargetsResponse",
        "YesNoResponse",
    )

    /**
     * Field allowlist for every audited replay carrier whose addition would require an identity
     * review. Nested card/rules values are still serialized in full because their logical card
     * definition is itself the pinned content; these root and payment/response carriers are the
     * replay protocol surface that must fail closed when v5 grows.
     */
    private val supportedReplayFieldsByType = mapOf(
        "ReplaySetup" to setOf(
            "seed", "format", "attackMode", "startingHandSize", "skipMulligans",
            "useHandSmoother", "handSmootherCandidates", "startingPlayerIndex", "teams",
            "players", "seatRoster",
        ),
        "ReplayPlayerSetup" to setOf("playerId", "name", "deck", "startingLife", "commanderCardName"),
        "Deck" to setOf("cards", "commander", "cardEntries", "commanderPrinting", "sideboard"),
        "CardEntry" to setOf("name", "printing"),
        "PrintingRef" to setOf("setCode", "collectorNumber"),
        "ReplayYieldEntry" to setOf("afterActionCount", "playerId", "op", "identity", "kind"),
        "Standard" to emptySet(),
        "Commander" to setOf(
            "commanderDamageThreshold", "deckSize", "startingLife", "startingHandSize",
            "alwaysDivertToCommand",
        ),
        "MomirBasic" to setOf("startingLife", "startingHandSize", "avatarCardName", "eligibleCreatureNames"),
        "TwoHeadedGiant" to setOf("startingLife", "startingHandSize", "poisonThreshold"),
        "TeamVsTeam" to setOf(
            "startingLife", "commanderDamageThreshold", "deckSize", "alwaysDivertToCommand",
        ),
        "PassPriority" to setOf("playerId"),
        "CastSpell" to setOf(
            "playerId", "cardId", "targets", "xValue", "paymentStrategy", "alternativePayment",
            "additionalCostPayment", "castFaceDown", "declaredCostSlot", "wasWaterbendPaid",
            "giftRecipient", "splicedCardIds", "damageDistribution", "useAlternativeCost", "chosenModes",
            "modeTargetsOrdered", "modeTargetRequirementsOrdered", "modeDamageDistribution", "graveyardLifeCost",
            "graveyardCastRider", "conspiredCreatures", "casualtyCreature", "faceIndex",
            "useWithoutPayingManaCost", "alternativeCostType", "preResolvedZoneChangeIds",
            "preResolvedSneakAttackDefenderId", "preResolvedWebSlingReturnedManaValue",
        ),
        "ActivateAbility" to setOf(
            "playerId", "sourceId", "abilityId", "targets", "costPayment", "manaColorChoice", "xValue",
            "repeatCount", "paymentStrategy", "alternativePayment", "damageDistribution",
            "opponentTargetsChosen", "preResolvedZoneChangeIds",
        ),
        "CycleCard" to setOf("playerId", "cardId", "paymentStrategy", "xValue"),
        "PlotCard" to setOf("playerId", "cardId", "paymentStrategy"),
        "ForetellCard" to setOf("playerId", "cardId", "paymentStrategy"),
        "SuspendCardFromHand" to setOf("playerId", "cardId", "paymentStrategy"),
        "TypecycleCard" to setOf("playerId", "cardId", "paymentStrategy"),
        "PlayLand" to setOf("playerId", "cardId"),
        "DeclareAttackers" to setOf("playerId", "attackers", "bands"),
        "DeclareBlockers" to setOf("playerId", "blockers"),
        "OrderBlockers" to setOf("playerId", "attackerId", "orderedBlockers"),
        "ChooseManaColor" to setOf("playerId", "color"),
        "SubmitDecision" to setOf("playerId", "response"),
        "TakeMulligan" to setOf("playerId"),
        "KeepHand" to setOf("playerId"),
        "BottomCards" to setOf("playerId", "cardIds"),
        "Concede" to setOf("playerId"),
        "CrewVehicle" to setOf("playerId", "vehicleId", "crewCreatures", "crewAbilityKey"),
        "SaddleMount" to setOf("playerId", "mountId", "saddleCreatures"),
        "TurnFaceUp" to setOf("playerId", "sourceId", "paymentStrategy", "costTargetIds", "xValue", "procedureIndex"),
        "UnlockRoomDoor" to setOf("playerId", "roomId", "faceId", "paymentStrategy"),
        "AutoPay" to emptySet(),
        "FromPool" to emptySet(),
        "Explicit" to setOf("manaAbilitiesToActivate", "paymentPlan"),
        "ExplicitV2" to setOf("manaAbilitiesToActivate", "paymentPlan"),
        "ExplicitV3" to setOf("paymentPlan"),
        "TargetsResponse" to setOf("decisionId", "selectedTargets"),
        "CardsSelectedResponse" to setOf("decisionId", "selectedCards"),
        "YesNoResponse" to setOf("decisionId", "choice"),
        "BatchYesNoResponse" to setOf("decisionId", "choice", "applyToAll"),
        "ModesChosenResponse" to setOf("decisionId", "selectedModes"),
        "ColorChosenResponse" to setOf("decisionId", "color"),
        "NumberChosenResponse" to setOf("decisionId", "number"),
        "DistributionResponse" to setOf("decisionId", "distribution"),
        "OrderedResponse" to setOf("decisionId", "orderedObjects"),
        "PilesSplitResponse" to setOf("decisionId", "piles"),
        "OptionChosenResponse" to setOf("decisionId", "optionIndex"),
        "ReplacementChosenResponse" to setOf("decisionId", "fromIndex", "toIndex"),
        "BudgetModalResponse" to setOf("decisionId", "selectedModeIndices"),
        "DamageAssignmentResponse" to setOf("decisionId", "assignments"),
        "ManaSourcesSelectedResponse" to setOf(
            "decisionId", "selectedSources", "autoPay", "waterbendPermanents", "declined",
        ),
        "CombatResolutionResponse" to setOf(
            "decisionId", "edges", "orderedBlockers", "orderedAttackers",
        ),
        "CancelDecisionResponse" to setOf("decisionId"),
        "CardDefinition" to setOf(
            "name", "manaCost", "typeLine", "oracleText", "creatureStats", "keywords", "flags",
            "keywordAbilities", "script", "equipCost", "oracleId", "setCode", "backFace", "metadata",
            "startingLoyalty", "startingDefense", "legalFormats", "colorIdentityOverride", "colorIndicator",
            "layout", "cardFaces", "hasNoManaCost", "meldResult",
        ),
        "CardFace" to setOf("name", "manaCost", "typeLine", "oracleText", "keywords", "script", "imageUri"),
        "ScryfallMetadata" to setOf(
            "collectorNumber", "rarity", "artist", "flavorText", "imageUri", "imageUriByCreatureSubtype",
            "scryfallId", "releaseDate", "rulings", "imageRotation", "inBooster",
        ),
        "Ruling" to setOf("date", "text"),
    )

    private data class ReplayPart(
        val element: JsonElement,
        val descriptor: SerialDescriptor,
        val path: List<String>,
    )

    private data class AbilityAliasPlan(
        val aliases: Map<String, String>,
        val reservedRawIds: Set<String>,
    )

    private data class PinnedCardElement(
        val identity: String,
        val cardName: String,
        val element: JsonElement,
    )

    /** Compute the versioned identity for one supported logical CompactReplay. */
    fun identity(replay: CompactReplay): ReplayContentIdentityV1 {
        val preimage = canonicalPreimage(replay)
        return ReplayContentIdentityV1(
            replayVersion = replay.version,
            value = sha256(preimage.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    /**
     * Expose the exact canonical preimage to same-module characterization tests and future replay
     * tooling. This is not a storage encoding and is not built from [ReplayCodec].
     */
    internal fun canonicalPreimage(replay: CompactReplay): String {
        require(replay.version == CompactReplay.CURRENT_VERSION) {
            "Unsupported CompactReplay version for replay-content identity: ${replay.version}"
        }
        requireReplayInputShape(replay)

        val setup = normalizedSetup(replay.setup)
        requireTypedReplayShape(
            element = setup,
            descriptor = ReplaySetup.serializer().descriptor,
            path = listOf(SETUP_PATH),
        )
        val actions = JsonArray(
            replay.actions.map { action ->
                persistenceJson.encodeToJsonElement(GameAction.serializer(), action)
            },
        )
        replay.actions.forEachIndexed { index, action ->
            requireSupportedAction(action, actions[index])
            requireTypedReplayShape(
                element = actions[index],
                descriptor = ListSerializer(GameAction.serializer()).descriptor.getElementDescriptor(0),
                path = listOf(ACTIONS_PATH, index.toString()),
            )
        }
        val yields = persistenceJson.encodeToJsonElement(
            ListSerializer(ReplayYieldEntry.serializer()),
            replay.yields,
        )
        requireTypedReplayShape(
            element = yields,
            descriptor = ListSerializer(ReplayYieldEntry.serializer()).descriptor,
            path = listOf("yields"),
        )
        val pinnedCards = JsonArray(canonicalPinnedCards(replay.pinnedCards).map(PinnedCardElement::element))
        requireTypedReplayShape(
            element = pinnedCards,
            descriptor = ListSerializer(CardDefinition.serializer()).descriptor,
            path = listOf("pinnedCards"),
        )

        val parts = listOf(
            ReplayPart(
                element = setup,
                descriptor = ReplaySetup.serializer().descriptor,
                path = listOf(SETUP_PATH),
            ),
            ReplayPart(
                element = actions,
                descriptor = ListSerializer(GameAction.serializer()).descriptor,
                path = listOf(ACTIONS_PATH),
            ),
            ReplayPart(
                element = yields,
                descriptor = ListSerializer(ReplayYieldEntry.serializer()).descriptor,
                path = listOf("yields"),
            ),
            ReplayPart(
                element = pinnedCards,
                descriptor = ListSerializer(CardDefinition.serializer()).descriptor,
                path = listOf("pinnedCards"),
            ),
        )
        val aliasPlan = collectAbilityAliases(parts)
        val abilityAliases = AbilityIdAliasTable(
            initialAliases = aliasPlan.aliases,
            reservedRawIds = aliasPlan.reservedRawIds,
        )

        val canonical = buildJsonObject {
            put("schema", REPLAY_CONTENT_IDENTITY_V1_SCHEMA_IDENTITY)
            put("replayVersion", replay.version)
            put(
                SETUP_PATH,
                canonicalize(
                    element = setup,
                    path = listOf(SETUP_PATH),
                    descriptor = ReplaySetup.serializer().descriptor,
                    abilityAliases = abilityAliases,
                ),
            )
            put(
                ACTIONS_PATH,
                canonicalize(
                    element = actions,
                    path = listOf(ACTIONS_PATH),
                    descriptor = ListSerializer(GameAction.serializer()).descriptor,
                    abilityAliases = abilityAliases,
                ),
            )
            put(
                "yields",
                canonicalize(
                    element = yields,
                    path = listOf("yields"),
                    descriptor = ListSerializer(ReplayYieldEntry.serializer()).descriptor,
                    abilityAliases = abilityAliases,
                ),
            )
            put(
                "pinnedCards",
                canonicalize(
                    element = pinnedCards,
                    path = listOf("pinnedCards"),
                    descriptor = ListSerializer(CardDefinition.serializer()).descriptor,
                    abilityAliases = abilityAliases,
                ),
            )
        }
        return canonicalJson(canonical)
    }

    /** Keep malformed yield coordinates and typed mutations out of the trusted identity path. */
    private fun requireReplayInputShape(replay: CompactReplay) {
        require(replay.setup.players.size >= 2) {
            "Replay setup requires at least two players"
        }
        val setupPlayerIds = replay.setup.players.map { it.playerId }
        require(setupPlayerIds.all(String::isNotBlank)) {
            "Replay setup player identities must not be blank"
        }
        require(setupPlayerIds.distinct().size == setupPlayerIds.size) {
            "Replay setup contains duplicate player identities"
        }
        require(replay.setup.players.all { it.name.isNotBlank() }) {
            "Replay setup player names must not be blank"
        }
        require(replay.setup.startingHandSize >= 0) {
            "Replay setup starting hand size must not be negative"
        }
        require(!replay.setup.useHandSmoother || replay.setup.handSmootherCandidates in 2..3) {
            "Replay setup hand-smoother candidates must be in the supported range"
        }
        replay.setup.startingPlayerIndex?.let { index ->
            require(index in replay.setup.players.indices) {
                "Replay setup starting-player index is outside the player list"
            }
        }
        replay.setup.teams?.let { teams ->
            require(teams.flatten().sorted() == replay.setup.players.indices.toList()) {
                "Replay setup teams must partition the setup players exactly once"
            }
        }
        replay.setup.players.forEach { player ->
            val libraryEntries = player.deck.cardEntries.ifEmpty {
                player.deck.cards.map(::CardEntry)
            }
            require(libraryEntries.all { it.name.isNotBlank() }) {
                "Replay setup deck entry names must not be blank"
            }
            require(player.deck.sideboard.all { it.name.isNotBlank() }) {
                "Replay setup sideboard entry names must not be blank"
            }
            (libraryEntries + player.deck.sideboard).forEach { entry ->
                entry.printing?.let { printing ->
                    val setCode = printing.setCode
                    val collectorNumber = printing.collectorNumber
                    require(setCode.isNotBlank() && collectorNumber.isNotBlank()) {
                        "Replay setup printing references must have stable identities"
                    }
                }
            }
            if (replay.setup.format.usesCommanders) {
                require(!player.commanderCardName.isNullOrBlank()) {
                    "Commander replay setup requires a commander card name for every player"
                }
                player.deck.commanderPrinting?.let { printing ->
                    val setCode = printing.setCode
                    val collectorNumber = printing.collectorNumber
                    require(setCode.isNotBlank() && collectorNumber.isNotBlank()) {
                        "Replay setup commander printing references must have stable identities"
                    }
                }
            }
        }
        val playerIds = replay.setup.players.map { it.playerId }.toSet()
        replay.yields.forEach { yield ->
            require(yield.afterActionCount in 0..replay.actions.size) {
                "Replay yield coordinate is outside the applied action stream: " +
                    yield.afterActionCount
            }
            require(yield.playerId in playerIds) {
                "Replay yield references an unknown setup player: ${yield.playerId}"
            }
            when (yield.op) {
                ReplayYieldOp.SET -> require(yield.identity != null && yield.kind != null) {
                    "SET replay yield requires an ability identity and kind"
                }
                ReplayYieldOp.CLEAR_ABILITY -> require(yield.identity != null && yield.kind == null) {
                    "CLEAR_ABILITY replay yield requires an ability identity and no kind"
                }
                ReplayYieldOp.CLEAR_ALL -> require(yield.identity == null && yield.kind == null) {
                    "CLEAR_ALL replay yield cannot carry an ability identity or kind"
                }
            }
            yield.identity?.let { identity ->
                require(identity.cardDefinitionId.isNotBlank()) {
                    "Replay yield ability identity requires a card-definition identity"
                }
            }
        }
    }

    private fun requireSupportedAction(action: GameAction, encoded: JsonElement) {
        val type = encoded.jsonObject["type"]?.jsonPrimitive?.contentOrNull
        require(type in supportedActionTypes) {
            "Unsupported CompactReplay action type: ${type ?: "missing"}"
        }
        requireKnownReplayFields(encoded.jsonObject, checkNotNull(type))
        if (type == "SubmitDecision") {
            val response = encoded.jsonObject["response"]?.jsonObject
            val responseType = response
                ?.get("type")
                ?.jsonPrimitive
                ?.contentOrNull
            require(responseType in supportedDecisionResponseTypes) {
                "Unsupported CompactReplay decision response type: ${responseType ?: "missing"}"
            }
            requireKnownReplayFields(response, checkNotNull(responseType))
        }
        require(action.playerId.value.isNotBlank()) {
            "CompactReplay action player identity must not be blank"
        }
    }

    /** Reject malformed typed identifier shapes before canonicalization can turn them into bytes. */
    private fun requireTypedReplayShape(
        element: JsonElement,
        descriptor: SerialDescriptor?,
        path: List<String>,
    ) {
        when (element) {
            is JsonObject -> {
                val concrete = resolvePolymorphicDescriptor(descriptor, element)
                val typeName = concrete?.serialName?.substringAfterLast('.')
                typeName?.let { requireKnownReplayFields(element, it) }
                val mapDescriptor = concrete?.takeIf { it.kind == StructureKind.MAP }
                if (mapDescriptor != null) {
                    val keyDescriptor = mapDescriptor.getElementDescriptor(0)
                    val valueDescriptor = mapDescriptor.getElementDescriptor(1)
                    element.entries.forEach { (key, value) ->
                        requireTypedReplayShape(JsonPrimitive(key), keyDescriptor, path + "map-key")
                        requireTypedReplayShape(value, valueDescriptor, path + "map-value")
                    }
                } else {
                    element.entries.forEach { (key, value) ->
                        requireTypedReplayShape(value, concrete?.fieldDescriptor(key), path + key)
                    }
                }
            }

            is JsonArray -> {
                if (descriptor?.kind == StructureKind.MAP) {
                    require(element.size % 2 == 0) {
                        "Replay map encoding has an odd element count at ${path.joinToString(".")}"
                    }
                    val keyDescriptor = descriptor.getElementDescriptor(0)
                    val valueDescriptor = descriptor.getElementDescriptor(1)
                    for (index in 0 until element.size / 2) {
                        requireTypedReplayShape(element[index * 2], keyDescriptor, path + "map-key")
                        requireTypedReplayShape(element[index * 2 + 1], valueDescriptor, path + "map-value")
                    }
                } else {
                    val elementDescriptor = descriptor?.getElementDescriptorOrNull()
                    element.forEach { child -> requireTypedReplayShape(child, elementDescriptor, path + "array-value") }
                }
            }

            is JsonPrimitive -> {
                if (
                    descriptor.isEntityIdDescriptor() ||
                    descriptor.isAbilityIdDescriptor() ||
                    descriptor.isRoomFaceIdDescriptor()
                ) {
                    require(element.isString && element.content.isNotBlank()) {
                        "Blank or malformed typed replay identity at ${path.joinToString(".")}"
                    }
                }
            }
        }
    }

    private fun requireKnownReplayFields(element: JsonObject?, typeName: String) {
        val supportedFields = supportedReplayFieldsByType[typeName] ?: return
        val discriminator = if (element != null && "type" in element) setOf("type") else emptySet()
        val unknownFields = (element?.keys.orEmpty() - supportedFields - discriminator)
        require(unknownFields.isEmpty()) {
            "Unsupported replay fields for $typeName: ${unknownFields.sorted()}"
        }
    }

    private fun normalizedSetup(setup: ReplaySetup): JsonElement {
        val normalized = setup.copy(
            players = setup.players.map { player ->
                player.copy(
                    commanderCardName = player.commanderCardName.takeIf { setup.format.usesCommanders },
                    deck = player.deck.withCanonicalContent(
                        usesCommanders = setup.format.usesCommanders,
                        commanderCardName = player.commanderCardName,
                    ),
                )
            },
        )
        return persistenceJson.encodeToJsonElement(ReplaySetup.serializer(), normalized)
    }

    private fun Deck.withCanonicalContent(
        usesCommanders: Boolean,
        commanderCardName: String?,
    ): Deck {
        // GameInitializer consumes cardEntries when present and otherwise derives CardEntry values
        // from cards. Normalize both representations to the effective ordered library so a stale
        // duplicate `cards` field cannot create a second identity for the same initialization.
        val libraryEntries = cardEntries.ifEmpty { cards.map(::CardEntry) }
        return copy(
            cards = libraryEntries.map(CardEntry::name),
            // GameInitializer takes the commander from ReplayPlayerSetup.commanderCardName; the
            // Deck.commander compatibility field is not read by ReplayReconstructor/GameInitializer.
            commander = null,
            cardEntries = libraryEntries,
            commanderPrinting = commanderPrinting.takeIf { usesCommanders && commanderCardName != null },
            // The rules treat the sideboard as unordered, but GameInitializer allocates its entity
            // IDs in this recorded order. Those IDs can be referenced by later wish decisions, so
            // preserve the source order in the replay-content identity.
            sideboard = sideboard,
        )
    }

    private fun canonicalPinnedCards(pins: List<String>): List<PinnedCardElement> {
        val parsed = pins.map { encoded ->
            val card = try {
                CardLoader.fromJsonPreservingIds(encoded)
            } catch (failure: Exception) {
                throw IllegalArgumentException("Malformed pinned card definition", failure)
            }
            require(card.name.isNotBlank()) { "Pinned card definition requires a stable name" }
            val setCode = card.setCode
            val collectorNumber = card.metadata.collectorNumber
            require(setCode == null || setCode.isNotBlank()) {
                "Pinned card definition set identity must not be blank"
            }
            require(collectorNumber == null || collectorNumber.isNotBlank()) {
                "Pinned card definition collector identity must not be blank"
            }
            val identity = stableCardIdentity(card)
            val canonical = try {
                CardSerialization.compactJson.parseToJsonElement(CardExporter.exportToCompactJson(card))
            } catch (failure: Exception) {
                throw IllegalArgumentException("Pinned card definition cannot be canonically encoded", failure)
            }
            PinnedCardElement(identity = identity, cardName = card.name, element = canonical)
        }
        require(parsed.map(PinnedCardElement::identity).distinct().size == parsed.size) {
            "Replay contains duplicate pinned card identities"
        }
        require(parsed.map(PinnedCardElement::cardName).distinct().size == parsed.size) {
            "Replay contains multiple pinned definitions for one card name"
        }
        return parsed.sortedBy(PinnedCardElement::identity)
    }

    private fun stableCardIdentity(card: CardDefinition): String =
        listOfNotNull(card.name, card.setCode, card.metadata.collectorNumber).joinToString("#")

    private fun collectAbilityAliases(parts: List<ReplayPart>): AbilityAliasPlan {
        val rawIds = linkedSetOf<String>()
        val generatedIds = linkedSetOf<String>()
        val pinnedGeneratedIds = linkedSetOf<String>()
        val replayReferenceGeneratedIds = linkedSetOf<String>()
        parts.forEach { part ->
            val partRawIds = linkedSetOf<String>()
            val partGeneratedIds = linkedSetOf<String>()
            collectAbilityIds(
                element = part.element,
                path = part.path,
                descriptor = part.descriptor,
                rawIds = partRawIds,
                generatedIds = partGeneratedIds,
            )
            rawIds += partRawIds
            generatedIds += partGeneratedIds
            when (part.path.firstOrNull()) {
                "pinnedCards" -> pinnedGeneratedIds += partGeneratedIds
                "actions", "yields" -> replayReferenceGeneratedIds += partGeneratedIds
            }
        }
        val unanchoredGeneratedIds = replayReferenceGeneratedIds - pinnedGeneratedIds
        require(unanchoredGeneratedIds.isEmpty()) {
            "Generated replay ability handles require a pinned card-definition anchor: " +
                unanchoredGeneratedIds.sorted()
        }
        val aliasTable = AbilityIdAliasTable(reservedRawIds = rawIds - generatedIds)
        val aliases = LinkedHashMap<String, String>()
        generatedIds.forEach { rawId -> aliases[rawId] = aliasTable.aliasIfGenerated(rawId) }
        return AbilityAliasPlan(aliases = aliases, reservedRawIds = rawIds - generatedIds)
    }

    private fun collectAbilityIds(
        element: JsonElement,
        path: List<String>,
        descriptor: SerialDescriptor?,
        rawIds: MutableSet<String>,
        generatedIds: MutableSet<String>,
    ) {
        when (element) {
            is JsonObject -> {
                val concrete = resolvePolymorphicDescriptor(descriptor, element)
                val mapDescriptor = concrete?.takeIf { it.kind == StructureKind.MAP }
                val entries = element.entries.toList()
                if (mapDescriptor != null) {
                    val keyDescriptor = mapDescriptor.getElementDescriptor(0)
                    val valueDescriptor = mapDescriptor.getElementDescriptor(1)
                    entries
                        .sortedWith(compareBy({
                            render(
                                shape(JsonPrimitive(it.key), keyDescriptor, path + "map-key")
                            )
                        }, { render(shape(it.value, valueDescriptor, path + "map-value")) }))
                        .forEach { (key, value) ->
                            collectAbilityPrimitive(JsonPrimitive(key), keyDescriptor, rawIds, generatedIds)
                            collectAbilityIds(
                                value,
                                path + "map-value",
                                valueDescriptor,
                                rawIds,
                                generatedIds,
                            )
                        }
                } else {
                    entries.sortedBy(Map.Entry<String, JsonElement>::key).forEach { (key, value) ->
                        if (shouldDropReplayActionField(path, concrete, key)) return@forEach
                        collectAbilityIds(
                            value,
                            path + key,
                            concrete?.fieldDescriptor(key),
                            rawIds,
                            generatedIds,
                        )
                    }
                }
            }

            is JsonArray -> {
                if (descriptor?.kind == StructureKind.MAP) {
                    require(element.size % 2 == 0) { "Replay map encoding has an odd element count" }
                    val keyDescriptor = descriptor.getElementDescriptor(0)
                    val valueDescriptor = descriptor.getElementDescriptor(1)
                    (0 until element.size / 2)
                        .map { index ->
                            index to render(
                                JsonArray(
                                    listOf(
                                        shape(element[index * 2], keyDescriptor, path + "map-key"),
                                        shape(element[index * 2 + 1], valueDescriptor, path + "map-value"),
                                    ),
                                ),
                            )
                        }
                        .sortedBy { it.second }
                        .forEach { (index, _) ->
                            collectAbilityIds(
                                element[index * 2],
                                path + "map-key",
                                keyDescriptor,
                                rawIds,
                                generatedIds,
                            )
                            collectAbilityIds(
                                element[index * 2 + 1],
                                path + "map-value",
                                valueDescriptor,
                                rawIds,
                                generatedIds,
                            )
                        }
                    return
                }

                val elementDescriptor = descriptor?.getElementDescriptorOrNull()
                val indexes = if (descriptor.isUnorderedSetDescriptor()) {
                    element.indices.sortedBy { index ->
                        render(shape(element[index], elementDescriptor, path + "set-value"))
                    }
                } else {
                    element.indices
                }
                indexes.forEach { index ->
                    collectAbilityIds(
                        element[index],
                        path + index.toString(),
                        elementDescriptor,
                        rawIds,
                        generatedIds,
                    )
                }
            }

            is JsonPrimitive -> collectAbilityPrimitive(element, descriptor, rawIds, generatedIds)
        }
    }

    private fun collectAbilityPrimitive(
        element: JsonElement,
        descriptor: SerialDescriptor?,
        rawIds: MutableSet<String>,
        generatedIds: MutableSet<String>,
    ) {
        val primitive = element as? JsonPrimitive ?: return
        if (!primitive.isString || !descriptor.isAbilityIdDescriptor()) return
        rawIds += primitive.content
        if (AbilityIdAliasTable.isGenerated(primitive.content)) generatedIds += primitive.content
    }

    private fun canonicalize(
        element: JsonElement,
        path: List<String>,
        descriptor: SerialDescriptor?,
        abilityAliases: AbilityIdAliasTable,
    ): JsonElement = when (element) {
        is JsonObject -> {
            val concrete = resolvePolymorphicDescriptor(descriptor, element)
            val mapDescriptor = concrete?.takeIf { it.kind == StructureKind.MAP }
            if (mapDescriptor != null) {
                val keyDescriptor = mapDescriptor.getElementDescriptor(0)
                val valueDescriptor = mapDescriptor.getElementDescriptor(1)
                val entries = element.entries.map { (key, value) ->
                    val canonicalKey = canonicalize(JsonPrimitive(key), path + "map-key", keyDescriptor, abilityAliases)
                    val canonicalValue = canonicalize(value, path + "map-value", valueDescriptor, abilityAliases)
                    canonicalKey to canonicalValue
                }.sortedWith(compareBy({ render(it.first) }, { render(it.second) }))
                JsonObject(LinkedHashMap<String, JsonElement>().apply {
                    entries.forEach { (key, value) ->
                        require(key is JsonPrimitive && key.isString) {
                            "Replay map key cannot be represented as a JSON object key"
                        }
                        put(key.content, value)
                    }
                })
            } else {
                val entries = element.entries
                    .filterNot { (key, _) -> shouldDropReplayActionField(path, concrete, key) }
                    .filterNot { (key, _) -> path == listOf(SETUP_PATH) && key == "seatRoster" }
                    .sortedBy(Map.Entry<String, JsonElement>::key)
                    .map { (key, value) ->
                        key to canonicalize(
                            element = value,
                            path = path + key,
                            descriptor = concrete?.fieldDescriptor(key),
                            abilityAliases = abilityAliases,
                        )
                    }
                JsonObject(LinkedHashMap<String, JsonElement>().apply {
                    entries.forEach { (key, value) -> put(key, value) }
                })
            }
        }

        is JsonArray -> {
            if (descriptor?.kind == StructureKind.MAP) {
                require(element.size % 2 == 0) { "Replay map encoding has an odd element count" }
                val keyDescriptor = descriptor.getElementDescriptor(0)
                val valueDescriptor = descriptor.getElementDescriptor(1)
                val pairs = (0 until element.size / 2).map { index ->
                    canonicalize(element[index * 2], path + "map-key", keyDescriptor, abilityAliases) to
                        canonicalize(element[index * 2 + 1], path + "map-value", valueDescriptor, abilityAliases)
                }.sortedWith(compareBy({ render(it.first) }, { render(it.second) }))
                JsonArray(pairs.flatMap { (key, value) -> listOf(key, value) })
            } else {
                val elementDescriptor = descriptor?.getElementDescriptorOrNull()
                val canonical = element.mapIndexed { index, child ->
                    canonicalize(child, path + index.toString(), elementDescriptor, abilityAliases)
                }
                if (descriptor.isUnorderedSetDescriptor()) {
                    JsonArray(canonical.sortedBy(::render))
                } else {
                    JsonArray(canonical)
                }
            }
        }

        is JsonPrimitive -> if (element.isString && descriptor.isAbilityIdDescriptor()) {
            JsonPrimitive(abilityAliases.aliasIfGenerated(element.content))
        } else {
            element
        }
    }

    private fun shape(
        element: JsonElement,
        descriptor: SerialDescriptor?,
        path: List<String>,
    ): JsonElement = when (element) {
        is JsonObject -> {
            val concrete = resolvePolymorphicDescriptor(descriptor, element)
            val mapDescriptor = concrete?.takeIf { it.kind == StructureKind.MAP }
            if (mapDescriptor != null) {
                val keyDescriptor = mapDescriptor.getElementDescriptor(0)
                val valueDescriptor = mapDescriptor.getElementDescriptor(1)
                JsonArray(
                    element.entries
                        .map { (key, value) ->
                            JsonArray(
                                listOf(
                                    shape(JsonPrimitive(key), keyDescriptor, path + "map-key"),
                                    shape(value, valueDescriptor, path + "map-value"),
                                ),
                            )
                        }
                        .sortedBy(::render),
                )
            } else {
                JsonObject(LinkedHashMap<String, JsonElement>().apply {
                    element.entries
                        .filterNot { (key, _) -> shouldDropReplayActionField(path, concrete, key) }
                        .filterNot { (key, _) -> path == listOf(SETUP_PATH) && key == "seatRoster" }
                        .sortedBy(Map.Entry<String, JsonElement>::key)
                        .forEach { (key, value) ->
                            put(key, shape(value, concrete?.fieldDescriptor(key), path + key))
                        }
                })
            }
        }

        is JsonArray -> {
            if (descriptor?.kind == StructureKind.MAP) {
                require(element.size % 2 == 0) { "Replay map encoding has an odd element count" }
                val keyDescriptor = descriptor.getElementDescriptor(0)
                val valueDescriptor = descriptor.getElementDescriptor(1)
                JsonArray(
                    (0 until element.size / 2)
                        .map { index ->
                            JsonArray(
                                listOf(
                                    shape(element[index * 2], keyDescriptor, path + "map-key"),
                                    shape(element[index * 2 + 1], valueDescriptor, path + "map-value"),
                                ),
                            )
                        }
                        .sortedBy(::render),
                )
            } else {
                val childDescriptor = descriptor?.getElementDescriptorOrNull()
                val children = element.map { child -> shape(child, childDescriptor, path + "array-value") }
                if (descriptor.isUnorderedSetDescriptor()) {
                    JsonArray(children.sortedBy(::render))
                } else {
                    JsonArray(children)
                }
            }
        }

        is JsonPrimitive -> if (element.isString && descriptor.isAbilityIdDescriptor() &&
            AbilityIdAliasTable.isGenerated(element.content)
        ) {
            JsonPrimitive("<generated-ability>")
        } else {
            element
        }
    }

    private fun shouldDropDecisionId(
        path: List<String>,
        descriptor: SerialDescriptor?,
        key: String,
    ): Boolean = key == "decisionId" &&
        path.size >= 3 &&
        path.firstOrNull() == ACTIONS_PATH &&
        path.lastOrNull() == "response" &&
        descriptor?.serialName?.substringAfterLast('.')?.endsWith("Response") == true

    private fun shouldDropReplayActionField(
        path: List<String>,
        descriptor: SerialDescriptor?,
        key: String,
    ): Boolean = shouldDropDecisionId(path, descriptor, key) || (
        path.size >= 3 &&
            path.firstOrNull() == ACTIONS_PATH &&
            path.lastOrNull() == "response" &&
            descriptor?.serialName?.substringAfterLast('.') == "CombatResolutionResponse" &&
            key in setOf("orderedBlockers", "orderedAttackers")
        )

    private fun resolvePolymorphicDescriptor(
        descriptor: SerialDescriptor?,
        element: JsonObject,
    ): SerialDescriptor? {
        if (descriptor == null || descriptor.kind !is PolymorphicKind) return descriptor
        val typeName = element["type"]?.jsonPrimitive?.contentOrNull ?: return descriptor
        return descriptor.findConcreteDescriptor(typeName) ?: descriptor
    }

    private fun SerialDescriptor.findConcreteDescriptor(
        typeName: String,
        seen: MutableSet<String> = mutableSetOf(),
    ): SerialDescriptor? {
        if (!seen.add(serialName)) return null
        if (serialName == typeName || serialName.substringAfterLast('.') == typeName.substringAfterLast('.')) {
            return this
        }
        for (index in 0 until elementsCount) {
            val match = getElementDescriptor(index).findConcreteDescriptor(typeName, seen)
            if (match != null) return match
        }
        return null
    }

    private fun SerialDescriptor.fieldDescriptor(key: String): SerialDescriptor? {
        if (kind == StructureKind.MAP) return getElementDescriptor(1)
        if (kind != StructureKind.CLASS && kind !is PolymorphicKind) return null
        val index = getElementIndex(key)
        return if (index < 0) null else getElementDescriptor(index)
    }

    private fun SerialDescriptor.getElementDescriptorOrNull(): SerialDescriptor? =
        if (elementsCount == 0) null else getElementDescriptor(0)

    private fun SerialDescriptor?.isUnorderedSetDescriptor(): Boolean =
        this != null && (
            serialName.endsWith(".HashSet") ||
                serialName.endsWith(".LinkedHashSet") ||
                serialName == "kotlin.collections.Set"
            )

    private fun SerialDescriptor?.isAbilityIdDescriptor(): Boolean =
        this?.serialName == "com.wingedsheep.sdk.scripting.AbilityId"

    private fun SerialDescriptor?.isRoomFaceIdDescriptor(): Boolean =
        this?.serialName == "com.wingedsheep.engine.state.components.identity.RoomFaceId"

    private fun SerialDescriptor?.isEntityIdDescriptor(): Boolean =
        this?.serialName == "com.wingedsheep.sdk.model.EntityId"

    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> JsonObject(LinkedHashMap<String, JsonElement>().apply {
            element.entries.sortedBy(Map.Entry<String, JsonElement>::key).forEach { (key, value) ->
                put(key, value)
            }
        }).toString()
        else -> element.toString()
    }

    private fun render(element: JsonElement): String = element.toString()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
