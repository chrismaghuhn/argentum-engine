import type { AiLiveGame, AiTournamentStatus } from '@/api/aiTournamentApi'

export type ArenaPhase =
  | 'IDLE'
  | 'STARTING'
  | 'WAITING_FOR_LIVE_GAME'
  | 'LIVE'
  | 'COMPLETE'
  | 'ERROR'

export type ArenaErrorKind = 'CREATE_ERROR' | 'STATUS_ERROR' | 'LOST_LOBBY'

export function arenaPhaseForStatus(
  status: AiTournamentStatus,
): Exclude<ArenaPhase, 'IDLE' | 'STARTING' | 'ERROR'> {
  if (status.complete) return 'COMPLETE'
  return status.liveGames.length > 0 ? 'LIVE' : 'WAITING_FOR_LIVE_GAME'
}

export function arenaErrorAction(kind: ArenaErrorKind): 'RETRY_STATUS' | 'START_NEW_MATCH' {
  return kind === 'STATUS_ERROR' ? 'RETRY_STATUS' : 'START_NEW_MATCH'
}

export function firstUnwatchedLiveGame(
  games: readonly AiLiveGame[],
  wasWatched: (id: string) => boolean,
): AiLiveGame | null {
  return games.find((game) => !wasWatched(game.gameSessionId)) ?? null
}

/**
 * TournamentMatchStarting assigns a session id before the server has finished starting the game.
 * The normal player's mulligan decision is the first usable signal that GameStarted completed.
 */
export function humanGameReadyForNavigation(sessionId: string | null, hasMulliganDecision: boolean): boolean {
  return sessionId !== null && hasMulliganDecision
}
