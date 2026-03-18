import { test, expect } from '@playwright/test'

test.describe('Components Registry UI – smoke', () => {

  test('loads and shows component list', async ({ page }) => {
    await page.goto('/ui')
    // redirects to /ui/components
    await page.waitForURL('**/ui/components')
    await expect(page.getByRole('heading', { name: 'Components' })).toBeVisible()

    // total count badge is visible and non-zero
    const badge = page.locator('text=/\\d+ total/')
    await expect(badge).toBeVisible({ timeout: 10_000 })
    const text = await badge.textContent()
    const count = parseInt(text ?? '0')
    expect(count).toBeGreaterThan(0)

    // table has rows
    const rows = page.locator('table tbody tr')
    await expect(rows.first()).toBeVisible({ timeout: 10_000 })
  })

  test('navigation links work', async ({ page }) => {
    await page.goto('/ui/components')

    await page.getByRole('link', { name: /audit/i }).click()
    await page.waitForURL('**/ui/audit')
    await expect(page.getByRole('heading', { name: /audit/i })).toBeVisible()

    await page.getByRole('link', { name: /admin/i }).click()
    await page.waitForURL('**/ui/admin')
    await expect(page.getByRole('heading', { name: /admin/i })).toBeVisible()

    await page.getByRole('link', { name: /components/i }).first().click()
    await page.waitForURL('**/ui/components')
    await expect(page.getByRole('heading', { name: 'Components' })).toBeVisible()
  })

  test('component detail page loads', async ({ page }) => {
    // Get a component ID from the API directly to navigate without depending on table click timing
    const resp = await page.request.get('/rest/api/4/components?page=0&size=1')
    const data = await resp.json()
    const firstId = data.content[0].id

    await page.goto(`/ui/components/${firstId}`, { waitUntil: 'networkidle' })
    await page.waitForURL('**/ui/components/**')

    // header with component name and Save / Delete buttons
    await expect(page.getByRole('button', { name: /save/i })).toBeVisible({ timeout: 10_000 })
    await expect(page.getByRole('button', { name: /delete/i })).toBeVisible()

    // tabs present
    await expect(page.getByRole('tab', { name: /general/i })).toBeVisible()
    await expect(page.getByRole('tab', { name: /build/i })).toBeVisible()
    await expect(page.getByRole('tab', { name: /vcs/i })).toBeVisible()

    console.log('Opened component detail:', firstId)
  })

  test('no JS errors on main pages', async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', err => errors.push(err.message))
    page.on('console', msg => {
      if (msg.type() === 'error') errors.push(msg.text())
    })

    for (const path of ['/ui/components', '/ui/audit', '/ui/admin']) {
      await page.goto(path)
      await page.waitForLoadState('networkidle')
    }

    expect(errors).toEqual([])
  })
})
