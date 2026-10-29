#!/usr/bin/env node
// Biến một thư mục file .svg thành object Kotlin `AppIcons` (mỗi file một icon, tên theo tên file: chevron-right.svg → AppIcons.ChevronRight).
//   node tools/svg-to-kotlin.mjs appkit-ui/icons --package kmp.bootstrap.appkit.ui --out appkit-ui/src/commonMain/kotlin/kmp/bootstrap/appkit/ui/AppIcons.kt
// Thêm icon: thả file .svg vào thư mục rồi chạy lại. Icon Lucide / Heroicons / Feather / Tabler dùng được nguyên file
// (stroke="currentColor" sẽ lấy màu truyền vào AppIcon). Hỗ trợ: path, line, polyline, polygon, circle, ellipse, rect (xem appkit-core/.../Svg.kt).
import { readFileSync, writeFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

const args = process.argv.slice(2)
const opt = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 ? args[i + 1] : d }
const dir = args.find((a, i) => !a.startsWith('--') && !args[i - 1]?.startsWith('--'))
if (!dir) { console.error('Cách dùng: svg-to-kotlin.mjs <thư mục svg> --package <pkg> [--object AppIcons] [--out file.kt]'); process.exit(2) }
const pkg = opt('package', 'app.ui'), obj = opt('object', 'AppIcons'), out = opt('out')

const pascal = s => s.replace(/\.svg$/i, '').split(/[^A-Za-z0-9]+/).filter(Boolean).map(w => w[0].toUpperCase() + w.slice(1)).join('')
const files = readdirSync(dir).filter(f => f.toLowerCase().endsWith('.svg')).sort()
if (!files.length) { console.error(`Không có file .svg trong ${dir}`); process.exit(1) }

const names = new Map()
const entries = files.map(f => {
  let name = pascal(f); if (/^\d/.test(name)) name = 'Icon' + name
  if (names.has(name)) throw new Error(`Hai file cùng ra tên ${name}: ${names.get(name)} và ${f}`)
  names.set(name, f)
  const svg = readFileSync(join(dir, f), 'utf8').replace(/<\?xml[^>]*\?>|<!--[\s\S]*?-->/g, '').replace(/\s+/g, ' ').replace(/> </g, '><').trim()
  if (!/<svg\b/i.test(svg)) throw new Error(`${f} không phải SVG`)
  return { name, key: f.replace(/\.svg$/i, ''), svg: svg.replace(/\$/g, () => "${'$'}").replace(/"""/g, () => '""${\'"\'}') }
})

const kt = `// SINH TỰ ĐỘNG bởi tools/svg-to-kotlin.mjs từ ${dir.split('/').pop()}/*.svg — đừng sửa tay: thêm/sửa file .svg rồi chạy lại script.
package ${pkg}

import kmp.bootstrap.appkit.SvgIcon
import kmp.bootstrap.appkit.SvgParser

object ${obj} {
${entries.map(e => `    val ${e.name}: SvgIcon by lazy { SvgParser.parse("""${e.svg}""") }`).join('\n')}

    /** Mọi icon theo tên file (ví dụ "chevron-right") — để chọn icon theo chuỗi (cấu hình, dữ liệu từ server). */
    val all: Map<String, SvgIcon> get() = mapOf(${entries.map(e => `"${e.key}" to ${e.name}`).join(', ')})
}
`
if (out) { writeFileSync(out, kt); console.error(`đã ghi ${out} (${entries.length} icon)`) } else process.stdout.write(kt)
