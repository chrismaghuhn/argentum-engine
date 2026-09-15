export const AI_TOURNAMENT_API = '/api/dev/ai-tournament'
export const CURRICULUM_PRESET_IDENTITY = 'argentum-mtg-ml-akiri-chevill-curriculum@v1'

export interface AiLiveGame {
  gameSessionId: string
  player1Name: string
  player2Name: string
  player1Life: number
  player2Life: number
  turnNumber: number
}

export interface CurriculumSourceProvenance {
  sourcePath: string
  sourceDigest: string
  commander: string
  cardCount: number
}

export interface AiTournamentResponse {
  lobbyId: string
  spectateUrl: string
  message: string
  presetIdentity: string | null
  curriculumSources: CurriculumSourceProvenance[]
}

export interface AiTournamentStatus {
  lobbyId: string
  state: string
  playerNames: string[]
  decksSubmitted: number
  round: number
  totalRounds: number
  complete: boolean
  liveGames: AiLiveGame[]
  presetIdentity: string | null
  curriculumSources: CurriculumSourceProvenance[]
}

export class AiTournamentApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
    this.name = 'AiTournamentApiError'
  }
}

async function readJsonOrThrow<T>(response: Response, fallbackMessage: string): Promise<T> {
  if (response.ok) return await response.json() as T

  const responseText = await response.text()
  let message = fallbackMessage
  if (responseText) {
    try {
      const parsed: unknown = JSON.parse(responseText)
      if (typeof parsed === 'object' && parsed !== null && 'message' in parsed) {
        const serverMessage = parsed.message
        if (typeof serverMessage === 'string' && serverMessage.length > 0) message = serverMessage
      }
    } catch {
      // Proxies and disabled dev routes may return non-JSON error pages.
    }
  }
  throw new AiTournamentApiError(response.status, message)
}

export async function createCurriculumAiTournament(): Promise<AiTournamentResponse> {
  const response = await fetch(AI_TOURNAMENT_API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ preset: CURRICULUM_PRESET_IDENTITY }),
  })
  return readJsonOrThrow<AiTournamentResponse>(response, 'Failed to create the Research Arena match')
}

export async function fetchAiTournamentStatus(lobbyId: string): Promise<AiTournamentStatus> {
  const response = await fetch(`${AI_TOURNAMENT_API}/${encodeURIComponent(lobbyId)}`)
  return readJsonOrThrow<AiTournamentStatus>(response, 'Failed to refresh the AI tournament status')
}
