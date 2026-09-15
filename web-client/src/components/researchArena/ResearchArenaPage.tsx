import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useGameStore } from '@/store/gameStore.ts'
import { useConnectName } from '@/store/useConnectName'
import {
  AiTournamentApiError,
  createCurriculumAiTournament,
  fetchAiTournamentStatus,
  type AiTournamentStatus,
  CURRICULUM_PRESET_IDENTITY,
} from '@/api/aiTournamentApi'
import {
  arenaPhaseForStatus,
  firstUnwatchedLiveGame,
  type ArenaErrorKind,
  type ArenaPhase,
} from './researchArenaState'

const STATUS_POLL_INTERVAL_MS = 1500
const WATCHED_GAME_KEY_PREFIX = 'argentum-research-arena-watched:'

interface ArenaError {
  kind: ArenaErrorKind
  message: string
}

export function ResearchArenaPage() {
  const { lobbyId: routeLobbyId } = useParams<{ lobbyId?: string }>()
  const navigate = useNavigate()
  const connectionStatus = useGameStore((state) => state.connectionStatus)
  const connect = useGameStore((state) => state.connect)
  const startCurriculumHumanVsEngineAi = useGameStore((state) => state.startCurriculumHumanVsEngineAi)
  const sessionId = useGameStore((state) => state.sessionId)
  const lastError = useGameStore((state) => state.lastError)
  const clearError = useGameStore((state) => state.clearError)
  const sessionReplaced = useGameStore((state) => state.sessionReplaced)
  const { name: connectName, resolving: nameResolving } = useConnectName()
  const [phase, setPhase] = useState<ArenaPhase>(() => routeLobbyId ? 'STARTING' : 'IDLE')
  const [lobbyId, setLobbyId] = useState<string | null>(() => routeLobbyId ?? null)
  const [status, setStatus] = useState<AiTournamentStatus | null>(null)
  const [error, setError] = useState<ArenaError | null>(null)
  const [pollEnabled, setPollEnabled] = useState(Boolean(routeLobbyId))
  const [launching, setLaunching] = useState(false)
  const launchInFlightRef = useRef(false)
  const autoWatchedGameRef = useRef<string | null>(null)
  const [playerName, setPlayerName] = useState(() => localStorage.getItem('argentum-player-name') || '')
  const [humanLaunching, setHumanLaunching] = useState(false)
  const [humanLaunchError, setHumanLaunchError] = useState<string | null>(null)
  const humanLaunchInFlightRef = useRef(false)
  const hasConnectedRef = useRef(false)

  useEffect(() => {
    if (sessionReplaced) return
    if (connectName && connectionStatus === 'disconnected' && !hasConnectedRef.current) {
      hasConnectedRef.current = true
      connect(connectName)
    }
  }, [connectionStatus, connect, connectName, sessionReplaced])

  const handleConnect = useCallback(() => {
    const trimmedName = playerName.trim()
    if (!trimmedName) return
    localStorage.setItem('argentum-player-name', trimmedName)
    hasConnectedRef.current = true
    connect(trimmedName)
  }, [connect, playerName])

  const startHumanMatch = useCallback(() => {
    if (humanLaunchInFlightRef.current || launchInFlightRef.current || connectionStatus !== 'connected') return
    humanLaunchInFlightRef.current = true
    setHumanLaunching(true)
    setHumanLaunchError(null)
    clearError()
    startCurriculumHumanVsEngineAi()
  }, [clearError, connectionStatus, startCurriculumHumanVsEngineAi])

  useEffect(() => {
    if (!humanLaunching) return
    if (lastError) {
      humanLaunchInFlightRef.current = false
      setHumanLaunching(false)
      setHumanLaunchError(lastError.message)
      return
    }
    if (sessionId) {
      navigate('/', { replace: true })
    }
  }, [humanLaunching, lastError, navigate, sessionId])

  const startNewMatch = useCallback(async () => {
    if (launchInFlightRef.current || humanLaunchInFlightRef.current) return

    launchInFlightRef.current = true
    setLaunching(true)
    setPhase('STARTING')
    setError(null)
    setStatus(null)
    setLobbyId(null)
    setPollEnabled(false)

    try {
      const created = await createCurriculumAiTournament()
      if (!created.lobbyId) {
        throw new Error(created.message || 'The server did not return a lobby id')
      }
      navigate(`/dev/research-arena/${encodeURIComponent(created.lobbyId)}`)
    } catch (cause) {
      const apiError = cause instanceof AiTournamentApiError ? cause : null
      setError({
        kind: 'CREATE_ERROR',
        message: apiError?.status === 404
          ? 'Research Arena dev endpoint is not enabled on this server.'
          : cause instanceof Error ? cause.message : String(cause),
      })
      setPhase('ERROR')
    } finally {
      launchInFlightRef.current = false
      setLaunching(false)
    }
  }, [navigate])

  useEffect(() => {
    if (!routeLobbyId) {
      setLobbyId(null)
      setStatus(null)
      setError(null)
      setPhase('IDLE')
      setPollEnabled(false)
      autoWatchedGameRef.current = null
      return
    }

    setLobbyId(routeLobbyId)
    setStatus(null)
    setError(null)
    setPhase('STARTING')
    setPollEnabled(true)
    autoWatchedGameRef.current = null
  }, [routeLobbyId])

  useEffect(() => {
    if (!routeLobbyId || !pollEnabled) return

    let disposed = false
    let timeoutId: number | null = null

    const poll = async () => {
      try {
        const nextStatus = await fetchAiTournamentStatus(routeLobbyId)
        if (disposed) return

        setLobbyId(nextStatus.lobbyId || routeLobbyId)
        setStatus(nextStatus)
        setError(null)
        const nextPhase = arenaPhaseForStatus(nextStatus)
        setPhase(nextPhase)

        if (nextPhase === 'COMPLETE') {
          setPollEnabled(false)
          return
        }

        timeoutId = window.setTimeout(() => void poll(), STATUS_POLL_INTERVAL_MS)
      } catch (cause) {
        if (disposed) return

        const apiError = cause instanceof AiTournamentApiError ? cause : null
        setPollEnabled(false)
        setError({
          kind: apiError?.status === 404 ? 'LOST_LOBBY' : 'STATUS_ERROR',
          message: apiError?.status === 404
            ? 'The Research Arena lobby is no longer available.'
            : cause instanceof Error ? cause.message : String(cause),
        })
        setPhase('ERROR')
      }
    }

    void poll()
    return () => {
      disposed = true
      if (timeoutId !== null) window.clearTimeout(timeoutId)
    }
  }, [pollEnabled, routeLobbyId])

  const retryStatus = useCallback(() => {
    if (!routeLobbyId || error?.kind !== 'STATUS_ERROR') return
    setError(null)
    setPhase(status ? arenaPhaseForStatus(status) : 'WAITING_FOR_LIVE_GAME')
    setPollEnabled(true)
  }, [error?.kind, routeLobbyId, status])

  const watchGame = useCallback((gameSessionId: string) => {
    sessionStorage.setItem(`${WATCHED_GAME_KEY_PREFIX}${gameSessionId}`, '1')
    window.location.assign(`/?spectate=${encodeURIComponent(gameSessionId)}`)
  }, [])

  useEffect(() => {
    if (phase !== 'LIVE') return

    const nextGame = firstUnwatchedLiveGame(
      status?.liveGames ?? [],
      (gameSessionId) => sessionStorage.getItem(`${WATCHED_GAME_KEY_PREFIX}${gameSessionId}`) === '1',
    )
    if (!nextGame || autoWatchedGameRef.current === nextGame.gameSessionId) return

    autoWatchedGameRef.current = nextGame.gameSessionId
    watchGame(nextGame.gameSessionId)
  }, [phase, status, watchGame])

  const statusLabel = getStatusLabel(phase, status, error)
  const canStartNewMatch = !launching && !humanLaunching && (
    phase === 'IDLE' ||
    phase === 'COMPLETE' ||
    (phase === 'ERROR' && error?.kind !== 'STATUS_ERROR')
  )
  const showNameEntry = connectionStatus === 'disconnected' && !connectName && !nameResolving && !sessionReplaced
  const canStartHumanMatch = connectionStatus === 'connected' && !humanLaunching && !launching && !sessionId

  return (
    <div style={styles.page}>
      <header style={styles.header}>
        <div>
          <h1 style={styles.title}>Research Arena <span style={styles.devTag}>dev</span></h1>
          <p style={styles.subtitle}>Development / ML Research Tooling</p>
        </div>
        <a href="/" style={styles.homeLink}>← Home</a>
      </header>

      <div style={styles.arenaGrid}>
        <section style={styles.card} aria-labelledby="matchup-heading">
          <p style={styles.eyebrow}>Locked MTG ML Curriculum</p>
          <h2 id="matchup-heading" style={styles.cardTitle}>Research Arena matchup</h2>
          <div style={styles.matchup}>
            <div style={styles.seatCard}>Akiri, Fearless Voyager</div>
            <span style={styles.vs}>VS</span>
            <div style={styles.seatCard}>Chevill, Bane of Monsters</div>
          </div>
          <div style={styles.matchMeta}>
            <span>Rules: Commander</span>
            <span>Controllers: Engine AI vs Engine AI</span>
            <span>Preset: {CURRICULUM_PRESET_IDENTITY}</span>
          </div>
          <div style={styles.actionRow}>
            {canStartNewMatch && (
              <button type="button" style={styles.primaryButton} disabled={launching} onClick={() => void startNewMatch()}>
                {phase === 'IDLE' ? 'Start & Watch' : 'Start new match'}
              </button>
            )}
            {phase === 'ERROR' && error?.kind === 'STATUS_ERROR' && (
              <button type="button" style={styles.primaryButton} onClick={retryStatus}>
                Retry status
              </button>
            )}
            <button
              type="button"
              style={{ ...styles.primaryButton, ...(canStartHumanMatch ? {} : styles.disabledButton) }}
              disabled={!canStartHumanMatch}
              onClick={startHumanMatch}
            >
              {humanLaunching ? 'Starting human match…' : 'Play Akiri vs Engine AI Chevill'}
            </button>
          </div>
          {showNameEntry && (
            <div style={styles.connectForm}>
              <label htmlFor="research-arena-player-name" style={styles.smallText}>Connect as a player to enable the human seat</label>
              <div style={styles.connectRow}>
                <input
                  id="research-arena-player-name"
                  type="text"
                  value={playerName}
                  onChange={(event) => setPlayerName(event.target.value)}
                  onKeyDown={(event) => { if (event.key === 'Enter') handleConnect() }}
                  placeholder="Your name"
                  maxLength={20}
                  style={styles.nameInput}
                />
                <button
                  type="button"
                  style={{ ...styles.secondaryButton, ...(playerName.trim() ? {} : styles.disabledButton) }}
                  disabled={!playerName.trim()}
                  onClick={handleConnect}
                >
                  Connect
                </button>
              </div>
            </div>
          )}
          {connectionStatus === 'connecting' && <p style={styles.smallText}>Connecting the existing player session…</p>}
          {sessionReplaced && <p role="alert" style={styles.errorText}>This player session is active in another tab or device.</p>}
          {humanLaunchError && <p role="alert" style={styles.errorText}>{humanLaunchError}</p>}
        </section>

        <section style={styles.card} aria-labelledby="status-heading">
          <div style={styles.statusHeader}>
            <div>
              <p style={styles.eyebrow}>Match status</p>
              <h2 id="status-heading" style={styles.cardTitle}>Status</h2>
            </div>
            <span style={{ ...styles.statusPill, ...statusPillColor(phase) }} aria-live="polite">
              {statusLabel}
            </span>
          </div>
          {lobbyId && <p style={styles.smallText}>Lobby: <code>{lobbyId}</code></p>}
          {error && <p role="alert" style={styles.errorText}>{error.message}</p>}
          {!error && phase === 'IDLE' && <p style={styles.smallText}>Start the locked preset to create a new server-owned lobby.</p>}
          {status && (
            <p style={styles.smallText}>
              Round {status.round}/{status.totalRounds || '—'} · {status.playerNames.length} players
            </p>
          )}
        </section>
      </div>

      <section style={styles.card} aria-labelledby="live-games-heading">
        <div style={styles.statusHeader}>
          <div>
            <p style={styles.eyebrow}>Existing tournament status</p>
            <h2 id="live-games-heading" style={styles.cardTitle}>Live game</h2>
          </div>
          {phase === 'LIVE' && <span style={styles.liveIndicator}>● Live</span>}
        </div>
        {!status?.liveGames.length ? (
          <p style={styles.smallText}>
            {phase === 'COMPLETE'
              ? 'The tournament is complete.'
              : error ? 'No refreshed live-game data is available.' : 'Waiting for a live game…'}
          </p>
        ) : (
          <div>
            {status.liveGames.map((game) => (
              <div key={game.gameSessionId} style={styles.liveGameRow}>
                <div>
                  <div style={styles.gameNames}>{game.player1Name} vs {game.player2Name}</div>
                  <div style={styles.smallText}>Turn {game.turnNumber} · {game.player1Life} / {game.player2Life} life</div>
                </div>
                <button type="button" style={styles.watchButton} onClick={() => watchGame(game.gameSessionId)}>
                  Watch
                </button>
              </div>
            ))}
          </div>
        )}
      </section>

      <section style={styles.card}>
        <details>
          <summary style={styles.provenanceSummary}>Match provenance</summary>
          <div style={styles.provenanceBody}>
            <div>
              <span style={styles.provenanceLabel}>Preset</span>
              <code style={styles.valueCode}>{status?.presetIdentity ?? 'Waiting for server status'}</code>
            </div>
            {status?.curriculumSources.map((source) => (
              <div key={source.sourcePath} style={styles.sourceBlock}>
                <strong>{source.commander}</strong>
                <code style={styles.valueCode}>{source.sourcePath}</code>
                <span style={styles.smallText}>{source.cardCount} cards</span>
                <code style={styles.digest}>{source.sourceDigest}</code>
                <button
                  type="button"
                  style={styles.copyButton}
                  onClick={() => void navigator.clipboard?.writeText(source.sourceDigest)}
                >
                  Copy digest
                </button>
              </div>
            ))}
            {status && status.curriculumSources.length === 0 && (
              <p style={styles.smallText}>The server has not returned curriculum source provenance yet.</p>
            )}
          </div>
        </details>
      </section>
    </div>
  )
}

function getStatusLabel(phase: ArenaPhase, status: AiTournamentStatus | null, error: ArenaError | null): string {
  switch (phase) {
    case 'STARTING': return 'Starting…'
    case 'WAITING_FOR_LIVE_GAME': return 'Waiting for match…'
    case 'LIVE': return `Live — Turn ${status?.liveGames[0]?.turnNumber ?? '—'}`
    case 'COMPLETE': return 'Completed'
    case 'ERROR': return error?.kind === 'LOST_LOBBY' ? 'Lobby unavailable' : 'Error'
    default: return 'Ready'
  }
}

function statusPillColor(phase: ArenaPhase): CSSProperties {
  switch (phase) {
    case 'LIVE': return { background: '#166534', color: '#dcfce7' }
    case 'COMPLETE': return { background: '#3730a3', color: '#e0e7ff' }
    case 'ERROR': return { background: '#7f1d1d', color: '#fecaca' }
    default: return { background: '#78350f', color: '#fef3c7' }
  }
}

const styles: Record<string, CSSProperties> = {
  page: {
    minHeight: '100vh',
    background: '#0a0a12',
    color: '#e2e8f0',
    padding: '28px clamp(18px, 4vw, 52px)',
    fontFamily: 'system-ui, sans-serif',
  },
  header: { display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 20, maxWidth: 1080, margin: '0 auto 24px' },
  title: { fontSize: 30, margin: 0, display: 'flex', alignItems: 'center', gap: 10 },
  subtitle: { color: '#94a3b8', fontSize: 14, margin: '7px 0 0' },
  devTag: { fontSize: 11, background: '#7f1d1d', color: '#fecaca', padding: '3px 8px', borderRadius: 999, textTransform: 'uppercase', letterSpacing: 1 },
  homeLink: { color: '#7dd3fc', textDecoration: 'none', paddingTop: 7 },
  arenaGrid: { display: 'flex', flexWrap: 'wrap', alignItems: 'stretch', gap: 16, maxWidth: 1080, margin: '0 auto 16px' },
  card: { flex: '1 1 420px', background: '#11131d', border: '1px solid #1f2433', borderRadius: 14, padding: 20, marginBottom: 16, maxWidth: 1080, marginLeft: 'auto', marginRight: 'auto' },
  eyebrow: { color: '#7dd3fc', fontSize: 11, letterSpacing: 1, margin: '0 0 7px', textTransform: 'uppercase' },
  cardTitle: { color: '#f8fafc', fontSize: 19, margin: 0 },
  matchup: { display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) auto minmax(0, 1fr)', gap: 10, alignItems: 'center', marginTop: 20 },
  seatCard: { background: '#1e293b', border: '1px solid #334155', borderRadius: 9, color: '#f8fafc', fontSize: 14, fontWeight: 700, padding: '14px 10px', textAlign: 'center' },
  vs: { color: '#fbbf24', fontWeight: 800, letterSpacing: 1 },
  matchMeta: { display: 'flex', flexDirection: 'column', gap: 5, color: '#94a3b8', fontSize: 12, lineHeight: 1.4, marginTop: 14 },
  actionRow: { display: 'flex', flexWrap: 'wrap', gap: 10, marginTop: 20 },
  primaryButton: { background: '#2563eb', color: '#fff', border: 0, borderRadius: 8, cursor: 'pointer', fontSize: 14, fontWeight: 700, padding: '10px 16px' },
  secondaryButton: { background: 'transparent', color: '#7dd3fc', border: '1px solid #334155', borderRadius: 8, cursor: 'pointer', fontSize: 13, padding: '9px 14px' },
  disabledButton: { cursor: 'not-allowed', opacity: 0.5 },
  connectForm: { display: 'flex', flexDirection: 'column', gap: 8, marginTop: 16 },
  connectRow: { display: 'flex', flexWrap: 'wrap', gap: 8 },
  nameInput: { background: '#0f172a', border: '1px solid #334155', borderRadius: 7, color: '#e2e8f0', fontSize: 13, minWidth: 180, padding: '9px 10px' },
  statusHeader: { display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 },
  statusPill: { borderRadius: 999, fontSize: 12, fontWeight: 700, padding: '5px 10px', whiteSpace: 'nowrap' },
  smallText: { color: '#94a3b8', fontSize: 12, lineHeight: 1.5, margin: '10px 0 0' },
  errorText: { background: '#3f1116', border: '1px solid #7f1d1d', borderRadius: 8, color: '#fecaca', fontSize: 13, lineHeight: 1.45, margin: '14px 0 0', padding: '10px 12px' },
  liveIndicator: { color: '#86efac', fontSize: 12, fontWeight: 700 },
  liveGameRow: { alignItems: 'center', borderTop: '1px solid #1f2433', display: 'flex', gap: 14, justifyContent: 'space-between', padding: '14px 0 2px' },
  gameNames: { color: '#f8fafc', fontSize: 14, fontWeight: 700 },
  watchButton: { background: '#166534', border: '1px solid #16a34a', borderRadius: 8, color: '#dcfce7', cursor: 'pointer', fontSize: 13, fontWeight: 700, padding: '9px 16px' },
  provenanceSummary: { color: '#93c5fd', cursor: 'pointer', fontSize: 14, fontWeight: 700 },
  provenanceBody: { display: 'flex', flexDirection: 'column', gap: 16, marginTop: 16 },
  provenanceLabel: { color: '#94a3b8', display: 'block', fontSize: 11, marginBottom: 5, textTransform: 'uppercase' },
  valueCode: { color: '#e2e8f0', display: 'block', fontSize: 12, overflowWrap: 'anywhere' },
  sourceBlock: { borderTop: '1px solid #1f2433', display: 'flex', flexDirection: 'column', gap: 5, paddingTop: 14 },
  digest: { color: '#fbbf24', fontSize: 11, overflowWrap: 'anywhere' },
  copyButton: { alignSelf: 'flex-start', background: '#1e293b', border: '1px solid #334155', borderRadius: 6, color: '#cbd5e1', cursor: 'pointer', fontSize: 11, padding: '5px 8px' },
}
