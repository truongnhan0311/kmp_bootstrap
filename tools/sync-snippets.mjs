#!/usr/bin/env node
// Chép các đoạn `// region: <tên>` … `// endregion` từ các file ví dụ (*Examples.kt — đã biên dịch và chạy trong test) vào tài liệu .md
// tại chỗ có đánh dấu  <!-- snippet: <tên> -->  …  <!-- /snippet -->.  Nhờ vậy tài liệu KHÔNG BAO GIỜ lệch khỏi code chạy được.
//   node tools/sync-snippets.mjs          ghi lại tài liệu
//   node tools/sync-snippets.mjs --check  chỉ kiểm: thoát mã 1 nếu tài liệu lệch (dùng cho CI)
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

const check = process.argv.includes('--check')
const walk = (dir, out = []) => {
  for (const f of readdirSync(dir)) {
    if (['build', '.git', '.gradle', 'node_modules', '.kotlin'].includes(f)) continue
    const p = join(dir, f)
    statSync(p).isDirectory() ? walk(p, out) : out.push(p)
  }
  return out
}
const files = walk('.')

const snippets = {}
for (const f of files.filter(f => /Examples\.kt$/.test(f))) {
  const lines = readFileSync(f, 'utf8').split('\n')
  let name = null, buf = []
  for (const line of lines) {
    const start = line.match(/^\s*\/\/ region: (\S+)/)
    if (start) { name = start[1]; buf = []; continue }
    if (/^\s*\/\/ endregion/.test(line) && name) {
      const indent = Math.min(...buf.filter(l => l.trim()).map(l => l.match(/^ */)[0].length))
      if (snippets[name]) throw new Error(`snippet trùng tên: ${name}`)
      snippets[name] = buf.map(l => l.slice(Number.isFinite(indent) ? indent : 0)).join('\n').replace(/\s+$/, '')
      name = null; continue
    }
    if (name) buf.push(line)
  }
}

let bad = 0
for (const f of files.filter(f => f.endsWith('.md'))) {
  const src = readFileSync(f, 'utf8')
  const next = src.replace(/<!-- snippet: (\S+) -->[\s\S]*?<!-- \/snippet -->/g, (_, name) => {
    if (!(name in snippets)) { console.error(`${f}: không có snippet '${name}'`); bad++; return _ }
    return `<!-- snippet: ${name} -->\n\`\`\`kotlin\n${snippets[name]}\n\`\`\`\n<!-- /snippet -->`
  })
  if (next !== src) { if (check) { console.error(`${f}: lệch so với code ví dụ — chạy node tools/sync-snippets.mjs`); bad++ } else { writeFileSync(f, next); console.log('cập nhật ' + f) } }
}
const used = new Set(files.filter(f => f.endsWith('.md')).flatMap(f => [...readFileSync(f, 'utf8').matchAll(/<!-- snippet: (\S+) -->/g)].map(m => m[1])))
for (const n of Object.keys(snippets)) if (!used.has(n)) console.error(`cảnh báo: snippet '${n}' chưa được tài liệu nào dùng`)
console.log(`${Object.keys(snippets).length} snippet`)
process.exit(bad ? 1 : 0)
