package com.wingedsheep.gym

import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.ActionRegistry
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.ChosenSemanticActionV1
import com.wingedsheep.gym.contract.ChosenSemanticResponseV1
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.ObservationCanonicalizer
import com.wingedsheep.gym.contract.ObservationResult
import com.wingedsheep.gym.contract.OrderingDomain
import com.wingedsheep.gym.contract.PlayerObservationV1
import com.wingedsheep.gym.contract.ReplayChosenInputV1
import com.wingedsheep.gym.contract.ReplayContentIdentityV1
import com.wingedsheep.gym.contract.ReplayFidelity
import com.wingedsheep.gym.contract.ReplayTrajectoryBindingV1
import com.wingedsheep.gym.contract.ResolvedAction
import com.wingedsheep.gym.contract.SchemaHash
import com.wingedsheep.gym.contract.StructuredCardInfo
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.service.DeckResolver
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.gym.trainer.actor.ActorEpisodeExecutor
import com.wingedsheep.gym.trainer.actor.ActorEpisodeOutcome
import com.wingedsheep.gym.trainer.actor.ActorStateV1
import com.wingedsheep.gym.trainer.actor.AtomicActorStatusFileSink
import com.wingedsheep.gym.trainer.actor.ExecutionAttemptIdentityV1
import com.wingedsheep.gym.trainer.actor.FileStoreStorageCapacityProbeV1
import com.wingedsheep.gym.trainer.actor.LocalActorExecutionRequestV1
import com.wingedsheep.gym.trainer.actor.LocalActorExecutionV1
import com.wingedsheep.gym.trainer.actor.LocalPublicationEnvelopeV1
import com.wingedsheep.gym.trainer.actor.LocalPublishedEnvelopeV1
import com.wingedsheep.gym.trainer.actor.MeasuredStoragePreflightV1
import com.wingedsheep.gym.trainer.actor.OfflineAdmissionRequestV1
import com.wingedsheep.gym.trainer.actor.OfflineReplayFailureCodeV1
import com.wingedsheep.gym.trainer.actor.OfflineReplayVerificationResultV1
import com.wingedsheep.gym.trainer.actor.OfflineAdmissionV1
import com.wingedsheep.gym.trainer.actor.OfflineReplayVerifierV1
import com.wingedsheep.gym.trainer.actor.ReimportedLocalPublicationEnvelopeV1
import com.wingedsheep.gym.trainer.actor.StoragePreflightConfigurationV1
import com.wingedsheep.gym.trainer.actor.TrajectoryV1B2Sink
import com.wingedsheep.gym.trainer.actor.WorkAssignmentV1
import com.wingedsheep.gym.trainer.actor.WorkItemV1
import com.wingedsheep.gym.trainer.actor.WorkloadJobV1
import com.wingedsheep.gym.trainer.actor.WorkloadPlanV1
import com.wingedsheep.gym.trainer.trajectory.CompactReplayLinkV1
import com.wingedsheep.gym.trainer.trajectory.DatasetManifestV1
import com.wingedsheep.gym.trainer.trajectory.DatasetMetadataV1
import com.wingedsheep.gym.trainer.trajectory.DecisionRecordV1
import com.wingedsheep.gym.trainer.trajectory.EnvironmentIdentityV1
import com.wingedsheep.gym.trainer.trajectory.EpisodeMetadataV1
import com.wingedsheep.gym.trainer.trajectory.PolicyProvenanceV1
import com.wingedsheep.gym.trainer.trajectory.RosterSeatV1
import com.wingedsheep.gym.trainer.trajectory.SemanticDecisionIdentityV1
import com.wingedsheep.gym.trainer.trajectory.SemanticReplayInputV1
import com.wingedsheep.gym.trainer.trajectory.SemanticReplayPrefixV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryAdmissionResult
import com.wingedsheep.gym.trainer.trajectory.TrajectoryValidationResult
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Validator
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Admission
import com.wingedsheep.gym.trainer.trajectory.TrajectoryV1Writer
import com.wingedsheep.gameserver.curriculum.CurriculumAiTournamentPreset
import com.wingedsheep.gameserver.curriculum.CurriculumDeckSourceLoader
import com.wingedsheep.gameserver.curriculum.CurriculumDeckSourceV1
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.replay.CompactReplay
import com.wingedsheep.gameserver.replay.GymReplayFrameSource
import com.wingedsheep.gameserver.replay.ReplayCardPin
import com.wingedsheep.gameserver.replay.ReplayCheckpoint
import com.wingedsheep.gameserver.replay.ReplayCodec
import com.wingedsheep.gameserver.replay.ReplayContentCanonicalizerV1
import com.wingedsheep.gameserver.replay.ReplayFingerprint
import com.wingedsheep.gameserver.replay.ReplayPlayerInfo
import com.wingedsheep.gameserver.replay.ReplayPlayerSetup
import com.wingedsheep.gameserver.replay.ReplayRecordingPolicy
import com.wingedsheep.gameserver.replay.ReplaySetup
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.serialization.CardSerialization
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Producer-facing knobs for the KA06 characterization. */
internal const val KA06_JOB_COUNT = 1
internal const val KA06_MAX_STEPS = 40
internal const val KA06_SEAT0_PLAYER_ID = "ka06-seat-0"
internal const val KA06_SEAT1_PLAYER_ID = "ka06-seat-1"
internal const val KA06_POLICY_IDENTITY = "ka06-transported-replay-reference-policy@v1"
internal const val KA06_POLICY_RNG_IDENTITY = "explicit-seed/kotlin-policy-state-v1"
internal const val KA06_COMMANDER_STARTING_LIFE = 40
internal const val KA06_LINK_REPLAY_SCHEMA_IDENTITY = "argentum-compact-replay@v6"

/**
 * KA06 transported-replay reconstruction authority characterization harness.
 *
 * PROCESS A (the test JVM) is the producer: one deterministic local episode is generated through
 * the accepted trusted Gym/Environment V1 path, verified through the real A4 fold, admitted
 * through the real A6 gate, published through the real B2 writer, and finalized through the real
 * [LocalPublicationEnvelopeV1] publication envelope.
 *
 * PROCESS B is a fresh JVM launched via [KaggleActor06VerifierWorkerMain]. It receives only the
 * envelope directory path, re-imports the durable bytes, re-derives the environment identity from
 * repository authority, rebuilds the replay from the durable identity plus ordered transported
 * semantic choices, rebinds every choice against the CURRENT reconstructed boundaries, and
 * independently produces a new A4 verification. No producer in-memory object crosses the
 * boundary; the claimant observation/domain fields are compared against regenerated values only.
 */
internal object KaggleActor06TransportedReplayHarness {

    // ------------------------------------------------------------------
    // Producer: durable transported fixture (process A)
    // ------------------------------------------------------------------

    /** One produced transported fixture with only durable facts retained. */
    internal class Transport(
        repositoryRoot: Path = ka06RepositoryRoot(),
    ) {
        lateinit var envelopeDirectory: Path
        lateinit var plan: WorkloadPlanV1

        /** Durable claimant values the spec may compare against verifier output. */
        val claimantSemanticEpisodeId: String
        val claimantTrajectoryId: String
        val claimantReplayContentIdentity: String
        val claimantReplayActionCount: Int

        /** Producer in-memory objects; never passed to or rendered by the verifier. */
        internal val producerTrajectory: TrajectoryV1
        internal val producerBinding: ReplayTrajectoryBindingV1

        init {
            val registry = ka06ExactPairRegistry()
            val loader = CurriculumDeckSourceLoader(repositoryRoot)
            val akiri = loader.load(CurriculumAiTournamentPreset.AKIRI_CHEVILL.sourcePaths[0])
            val chevill = loader.load(CurriculumAiTournamentPreset.AKIRI_CHEVILL.sourcePaths[1])
            // The publication path requires verified source-revision evidence: the durable engine
            // commit must be the real runtime HEAD (section 21 uses the current source revision).
            val engineCommit = checkNotNull(ka06GitHead(repositoryRoot)) {
                "KA06 producer requires the repository HEAD to derive the durable engine commit"
            }
            val plan = ka06WorkloadPlan(engineCommit, akiri.sourceDigest, chevill.sourceDigest)
            val fullAssignment = WorkAssignmentV1.from(plan, (0 until KA06_JOB_COUNT).toList())
            val item = fullAssignment.items.single()
            val episode = generateEpisode(item, registry, akiri, chevill)

            val metadata = DatasetMetadataV1(
                maxShardBytes = 16L * 1024L * 1024L,
                maxEpisodesPerShard = 1,
            )
            val producerOutputRoot = Files.createTempDirectory("ka06-producer-b2-")
            val publicationRoot = Files.createTempDirectory("ka06-publication-")
            val run = LocalActorExecutionV1.run(
                LocalActorExecutionRequestV1(
                    assignment = fullAssignment,
                    executionAttemptIdentity = ExecutionAttemptIdentityV1("ka06-producer-attempt"),
                    sourceRepositoryRoot = repositoryRoot,
                    preflight = {
                        MeasuredStoragePreflightV1(
                            probe = FileStoreStorageCapacityProbeV1(),
                            workingRoot = publicationRoot,
                            scratchRoot = producerOutputRoot,
                            configuration = StoragePreflightConfigurationV1(
                                estimatedFinalizedOutputBytes = 4L * 1024L * 1024L,
                                requiredAdditionalScratchBytes = 4L * 1024L * 1024L,
                                publicationOverheadBytes = 1L * 1024L * 1024L,
                                workingSafetyReserveBytes = 1L * 1024L * 1024L,
                                providerSafetyReserveBytes = 1L * 1024L * 1024L,
                                configuredProviderOutputCapBytes = 1L * 1024L * 1024L * 1024L,
                            ),
                        ).evaluate()
                    },
                    episodeExecutor = ActorEpisodeExecutor {
                        ActorEpisodeOutcome.Completed(
                            trajectory = episode.trajectory,
                            replayTrajectoryBinding = episode.binding,
                        )
                    },
                    sinkFactory = {
                        TrajectoryV1B2Sink(TrajectoryV1Writer(producerOutputRoot, metadata))
                    },
                    statusSink = AtomicActorStatusFileSink(
                        producerOutputRoot.resolve("status-final.json"),
                    ),
                    requestedConcurrency = 1,
                    actualConcurrency = 1,
                )
            )
            check(run.status.state == ActorStateV1.FINALIZED_LOCAL) {
                "KA06 producer actor did not finalize locally: ${run.status.state}"
            }
            val runnerManifest = checkNotNull(run.manifest)
            val producerDatasetRoot = producerOutputRoot.resolve("dataset-${runnerManifest.datasetId}")
            val published: LocalPublishedEnvelopeV1 = LocalPublicationEnvelopeV1.publish(
                sourceDatasetDirectory = producerDatasetRoot,
                destinationDirectory = publicationRoot,
                assignment = fullAssignment,
                status = run.status,
                report = run.report,
            )
            envelopeDirectory = published.envelopeDirectory
            this.plan = plan
            claimantSemanticEpisodeId = episode.trajectory.semanticEpisodeId
            claimantTrajectoryId = episode.trajectory.trajectoryId
            claimantReplayContentIdentity =
                episode.trajectory.episodeMetadata.compactReplayLink.replayContentIdentity
            claimantReplayActionCount =
                episode.trajectory.episodeMetadata.compactReplayLink.replayActionCount
            producerTrajectory = episode.trajectory
            producerBinding = episode.binding
        }
    }

    internal fun ka06WorkloadPlan(
        engineCommit: String,
        akiriDigest: String,
        chevillDigest: String,
    ): WorkloadPlanV1 = WorkloadPlanV1(
        workloadNamespace = "argentum-ka06-transported-replay-characterization",
        rolloutGeneration = "2026-09-16-v1",
        behaviorPolicyCampaignIdentity = KA06_POLICY_IDENTITY,
        opponentPolicyCampaignIdentity = KA06_POLICY_IDENTITY,
        jobs = (0 until KA06_JOB_COUNT).map { ordinal ->
            WorkloadJobV1(
                jobOrdinal = ordinal,
                environmentIdentity = ka06EnvironmentIdentity(
                    ordinal, engineCommit, akiriDigest, chevillDigest,
                ),
                policyProvenance = ka06PolicyProvenance(ordinal),
            )
        },
    )

    internal fun ka06EnvironmentIdentity(
        ordinal: Int,
        engineCommit: String,
        akiriDigest: String,
        chevillDigest: String,
    ): EnvironmentIdentityV1 {
        val players = listOf(EntityId(KA06_SEAT0_PLAYER_ID), EntityId(KA06_SEAT1_PLAYER_ID))
        return EnvironmentIdentityV1(
            engineCommit = engineCommit,
            cardDefinitionIdentity = ka06LockedCardDefinitionDigestPlaceholder(),
            akiriDeckIdentity = akiriDigest,
            chevillDeckIdentity = chevillDigest,
            format = "COMMANDER",
            attackMode = AttackMode.MULTIPLE.name,
            startingHandSize = 7,
            skipMulligans = true,
            useHandSmoother = false,
            roster = listOf(
                RosterSeatV1(
                    seatIndex = 0,
                    playerId = players[0],
                    role = "AKIRI",
                    deckIdentity = akiriDigest,
                    commanderDefinitionIdentity = KA06_AKIRI_COMMANDER,
                ),
                RosterSeatV1(
                    seatIndex = 1,
                    playerId = players[1],
                    role = "CHEVILL",
                    deckIdentity = chevillDigest,
                    commanderDefinitionIdentity = KA06_CHEVILL_COMMANDER,
                ),
            ),
            startingPlayer = players[0],
            actualEngineSeed = ordinal.toLong(),
        )
    }

    internal fun ka06PolicyProvenance(ordinal: Int): PolicyProvenanceV1 = PolicyProvenanceV1(
        behaviorPolicyIdentity = KA06_POLICY_IDENTITY,
        opponentPolicyIdentity = KA06_POLICY_IDENTITY,
        behaviorPolicyRole = "EXTERNAL_CONTROLLER",
        opponentPolicyRole = "EXTERNAL_CONTROLLER",
        policyRngIdentity = KA06_POLICY_RNG_IDENTITY,
        policySeed = 61_733_007L + ordinal,
        policySourceIdentity = "ka06-transported-replay-reference-policy-source@v1",
    )

    private const val KA06_AKIRI_COMMANDER = "Akiri, Fearless Voyager"
    private const val KA06_CHEVILL_COMMANDER = "Chevill, Bane of Monsters"

    /**
     * Placeholder card-definition identity used only to reserve the identity slot; the producer
     * fills the accepted locked-pair digest before the trajectory identity is recomputed.
     */
    private fun ka06LockedCardDefinitionDigestPlaceholder(): String = "0".repeat(64)

    private data class Ka06Episode(
        val trajectory: TrajectoryV1,
        val binding: ReplayTrajectoryBindingV1,
    )

    /** Accepted KA04/A9-style producer recipe at minimum scale. */
    private fun generateEpisode(
        item: WorkItemV1,
        registry: CardRegistry,
        akiri: CurriculumDeckSourceV1,
        chevill: CurriculumDeckSourceV1,
    ): Ka06Episode {
        val resolver = DeckResolver(registry)
        val akiriDeck = resolver.resolve(DeckSpec.Explicit(akiri.libraryDeckList()))
        val chevillDeck = resolver.resolve(DeckSpec.Explicit(chevill.libraryDeckList()))
        val identity = item.environmentIdentity
        val players = listOf(
            PlayerConfig(
                name = "Akiri",
                deck = akiriDeck,
                startingLife = KA06_COMMANDER_STARTING_LIFE,
                playerId = identity.roster[0].playerId,
                commanderCardName = checkNotNull(identity.roster[0].commanderDefinitionIdentity),
            ),
            PlayerConfig(
                name = "Chevill",
                deck = chevillDeck,
                startingLife = KA06_COMMANDER_STARTING_LIFE,
                playerId = identity.roster[1].playerId,
                commanderCardName = checkNotNull(identity.roster[1].commanderDefinitionIdentity),
            ),
        )
        val config = GameConfig(
            players = players,
            startingHandSize = identity.startingHandSize,
            skipMulligans = identity.skipMulligans,
            useHandSmoother = identity.useHandSmoother,
            startingPlayerIndex = players.indexOfFirst { it.playerId == identity.startingPlayer },
            format = Format.Commander(),
            attackMode = AttackMode.valueOf(identity.attackMode),
            seed = identity.actualEngineSeed,
        )
        val environment = GameEnvironment.create(
            cardRegistry = registry,
            executionMode = GameEnvironmentMode.TRUSTED,
        )
        environment.reset(config, KA06_MAX_STEPS)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
        )
        val policy = DeterministicExternalPolicy()
        var policyState = DeterministicPolicyState(item.policyProvenance.policySeed)
        val actions = mutableListOf<GameAction>()
        val checkpoints = mutableListOf<ReplayCheckpoint>()
        var result = gym.observe()
        var observation = requireKa06TrainingObservation(result)
        var transitions = 0
        while (!observation.terminated && !observation.truncated) {
            require(transitions < KA06_MAX_STEPS) { "KA06 producer exceeded its bounded horizon" }
            val choice = policy.choose(observation, policyState)
            policyState = policyState.afterChoice()
            when (choice) {
                is SemanticChoice.Gap -> error("KA06 producer policy gap: ${choice.code}")
                is SemanticChoice.Action -> {
                    val view = observation.legalActions.singleOrNull { it.actionId == choice.actionId }
                        ?: error("KA06 producer selected an action outside the public domain")
                    val resolved = result.registry.resolve(view.actionId)
                    when (resolved) {
                        is ResolvedAction.Legal -> actions += if (choice.payload == null) {
                            resolved.action
                        } else {
                            ka06MaterializeAction(resolved.action, choice.payload)
                        }

                        is ResolvedAction.Decision -> {
                            require(choice.payload == null)
                            actions += SubmitDecision(
                                checkNotNull(observation.agentToAct),
                                resolved.response,
                            )
                        }

                        ResolvedAction.Unknown -> error("KA06 producer action did not resolve")
                    }
                    result = if (choice.payload == null) {
                        gym.step(choice.actionId)
                    } else {
                        gym.step(choice.actionId, choice.payload)
                    }
                }

                is SemanticChoice.Structured -> {
                    val pending = checkNotNull(observation.pendingDecision)
                    val decisionId = checkNotNull(pending.decisionId)
                    val response = ka06ToDecisionResponse(decisionId, choice.selection)
                    actions += SubmitDecision(pending.playerId, response)
                    result = gym.submitDecision(response, actorId = observation.agentToAct)
                }
            }
            transitions += 1
            if (transitions % ReplayRecordingPolicy.CHECKPOINT_EVERY_ACTIONS == 0) {
                checkpoints += ReplayCheckpoint(
                    afterActionCount = transitions,
                    fingerprint = ReplayFingerprint.of(environment.state, CompactReplay.CURRENT_VERSION),
                )
            }
            observation = requireKa06TrainingObservation(result)
        }
        val closure = checkNotNull(environment.episodeClosure)
        require(closure.stepCount == actions.size)
        if (checkpoints.lastOrNull()?.afterActionCount != actions.size) {
            checkpoints += ReplayCheckpoint(
                afterActionCount = actions.size,
                fingerprint = ReplayFingerprint.of(environment.state, CompactReplay.CURRENT_VERSION),
            )
        }
        val setup = ka06ReplaySetup(config)
        val replay = CompactReplay(
            version = CompactReplay.CURRENT_VERSION,
            gameId = "ka06-job-${item.jobOrdinal}",
            players = config.players.map { player ->
                ReplayPlayerInfo(checkNotNull(player.playerId).value, player.name)
            },
            startedAt = "2026-09-16T00:00:00Z",
            endedAt = "2026-09-16T00:00:01Z",
            winnerName = (closure as? EpisodeClosureV1.GameTerminal)?.winnerId?.let { winner ->
                config.players.firstOrNull { it.playerId == winner }?.name
            },
            setup = setup,
            actions = actions,
            pinnedCards = ReplayCardPin.capture(registry, setup),
            checkpoints = checkpoints,
        )
        val decodedReplay = ReplayCodec.decode(ReplayCodec.encode(replay))
        require(decodedReplay == replay)
        val replayIdentity = ReplayContentCanonicalizerV1.identity(decodedReplay)
        val binding = GymReplayFrameSource(
            replay = decodedReplay,
            cardRegistry = registry,
            fallbackPerspectivePlayerIndex = 0,
            tailClosure = closure,
        ).verifyTrajectoryBinding()
        require(binding.verificationBinding.verification.fidelity == ReplayFidelity.EXACT) {
            "KA06 producer replay was not exact: " +
                binding.verificationBinding.verification.failureReason
        }
        require(binding.chosenInputBinding.chosenInputs.size == actions.size)
        return Ka06Episode(
            trajectory = ka06BuildTrajectory(
                item = item,
                identity = identity,
                replay = decodedReplay,
                replayIdentity = replayIdentity,
                binding = binding,
                closure = closure,
                registry = registry,
                akiri = akiri,
                chevill = chevill,
            ),
            binding = binding,
        )
    }

    // ------------------------------------------------------------------
    // Independent verifier worker (process B entrypoints)
    // ------------------------------------------------------------------

    internal fun ka06VerifierWorkerMain(args: Array<String>) {
        val exitFile = runCatching {
            Path.of(args[2])
        }.getOrElse { error("KA06 verifier worker requires an exit file path") }
        val outcome = runCatching {
            val mode = args.getOrNull(0) ?: error("KA06 verifier worker requires a mode")
            val envelopeDirectory = Path.of(args.getOrElse(1) { error("KA06 verifier worker requires an envelope path") })
            when (mode) {
                "verify" -> ka06VerifyMode(envelopeDirectory)
                "tampered-seed" -> ka06TamperedSeedMode(envelopeDirectory)
                "tampered-choice" -> ka06TamperedChoiceMode(envelopeDirectory)
                "truncated-range" -> ka06TruncatedRangeMode(envelopeDirectory)
                "wrong-environment" -> ka06WrongEnvironmentMode(envelopeDirectory)
                "wrong-replay-identity" -> ka06WrongReplayIdentityMode(envelopeDirectory)
                "admission-probe-verifier" ->
                    ka06AdmissionProbeMode(envelopeDirectory, withVerifier = true)

                "admission-probe-no-proof" ->
                    ka06AdmissionProbeMode(envelopeDirectory, withVerifier = false)

                else -> error("Unsupported KA06 verifier mode: $mode")
            }
        }
        val exitJson = buildJsonObject {
            if (outcome.isSuccess) {
                put("result", outcome.getOrThrow())
            } else {
                put("result", JsonObject(emptyMap()))
                put("workerFailure", outcome.exceptionOrNull()?.message ?: "unknown failure")
            }
        }
        Files.writeString(
            exitFile,
            exitJson.toString(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        if (outcome.isFailure) {
            Runtime.getRuntime().halt(1)
        }
    }

    private fun ka06VerifyMode(envelopeDirectory: Path): JsonObject {
        val verification = verifyTransportedEpisode(envelopeDirectory)
        return buildJsonObject {
            put("status", "VERIFIED")
            put("verifierSemanticEpisodeId", verification.semanticEpisodeId)
            put("verifierTrajectoryId", verification.trajectoryId)
            put("verifierContentIdentity", verification.replayContentIdentity)
            put("verifierReplayActionCount", verification.replayActionCount.toString())
            put("verifierIdentityDigest", verification.environmentIdentityDigest)
            put("verifierObservationDigest", verification.observationDigest)
            put("verifierDomainDigest", verification.domainDigest)
            put("verifierChoiceDigest", verification.choiceDigest)
        }
    }

    /** §20 control A: a tampered durable engine seed must diverge the fresh reconstruction. */
    private fun ka06TamperedSeedMode(envelopeDirectory: Path): JsonObject {
        val episode = readFirstReimportedEpisode(envelopeDirectory)
        val tamperedSeed = episode.identity.actualEngineSeed + 1L
        val rejected = runCatching {
            verifyTransportedEpisode(envelopeDirectory, seedOverride = tamperedSeed)
        }.isFailure
        check(rejected) { "Verification must fail under a tampered engine seed" }
        return singleControl("tampered-seed", "rejected")
    }

    /** §20 control B: one substituted semantic choice must fail the fresh rebinding. */
    private fun ka06TamperedChoiceMode(envelopeDirectory: Path): JsonObject {
        val episode = readFirstReimportedEpisode(envelopeDirectory)
        val actionIndex = episode.trajectory.decisions.indexOfFirst {
            it.chosenSemanticAction != null
        }
        check(actionIndex >= 0) { "Tampered-choice control requires a chosen semantic action" }
        val rejected = runCatching {
            verifyTransportedEpisode(envelopeDirectory, tamperActionIndex = actionIndex)
        }.isFailure
        check(rejected) { "Verification must fail under a substituted semantic choice" }
        return singleControl("tampered-choice", "rejected")
    }

    /** §20 control C: a truncated transported choice range cannot reach factual closure. */
    private fun ka06TruncatedRangeMode(envelopeDirectory: Path): JsonObject {
        val episode = readFirstReimportedEpisode(envelopeDirectory)
        val rejected = runCatching {
            verifyTransportedEpisode(envelopeDirectory, truncateTrailingChoices = 1)
        }.isFailure
        check(rejected) { "Verification must fail for a truncated choice range" }
        return singleControl("truncated-range", "rejected")
    }

    /** §20 control D: a wrong environment identity is rejected before any EXACT claim. */
    private fun ka06WrongEnvironmentMode(envelopeDirectory: Path): JsonObject {
        val episode = readFirstReimportedEpisode(envelopeDirectory)
        val wrongIdentity = episode.identity.copy(cardDefinitionIdentity = "F".repeat(64))
        val derived = runCatching { deriveEnvironmentIdentity(wrongIdentity) }.getOrNull()
        check(derived != wrongIdentity) {
            "Wrong-environment control must not accept a mismatched card-definition identity"
        }
        return singleControl("wrong-environment", "rejected-before-exact")
    }

    /** §20 control E: a wrong claimant replay content identity must block the verifier. */
    private fun ka06WrongReplayIdentityMode(envelopeDirectory: Path): JsonObject {
        val episode = readFirstReimportedEpisode(envelopeDirectory)
        val wrongIdentity = ka06DigestText("ka06-wrong-replay-content-identity")
        val rejected = runCatching {
            verifyTransportedEpisode(envelopeDirectory, contentIdentityOverride = wrongIdentity)
        }.isFailure
        check(rejected) { "Verification must fail for a wrong replay content identity" }
        return singleControl("wrong-replay-identity", "rejected")
    }

    /** §23 test-only OfflineAdmissionV1 probe with and without an independent verifier. */
    private fun ka06AdmissionProbeMode(
        envelopeDirectory: Path,
        withVerifier: Boolean,
    ): JsonObject {
        val reimported = LocalPublicationEnvelopeV1.reimport(envelopeDirectory)
        val verifier: OfflineReplayVerifierV1? = if (withVerifier) {
            val verifierLambda = OfflineReplayVerifierV1 { trajectory ->
                val verification = verifyTransportedEpisode(envelopeDirectory)
                val freshBinding = verification.freshBinding
                if (freshBinding != null) {
                    require(verification.semanticEpisodeId == trajectory.semanticEpisodeId) {
                        "Admission verifier verified a different semantic episode"
                    }
                    OfflineReplayVerificationResultV1.Verified(freshBinding)
                } else {
                    OfflineReplayVerificationResultV1.Unavailable(
                        OfflineReplayFailureCodeV1.REPLAY_NOT_EXACT,
                    )
                }
            }
            verifierLambda
        } else {
            null
        }
        val admission = OfflineAdmissionV1.admit(
            OfflineAdmissionRequestV1(
                assignment = reimported.assignment,
                sources = listOf(reimported),
                replayVerifier = verifier,
            ),
        )
        return buildJsonObject {
            put("status", admission.offlineReplayReverification.name)
            put("datasetEligible", admission.datasetEligible.toString())
            put("acceptedCount", admission.acceptedSources.size.toString())
        }
    }

    private fun singleControl(name: String, result: String): JsonObject = buildJsonObject {
        put("control", name)
        put("result", result)
    }

    // ------------------------------------------------------------------
    // Independent verifier core (fresh process, no producer objects)
    // ------------------------------------------------------------------

    internal class Ka06VerificationReport(
        val semanticEpisodeId: String,
        val trajectoryId: String,
        val replayContentIdentity: String,
        val replayActionCount: Int,
        val environmentIdentityDigest: String,
        val observationDigest: String,
        val domainDigest: String,
        val choiceDigest: String,
        val freshBinding: ReplayTrajectoryBindingV1?,
    )

    private class ReimportedEpisode(
        val trajectory: TrajectoryV1,
        val reimported: ReimportedLocalPublicationEnvelopeV1,
    ) {
        val identity: EnvironmentIdentityV1
            get() = trajectory.episodeMetadata.environmentIdentity
    }

    private class RebuiltReplay(
        val replay: CompactReplay,
        val closure: EpisodeClosureV1,
    )

    /**
     * Core independent verification of one transported envelope.
     *
     * Every authoritative input is either durable (the strict re-imported artifact), repository
     * runtime configuration permitted by the durable identity (locked decks, card catalog, HEAD
     * commit), or regenerated by this verifier. Claimant observations/domains are used only as
     * comparison targets, never as reconstruction authority.
     */
    internal fun verifyTransportedEpisode(
        envelopeDirectory: Path,
        seedOverride: Long? = null,
        contentIdentityOverride: String? = null,
        tamperActionIndex: Int? = null,
        truncateTrailingChoices: Int = 0,
    ): Ka06VerificationReport {
        val episode = readFirstReimportedEpisode(envelopeDirectory)
        val durableIdentity = episode.identity
        val decisions = episode.trajectory.decisions
        val link = episode.trajectory.episodeMetadata.compactReplayLink
        val expectedContentIdentity = contentIdentityOverride ?: link.replayContentIdentity

        // A5 fail-closed validation of the transported claimant trajectory (no engine consulted).
        when (TrajectoryV1Validator.validate(episode.trajectory)) {
            is TrajectoryValidationResult.Valid -> Unit
            is TrajectoryValidationResult.QuarantineEligible,
            is TrajectoryValidationResult.Rejected,
            -> error("Transported claimant trajectory failed A5 validation")
        }

        // Re-derive the environment identity from repository authority; the durable identity is
        // the claimant this derivation must reproduce. The seed is not repository-derivable, so
        // it is durable claimant state; a seed override is only a tamper-control injection.
        val rederived = deriveEnvironmentIdentity(durableIdentity)
        require(rederived == durableIdentity) {
            "Rederived environment identity differs from the transported identity"
        }
        val reconstructionIdentity = if (seedOverride != null) {
            rederived.copy(actualEngineSeed = seedOverride)
        } else {
            rederived
        }

        // Independent fresh execution from the reconstructed config + ordered semantic choices.
        val rebuilt = rebuildReplay(
            episode = episode,
            identity = reconstructionIdentity,
            tamperActionIndex = tamperActionIndex,
            truncateTrailingChoices = truncateTrailingChoices,
        )

        // Fresh A4 fold over the verifier's own rebuilt replay bytes.
        val replayBytes = ReplayCodec.encode(rebuilt.replay)
        val decodedReplay = ReplayCodec.decode(replayBytes)
        require(decodedReplay == rebuilt.replay)
        val freshContentIdentity = ReplayContentCanonicalizerV1.identity(decodedReplay)
        require(freshContentIdentity.value == expectedContentIdentity) {
            "Rebuilt replay content identity diverges from the expected claimant value"
        }
        val freshSource = GymReplayFrameSource(
            replay = decodedReplay,
            cardRegistry = ka06ExactPairRegistry(),
            fallbackPerspectivePlayerIndex = 0,
            tailClosure = rebuilt.closure,
        )
        val freshBinding = freshSource.verifyTrajectoryBinding()
        val freshVerification = freshBinding.verificationBinding.verification
        require(freshVerification.fidelity == ReplayFidelity.EXACT) {
            "Fresh A4 fold was not exact: ${freshVerification.failureReason}"
        }
        require(freshVerification.completeRangeVerified)

        if (truncateTrailingChoices > 0) {
            // The truncated control ends here on purpose: it must be detectable that the fresh
            // range/closure cannot reach the claimant's complete transported range.
            require(freshVerification.replayActionCount ==
                link.replayActionCount - truncateTrailingChoices
            ) {
                "Truncated fresh range did not shrink as expected"
            }
            require(freshVerification.replayActionCount != link.replayActionCount)
            error("Truncated choice range did not reproduce the claimant's complete range")
        }

        // Rebind every transported semantic choice against the CURRENT reconstructed boundaries.
        var prefix = SemanticReplayPrefixV1()
        val observationCanonical = StringBuilder()
        val domainCanonical = StringBuilder()
        val choiceCanonical = StringBuilder()
        for ((index, record) in decisions.withIndex()) {
            val frame = freshVerification.frames.getOrNull(index)
                ?: error("Fresh fold produced no frame at $index")
            require(frame.replayActionIndex == index)
            require(frame.perspectivePlayerId == record.perspectivePlayerId)
            require(frame.observation.perspectivePlayerId == record.perspectivePlayerId)

            // Regenerated model-facing boundaries come only from the fresh fold; the stored
            // claimant fields are comparison targets (section 18), never the expected value.
            require(frame.observation == record.observationBefore) {
                "Fresh observation diverges at $index"
            }
            require(frame.completeLegalDomain == record.completeLegalDomain) {
                "Fresh legal domain diverges at $index"
            }
            require(frame.candidateDomainDigest == record.candidateDomainDigest) {
                "Fresh candidate digest diverges at $index"
            }
            observationCanonical.append(
                A3SemanticJson.canonicalJson(
                    A3SemanticJson.strictJson.encodeToJsonElement(
                        PlayerObservationV1.serializer(),
                        frame.observation,
                    ),
                ),
            ).append('\n')
            domainCanonical.append(
                A3SemanticJson.canonicalJson(
                    A3SemanticJson.strictJson.encodeToJsonElement(
                        CompleteLegalDomainV1.serializer(),
                        frame.completeLegalDomain,
                    ),
                ),
            ).append('\n')

            // Rebind the transported semantic choice against the fresh boundary.
            val chosenInput = freshBinding.chosenInputBinding.chosenInputs.getOrNull(index)
                ?: error("Fresh chosen-input binding is missing index $index")
            require(chosenInput.replayActionIndex == index)
            require(chosenInput.perspectivePlayerId == record.perspectivePlayerId)
            if (index == tamperActionIndex) {
                // The tamper control substituted a different legal action during the rebuild;
                // the fresh semantic choice must diverge from the transported claimant choice.
                val diverged = chosenInput.chosenSemanticAction != record.chosenSemanticAction ||
                    chosenInput.chosenSemanticResponse != record.chosenSemanticResponse
                require(diverged) {
                    "Tampered choice was silently accepted at $index"
                }
                error("Tampered semantic choice diverged from the transported claimant at $index")
            }
            require(chosenInput.chosenSemanticAction != null || chosenInput.chosenSemanticResponse != null)
            require(record.chosenSemanticAction != null || record.chosenSemanticResponse != null)
            if (chosenInput.chosenSemanticAction != null) {
                require(chosenInput.chosenSemanticAction == record.chosenSemanticAction) {
                    "Rebound action diverges from the transported choice at $index"
                }
                choiceCanonical.append(
                    A3SemanticJson.canonicalJson(
                        A3SemanticJson.strictJson.encodeToJsonElement(
                            ChosenSemanticActionV1.serializer(),
                            checkNotNull(chosenInput.chosenSemanticAction),
                        ),
                    ),
                ).append('\n')
            } else {
                require(chosenInput.chosenSemanticResponse == record.chosenSemanticResponse) {
                    "Rebound response diverges from the transported choice at $index"
                }
                choiceCanonical.append(
                    A3SemanticJson.canonicalJson(
                        A3SemanticJson.strictJson.encodeToJsonElement(
                            ChosenSemanticResponseV1.serializer(),
                            checkNotNull(chosenInput.chosenSemanticResponse),
                        ),
                    ),
                ).append('\n')
            }

            // Recompute the semantic decision identity from the fresh prefix + fresh boundaries.
            val freshIdentity = SemanticDecisionIdentityV1.from(
                semanticEpisodeId = episode.trajectory.episodeMetadata.semanticEpisodeId,
                prefix = prefix,
                replayActionIndex = index,
                observation = frame.observation,
                domain = frame.completeLegalDomain,
                perspectivePlayerId = record.perspectivePlayerId.value,
            )
            require(freshIdentity.decisionKind == record.decisionKind) {
                "Fresh decision kind diverges at $index"
            }
            require(freshIdentity.semanticDecisionId() == record.semanticDecisionId) {
                "Fresh semantic decision id diverges at $index"
            }
            val input = chosenInput.chosenSemanticAction?.let(SemanticReplayInputV1::action)
                ?: SemanticReplayInputV1.response(checkNotNull(chosenInput.chosenSemanticResponse))
            prefix = prefix.copy(inputs = prefix.inputs + input)
        }
        require(decisions.size == link.replayActionCount)
        require(freshVerification.replayActionCount == link.replayActionCount)
        require(freshVerification.closure == episode.trajectory.episodeMetadata.closure) {
            "Fresh closure diverges from the transported claimant closure"
        }

        // Independently rebuild the TrajectoryV1 from fresh evidence and validate through A6.
        val freshTrajectory = TrajectoryV1(
            trajectoryId = "0".repeat(64),
            episodeMetadata = episode.trajectory.episodeMetadata,
            decisions = freshBinding.chosenInputBinding.chosenInputs.mapIndexed { index, chosen ->
                val frame = freshVerification.frames[index]
                val identity = SemanticDecisionIdentityV1.from(
                    semanticEpisodeId = episode.trajectory.episodeMetadata.semanticEpisodeId,
                    prefix = SemanticReplayPrefixV1(
                        inputs = freshBinding.chosenInputBinding.chosenInputs
                            .take(index)
                            .map { chosenEarlier ->
                                chosenEarlier.chosenSemanticAction
                                    ?.let(SemanticReplayInputV1::action)
                                    ?: SemanticReplayInputV1.response(
                                        checkNotNull(chosenEarlier.chosenSemanticResponse),
                                    )
                            },
                    ),
                    replayActionIndex = index,
                    observation = frame.observation,
                    domain = frame.completeLegalDomain,
                    perspectivePlayerId = frame.perspectivePlayerId.value,
                )
                DecisionRecordV1(
                    decisionIndex = index,
                    replayActionIndex = index,
                    replayFrameIndex = index,
                    perspectivePlayerId = frame.perspectivePlayerId,
                    decisionKind = identity.decisionKind,
                    semanticDecisionId = identity.semanticDecisionId(),
                    observationBefore = frame.observation,
                    completeLegalDomain = frame.completeLegalDomain,
                    candidateDomainDigest = frame.candidateDomainDigest,
                    chosenSemanticAction = chosen.chosenSemanticAction,
                    chosenSemanticResponse = chosen.chosenSemanticResponse,
                )
            },
        ).let { provisional -> provisional.copy(trajectoryId = provisional.recomputeTrajectoryId()) }
        require(freshTrajectory.trajectoryId == episode.trajectory.trajectoryId) {
            "Fresh trajectory identity diverges from the transported trajectory id"
        }
        val admission = TrajectoryV1Admission.admit(
            trajectory = freshTrajectory,
            binding = freshBinding,
            episodeOrdinal = 0,
        )
        require(admission is TrajectoryAdmissionResult.Admitted) {
            "Verifier-rebuilt trajectory failed A6 admission: " +
                ((admission as? TrajectoryAdmissionResult.Quarantined)?.metadata?.reason ?: "unknown")
        }

        // A second independent A4 pass over the verifier's own replay bytes (§28 repetition at
        // the fold level; the full process-level repetition is performed by the spec).
        val secondBinding = GymReplayFrameSource(
            replay = decodedReplay,
            cardRegistry = ka06ExactPairRegistry(),
            fallbackPerspectivePlayerIndex = 0,
            tailClosure = rebuilt.closure,
        ).verifyTrajectoryBinding()
        require(secondBinding.verificationBinding.verification.fidelity == ReplayFidelity.EXACT)
        require(secondBinding.verificationBinding.verification.completeRangeVerified)
        require(
            secondBinding.verificationBinding.replayContentIdentity.value ==
                freshContentIdentity.value,
        )

        return Ka06VerificationReport(
            semanticEpisodeId = freshTrajectory.semanticEpisodeId,
            trajectoryId = freshTrajectory.trajectoryId,
            replayContentIdentity = freshContentIdentity.value,
            replayActionCount = freshVerification.replayActionCount,
            environmentIdentityDigest = ka06CanonicalDigest(rederived),
            observationDigest = ka06DigestText(observationCanonical.toString()),
            domainDigest = ka06DigestText(domainCanonical.toString()),
            choiceDigest = ka06DigestText(choiceCanonical.toString()),
            freshBinding = freshBinding,
        )
    }

    /** Strict re-import + A7 reader validation only. */
    private fun readFirstReimportedEpisode(envelopeDirectory: Path): ReimportedEpisode {
        val reimported = LocalPublicationEnvelopeV1.reimport(envelopeDirectory)
        val trajectory = reimported.streamEpisodes().first()
        return ReimportedEpisode(trajectory, reimported)
    }

    /**
     * Re-derive the durable environment identity from repository authority. The transported
     * identity is the claimant to compare against; pass-through fields that are not repository
     * derivable (engine commit, seed, starting player) come from the durable identity itself.
     */
    private fun deriveEnvironmentIdentity(expected: EnvironmentIdentityV1): EnvironmentIdentityV1 {
        val repositoryRoot = ka06RepositoryRoot()
        val loader = CurriculumDeckSourceLoader(repositoryRoot)
        val akiri = loader.load(CurriculumAiTournamentPreset.AKIRI_CHEVILL.sourcePaths[0])
        val chevill = loader.load(CurriculumAiTournamentPreset.AKIRI_CHEVILL.sourcePaths[1])
        val registry = ka06ExactPairRegistry()
        val cardDigest = ka06LockedCardDefinitionDigest(akiri, chevill, registry)
        return EnvironmentIdentityV1(
            engineCommit = expected.engineCommit,
            cardDefinitionIdentity = cardDigest,
            akiriDeckIdentity = akiri.sourceDigest,
            chevillDeckIdentity = chevill.sourceDigest,
            format = expected.format,
            attackMode = expected.attackMode,
            startingHandSize = expected.startingHandSize,
            skipMulligans = expected.skipMulligans,
            useHandSmoother = expected.useHandSmoother,
            teams = expected.teams,
            roster = expected.roster,
            startingPlayer = expected.startingPlayer,
            actualEngineSeed = expected.actualEngineSeed,
        )
    }

    /**
     * Rebuild the CompactReplay from the durable identity plus the ordered transported semantic
     * choices. Each choice is rebound against the CURRENT public boundary before execution; no
     * stored observation/domain is used as reconstruction authority.
     */
    private fun rebuildReplay(
        episode: ReimportedEpisode,
        identity: EnvironmentIdentityV1,
        tamperActionIndex: Int? = null,
        truncateTrailingChoices: Int = 0,
    ): RebuiltReplay {
        val repositoryRoot = ka06RepositoryRoot()
        val loader = CurriculumDeckSourceLoader(repositoryRoot)
        val akiri = loader.load(CurriculumAiTournamentPreset.AKIRI_CHEVILL.sourcePaths[0])
        val chevill = loader.load(CurriculumAiTournamentPreset.AKIRI_CHEVILL.sourcePaths[1])
        val registry = ka06ExactPairRegistry()
        val resolver = DeckResolver(registry)
        val akiriDeck = resolver.resolve(DeckSpec.Explicit(akiri.libraryDeckList()))
        val chevillDeck = resolver.resolve(DeckSpec.Explicit(chevill.libraryDeckList()))
        val seat0 = identity.roster[0]
        val seat1 = identity.roster[1]
        val players = listOf(
            PlayerConfig(
                name = seat0.role.lowercase().replaceFirstChar { it.uppercase() },
                deck = if (seat0.role == "AKIRI") akiriDeck else chevillDeck,
                startingLife = KA06_COMMANDER_STARTING_LIFE,
                playerId = seat0.playerId,
                commanderCardName = checkNotNull(seat0.commanderDefinitionIdentity),
            ),
            PlayerConfig(
                name = seat1.role.lowercase().replaceFirstChar { it.uppercase() },
                deck = if (seat1.role == "AKIRI") akiriDeck else chevillDeck,
                startingLife = KA06_COMMANDER_STARTING_LIFE,
                playerId = seat1.playerId,
                commanderCardName = checkNotNull(seat1.commanderDefinitionIdentity),
            ),
        )
        val config = GameConfig(
            players = players,
            startingHandSize = identity.startingHandSize,
            skipMulligans = identity.skipMulligans,
            useHandSmoother = identity.useHandSmoother,
            startingPlayerIndex = players.indexOfFirst { it.playerId == identity.startingPlayer },
            format = Format.Commander(),
            attackMode = AttackMode.valueOf(identity.attackMode),
            seed = identity.actualEngineSeed,
        )
        val chosenInputs = episode.trajectory.decisions
            .dropLast(truncateTrailingChoices)
            .map { record ->
                ReplayChosenInputV1(
                    replayActionIndex = record.replayActionIndex,
                    perspectivePlayerId = record.perspectivePlayerId,
                    chosenSemanticAction = record.chosenSemanticAction,
                    chosenSemanticResponse = record.chosenSemanticResponse,
                )
            }

        val environment = GameEnvironment.create(
            cardRegistry = registry,
            executionMode = GameEnvironmentMode.TRUSTED,
        )
        environment.reset(config, KA06_MAX_STEPS)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
        )
        val actions = mutableListOf<GameAction>()
        val checkpoints = mutableListOf<ReplayCheckpoint>()
        var result = gym.observe()
        var observation = requireKa06TrainingObservation(result)
        for ((index, chosen) in chosenInputs.withIndex()) {
            check(!observation.terminated && !observation.truncated) {
                "Fresh episode ended before consuming semantic input $index"
            }
            val rebound: ReboundAction = if (chosen.chosenSemanticAction != null) {
                if (index == tamperActionIndex) {
                    ReboundAction(
                        action = ka06SubstituteAction(result, observation, index),
                        viewIndex = null,
                        payload = null,
                    )
                } else {
                    ka06RebindAction(
                        result,
                        observation,
                        checkNotNull(chosen.chosenSemanticAction),
                        index,
                    )
                }
            } else {
                ReboundAction(
                    action = ka06RebindResponse(result, observation, chosen, index),
                    viewIndex = null,
                    payload = null,
                )
            }
            result = if (rebound.action is SubmitDecision) {
                gym.submitDecision(rebound.action.response, actorId = observation.agentToAct)
            } else {
                val viewIndex = checkNotNull(rebound.viewIndex) {
                    "Rebound action at $index has no CURRENT public view"
                }
                val payload = rebound.payload
                if (payload == null) {
                    gym.step(observation.legalActions[viewIndex].actionId)
                } else {
                    gym.step(observation.legalActions[viewIndex].actionId, payload)
                }
            }
            actions += rebound.action
            if (actions.size % ReplayRecordingPolicy.CHECKPOINT_EVERY_ACTIONS == 0) {
                checkpoints += ReplayCheckpoint(
                    afterActionCount = actions.size,
                    fingerprint = ReplayFingerprint.of(environment.state, CompactReplay.CURRENT_VERSION),
                )
            }
            observation = requireKa06TrainingObservation(result)
        }
        val closure = checkNotNull(environment.episodeClosure) {
            "Fresh execution ended without a typed closure"
        }
        require(closure.stepCount == actions.size)
        if (checkpoints.lastOrNull()?.afterActionCount != actions.size) {
            checkpoints += ReplayCheckpoint(
                afterActionCount = actions.size,
                fingerprint = ReplayFingerprint.of(environment.state, CompactReplay.CURRENT_VERSION),
            )
        }
        val setup = ka06ReplaySetup(config)
        val replay = CompactReplay(
            version = CompactReplay.CURRENT_VERSION,
            gameId = "ka06-verifier-reconstruction",
            players = config.players.map { player ->
                ReplayPlayerInfo(checkNotNull(player.playerId).value, player.name)
            },
            startedAt = "2026-09-16T00:00:00Z",
            endedAt = "2026-09-16T00:00:01Z",
            winnerName = (closure as? EpisodeClosureV1.GameTerminal)?.winnerId?.let { winner ->
                config.players.firstOrNull { it.playerId == winner }?.name
            },
            setup = setup,
            actions = actions,
            pinnedCards = ReplayCardPin.capture(registry, setup),
            checkpoints = checkpoints,
        )
        require(ReplayCodec.decode(ReplayCodec.encode(replay)) == replay)
        return RebuiltReplay(replay, closure)
    }

    /**
     * Rebind one transported action candidate against the CURRENT public domain and registry.
     * Returns the rebound action plus the CURRENT public view index it was bound to, so the
     * step path can submit through the same live action id the rebind proved membership for.
     */
    private data class ReboundAction(
        val action: GameAction,
        val viewIndex: Int?,
        val payload: JsonObject?,
    )

    private fun ka06RebindAction(
        result: ObservationResult,
        observation: TrainingObservation,
        chosen: ChosenSemanticActionV1,
        index: Int,
    ): ReboundAction {
        val currentDomain = CompleteLegalDomainV1.from(observation)
        val chosenJson = A3SemanticJson.canonicalJson(chosen.candidate)
        val viewIndex = currentDomain.candidates.indexOfFirst { candidate ->
            A3SemanticJson.canonicalJson(candidate) == chosenJson
        }
        require(viewIndex >= 0 && viewIndex < observation.legalActions.size) {
            "Transported candidate at $index is not in the CURRENT public domain"
        }
        val view = observation.legalActions[viewIndex]
        return when (val resolved = result.registry.resolve(view.actionId)) {
            is ResolvedAction.Legal -> {
                val action = if (chosen.choicePayload.isEmpty()) {
                    resolved.action
                } else {
                    ka06MaterializeAction(resolved.action, chosen.choicePayload)
                }
                ReboundAction(
                    action = action,
                    viewIndex = viewIndex,
                    payload = chosen.choicePayload.takeIf { it.isNotEmpty() },
                )
            }

            is ResolvedAction.Decision -> error(
                "Transported ACTION-class choice at $index resolved to a decision",
            )

            else -> error("Transported choice at $index did not resolve")
        }
    }

    /** Tamper-control injection: submit a different CURRENT legal action at this boundary. */
    private fun ka06SubstituteAction(
        result: ObservationResult,
        observation: TrainingObservation,
        index: Int,
    ): GameAction {
        val alternatives = observation.legalActions
            .mapNotNull { view ->
                when (val resolved = result.registry.resolve(view.actionId)) {
                    is ResolvedAction.Legal -> resolved.action
                    is ResolvedAction.Decision -> null
                    ResolvedAction.Unknown -> null
                }
            }
        require(alternatives.size >= 2) {
            "Tamper control at $index requires at least two current legal actions"
        }
        return alternatives[1]
    }

    /**
     * Rebind one transported response against the CURRENT pending decision. Folded options are
     * rebound through the current public domain's registered decision responses; structured
     * responses are decoded with the live routing id, with semantic ordering references mapped
     * back through the current ordering domain.
     */
    private fun ka06RebindResponse(
        result: ObservationResult,
        observation: TrainingObservation,
        chosen: ReplayChosenInputV1,
        index: Int,
    ): GameAction {
        val pending = checkNotNull(observation.pendingDecision) {
            "Transported response at $index has no CURRENT pending decision"
        }
        require(pending.playerId == chosen.perspectivePlayerId) {
            "Transported response perspective at $index disagrees with the CURRENT decision"
        }
        val decisionId = checkNotNull(pending.decisionId) {
            "CURRENT pending decision at $index carries no routing id"
        }
        val semantic = checkNotNull(chosen.chosenSemanticResponse)
        val responseJson = semantic.response
        if (pending.requiresStructuredResponse) {
            val domain = CompleteLegalDomainV1.from(observation)
            return SubmitDecision(
                pending.playerId,
                ka06DecodeStructuredResponse(domain, responseJson, decisionId, index),
            )
        }
        // Folded option: rebind through the CURRENT public domain using the accepted membership
        // rule (exact actionSemantics match, ignoring stored option metadata).
        val domain = CompleteLegalDomainV1.from(observation)
        val responseCanonical = A3SemanticJson.canonicalJson(responseJson)
        val viewIndex = domain.candidates.indexOfFirst { candidate ->
            val actionSemantics = candidate["actionSemantics"] as? JsonObject ?: return@indexOfFirst false
            A3SemanticJson.canonicalJson(actionSemantics) == responseCanonical ||
                A3SemanticJson.canonicalJson(
                    JsonObject(actionSemantics.filterKeys { it != "optionMetadata" }),
                ) == responseCanonical
        }
        require(viewIndex >= 0 && viewIndex < observation.legalActions.size) {
            "Transported folded response at $index is not in the CURRENT public domain"
        }
        val view = observation.legalActions[viewIndex]
        return when (val resolved = result.registry.resolve(view.actionId)) {
            is ResolvedAction.Decision ->
                SubmitDecision(pending.playerId, resolved.response.withDecisionId(decisionId))

            is ResolvedAction.Legal -> error(
                "Transported RESPONSE-class choice at $index resolved to a plain action",
            )

            else -> error("Transported response at $index did not resolve")
        }
    }

    /** Decode one structured semantic response JSON into a live [DecisionResponse]. */
    private fun ka06DecodeStructuredResponse(
        domain: CompleteLegalDomainV1,
        responseJson: JsonObject,
        decisionId: String,
        index: Int,
    ): DecisionResponse {
        val decodedJson: JsonObject = if (
            (responseJson["type"] as? JsonPrimitive)?.contentOrNull == "OrderedResponse" &&
            (responseJson["orderedObjects"] as? JsonArray)?.any { it is JsonObject } == true
        ) {
            val ordering = domain.structuredDomain as? OrderingDomain
                ?: error("Semantic ordering response at $index has no current ordering domain")
            val objectByReference = ordering.objects.associateBy { objectId ->
                A3SemanticJson.canonicalJson(
                    ObservationCanonicalizer.semanticOrderingObject(
                        objectId = objectId.value,
                        label = ordering.objectLabels?.get(objectId),
                        cardInfo = ordering.cardInfo?.get(objectId)?.let { cardInfo ->
                            A3SemanticJson.strictJson.encodeToJsonElement(
                                StructuredCardInfo.serializer(),
                                cardInfo,
                            )
                        },
                    ),
                )
            }
            val orderedIds = (responseJson.getValue("orderedObjects") as JsonArray).map { reference ->
                val key = A3SemanticJson.canonicalJson(reference)
                (objectByReference[key]
                    ?: error("Semantic ordering reference at $index is not in the CURRENT domain")).value
            }
            buildJsonObject {
                responseJson.forEach { (key, value) -> if (key != "orderedObjects") put(key, value) }
                put("orderedObjects", JsonArray(orderedIds.map { JsonPrimitive(it) }))
            }
        } else {
            responseJson
        }
        val withRoutingId = buildJsonObject {
            decodedJson.forEach { (key, value) -> put(key, value) }
            put("decisionId", decisionId)
        }
        return A3SemanticJson.decodeStrict(
            DecisionResponse.serializer(),
            withRoutingId,
            "rebound decision response at $index",
        )
    }

    // ------------------------------------------------------------------
    // Shared accepted-path helpers (both sides)
    // ------------------------------------------------------------------

    private fun ka06BuildTrajectory(
        item: WorkItemV1,
        identity: EnvironmentIdentityV1,
        replay: CompactReplay,
        replayIdentity: ReplayContentIdentityV1,
        binding: ReplayTrajectoryBindingV1,
        closure: EpisodeClosureV1,
        registry: CardRegistry,
        akiri: CurriculumDeckSourceV1,
        chevill: CurriculumDeckSourceV1,
    ): TrajectoryV1 {
        val metadataBase = EpisodeMetadataV1(
            semanticEpisodeId = "0".repeat(64),
            collectionJobId = "0".repeat(64),
            environmentIdentity = identity.copy(
                cardDefinitionIdentity = ka06LockedCardDefinitionDigest(akiri, chevill, registry),
            ),
            policyProvenance = item.policyProvenance,
            compactReplayLink = CompactReplayLinkV1(
                replayVersion = replay.version,
                replaySchemaIdentity = KA06_LINK_REPLAY_SCHEMA_IDENTITY,
                replayContentIdentity = replayIdentity.value,
                replayActionCount = replay.actions.size,
            ),
            closure = closure,
        )
        val withSemanticId = metadataBase.copy(semanticEpisodeId = metadataBase.recomputeSemanticEpisodeId())
        val metadata = withSemanticId.copy(collectionJobId = withSemanticId.recomputeCollectionJobId())
        var prefix = SemanticReplayPrefixV1()
        val records = binding.chosenInputBinding.chosenInputs.mapIndexed { index, chosen ->
            val frame = binding.verificationBinding.verification.frames[index]
            val decisionIdentity = SemanticDecisionIdentityV1.from(
                semanticEpisodeId = metadata.semanticEpisodeId,
                prefix = prefix,
                replayActionIndex = index,
                observation = frame.observation,
                domain = frame.completeLegalDomain,
                perspectivePlayerId = frame.perspectivePlayerId.value,
            )
            val record = DecisionRecordV1(
                decisionIndex = index,
                replayActionIndex = index,
                replayFrameIndex = index,
                perspectivePlayerId = frame.perspectivePlayerId,
                decisionKind = decisionIdentity.decisionKind,
                semanticDecisionId = decisionIdentity.semanticDecisionId(),
                observationBefore = frame.observation,
                completeLegalDomain = frame.completeLegalDomain,
                candidateDomainDigest = frame.candidateDomainDigest,
                chosenSemanticAction = chosen.chosenSemanticAction,
                chosenSemanticResponse = chosen.chosenSemanticResponse,
            )
            val input = chosen.chosenSemanticAction?.let(SemanticReplayInputV1::action)
                ?: SemanticReplayInputV1.response(checkNotNull(chosen.chosenSemanticResponse))
            prefix = prefix.copy(inputs = prefix.inputs + input)
            record
        }
        val provisional = TrajectoryV1(
            trajectoryId = "0".repeat(64),
            episodeMetadata = metadata,
            decisions = records,
        )
        return provisional.copy(trajectoryId = provisional.recomputeTrajectoryId())
    }

    internal fun ka06MaterializeAction(
        template: GameAction,
        payload: JsonObject,
    ): GameAction {
        val templateJson = A3SemanticJson.strictJson
            .encodeToJsonElement(GameAction.serializer(), template)
            .jsonObject
        require(payload["type"] == null || payload["type"] == templateJson["type"])
        val merged = buildJsonObject {
            templateJson.forEach { (key, value) -> put(key, value) }
            payload.forEach { (key, value) -> if (key != "abilityKey") put(key, value) }
        }
        return A3SemanticJson.decodeStrict(GameAction.serializer(), merged, "materialized action")
    }

    private fun ka06ToDecisionResponse(
        decisionId: String,
        selection: SemanticDecision,
    ): DecisionResponse = when (selection) {
        is SemanticDecision.Targets ->
            com.wingedsheep.engine.core.TargetsResponse(decisionId, selection.selected)
        is SemanticDecision.Cards ->
            com.wingedsheep.engine.core.CardsSelectedResponse(decisionId, selection.selected)
        is SemanticDecision.Modes ->
            com.wingedsheep.engine.core.ModesChosenResponse(decisionId, selection.selected)
        is SemanticDecision.Color ->
            com.wingedsheep.engine.core.ColorChosenResponse(decisionId, selection.selected)
        is SemanticDecision.Number ->
            com.wingedsheep.engine.core.NumberChosenResponse(decisionId, selection.selected)
        is SemanticDecision.Distribution ->
            com.wingedsheep.engine.core.DistributionResponse(decisionId, selection.selected)
        is SemanticDecision.Ordered ->
            com.wingedsheep.engine.core.OrderedResponse(decisionId, selection.selected)
        is SemanticDecision.Piles ->
            com.wingedsheep.engine.core.PilesSplitResponse(decisionId, selection.selected)
        is SemanticDecision.Option ->
            com.wingedsheep.engine.core.OptionChosenResponse(decisionId, selection.selected)
        is SemanticDecision.Replacement ->
            com.wingedsheep.engine.core.ReplacementChosenResponse(decisionId, selection.from, selection.to)
        is SemanticDecision.Budget ->
            com.wingedsheep.engine.core.BudgetModalResponse(decisionId, selection.selected)
        is SemanticDecision.Damage -> com.wingedsheep.engine.core.CombatResolutionResponse(
            decisionId = decisionId,
            edges = selection.selected.map {
                com.wingedsheep.engine.core.DamageEdgeAmount(it.edgeId, it.amount)
            },
        )
        is SemanticDecision.Payment -> selection.toDecisionResponse(decisionId)
    }

    private fun ka06ReplaySetup(config: GameConfig): ReplaySetup {
        val playerIds = config.players.map { checkNotNull(it.playerId) }
        return ReplaySetup(
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
    }

    internal fun requireKa06TrainingObservation(result: ObservationResult): TrainingObservation {
        check(result.diagnostics.isEmpty()) { "KA06 public observation carried diagnostics" }
        val observation = result.observation as? TrainingObservation
            ?: error("KA06 expected a TrainingObservation")
        check(observation.schemaHash == SchemaHash.CURRENT) {
            "KA06 used a stale Gym schema hash: ${observation.schemaHash}"
        }
        return observation
    }

    internal fun ka06ExactPairRegistry(): CardRegistry = CardRegistry().apply {
        MtgSetCatalog.all.forEach { set ->
            register(set.cards)
            register(set.basicLands)
        }
    }

    internal fun ka06RepositoryRoot(): Path = (
        System.getProperty("ka04.gradleProjectRoot")?.let(Path::of)
            ?: System.getProperty("ka06.repositoryRoot")?.let(Path::of)
            ?: generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .firstOrNull { Files.isDirectory(it.resolve("docs/ml/curriculum")) }
        )
        ?.toAbsolutePath()
        ?.normalize()
        ?.also { root ->
            require(Files.isDirectory(root.resolve("docs/ml/curriculum"))) {
                "KA06 repository root does not contain the locked curriculum"
            }
        }
        ?: error("KA06 repository root not found")

    /** Accepted locked-pair card-definition digest recipe (exact-pair acceptance authority). */
    internal fun ka06LockedCardDefinitionDigest(
        akiri: CurriculumDeckSourceV1,
        chevill: CurriculumDeckSourceV1,
        registry: CardRegistry,
    ): String {
        val lockedCards = (akiri.deckList.keys + chevill.deckList.keys).distinct()
        val resolved = lockedCards.mapNotNull(registry::getCard).distinctBy { it.name }
        check(resolved.size >= lockedCards.size - 2) {
            "KA06 card-definition digest could not resolve all locked deck cards"
        }
        val serialized = resolved.map { card ->
            card.name to CardSerialization.json.encodeToJsonElement(
                com.wingedsheep.sdk.model.CardDefinition.serializer(),
                card,
            )
        }.sortedBy { it.first }
        val digestInput = serialized.joinToString("\n") { (name, json) ->
            "$name:${A3SemanticJson.canonicalJson(json)}"
        }
        return ka06DigestText(digestInput).uppercase()
    }

    internal fun ka06CanonicalDigest(identity: EnvironmentIdentityV1): String = A3SemanticJson.sha256(
        A3SemanticJson.canonicalJson(
            A3SemanticJson.strictJson.encodeToJsonElement(
                EnvironmentIdentityV1.serializer(),
                identity,
            ),
        ).toByteArray(StandardCharsets.UTF_8),
    )

    internal fun ka06DigestText(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    internal fun ka06GitHead(repositoryRoot: Path): String? {
        val process = ProcessBuilder(listOf("git", "rev-parse", "HEAD"))
            .directory(repositoryRoot.toFile())
            .start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished || process.exitValue() != 0) return null
        return String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
            .trim()
            .takeIf { it.isNotBlank() }
    }
}

/** Public entrypoint shim for the test-source verifier worker JVM (process B). */
object KaggleActor06VerifierWorkerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        KaggleActor06TransportedReplayHarness.ka06VerifierWorkerMain(args)
    }
}

/** Parsed verifier worker exit file. */
internal data class KaggleActor06VerifierExit(
    val result: JsonObject,
    val workerFailure: String?,
) {
    val success: Boolean get() = workerFailure == null
    val status: String? get() = (result["status"] as? JsonPrimitive)?.contentOrNull

    fun field(name: String): String? = (result[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        private val lenientJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun parse(content: String): KaggleActor06VerifierExit {
            val parsed = lenientJson.parseToJsonElement(content).jsonObject
            val result = parsed["result"] as? JsonObject ?: JsonObject(emptyMap())
            return KaggleActor06VerifierExit(
                result = result,
                workerFailure = (parsed["workerFailure"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }
}
