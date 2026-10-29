// node --test tools/*.test.mjs
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { writeFileSync, mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const run = (css, ...extra) => {
  const f = join(mkdtempSync(join(tmpdir(), 'shadcn-')), 'a.css')
  writeFileSync(f, css)
  return execFileSync('node', ['tools/shadcn-theme.mjs', f, '--package', 'x', '--name', 'T', ...extra], { encoding: 'utf8' })
}
const ALL = ['background', 'foreground', 'card', 'card-foreground', 'popover', 'popover-foreground', 'primary', 'primary-foreground', 'secondary',
  'secondary-foreground', 'muted', 'muted-foreground', 'accent', 'accent-foreground', 'destructive', 'destructive-foreground', 'border', 'input', 'ring']
const block = (sel, fill) => `${sel} {\n${ALL.map(k => `  --${k}: ${fill(k)};`).join('\n')}\n}\n`

test('oklch của shadcn mặc định ra đúng màu (trắng, gần-đen, xám trung tính)', () => {
  const css = '@import "tailwindcss";\n' + block(':root', k => k === 'background' ? 'oklch(1 0 0)' : k === 'foreground' ? 'oklch(0.145 0 0)' : 'oklch(0.5 0 0)') +
    block('.dark', k => k === 'background' ? 'oklch(0.145 0 0)' : 'oklch(1 0 0)')
  const kt = run(css)
  assert.match(kt, /background = Color\(0xFFFFFFFF\)/)
  assert.match(kt, /foreground = Color\(0xFF0A0A0A\)/)   // neutral-950 của shadcn
  assert.match(kt, /val TDarkColors/)
  assert.match(kt.split('val TDarkColors')[1], /background = Color\(0xFF0A0A0A\)/)
})

test('hsl, rgb, hex ngắn và var() lồng nhau', () => {
  const css = ':root{--brand:#0f0;}\n' + block(':root', k => k === 'primary' ? 'var(--brand)' : k === 'background' ? 'hsl(0 0% 100%)' : k === 'border' ? 'rgb(255, 0, 0)' : '#000')
  const kt = run(css)
  assert.match(kt, /primary = Color\(0xFF00FF00\)/)
  assert.match(kt, /background = Color\(0xFFFFFFFF\)/)
  assert.match(kt, /border = Color\(0xFFFF0000\)/)
})

test('token thiếu rơi về token liên quan; thiếu hẳn thì báo lỗi rõ', () => {
  const css = block(':root', k => '#123456').split('\n').filter(l => !/--(ring|input|popover)/.test(l)).join('\n')
  const kt = run(css)
  assert.match(kt, /ring = Color\(0xFF123456\)/)
  assert.throws(() => run(':root{--x:#fff;}'), /thiếu token màu --background/)
})
