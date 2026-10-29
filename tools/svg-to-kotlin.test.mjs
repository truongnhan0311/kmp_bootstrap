// node --test tools/*.test.mjs
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { writeFileSync, mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const gen = files => {
  const dir = mkdtempSync(join(tmpdir(), 'svg-'))
  for (const [n, c] of Object.entries(files)) writeFileSync(join(dir, n), c)
  return execFileSync('node', ['tools/svg-to-kotlin.mjs', dir, '--package', 'x.y'], { encoding: 'utf8' })
}

test('mỗi file một icon, tên PascalCase, có bảng tra theo tên file', () => {
  const kt = gen({ 'chevron-right.svg': '<svg viewBox="0 0 24 24"><path d="m9 5 7 7-7 7"/></svg>', 'home.svg': '<svg viewBox="0 0 24 24">\n  <path d="M4 10z"/>\n</svg>' })
  assert.match(kt, /val ChevronRight: SvgIcon by lazy/); assert.match(kt, /val Home: SvgIcon by lazy/)
  assert.match(kt, /"chevron-right" to ChevronRight/); assert.match(kt, /package x\.y/)
  assert.match(kt, /<svg viewBox="0 0 24 24"><path d="M4 10z"\/><\/svg>/, 'xuống dòng/khoảng trắng thừa được gọn')
})

test('ký tự $ không bị Kotlin hiểu thành nội suy chuỗi; tên bắt đầu bằng số được thêm tiền tố', () => {
  const kt = gen({ '24-hours.svg': '<svg viewBox="0 0 24 24"><path d="M0 0" data-x="$a"/></svg>' })
  assert.match(kt, /val Icon24Hours/); assert.ok(kt.includes("${'$'}a"))
})

test('hai file cùng ra một tên và file không phải SVG đều báo lỗi rõ', () => {
  assert.throws(() => gen({ 'a-b.svg': '<svg/>', 'a_b.svg': '<svg/>' }), /Hai file cùng ra tên AB/)
  assert.throws(() => gen({ 'x.svg': 'không phải svg' }), /không phải SVG/)
})
