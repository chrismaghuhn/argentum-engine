import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  CURRICULUM_PRESET_IDENTITY,
  createCurriculumAiTournament,
  fetchAiTournamentStatus,
} from './aiTournamentApi'

afterEach(() => vi.unstubAllGlobals())

describe('AI tournament transport', () => {
  it('sends the exact preset-only create request', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      lobbyId: 'lobby-1',
      spectateUrl: '/tournament/lobby-1',
      message: 'created',
      presetIdentity: CURRICULUM_PRESET_IDENTITY,
      curriculumSources: [],
    }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await createCurriculumAiTournament()

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/dev/ai-tournament')
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ 'Content-Type': 'application/json' })
    expect(JSON.parse(String(init.body))).toEqual({ preset: CURRICULUM_PRESET_IDENTITY })
  })

  it('parses the existing status response', async () => {
    const status = {
      lobbyId: 'lobby-1',
      state: 'TOURNAMENT_ACTIVE',
      playerNames: ['Akiri', 'Chevill'],
      decksSubmitted: 2,
      round: 1,
      totalRounds: 3,
      complete: false,
      liveGames: [{
        gameSessionId: 'game-1',
        player1Name: 'Akiri',
        player2Name: 'Chevill',
        player1Life: 40,
        player2Life: 37,
        turnNumber: 3,
      }],
      presetIdentity: CURRICULUM_PRESET_IDENTITY,
      curriculumSources: [{
        sourcePath: 'docs/ml/curriculum/akiri-v0.1.txt',
        sourceDigest: 'E774',
        commander: 'Akiri, Fearless Voyager',
        cardCount: 100,
      }],
    }
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(status), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(fetchAiTournamentStatus('lobby-1')).resolves.toEqual(status)
    expect(fetchMock).toHaveBeenCalledWith('/api/dev/ai-tournament/lobby-1')
  })

  it('keeps the HTTP status and server message on failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ message: 'locked preset rejected' }), { status: 400 }),
    ))

    await expect(createCurriculumAiTournament()).rejects.toMatchObject({
      name: 'AiTournamentApiError',
      status: 400,
      message: 'locked preset rejected',
    })
  })
})
