import { describe, expect, it } from 'vitest'
import type { AiLiveGame, AiTournamentStatus } from '@/api/aiTournamentApi'
import {
  arenaErrorAction,
  arenaPhaseForStatus,
  firstUnwatchedLiveGame,
} from './researchArenaState'

const game: AiLiveGame = {
  gameSessionId: 'game-1',
  player1Name: 'Akiri',
  player2Name: 'Chevill',
  player1Life: 40,
  player2Life: 40,
  turnNumber: 3,
}

const status = (overrides: Partial<AiTournamentStatus>): AiTournamentStatus => ({
  lobbyId: 'lobby-1',
  state: 'TOURNAMENT_ACTIVE',
  playerNames: ['Akiri', 'Chevill'],
  decksSubmitted: 2,
  round: 1,
  totalRounds: 3,
  complete: false,
  liveGames: [],
  presetIdentity: null,
  curriculumSources: [],
  ...overrides,
})

describe('Research Arena state decisions', () => {
  it('uses COMPLETE when the server says complete', () => {
    expect(arenaPhaseForStatus(status({ complete: true, liveGames: [game] }))).toBe('COMPLETE')
  })

  it('uses LIVE only for a server-reported live game', () => {
    expect(arenaPhaseForStatus(status({ liveGames: [game] }))).toBe('LIVE')
    expect(arenaPhaseForStatus(status({}))).toBe('WAITING_FOR_LIVE_GAME')
  })

  it('retries status in place and starts new matches for create/lost errors', () => {
    expect(arenaErrorAction('STATUS_ERROR')).toBe('RETRY_STATUS')
    expect(arenaErrorAction('CREATE_ERROR')).toBe('START_NEW_MATCH')
    expect(arenaErrorAction('LOST_LOBBY')).toBe('START_NEW_MATCH')
  })

  it('selects the first unwatched live game', () => {
    expect(firstUnwatchedLiveGame([game], (id) => id === 'game-1')).toBeNull()
    expect(firstUnwatchedLiveGame([game], () => false)).toEqual(game)
  })
})
