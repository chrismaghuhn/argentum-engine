import { expect, test, type Page } from '@playwright/test'

const PRESET = 'argentum-mtg-ml-akiri-chevill-curriculum@v1'
const SOURCE_PATH = 'docs/ml/curriculum/akiri-v0.1.txt'
const SOURCE_DIGEST = 'E774FULLDIGEST'

function waitingStatus() {
  return {
    lobbyId: 'lobby-1',
    state: 'DECK_BUILDING',
    playerNames: ['Akiri, Fearless Voyager', 'Chevill, Bane of Monsters'],
    decksSubmitted: 2,
    round: 0,
    totalRounds: 3,
    complete: false,
    liveGames: [],
    presetIdentity: PRESET,
    curriculumSources: [{
      sourcePath: SOURCE_PATH,
      sourceDigest: SOURCE_DIGEST,
      commander: 'Akiri, Fearless Voyager',
      cardCount: 100,
    }],
  }
}

function liveStatus() {
  return {
    ...waitingStatus(),
    state: 'TOURNAMENT_ACTIVE',
    liveGames: [{
      gameSessionId: 'game-1',
      player1Name: 'Akiri, Fearless Voyager',
      player2Name: 'Chevill, Bane of Monsters',
      player1Life: 40,
      player2Life: 37,
      turnNumber: 3,
    }],
  }
}

async function stubCreate(page: Page, status = waitingStatus()) {
  let createCount = 0
  const requestBodies: unknown[] = []
  await page.route('**/api/dev/ai-tournament', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.continue()
      return
    }
    createCount += 1
    requestBodies.push(route.request().postDataJSON())
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        lobbyId: 'lobby-1',
        spectateUrl: '/tournament/lobby-1',
        message: 'created',
        presetIdentity: PRESET,
        curriculumSources: status.curriculumSources,
      }),
    })
  })
  return { getCreateCount: () => createCount, requestBodies }
}

async function stubStatus(page: Page, responses: Array<{ status?: number; body: Record<string, unknown> }>) {
  let statusCount = 0
  await page.route('**/api/dev/ai-tournament/lobby-1', async (route) => {
    const response = responses[Math.min(statusCount, responses.length - 1)]!
    statusCount += 1
    await route.fulfill({
      status: response.status ?? 200,
      contentType: 'application/json',
      body: JSON.stringify(response.body),
    })
  })
  return { getStatusCount: () => statusCount }
}

test.describe('Research Arena', () => {
  test('renders the locked development route and matchup', async ({ page }) => {
    await page.goto('/dev/research-arena')

    await expect(page.getByRole('heading', { name: /^Research Arena/ }).first()).toBeVisible()
    await expect(page.getByText('Development / ML Research Tooling')).toBeVisible()
    await expect(page.getByText('Akiri, Fearless Voyager')).toBeVisible()
    await expect(page.getByText('Chevill, Bane of Monsters')).toBeVisible()
    await expect(page.getByText('Engine AI vs Engine AI')).toBeVisible()
  })

  test('sends one exact preset request and renders server provenance', async ({ page }) => {
    let releaseCreate!: () => void
    const createGate = new Promise<void>((resolve) => { releaseCreate = resolve })
    let createCount = 0
    const requestBodies: unknown[] = []
    await page.route('**/api/dev/ai-tournament', async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      createCount += 1
      requestBodies.push(route.request().postDataJSON())
      await createGate
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          lobbyId: 'lobby-1', spectateUrl: '/tournament/lobby-1', message: 'created',
          presetIdentity: PRESET, curriculumSources: waitingStatus().curriculumSources,
        }),
      })
    })
    await stubStatus(page, [{ body: waitingStatus() }])

    await page.goto('/dev/research-arena')
    const start = page.getByRole('button', { name: 'Start & Watch' })
    await start.evaluate((button) => {
      button.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      button.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })
    await expect.poll(() => createCount).toBe(1)
    expect(requestBodies).toEqual([{ preset: PRESET }])
    releaseCreate()

    await expect(page).toHaveURL(/\/dev\/research-arena\/lobby-1$/)
    await page.getByText('Match provenance').click()
    await expect(page.getByText(SOURCE_PATH)).toBeVisible()
    await expect(page.getByText(SOURCE_DIGEST)).toBeVisible()
  })

  test('retries a failed status GET without creating another match', async ({ page }) => {
    const create = await stubCreate(page)
    const status = await stubStatus(page, [
      { status: 503, body: { message: 'temporary status failure' } },
      { body: waitingStatus() },
    ])

    await page.goto('/dev/research-arena')
    await page.getByRole('button', { name: 'Start & Watch' }).click()
    await expect(page).toHaveURL(/\/dev\/research-arena\/lobby-1$/)
    await expect(page.getByRole('button', { name: 'Retry status' })).toBeVisible()
    await page.getByRole('button', { name: 'Retry status' }).click()
    await expect(page.getByText('Waiting for match…')).toBeVisible()
    expect(create.getCreateCount()).toBe(1)
    expect(status.getStatusCount()).toBe(2)
  })

  test('shows only Retry status during STATUS_ERROR', async ({ page }) => {
    await stubStatus(page, [{ status: 503, body: { message: 'temporary status failure' } }])

    await page.goto('/dev/research-arena/lobby-1')

    await expect(page.getByRole('button', { name: 'Retry status' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Start new match' })).toHaveCount(0)
  })

  test('does not offer a new match while waiting or live', async ({ page }) => {
    await stubStatus(page, [{ body: waitingStatus() }])
    await page.goto('/dev/research-arena/lobby-1')
    await expect(page.getByText('Waiting for match…')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Start new match' })).toHaveCount(0)

    await page.addInitScript(() => {
      sessionStorage.setItem('argentum-research-arena-watched:game-1', '1')
    })
    await page.unroute('**/api/dev/ai-tournament/lobby-1')
    await stubStatus(page, [{ body: liveStatus() }])
    await page.goto('/dev/research-arena/lobby-1')
    await expect(page.getByText('Live — Turn 3')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Start new match' })).toHaveCount(0)
  })

  test('offers a new match after LOST_LOBBY', async ({ page }) => {
    await page.route('**/api/dev/ai-tournament/lobby-1', async (route) => {
      await route.fulfill({ status: 404, body: 'Not Found' })
    })

    await page.goto('/dev/research-arena/lobby-1')

    await expect(page.getByText('Lobby unavailable')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Start new match' })).toBeVisible()
  })

  test('handles a disabled create endpoint without starting status polling', async ({ page }) => {
    let statusCount = 0
    await page.route('**/api/dev/ai-tournament/lobby-1', async (route) => {
      statusCount += 1
      await route.continue()
    })
    await page.route('**/api/dev/ai-tournament', async (route) => {
      await route.fulfill({ status: 404, body: 'Not Found' })
    })

    await page.goto('/dev/research-arena')
    await page.getByRole('button', { name: 'Start & Watch' }).click()
    await expect(page.getByText('Research Arena dev endpoint is not enabled on this server.')).toBeVisible()
    await page.waitForTimeout(1_700)
    expect(statusCount).toBe(0)
  })

  test('auto-watches one live session and resumes without an auto-watch bounce', async ({ page }) => {
    await stubCreate(page)
    await stubStatus(page, [{ body: waitingStatus() }, { body: liveStatus() }])

    await page.goto('/dev/research-arena')
    await page.getByRole('button', { name: 'Start & Watch' }).click()
    await expect(page).toHaveURL(/\/dev\/research-arena\/lobby-1$/)
    await expect(page).toHaveURL(/[?]spectate=game-1$/)
    await expect.poll(() => page.evaluate(() => sessionStorage.getItem('argentum-research-arena-watched:game-1'))).toBe('1')

    await page.goBack()
    await expect(page).toHaveURL(/\/dev\/research-arena\/lobby-1$/)
    await page.waitForTimeout(1_700)
    expect(page.url()).not.toContain('spectate=game-1')
    await expect(page.getByRole('button', { name: 'Watch' })).toBeVisible()
  })
})
