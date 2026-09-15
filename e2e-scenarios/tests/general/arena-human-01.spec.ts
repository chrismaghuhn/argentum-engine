import { expect, test } from '@playwright/test'

test.describe('Research Arena Human vs Engine AI', () => {
  test('exposes the fixed Akiri human seat without changing the AI-AI viewer', async ({ page }) => {
    await page.goto('/dev/research-arena')

    await expect(page.getByRole('heading', { name: /^Research Arena/ }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Play Akiri vs Engine AI Chevill' })).toBeVisible()
    await expect(page.getByText('Engine AI vs Engine AI')).toBeVisible()
  })

  test('enters the normal player board without spectator navigation', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('argentum-player-name', 'Arena Browser Human')
    })

    await page.goto('/dev/research-arena')
    const play = page.getByRole('button', { name: 'Play Akiri vs Engine AI Chevill' })
    await expect(play).toBeEnabled({ timeout: 30_000 })
    await play.evaluate((button) => {
      button.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      button.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })

    await expect(page).toHaveURL(/\/$/, { timeout: 30_000 })
    await expect(page.getByRole('button', { name: 'Keep Hand' })).toBeVisible({ timeout: 30_000 })
    expect(page.url()).not.toContain('spectate=')
  })

  test('uses the existing name gate before enabling the human seat', async ({ page }) => {
    await page.goto('/dev/research-arena')

    const play = page.getByRole('button', { name: 'Play Akiri vs Engine AI Chevill' })
    await expect(play).toBeDisabled()
    await expect(page.getByLabel('Connect as a player to enable the human seat')).toBeVisible()

    await page.getByLabel('Connect as a player to enable the human seat').fill('Arena Named Human')
    await page.getByRole('button', { name: 'Connect' }).click()
    await expect(play).toBeEnabled({ timeout: 30_000 })
  })
})
