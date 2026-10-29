#!/usr/bin/env node
// Đọc biến CSS kiểu shadcn (--background, --primary, …) và sinh `AppColors` sáng/tối cho Kotlin.
// Nhờ đó web và app dùng CHUNG một bảng màu: sửa CSS rồi chạy lại script, không sửa màu ở hai nơi.
//
//   node tools/shadcn-theme.mjs <file.css> --package com.acme.ui --name Acme [--out Acme.kt]
//                               [--light ":root"] [--dark ".dark"]
//
// Cascade: màu sáng = khối `--light` đè lên `:root`; màu tối = khối `--dark` đè lên `:root`.
// shadcn mặc định: `:root` là sáng, `.dark` là tối  → bỏ qua --light/--dark.
// Taper: `:root` là tối, có `:root[data-theme='light']` → --light ":root[data-theme='light']" --dark ":root[data-theme='dark']".
// Hỗ trợ màu: #hex (3/4/6/8), rgb(), hsl(), oklch(), và var(--x) lồng nhau. Token không phải màu (radius…) bị bỏ qua.
import { readFileSync, writeFileSync } from 'node:fs'

const args = process.argv.slice(2)
const opt = (name, fallback) => { const i = args.indexOf('--' + name); return i >= 0 ? args[i + 1] : fallback }
const file = args.find((a, i) => !a.startsWith('--') && !args[i - 1]?.startsWith('--'))
if (!file) { console.error('Cách dùng: shadcn-theme.mjs <file.css> --package <pkg> --name <Tên> [--out file.kt] [--light sel] [--dark sel]'); process.exit(2) }
const pkg = opt('package', 'app.ui'), name = opt('name', 'App'), out = opt('out')
const norm = s => s.replace(/\s+/g, '').replace(/["']/g, '')
const lightSel = norm(opt('light', ':root')), darkSel = norm(opt('dark', '.dark'))

const css = readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
const blocks = new Map()
for (const m of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
  for (const sel of m[1].split(';').pop().split(',').map(norm)) {
    const decl = blocks.get(sel) ?? {}
    for (const d of m[2].matchAll(/(--[\w-]+)\s*:\s*([^;]+);?/g)) decl[d[1]] = d[2].trim()
    blocks.set(sel, decl)
  }
}
const base = blocks.get(':root') ?? {}
const cascade = sel => ({ ...base, ...(sel === ':root' ? {} : blocks.get(sel) ?? {}) })

function resolve(value, vars, seen = new Set()) {
  const m = value.match(/^var\(\s*(--[\w-]+)\s*(?:,\s*(.+))?\)$/)
  if (!m) return value
  if (seen.has(m[1])) throw new Error('var() vòng lặp: ' + m[1])
  seen.add(m[1])
  const next = vars[m[1]] ?? m[2]
  if (next === undefined) throw new Error('thiếu biến ' + m[1])
  return resolve(next.trim(), vars, seen)
}

const clamp = (x, lo = 0, hi = 1) => Math.min(hi, Math.max(lo, x))
const toByte = x => Math.round(clamp(x) * 255)
const gamma = c => c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055

function parseColor(v) {
  v = v.trim()
  let m
  if ((m = v.match(/^#([0-9a-f]{3,8})$/i))) {
    let h = m[1]
    if (h.length === 3 || h.length === 4) h = [...h].map(c => c + c).join('')
    if (h.length === 6) h += 'ff'
    if (h.length !== 8) return null
    return [0, 2, 4].map(i => parseInt(h.slice(i, i + 2), 16)).concat(parseInt(h.slice(6, 8), 16))
  }
  const fn = v.match(/^(rgba?|hsla?|oklch)\(\s*(.+)\)$/i)
  if (!fn) return null
  const parts = fn[2].split(/[\s,\/]+/).filter(Boolean)
  const num = (s, pct = 1) => s.endsWith('%') ? parseFloat(s) / 100 * pct : parseFloat(s)
  const alphaOf = i => parts[i] === undefined ? 255 : toByte(num(parts[i], 1))
  switch (fn[1].toLowerCase()) {
    case 'rgb': case 'rgba':
      return [0, 1, 2].map(i => Math.round(clamp(num(parts[i], 255), 0, 255))).concat(alphaOf(3))
    case 'hsl': case 'hsla': {
      const h = (parseFloat(parts[0]) % 360 + 360) % 360, s = num(parts[1]), l = num(parts[2])
      const k = n => (n + h / 30) % 12, a = s * Math.min(l, 1 - l)
      const f = n => l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)))
      return [f(0), f(8), f(4)].map(toByte).concat(alphaOf(3))
    }
    case 'oklch': {
      const L = num(parts[0]), C = parseFloat(parts[1]), H = parseFloat(parts[2]) * Math.PI / 180
      const a = C * Math.cos(H), b = C * Math.sin(H)
      const l_ = Math.pow(L + 0.3963377774 * a + 0.2158037573 * b, 3)
      const m_ = Math.pow(L - 0.1055613458 * a - 0.0638541728 * b, 3)
      const s_ = Math.pow(L - 0.0894841775 * a - 1.2914855480 * b, 3)
      const r = 4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_
      const g = -1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_
      const bl = -0.0041960863 * l_ - 0.7034186147 * m_ + 1.7076147010 * s_
      return [r, g, bl].map(c => toByte(gamma(clamp(c)))).concat(alphaOf(3))
    }
  }
  return null
}

const CORE = ['background', 'foreground', 'card', 'card-foreground', 'popover', 'popover-foreground', 'primary', 'primary-foreground',
  'secondary', 'secondary-foreground', 'muted', 'muted-foreground', 'accent', 'accent-foreground', 'destructive', 'destructive-foreground',
  'border', 'input', 'ring']
// Token thiếu thì rơi về token liên quan (shadcn bản cũ không có đủ cả 19).
const FALLBACK = { 'card-foreground': 'foreground', popover: 'card', 'popover-foreground': 'card-foreground', 'secondary-foreground': 'foreground',
  'accent-foreground': 'foreground', 'destructive-foreground': 'primary-foreground', ring: 'primary', input: 'border' }
const camel = s => s.replace(/-([a-z])/g, (_, c) => c.toUpperCase())
const hex = ([r, g, b, a]) => '0x' + [a, r, g, b].map(x => x.toString(16).padStart(2, '0')).join('').toUpperCase()

function colorsOf(vars) {
  const resolved = {}
  for (const [k, raw] of Object.entries(vars)) {
    let c = null
    try { c = parseColor(resolve(raw, vars)) } catch (e) { c = null }
    if (c) resolved[k.slice(2)] = c
  }
  const get = key => resolved[key] ?? (FALLBACK[key] ? get(FALLBACK[key]) : undefined)
  const core = {}
  for (const key of CORE) {
    core[key] = get(key)
    if (!core[key]) throw new Error(`thiếu token màu --${key} (và không có token để thay thế)`)
  }
  const extra = Object.fromEntries(Object.entries(resolved).filter(([k]) => !CORE.includes(k)))
  return { core, extra }
}

function emit(valName, dark, { core, extra }) {
  const lines = [`val ${valName} = AppColors(`, `    dark = ${dark},`]
  for (const key of CORE) lines.push(`    ${camel(key)} = Color(${hex(core[key])}),`)
  const ex = Object.entries(extra).sort(([a], [b]) => a.localeCompare(b))
  if (ex.length) {
    lines.push('    extra = mapOf(')
    for (const [k, v] of ex) lines.push(`        "${k}" to Color(${hex(v)}),`)
    lines.push('    ),')
  }
  lines.push(')')
  return lines.join('\n')
}

const light = colorsOf(cascade(lightSel)), dark = colorsOf(cascade(darkSel))
const kt = `// SINH TỰ ĐỘNG bởi tools/shadcn-theme.mjs từ ${file.split('/').pop()} — đừng sửa tay, sửa CSS rồi chạy lại.
package ${pkg}

import androidx.compose.ui.graphics.Color
import vn.truongnhan.appkit.ui.AppColors

${emit(name + 'LightColors', false, light)}

${emit(name + 'DarkColors', true, dark)}
`
if (out) { writeFileSync(out, kt); console.error('đã ghi ' + out) } else process.stdout.write(kt)
