import { expect, test } from '@playwright/test'

test.describe('Research Arena Human vs Engine AI', () => {
  test('exposes the fixed Akiri human seat without changing the AI-AI viewer', async ({ page }) => {
    await page.goto('/dev/research-arena')

    await expect(page.getByRole('heading', { name: /^Research Arena/ }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Play Akiri vs Engine AI Chevill' })).toBeVisible()
    await expect(page.getByText('Engine AI vs Engine AI')).toBeVisible()
  })
})
