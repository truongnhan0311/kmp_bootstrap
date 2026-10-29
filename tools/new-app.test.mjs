// node --test tools/*.test.mjs
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, existsSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const run = (...a) => spawnSync('node', ['tools/new-app.mjs', ...a], { encoding: 'utf8' })

test('sinh đủ tệp, nối kmp_bootstrap, package đúng', () => {
  const out = join(mkdtempSync(join(tmpdir(), 'app-')), 'my-app')
  const r = run('--name', 'my-app', '--id', 'com.acme.myapp', '--out', out, '--modules', 'network,push,platform,ui')
  assert.equal(r.status, 0, r.stderr)
  for (const f of ['settings.gradle.kts', 'composeApp/build.gradle.kts', 'composeApp/src/commonMain/kotlin/com/acme/myapp/App.kt', 'composeApp/src/commonMain/kotlin/com/acme/myapp/AppColors.kt',
    'androidApp/src/main/AndroidManifest.xml', 'androidApp/src/main/kotlin/com/acme/myapp/MainActivity.kt', 'iosApp/project.yml', 'iosApp/iosApp/ContentView.swift', 'gradle/libs.versions.toml'])
    assert.ok(existsSync(join(out, f)), f)
  assert.match(readFileSync(join(out, 'settings.gradle.kts'), 'utf8'), /includeBuild\(".*"\)/)
  const build = readFileSync(join(out, 'composeApp/build.gradle.kts'), 'utf8')
  for (const m of ['core', 'network', 'push', 'platform', 'ui']) assert.ok(build.includes(`kmp.bootstrap:appkit-${m}"`), m)
  assert.match(readFileSync(join(out, 'androidApp/src/main/kotlin/com/acme/myapp/MainActivity.kt'), 'utf8'), /AppKitPlatform\.attach\(this\)/)
})

test('chỉ network: không có ui/Compose theme, không có AppColors', () => {
  const out = join(mkdtempSync(join(tmpdir(), 'app-')), 'min')
  assert.equal(run('--name', 'min', '--id', 'com.acme.min', '--out', out, '--modules', 'network').status, 0)
  assert.ok(!existsSync(join(out, 'composeApp/src/commonMain/kotlin/com/acme/min/AppColors.kt')))
  assert.ok(!readFileSync(join(out, 'composeApp/build.gradle.kts'), 'utf8').includes('appkit-ui'))
})

test('push tự kéo network; tên/id/module sai bị từ chối; không ghi đè dự án có sẵn', () => {
  const dir = mkdtempSync(join(tmpdir(), 'app-')), out = join(dir, 'a')
  assert.equal(run('--name', 'a', '--id', 'com.acme.a', '--out', out, '--modules', 'push').status, 0)
  assert.ok(readFileSync(join(out, 'composeApp/build.gradle.kts'), 'utf8').includes('appkit-network'))
  assert.notEqual(run('--name', 'a', '--id', 'com.acme.a', '--out', out).status, 0, 'đã có dự án')
  assert.match(run('--name', 'Bad Name', '--id', 'com.acme.a', '--out', join(dir, 'b')).stderr, /--name/)
  assert.match(run('--name', 'ok', '--id', 'nodots', '--out', join(dir, 'c')).stderr, /--id/)
  assert.match(run('--name', 'ok', '--id', 'com.acme.ok', '--out', join(dir, 'd'), '--modules', 'network,bogus').stderr, /Module lạ/)
})
