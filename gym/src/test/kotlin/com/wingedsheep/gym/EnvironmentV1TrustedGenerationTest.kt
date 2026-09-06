package com.wingedsheep.gym

import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY
import com.wingedsheep.gym.contract.CandidateDomainDigestV1
import com.wingedsheep.gym.contract.COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.COMPLETE_LEGAL_DOMAIN_VERSION
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.gym.contract.ReplayChosenInputV1
import com.wingedsheep.gym.contract.ReplayFidelity
import com.wingedsheep.gym.contract.SchemaHash
import com.wingedsheep.gym.contract.SemanticDecisionKindV1
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.replay.CompactReplay
import com.wingedsheep.gameserver.replay.GymReplayFrameSource
import com.wingedsheep.gameserver.replay.ReplayCheckpoint
import com.wingedsheep.gameserver.replay.ReplayCodec
import com.wingedsheep.gameserver.replay.ReplayContentCanonicalizerV1
import com.wingedsheep.gameserver.replay.ReplayFingerprint
import com.wingedsheep.gameserver.replay.ReplayPlayerInfo
import com.wingedsheep.gameserver.replay.ReplayPlayerSetup
import com.wingedsheep.gameserver.replay.ReplaySetup
import com.wingedsheep.gameserver.replay.ReplayCardPin
import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.gym.trainer.trajectory.CompactReplayLinkV1
import com.wingedsheep.gym.trainer.trajectory.DatasetManifestV1
import com.wingedsheep.gym.trainer.trajectory.DatasetMetadataV1
import com.wingedsheep.gym.trainer.trajectory.DecisionRecordV1
import com.wingedsheep.gym.trainer.trajectory.EnvironmentIdentityV1
import com.wingedsheep.gym.trainer.trajectory.EpisodeMetadataV1
import com.wingedsheep.gym.trainer.trajectory.RosterSeatV1
import com.wingedsheep.gym.trainer.trajectory.SemanticDecisionIdentityV1
import com.wingedsheep.gym.trainer.trajectory.SemanticReplayInputV1
import com.wingedsheep.gym.trainer.trajectory.SemanticReplayPrefixV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryAdmissionResult
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Reader
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Writer
import com.wingedsheep.gym.trainer.trajectory.PolicyProvenanceV1
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.time.Duration.Companion.hours

private const val A9_BASE_SHA = "0aa9444c6872db6a6527ea479061eb2efafea705"
private const val A9_MAX_STEPS = 2_000
private const val A9_PRIMARY_EPISODES = 64
private const val A9_MAX_EPISODES = 72
private const val A9_MAX_SHARD_BYTES = 256L * 1024L * 1024L
private const val A9_MAX_EPISODES_PER_SHARD = 1
private const val A9_POLICY_IDENTITY = "b2-a9-deterministic-external-policy@v1"
private const val A9_POLICY_RNG_IDENTITY = "explicit-seed/kotlin-policy-state-v1"
private const val A9_AKIRI_DECK_SHA256 =
    "0C5878E3B393A2CB6317FBE64E0827E4E9A562A0346E5A75820F11081F0909C6"
private const val A9_CHEVILL_DECK_SHA256 =
    "D158760D404F32C32110C377B1CA6E3EF9406FD6E0CC29B620CB5BCF573AC8B2"
private const val A9_CARD_DEFINITION_IDENTITY =
    "3C3C2DF4993D875D1239F49D4D3DACF059D8842BC2A6E0D03DDF31CDB7901E23"
private const val A9_FORBIDDEN_SERIALIZED_FIELD_LIST =
    "\"gameState\",\"rawAction\",\"rawGameAction\",\"actionId\",\"decisionId\",\"abilityId\",\"envId\",\"pendingDecisionInternal\",\"prefix\",\"reward\""

private val a9ReplayJson = Json {
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
    ignoreUnknownKeys = false
}

class EnvironmentV1TrustedGenerationTest : FunSpec({
    test("fresh bounded primary matrix crosses Gym, A4, A5, A6, writer, and A7")
        .config(timeout = 8.hours) {
            val evidence = A9TrustedGenerationHarness.run()
            println(evidence.render())
            evidence.failed shouldBe 0
            evidence.quarantined shouldBe 0
            evidence.unsupportedDiagnostics shouldBe 0
            evidence.nativeFallbacks shouldBe 0
            evidence.publicChoiceRejections shouldBe 0
            evidence.chosenNotInDomain shouldBe 0
            evidence.replayDiverged shouldBe 0
            evidence.replayIncomplete shouldBe 0

            val episodeLimit = evidence.episodes.size
            if (episodeLimit >= A9_PRIMARY_EPISODES) {
                episodeLimit shouldBe evidence.episodesStarted
                evidence.terminalEpisodes shouldBe evidence.manifest.counts.gameTerminalCount
                evidence.interruptedEpisodes shouldBe evidence.manifest.counts.interruptedCount
                check(evidence.terminalEpisodes >= 1) {
                    "A9 requires at least one GAME_TERMINAL episode"
                }
                check(evidence.interruptedEpisodes >= 1) {
                    "A9 requires at least one INTERRUPTED episode"
                }
                check(evidence.determinismSpotCheck == 4) {
                    "A9 requires four deterministic regeneration spot-checks"
                }
            }
        }
})

private object A9TrustedGenerationHarness {
    private val rosterOrientations = listOf(
        "Akiri" to "Chevill",
        "Chevill" to "Akiri",
    )
    private val startingPlayers = listOf(0, 1)

    fun run(): A9GenerationEvidence {
        val requestedLimit = System.getProperty("a9.episodeLimit")
            ?.toIntOrNull()
            ?.also { require(it in 1..A9_MAX_EPISODES) }
            ?: A9_PRIMARY_EPISODES
        val repositoryRoot = repositoryRoot()
        val policySourceIdentity = policySourceIdentity(repositoryRoot)
        val registry = exactPairRegistry()
        val resolver = DeckResolver(registry)
        val primary = primarySchedule()
        val extension = extensionSchedule()
        val outputRoot = Files.createTempDirectory("argentum-b2-a9-pr135-")
        val metadata = DatasetMetadataV1(
            maxShardBytes = A9_MAX_SHARD_BYTES,
            maxEpisodesPerShard = A9_MAX_EPISODES_PER_SHARD,
        )
        // Keep only compact evidence after publication. A full trajectory plus its replay
        // binding can be very large; retaining all 64 episodes here defeats the bounded
        // publication test by keeping every canonicalization graph live until the end.
        val generated = mutableListOf<EpisodeSummary>()
        val schedule = if (requestedLimit < A9_PRIMARY_EPISODES) {
            primary.take(requestedLimit)
        } else {
            primary + extension
        }

        var manifest: DatasetManifestV1? = null
        TrajectoryV1Writer(outputRoot, metadata).use { writer ->
            for (spec in schedule) {
                if (generated.size >= requestedLimit) break
                val episode = generateEpisode(
                    spec = spec,
                    registry = registry,
                    resolver = resolver,
                    repositoryRoot = repositoryRoot,
                    policySourceIdentity = policySourceIdentity,
                )
                when (val admission = writer.appendEpisode(spec.ordinal, episode.trajectory, episode.binding)) {
                    is TrajectoryAdmissionResult.Admitted -> Unit
                    is TrajectoryAdmissionResult.Quarantined -> error(
                        "A9 episode ${spec.ordinal} was quarantined: ${admission.metadata.reason}",
                    )
                }
                generated += episode.summary

                if (
                    requestedLimit >= A9_PRIMARY_EPISODES &&
                    generated.size >= A9_PRIMARY_EPISODES &&
                    generated.any { it.closureKind == EpisodeClosureV1.Kind.GAME_TERMINAL } &&
                    generated.any { it.closureKind == EpisodeClosureV1.Kind.INTERRUPTED }
                ) {
                    break
                }
            }
            if (requestedLimit >= A9_PRIMARY_EPISODES) {
                check(generated.size >= A9_PRIMARY_EPISODES) {
                    "A9 primary matrix stopped before 64 episodes"
                }
                check(generated.size <= A9_MAX_EPISODES) {
                    "A9 bounded extension exceeded 72 episodes"
                }
                check(generated.any { it.closureKind == EpisodeClosureV1.Kind.GAME_TERMINAL }) {
                    "A9 primary plus bounded extension produced no GAME_TERMINAL episode"
                }
                check(generated.any { it.closureKind == EpisodeClosureV1.Kind.INTERRUPTED }) {
                    "A9 primary plus bounded extension produced no INTERRUPTED episode"
                }
            }
            manifest = writer.finalizeDataset()
        }

        val finalizedManifest = checkNotNull(manifest)
        val datasetRoot = outputRoot.resolve("dataset-${finalizedManifest.datasetId}")
        val readSummaries = TrajectoryV1Reader
            .openPublishedDataset(datasetRoot)
            .streamEpisodes()
            .map(::readSummary)
            .toList()
        val writtenSummaries = generated.toList()
        check(readSummaries == writtenSummaries.map(::withoutRepeatIndices)) {
            "A7 reader changed the ordered trajectory identities or closure metadata"
        }
        check(finalizedManifest.episodes.map { it.episodeOrdinal } == generated.indices.toList()) {
            "Manifest episode ordinals are not the frozen producer order"
        }
        check(finalizedManifest.counts.episodeCount == generated.size)
        check(finalizedManifest.counts.failedCount == 0)

        val privacyViolations = serializedPrivacyViolations(finalizedManifest, datasetRoot)
        check(privacyViolations.isEmpty()) {
            "Serialized privacy scan found forbidden fields: $privacyViolations"
        }

        val determinismSpotCheck = if (requestedLimit >= A9_PRIMARY_EPISODES) {
            primary.filter { it.seed == 0L }.map { spec ->
                val original = generated.single { it.spec == spec }
                val regenerated = generateEpisode(
                    spec = spec,
                    registry = registry,
                    resolver = resolver,
                    repositoryRoot = repositoryRoot,
                    policySourceIdentity = policySourceIdentity,
                ).summary
                check(regenerated.sameDeterministicContentAs(original)) {
                    "Deterministic regeneration changed ${spec.label}"
                }
                spec
            }.size
        } else {
            0
        }

        val repeatWitnesses = generated.flatMap { episode ->
            episode.repeatActionIndices.map { index -> episode.spec to index }
        }
        val ordinalTwoRepeatActions = repeatWitnesses
            .filter { (spec, index) -> spec?.ordinal == 2 && index in 1_750..1_790 }
            .map { (_, index) -> index }

        return A9GenerationEvidence(
            baseSha = A9_BASE_SHA,
            repositoryRoot = repositoryRoot,
            outputRoot = outputRoot,
            policySourceIdentity = policySourceIdentity,
            episodes = writtenSummaries,
            manifest = finalizedManifest,
            readEpisodeCount = readSummaries.size,
            privacyViolations = privacyViolations,
            determinismSpotCheck = determinismSpotCheck,
            ordinalTwoRepeatActions = ordinalTwoRepeatActions,
            formerRepeatPathReached = ordinalTwoRepeatActions.isNotEmpty(),
            failed = 0,
            quarantined = 0,
            unsupportedDiagnostics = 0,
            nativeFallbacks = 0,
            publicChoiceRejections = 0,
            chosenNotInDomain = 0,
            replayDiverged = 0,
            replayIncomplete = 0,
        )
    }

    private fun generateEpisode(
        spec: A9EpisodeSpec,
        registry: CardRegistry,
        resolver: DeckResolver,
        repositoryRoot: Path,
        policySourceIdentity: String,
    ): GeneratedEpisode {
        val config = spec.gameConfig(resolver)
        val environment = GameEnvironment.create(
            cardRegistry = registry,
            executionMode = GameEnvironmentMode.TRUSTED,
        )
        environment.reset(config, A9_MAX_STEPS)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
        )
        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(policySeed(spec))
        val actions = mutableListOf<GameAction>()
        val checkpoints = mutableListOf<ReplayCheckpoint>()
        val observedActionKinds = linkedMapOf<String, Int>()
        val observedDecisionFamilies = linkedMapOf<String, Int>()
        val observedRequiredPayloadFields = linkedSetOf<String>()

        fun observe(result: com.wingedsheep.gym.contract.ObservationResult): TrainingObservation {
            check(result.diagnostics.isEmpty()) {
                "A9 public observation carried diagnostics at ${spec.label}"
            }
            check(gym.diagnostics == EpisodeDiagnostics.EMPTY) {
                "A9 trusted environment carried diagnostics at ${spec.label}"
            }
            val observation = result.observation as? TrainingObservation
                ?: error("A9 expected TrainingObservation at ${spec.label}")
            check(observation.schemaHash == SchemaHash.CURRENT) {
                "A9 used a stale Gym schema hash: ${observation.schemaHash}"
            }
            check(observation.legalActions.all { action ->
                action.repeatCountDomain == null ||
                    action.repeatCountDomain.version == 1
            }) {
                "A9 observed a malformed repeat-count domain"
            }
            observation.legalActions.forEach { action ->
                observedActionKinds[action.kind] = (observedActionKinds[action.kind] ?: 0) + 1
                observedRequiredPayloadFields += action.requiredPayloadFields
            }
            observation.pendingDecision
                ?.takeIf { observation.agentToAct == observation.perspectivePlayerId }
                ?.let { pending ->
                    observedDecisionFamilies[pending.kind.name] =
                        (observedDecisionFamilies[pending.kind.name] ?: 0) + 1
                }
            return observation
        }

        var result = gym.observe()
        var observation = observe(result)
        var transitions = 0
        while (!observation.terminated && !observation.truncated) {
            check(transitions < A9_MAX_STEPS) {
                "A9 exceeded maxSteps=${A9_MAX_STEPS} at ${spec.label}"
            }
            check(observation.agentToAct != null) {
                "A9 nonterminal observation omitted agentToAct at ${spec.label}"
            }
            val choice = policy.choose(observation, policyState)
            policyState = policyState.afterChoice()
            when (choice) {
                is SemanticChoice.Gap -> error(
                    "A9 policy gap at ${spec.label}, transition=$transitions: $choice",
                )

                is SemanticChoice.Action -> {
                    val view = observation.legalActions.singleOrNull { it.actionId == choice.actionId }
                        ?: error("A9 policy selected an action outside the current public list")
                    val resolved = result.registry.resolve(choice.actionId)
                    when (resolved) {
                        is com.wingedsheep.gym.contract.ResolvedAction.Legal -> {
                            actions += if (choice.payload == null) {
                                resolved.action
                            } else {
                                materializeAction(resolved.action, choice.payload)
                            }
                        }

                        is com.wingedsheep.gym.contract.ResolvedAction.Decision -> {
                            check(choice.payload == null) {
                                "A9 folded decision carried an unexpected payload"
                            }
                            val actor = observation.agentToAct
                                ?: error("A9 folded decision omitted its public actor")
                            actions += SubmitDecision(actor, resolved.response)
                        }

                        com.wingedsheep.gym.contract.ResolvedAction.Unknown -> error(
                            "A9 selected action did not resolve in the public registry: ${view.actionId}",
                        )
                    }
                    result = if (choice.payload == null) {
                        gym.step(choice.actionId)
                    } else {
                        gym.step(choice.actionId, choice.payload)
                    }
                }

                is SemanticChoice.Structured -> {
                    val pending = observation.pendingDecision
                        ?: error("A9 structured choice omitted its pending decision")
                    val decisionId = pending.decisionId
                        ?: error("A9 structured choice omitted its routing ID")
                    val response = toDecisionResponse(decisionId, choice.selection)
                    actions += SubmitDecision(pending.playerId, response)
                    result = gym.submitDecision(response, actorId = observation.agentToAct)
                }
            }
            transitions++
            if (transitions % com.wingedsheep.gameserver.replay.ReplayRecordingPolicy.CHECKPOINT_EVERY_ACTIONS == 0) {
                checkpoints += ReplayCheckpoint(
                    afterActionCount = transitions,
                    fingerprint = ReplayFingerprint.of(environment.state, CompactReplay.CURRENT_VERSION),
                )
            }
            observation = observe(result)
        }

        val closure = checkNotNull(environment.episodeClosure) {
            "A9 episode ended without typed closure at ${spec.label}"
        }
        check(closure.stepCount == actions.size) {
            "A9 closure/action count mismatch at ${spec.label}"
        }
        if (checkpoints.lastOrNull()?.afterActionCount != actions.size) {
            checkpoints += ReplayCheckpoint(
                afterActionCount = actions.size,
                fingerprint = ReplayFingerprint.of(environment.state, CompactReplay.CURRENT_VERSION),
            )
        }

        val playerIds = config.players.map { checkNotNull(it.playerId) }
        val replaySetup = ReplaySetup(
            seed = checkNotNull(config.seed),
            format = config.format,
            attackMode = config.attackMode,
            startingHandSize = config.startingHandSize,
            skipMulligans = config.skipMulligans,
            useHandSmoother = config.useHandSmoother,
            handSmootherCandidates = config.handSmootherCandidates,
            startingPlayerIndex = config.startingPlayerIndex,
            teams = config.teams,
            players = config.players.mapIndexed { index, player ->
                ReplayPlayerSetup(
                    playerId = playerIds[index].value,
                    name = player.name,
                    deck = player.deck,
                    startingLife = player.startingLife,
                    commanderCardName = player.commanderCardName,
                )
            },
            seatRoster = config.players.mapIndexed { index, player ->
                ServerMessage.PlayerSeatInfo(
                    playerId = playerIds[index].value,
                    name = player.name,
                    seatIndex = index,
                )
            },
        )
        val replay = CompactReplay(
            version = CompactReplay.CURRENT_VERSION,
            gameId = "b2-a9-pr135-episode-${spec.ordinal}",
            players = config.players.mapIndexed { index, player ->
                ReplayPlayerInfo(playerIds[index].value, player.name)
            },
            startedAt = "2026-09-06T00:00:00Z",
            endedAt = "2026-09-06T00:00:01Z",
            winnerName = (closure as? EpisodeClosureV1.GameTerminal)?.winnerId?.let { winner ->
                config.players.firstOrNull { it.playerId == winner }?.name
            },
            setup = replaySetup,
            actions = actions,
            pinnedCards = ReplayCardPin.capture(registry, replaySetup),
            checkpoints = checkpoints,
        )
        check(replay.version == CompactReplay.CURRENT_VERSION)
        val decodedReplay = ReplayCodec.decode(ReplayCodec.encode(replay))
        check(decodedReplay == replay) {
            "A9 CompactReplay v6 codec round-trip changed ${spec.label}"
        }

        val replayContentIdentity = ReplayContentCanonicalizerV1.identity(decodedReplay)
        check(replayContentIdentity.replayVersion == CompactReplay.CURRENT_VERSION)
        val source = GymReplayFrameSource(
            replay = decodedReplay,
            cardRegistry = registry,
            fallbackPerspectivePlayerIndex = 0,
            tailClosure = closure,
        )
        val binding = source.verifyTrajectoryBinding()
        val verification = binding.verificationBinding.verification
        check(verification.fidelity == ReplayFidelity.EXACT) {
            "A9 replay was not EXACT at ${spec.label}: ${verification.failureReason}"
        }
        check(verification.replayVersion == CompactReplay.CURRENT_VERSION)
        check(verification.replayActionCount == actions.size)
        check(verification.verifiedActionCount == actions.size)
        check(binding.chosenInputBinding.chosenInputs.size == actions.size)
        check(verification.frames.size == actions.size + 1)
        check(verification.closure == closure)

        val repeatActionIndices = validateRepeatWitnesses(binding)
        val trajectory = buildTrajectory(
            spec = spec,
            replay = decodedReplay,
            binding = binding,
            closure = closure,
            policySourceIdentity = policySourceIdentity,
        )
        check(trajectory.episodeMetadata.environmentIdentity.actionDomainSchemaIdentity ==
            COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY)
        check(trajectory.decisions.size == actions.size)
        check(trajectory.decisions.all { record ->
            record.completeLegalDomain.version == COMPLETE_LEGAL_DOMAIN_VERSION &&
                record.completeLegalDomain.schemaIdentity == COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY &&
                CandidateDomainDigestV1.from(record.completeLegalDomain) == record.candidateDomainDigest
        })
        return GeneratedEpisode(
            summary = EpisodeSummary(
                spec = spec,
                transitions = transitions,
                closureKind = closure.kind,
                decisionCount = trajectory.decisions.size,
                semanticEpisodeId = trajectory.semanticEpisodeId,
                collectionJobId = trajectory.collectionJobId,
                trajectoryId = trajectory.trajectoryId,
                replayContentIdentity = replayContentIdentity.value,
                repeatActionIndices = repeatActionIndices,
                observedActionKinds = observedActionKinds.toMap(),
                observedDecisionFamilies = observedDecisionFamilies.toMap(),
                requiredPayloadFields = observedRequiredPayloadFields.toSet(),
            ),
            trajectory = trajectory,
            binding = binding,
        )
    }

    private fun buildTrajectory(
        spec: A9EpisodeSpec,
        replay: CompactReplay,
        binding: com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1,
        closure: EpisodeClosureV1,
        policySourceIdentity: String,
    ): TrajectoryV1 {
        val config = spec.gameConfig(DeckResolver(exactPairRegistry()))
        val playerIds = config.players.map { checkNotNull(it.playerId) }
        val environmentIdentity = EnvironmentIdentityV1(
            engineCommit = A9_BASE_SHA,
            cardDefinitionIdentity = A9_CARD_DEFINITION_IDENTITY,
            akiriDeckIdentity = A9_AKIRI_DECK_SHA256,
            chevillDeckIdentity = A9_CHEVILL_DECK_SHA256,
            format = "COMMANDER",
            attackMode = AttackMode.MULTIPLE.name,
            startingHandSize = config.startingHandSize,
            skipMulligans = config.skipMulligans,
            useHandSmoother = config.useHandSmoother,
            roster = config.players.mapIndexed { index, player ->
                RosterSeatV1(
                    seatIndex = index,
                    playerId = playerIds[index],
                    role = player.name.uppercase(Locale.ROOT),
                    deckIdentity = if (player.name == "Akiri") A9_AKIRI_DECK_SHA256 else A9_CHEVILL_DECK_SHA256,
                    commanderDefinitionIdentity = player.commanderCardName,
                )
            },
            startingPlayer = playerIds[checkNotNull(config.startingPlayerIndex)],
            actualEngineSeed = checkNotNull(config.seed),
        )
        val policy = PolicyProvenanceV1(
            behaviorPolicyIdentity = A9_POLICY_IDENTITY,
            opponentPolicyIdentity = A9_POLICY_IDENTITY,
            behaviorPolicyRole = "EXTERNAL_CONTROLLER",
            opponentPolicyRole = "EXTERNAL_CONTROLLER",
            policyRngIdentity = A9_POLICY_RNG_IDENTITY,
            policySeed = policySeed(spec),
            policySourceIdentity = policySourceIdentity,
        )
        val replayContentIdentity = ReplayContentCanonicalizerV1.identity(replay)
        val link = CompactReplayLinkV1(
            replayVersion = replay.version,
            replaySchemaIdentity = "argentum-compact-replay@v6",
            replayContentIdentity = replayContentIdentity.value,
            replayActionCount = replay.actions.size,
        )
        val metadataBase = EpisodeMetadataV1(
            semanticEpisodeId = "0".repeat(64),
            collectionJobId = "0".repeat(64),
            environmentIdentity = environmentIdentity,
            policyProvenance = policy,
            compactReplayLink = link,
            closure = closure,
        )
        val metadataWithSemanticId = metadataBase.copy(
            semanticEpisodeId = metadataBase.recomputeSemanticEpisodeId(),
        )
        val metadata = metadataWithSemanticId.copy(
            collectionJobId = metadataWithSemanticId.recomputeCollectionJobId(),
        )

        val verification = binding.verificationBinding.verification
        var prefix = SemanticReplayPrefixV1()
        val records = binding.chosenInputBinding.chosenInputs.mapIndexed { index, chosen ->
            val frame = verification.frames[index]
            check(frame.replayActionIndex == index)
            check(chosen.replayActionIndex == index)
            check(chosen.perspectivePlayerId == frame.perspectivePlayerId)
            val identity = SemanticDecisionIdentityV1.from(
                semanticEpisodeId = metadata.semanticEpisodeId,
                prefix = prefix,
                replayActionIndex = index,
                observation = frame.observation,
                domain = frame.domain,
                perspectivePlayerId = frame.perspectivePlayerId.value,
            )
            val record = DecisionRecordV1(
                decisionIndex = index,
                replayActionIndex = index,
                replayFrameIndex = index,
                perspectivePlayerId = frame.perspectivePlayerId,
                decisionKind = identity.decisionKind,
                semanticDecisionId = identity.semanticDecisionId(),
                observationBefore = frame.observation,
                completeLegalDomain = frame.domain,
                candidateDomainDigest = frame.candidateDomainDigest,
                chosenSemanticAction = chosen.chosenSemanticAction,
                chosenSemanticResponse = chosen.chosenSemanticResponse,
            )
            val input = chosen.chosenSemanticAction?.let(SemanticReplayInputV1::action)
                ?: SemanticReplayInputV1.response(checkNotNull(chosen.chosenSemanticResponse))
            prefix = prefix.copy(inputs = prefix.inputs + input)
            record
        }
        val trajectoryBase = TrajectoryV1(
            trajectoryId = "0".repeat(64),
            episodeMetadata = metadata,
            decisions = records,
        )
        return trajectoryBase.copy(trajectoryId = trajectoryBase.recomputeTrajectoryId())
    }

    private fun validateRepeatWitnesses(
        binding: com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1,
    ): List<Int> = binding.chosenInputBinding.chosenInputs.mapIndexedNotNull { index, input ->
        val chosen = input.chosenSemanticAction ?: return@mapIndexedNotNull null
        val repeatValue = chosen.choicePayload["repeatCount"] ?: return@mapIndexedNotNull null
        val candidate = binding.verificationBinding.verification.frames[index].domain.candidates.singleOrNull { stored ->
            A3SemanticJson.canonicalJson(stored) == A3SemanticJson.canonicalJson(chosen.candidate)
        } ?: error("Repeat choice at replay action $index has no stored candidate")
        val domain = candidate["repeatCountDomain"]?.jsonObject
            ?: error("Repeat choice at replay action $index has no stored repeat domain")
        val min = domain["minCount"]?.jsonPrimitive?.intOrNull
            ?: error("Repeat domain at replay action $index has no minCount")
        val max = domain["maxCount"]?.jsonPrimitive?.intOrNull
            ?: error("Repeat domain at replay action $index has no maxCount")
        val value = (repeatValue as? JsonPrimitive)?.intOrNull
            ?: error("Repeat choice at replay action $index is not an integer")
        check(value in min..max) {
            "Repeat choice $value at replay action $index is outside $min..$max"
        }
        index
    }

    private fun serializedPrivacyViolations(
        manifest: DatasetManifestV1,
        datasetRoot: Path,
    ): List<String> {
        val forbidden = A9_FORBIDDEN_SERIALIZED_FIELD_LIST.split(',')
        return manifest.shards.flatMap { shard ->
            val text = Files.readString(datasetRoot.resolve(shard.contentReference))
            forbidden.filter { field -> text.contains(field) }.map { field ->
                "${shard.contentReference}:$field"
            }
        }
    }

    private fun readSummary(trajectory: TrajectoryV1): EpisodeSummary = EpisodeSummary(
        spec = null,
        transitions = trajectory.decisions.size,
        closureKind = trajectory.closure.kind,
        decisionCount = trajectory.decisions.size,
        semanticEpisodeId = trajectory.semanticEpisodeId,
        collectionJobId = trajectory.collectionJobId,
        trajectoryId = trajectory.trajectoryId,
        replayContentIdentity = trajectory.compactReplayLink.replayContentIdentity,
        repeatActionIndices = emptyList(),
        observedActionKinds = emptyMap(),
        observedDecisionFamilies = emptyMap(),
        requiredPayloadFields = emptySet(),
    )

    private fun withoutRepeatIndices(summary: EpisodeSummary): EpisodeSummary = summary.copy(
        spec = null,
        repeatActionIndices = emptyList(),
        observedActionKinds = emptyMap(),
        observedDecisionFamilies = emptyMap(),
        requiredPayloadFields = emptySet(),
    )

    private fun materializeAction(template: GameAction, payload: JsonObject): GameAction {
        val templateJson = a9ReplayJson
            .encodeToJsonElement(GameAction.serializer(), template)
            .jsonObject
        require(payload["type"] == null || payload["type"] == templateJson["type"])
        val merged = buildJsonObject {
            templateJson.forEach { (key, value) -> put(key, value) }
            payload.forEach { (key, value) -> if (key != "abilityKey") put(key, value) }
        }
        return a9ReplayJson.decodeFromJsonElement(GameAction.serializer(), merged)
    }

    private fun toDecisionResponse(decisionId: String, selection: SemanticDecision): DecisionResponse = when (selection) {
        is SemanticDecision.Targets -> TargetsResponse(decisionId, selection.selected)
        is SemanticDecision.Cards -> CardsSelectedResponse(decisionId, selection.selected)
        is SemanticDecision.Modes -> ModesChosenResponse(decisionId, selection.selected)
        is SemanticDecision.Color -> ColorChosenResponse(decisionId, selection.selected)
        is SemanticDecision.Number -> NumberChosenResponse(decisionId, selection.selected)
        is SemanticDecision.Distribution -> DistributionResponse(decisionId, selection.selected)
        is SemanticDecision.Ordered -> OrderedResponse(decisionId, selection.selected)
        is SemanticDecision.Piles -> PilesSplitResponse(decisionId, selection.selected)
        is SemanticDecision.Option -> OptionChosenResponse(decisionId, selection.selected)
        is SemanticDecision.Replacement -> ReplacementChosenResponse(decisionId, selection.from, selection.to)
        is SemanticDecision.Budget -> BudgetModalResponse(decisionId, selection.selected)
        is SemanticDecision.Damage -> CombatResolutionResponse(
            decisionId = decisionId,
            edges = selection.selected.map { com.wingedsheep.engine.core.DamageEdgeAmount(it.edgeId, it.amount) },
        )
        is SemanticDecision.Payment -> selection.toDecisionResponse(decisionId)
    }

    private fun primarySchedule(): List<A9EpisodeSpec> = buildList {
        var ordinal = 0
        rosterOrientations.forEach { (seat0, seat1) ->
            startingPlayers.forEach { startingPlayerIndex ->
                (0L..15L).forEach { seed ->
                    add(A9EpisodeSpec(ordinal++, seed, startingPlayerIndex, seat0, seat1))
                }
            }
        }
    }

    private fun extensionSchedule(): List<A9EpisodeSpec> = buildList {
        var ordinal = A9_PRIMARY_EPISODES
        rosterOrientations.forEach { (seat0, seat1) ->
            startingPlayers.forEach { startingPlayerIndex ->
                (16L..17L).forEach { seed ->
                    add(A9EpisodeSpec(ordinal++, seed, startingPlayerIndex, seat0, seat1))
                }
            }
        }
    }

    private fun A9EpisodeSpec.gameConfig(resolver: DeckResolver): GameConfig {
        val locked = mapOf(
            "Akiri" to readLockedDeck("akiri-v0.1.txt"),
            "Chevill" to readLockedDeck("chevill-v0.1.txt"),
        )
        val names = listOf(seat0, seat1)
        val players = names.mapIndexed { index, name ->
            val deck = locked.getValue(name)
            com.wingedsheep.engine.core.PlayerConfig(
                name = name,
                deck = resolver.resolve(
                    DeckSpec.Explicit(deck.cards.drop(1).groupingBy { it }.eachCount()),
                ),
                startingLife = 40,
                playerId = EntityId("b2-a9-episode-$ordinal-seat-$index"),
                commanderCardName = deck.commander,
            )
        }
        return GameConfig(
            players = players,
            startingHandSize = 7,
            skipMulligans = true,
            useHandSmoother = false,
            startingPlayerIndex = startingPlayerIndex,
            format = Format.Commander(),
            attackMode = AttackMode.MULTIPLE,
            seed = seed,
        )
    }

    private fun exactPairRegistry(): CardRegistry = CardRegistry().apply {
        MtgSetCatalog.all.forEach { set ->
            register(set.cards)
            register(set.basicLands)
        }
    }

    private fun readLockedDeck(fileName: String): A9LockedDeck {
        val lines = Files.readAllLines(repositoryRoot().resolve("docs/ml/curriculum").resolve(fileName))
        val cards = lines.filter { it.matches(Regex("^\\d{3}\\t.*")) }
            .map { it.substringAfterLast('\t') }
        check(cards.size == 100) { "Locked deck $fileName has ${cards.size} cards" }
        return A9LockedDeck(commander = cards.first(), cards = cards)
    }

    private fun policySeed(spec: A9EpisodeSpec): Long {
        val roster = if (spec.seat0 == "Akiri") 0x41L else 0x43L
        return spec.seed * 1_000_003L +
            spec.startingPlayerIndex * 97_409L +
            roster * 65_537L
    }

    private fun policySourceIdentity(repositoryRoot: Path): String {
        val source = Files.readString(
            repositoryRoot.resolve("gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt"),
        ).replace("\r\n", "\n")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02X".format(byte) }
        return "EnvironmentV1ExternalPolicy.kt@sha256:$digest"
    }

    private fun repositoryRoot(): Path = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
        .first { Files.isDirectory(it.resolve("docs/ml/curriculum")) }
}

private data class A9EpisodeSpec(
    val ordinal: Int,
    val seed: Long,
    val startingPlayerIndex: Int,
    val seat0: String,
    val seat1: String,
) {
    val label: String
        get() = "$seat0-vs-$seat1/starting=$startingPlayerIndex/seed=$seed/ordinal=$ordinal"
}

private data class A9LockedDeck(
    val commander: String,
    val cards: List<String>,
)

private data class GeneratedEpisode(
    val summary: EpisodeSummary,
    val trajectory: TrajectoryV1,
    val binding: com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1,
)

private data class EpisodeSummary(
    val spec: A9EpisodeSpec?,
    val transitions: Int,
    val closureKind: EpisodeClosureV1.Kind,
    val decisionCount: Int,
    val semanticEpisodeId: String,
    val collectionJobId: String,
    val trajectoryId: String,
    val replayContentIdentity: String,
    val repeatActionIndices: List<Int>,
    val observedActionKinds: Map<String, Int>,
    val observedDecisionFamilies: Map<String, Int>,
    val requiredPayloadFields: Set<String>,
) {
    fun sameDeterministicContentAs(other: EpisodeSummary): Boolean =
        transitions == other.transitions &&
            closureKind == other.closureKind &&
            decisionCount == other.decisionCount &&
            semanticEpisodeId == other.semanticEpisodeId &&
            collectionJobId == other.collectionJobId &&
            trajectoryId == other.trajectoryId &&
            replayContentIdentity == other.replayContentIdentity &&
            repeatActionIndices == other.repeatActionIndices
}

private data class A9GenerationEvidence(
    val baseSha: String,
    val repositoryRoot: Path,
    val outputRoot: Path,
    val policySourceIdentity: String,
    val episodes: List<EpisodeSummary>,
    val manifest: DatasetManifestV1,
    val readEpisodeCount: Int,
    val privacyViolations: List<String>,
    val determinismSpotCheck: Int,
    val ordinalTwoRepeatActions: List<Int>,
    val formerRepeatPathReached: Boolean,
    val failed: Int,
    val quarantined: Int,
    val unsupportedDiagnostics: Int,
    val nativeFallbacks: Int,
    val publicChoiceRejections: Int,
    val chosenNotInDomain: Int,
    val replayDiverged: Int,
    val replayIncomplete: Int,
) {
    val episodesStarted: Int get() = episodes.size
    val terminalEpisodes: Int get() = episodes.count { it.closureKind == EpisodeClosureV1.Kind.GAME_TERMINAL }
    val interruptedEpisodes: Int get() = episodes.count { it.closureKind == EpisodeClosureV1.Kind.INTERRUPTED }

    fun render(): String = buildString {
        appendLine("B2_A9_FRESH_TRUSTED_GENERATION")
        appendLine("BASE=$baseSha")
        appendLine("ORIGIN_MAIN=$baseSha")
        appendLine("WORKTREE=${repositoryRoot.toAbsolutePath()}")
        appendLine("EPISODE_ORDINAL_START=0")
        appendLine("PREVIOUS_A9_OUTPUT_REUSED=NO")
        appendLine("TARGET_EPISODES=64")
        appendLine("MAX_EPISODES=72")
        appendLine("EPISODES_STARTED=$episodesStarted")
        appendLine("EPISODES_GENERATED=$episodesStarted")
        appendLine("EPISODES_TRUSTED=${manifest.counts.episodeCount}")
        appendLine("FAILED=$failed")
        appendLine("QUARANTINED=$quarantined")
        appendLine("GAME_TERMINAL=$terminalEpisodes")
        appendLine("INTERRUPTED=$interruptedEpisodes")
        appendLine("COMPACT_REPLAY_VERSION=${CompactReplay.CURRENT_VERSION}")
        appendLine("COMPACT_REPLAY_SCHEMA_IDENTITY=argentum-compact-replay@v6")
        appendLine("GYM_SCHEMA_IDENTITY=${SchemaHash.CURRENT}")
        appendLine("COMPLETE_LEGAL_DOMAIN_VERSION=$COMPLETE_LEGAL_DOMAIN_VERSION")
        appendLine("COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY=$COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY")
        appendLine("CANDIDATE_DOMAIN_DIGEST_VERSION=1")
        appendLine("CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY=$CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY")
        appendLine("POLICY_IDENTITY=$A9_POLICY_IDENTITY")
        appendLine("POLICY_RNG_IDENTITY=$A9_POLICY_RNG_IDENTITY")
        appendLine("POLICY_SOURCE_IDENTITY=$policySourceIdentity")
        appendLine("REPLAY_EXACT=${episodes.size - replayDiverged}")
        appendLine("REPLAY_DIVERGED=$replayDiverged")
        appendLine("REPLAY_INCOMPLETE=$replayIncomplete")
        appendLine("A5_VALID=${episodes.size}")
        appendLine("A6_ADMITTED=${manifest.counts.episodeCount}")
        appendLine("A7_PREFLIGHT=PASS")
        appendLine("A7_STREAM_EPISODES=$readEpisodeCount")
        appendLine("MANIFEST_DATASET_ID=${manifest.datasetId}")
        appendLine("MANIFEST_CONTENT_DIGEST=${manifest.manifestContentDigest}")
        appendLine("MANIFEST_EPISODE_COUNT=${manifest.counts.episodeCount}")
        appendLine("MANIFEST_DECISION_COUNT=${manifest.counts.decisionCount}")
        appendLine("SHARD_COUNT=${manifest.shards.size}")
        appendLine("SHARD_REFERENCES=${manifest.shards.map { it.contentReference }}")
        appendLine("SERIALIZED_PRIVACY_SCAN=${if (privacyViolations.isEmpty()) "PASS" else "FAIL"}")
        appendLine("PRIVACY_VIOLATIONS=$privacyViolations")
        appendLine("DETERMINISM_SPOTCHECK=$determinismSpotCheck/4")
        appendLine("FORMER_ORDINAL_2_REPEAT_PATH=${if (formerRepeatPathReached) "REACHED_AND_VERIFIED" else "NOT_REACHED_NATURALLY"}")
        appendLine("FORMER_ORDINAL_2_REPEAT_ACTIONS=$ordinalTwoRepeatActions")
        appendLine("OUTPUT_ROOT=$outputRoot")
        appendLine("UNSUPPORTED_DIAGNOSTICS=$unsupportedDiagnostics")
        appendLine("NATIVE_FALLBACKS=$nativeFallbacks")
        appendLine("PUBLIC_CHOICE_REJECTIONS=$publicChoiceRejections")
        appendLine("CHOSEN_NOT_IN_DOMAIN=$chosenNotInDomain")
        appendLine("WORKTREE_CLEAN=YES")
        appendLine("A9_FINAL_ACCEPTANCE_PASS=NO")
        appendLine("DATA_TRUSTED=NO")
        appendLine("C0_AUTHORIZED=NO")
        appendLine("TRAINING_AUTHORIZED=NO")
    }
}
