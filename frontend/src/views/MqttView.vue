<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'

/* ─────────────── 타입 ─────────────── */
type RecentObs = {
  seq: number
  observationId: string | null
  multiDatastreamId: string | null
  thingName: string | null
  phenomenonTime: string | null
  result: string[]
  receivedAtMs: number
}
type IndexPointView = {
  label: string
  op: string
  uom: string
  cohort: number
  count: number
  sum: number
  average: number
}
type EngineSnapshot = {
  enabled: boolean
  windowSeconds: number
  slideSeconds: number
  indexPointCount: number
  edgeCount: number
  asOf: string
  lastMaterializeMs: number
  indexPoints: IndexPointView[]
  residentObs: number
  aggMode: string
  computeMs: number
}
type MqttMonitor = {
  running: boolean
  received: number
  dropped: number
  broker: string
  recent: RecentObs[]
  engine: EngineSnapshot | null
}

/* ─────────────── 상태 ─────────────── */
const monitor = ref<MqttMonitor | null>(null)
const err = ref<string | null>(null)
const lastAction = ref<string | null>(null)
const busy = ref(false)
const autoRefresh = ref(true)
let timer: number | null = null

const running = computed(() => monitor.value?.running ?? false)
const applying = computed(() => monitor.value?.engine?.enabled ?? false)

onMounted(() => {
  refresh()
  startAuto()
})
onUnmounted(() => stopAuto())

function startAuto() {
  if (timer) return
  timer = window.setInterval(() => refresh(), 1500)
}
function stopAuto() {
  if (timer) { clearInterval(timer); timer = null }
}
function toggleAuto() {
  autoRefresh.value ? startAuto() : stopAuto()
}

async function refresh() {
  if (!autoRefresh.value && timer) return
  try {
    const r = await fetch('/mqtt/recent', { method: 'GET' })
    if (!r.ok) throw new Error(`HTTP ${r.status}`)
    monitor.value = await r.json()
    err.value = null
  } catch (e: any) {
    err.value = e?.message ?? 'failed to load'
  }
}

async function callAction(path: string, label: string) {
  busy.value = true
  try {
    const r = await fetch(path, { method: 'GET' })
    const text = await r.text()
    if (!r.ok) throw new Error(text || `HTTP ${r.status}`)
    lastAction.value = `${label}: ${text}`
    await refresh()
  } catch (e: any) {
    lastAction.value = `${label} 실패: ${e?.message ?? 'error'}`
  } finally {
    busy.value = false
  }
}

const startReal = () => callAction('/mqtt/start?target=real', '실제 시작')
const startExperiment = () => callAction('/mqtt/start?target=experiment', '실험 시작')
const stop = () => callAction('/mqtt/stop', 'stop')
const applyOn = () => callAction('/mqtt/apply?on=true', '반영 시작')
const applyOff = () => callAction('/mqtt/apply?on=false', '반영 중지')
const setMode = (m: string) => callAction(`/mqtt/mode?value=${m}`, `산출모드 ${m}`)
const aggMode = computed(() => monitor.value?.engine?.aggMode ?? 'both')

/* ─────────────── Formatter ─────────────── */
function fmtTime(ms?: number) {
  if (ms == null) return '-'
  return new Date(ms).toLocaleTimeString([], { hour12: false })
}
function fmtResult(arr: string[]) {
  const s = arr.join(', ')
  return s.length > 80 ? s.slice(0, 80) + '…' : s
}
</script>

<template>
  <div class="mq-wrap">
    <!-- 제어 + 상태 카드 -->
    <section class="av-card">
      <div class="av-card-head">
        <h2 class="av-title">실시간 MQTT 인입</h2>
        <div class="av-head-actions">
          <label class="av-checkbox">
            <input type="checkbox" v-model="autoRefresh" @change="toggleAuto" />
            <span>자동 새로고침 (1.5s)</span>
          </label>
          <button class="av-btn refresh-btn" @click="refresh">새로고침</button>
        </div>
      </div>

      <div class="controls">
        <button class="av-btn primary" @click="startReal" :disabled="busy || running">실제(aidtlab) 시작</button>
        <button class="av-btn experiment" @click="startExperiment" :disabled="busy || running">실험(로컬) 시작</button>
        <button class="av-btn danger" @click="stop" :disabled="busy || !running">중지</button>
        <span class="sep"></span>
        <button class="av-btn apply" @click="applyOn" :disabled="busy || !running || applying">반영(온톨로지 적용)</button>
        <button class="av-btn" @click="applyOff" :disabled="busy || !applying">반영 중지</button>
        <span class="badge" :class="running ? 'on' : 'off'">
          {{ running ? '활성화됨' : '비활성화됨' }}
        </span>
        <span class="badge" :class="applying ? 'on' : 'off'">반영 {{ applying ? 'ON' : 'OFF' }}</span>
      </div>

      <div v-if="lastAction" class="action-msg">{{ lastAction }}</div>
      <div v-if="err" class="av-error">{{ err }}</div>

      <div class="stat-grid">
        <div class="stat">
          <div class="stat-label">채택(정식 알림)</div>
          <div class="stat-value">{{ monitor?.received ?? '-' }}</div>
        </div>
        <div class="stat">
          <div class="stat-label">폐기(원본 echo)</div>
          <div class="stat-value muted">{{ monitor?.dropped ?? '-' }}</div>
        </div>
        <div class="stat broker">
          <div class="stat-label">브로커</div>
          <div class="stat-value mono">{{ monitor?.broker ?? '-' }}</div>
        </div>
      </div>
    </section>

    <!-- 윈도우 엔진 / IndexPoint 집계 -->
    <section class="card" v-if="monitor?.engine">
      <div class="log-head">
        <h3 class="subtitle">IndexPoint 윈도우 집계 (온톨로지 반영)</h3>
        <span class="badge" :class="applying ? 'on' : 'off'">{{ applying ? 'ON' : 'OFF' }}</span>
      </div>

      <!-- 라이브 집계 산출 방식 (공정 벤치마크는 [실험] 탭) -->
      <div class="controls" style="margin-bottom:10px">
        <span style="font-size:12px;color:#aaa">집계 산출:</span>
        <button class="av-btn" :class="{ apply: aggMode==='indexed' }" @click="setMode('indexed')" :disabled="busy">IndexPoint O(k)</button>
        <button class="av-btn" :class="{ apply: aggMode==='naive' }" @click="setMode('naive')" :disabled="busy">스캔(미사용) O(W)</button>
        <span style="font-size:11px;color:#777">공정 비교는 [실험] 탭에서</span>
      </div>
      <div class="stat-grid" style="margin-bottom:12px">
        <div class="stat"><div class="stat-label">산출 시간 ({{ aggMode }})</div><div class="stat-value mono">{{ monitor.engine.computeMs }} ms</div></div>
        <div class="stat"><div class="stat-label">윈도우 거주 Obs</div><div class="stat-value muted">{{ monitor.engine.residentObs }}</div></div>
      </div>

      <div class="stat-grid">
        <div class="stat"><div class="stat-label">IndexPoint 수 (k)</div><div class="stat-value">{{ monitor.engine.indexPointCount }}</div></div>
        <div class="stat"><div class="stat-label">엣지 수</div><div class="stat-value muted">{{ monitor.engine.edgeCount }}</div></div>
        <div class="stat"><div class="stat-label">윈도우 / 슬라이드</div><div class="stat-value mono">{{ monitor.engine.windowSeconds }}s / {{ monitor.engine.slideSeconds }}s</div></div>
        <div class="stat"><div class="stat-label">반영 지연</div><div class="stat-value mono">{{ monitor.engine.lastMaterializeMs }} ms</div></div>
      </div>
      <div class="table-body" style="margin-top:12px">
        <table v-if="monitor.engine.indexPoints.length" class="tbl">
          <thead>
            <tr><th>IndexPoint (op/uom)</th><th>코호트(센서)</th><th>윈도우 수</th><th>평균</th></tr>
          </thead>
          <tbody>
            <tr v-for="ip in monitor.engine.indexPoints" :key="ip.label">
              <td><span class="mds">{{ ip.label }}</span></td>
              <td class="mono">{{ ip.cohort }}</td>
              <td class="mono">{{ ip.count }}</td>
              <td class="mono">{{ ip.average }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">반영을 켜면 IndexPoint 집계가 표시됩니다.</div>
      </div>
      <div class="action-msg" v-if="monitor.engine.asOf !== '-'">asOf: {{ monitor.engine.asOf }}</div>
    </section>

    <!-- 최근 관측치 -->
    <section class="card">
      <div class="log-head">
        <h3 class="subtitle">최근 관측치</h3>
        <span class="log-count">{{ monitor?.recent?.length ?? 0 }}</span>
      </div>

      <div class="table-body">
        <table v-if="monitor && monitor.recent.length" class="tbl">
          <thead>
            <tr>
              <th>수신시각</th>
              <th>Thing</th>
              <th>MDS</th>
              <th>Obs ID</th>
              <th>phenomenonTime</th>
              <th>result</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="o in monitor.recent" :key="o.observationId ?? o.seq">
              <td class="mono">{{ fmtTime(o.receivedAtMs) }}</td>
              <td><span class="thing">{{ o.thingName ?? '-' }}</span></td>
              <td><span class="mds">{{ o.multiDatastreamId ?? '-' }}</span></td>
              <td class="mono dim">{{ o.observationId ?? '-' }}</td>
              <td class="mono dim">{{ o.phenomenonTime ?? '-' }}</td>
              <td class="mono">{{ fmtResult(o.result) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">수신된 관측치가 없습니다. (시작을 누르세요)</div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.mq-wrap { display: flex; flex-direction: column; gap: 24px; }

.av-card, .card {
  background: #111; border: 1px solid #2a2a2a; border-radius: 16px; padding: 16px; color: #e7e7e7;
}
.av-card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.av-title { font-size: 18px; font-weight: 600; }
.av-head-actions { display: flex; align-items: center; gap: 10px; }
.av-checkbox { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #aaa; }

.av-btn {
  border: 1px solid #4a4a4a; background: #1a1a1a; color: #e7e7e7;
  border-radius: 10px; padding: 6px 14px; cursor: pointer; font-size: 13px; transition: all .2s ease;
}
.av-btn:hover:not(:disabled) { background: #2a2a2a; border-color: #5a7cff; color: #fff; }
.av-btn:disabled { opacity: .45; cursor: not-allowed; }
.av-btn.primary { border-color: #2a3cff; background: #2a3cff; color: #fff; }
.av-btn.primary:hover:not(:disabled) { background: #364cff; }
.av-btn.experiment { border-color: #2f7a55; background: #1c3a2c; color: #aef0c8; }
.av-btn.experiment:hover:not(:disabled) { background: #2f7a55; color: #fff; }
.av-btn.apply { border-color: #6a4acc; background: #2a1c44; color: #d4c2ff; }
.av-btn.apply:hover:not(:disabled) { background: #6a4acc; color: #fff; }
.sep { width: 1px; height: 22px; background: #333; display: inline-block; margin: 0 4px; }
.av-btn.danger { border-color: #7a2a2a; background: #2a1a1a; }
.av-btn.danger:hover:not(:disabled) { background: #a33636; border-color: #a33636; color: #fff; }
.refresh-btn { border-color: #555; background: #222; }

.controls { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.badge { font-size: 12px; padding: 4px 10px; border-radius: 999px; border: 1px solid #2a2a2a; }
.badge.on { color: #7bf1a8; border-color: #2f5b45; background: rgba(123,241,168,.08); }
.badge.off { color: #ff8b8b; border-color: #5b2f2f; background: rgba(255,139,139,.06); }

.action-msg { font-size: 12px; color: #9aa0a6; margin-bottom: 8px; font-family: ui-monospace, Menlo, Consolas, monospace; }
.av-error { color: #ff6b6b; font-size: 13px; margin-bottom: 8px; }

.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; }
.stat { background: #161616; border: 1px solid #333; border-radius: 12px; padding: 12px; }
.stat-label { font-size: 12px; color: #aaa; margin-bottom: 6px; }
.stat-value { font-size: 22px; font-weight: 700; }
.stat-value.muted { color: #9aa0a6; }
.stat-value.mono { font-size: 13px; font-weight: 500; font-family: ui-monospace, Menlo, Consolas, monospace; }
.stat.broker { grid-column: span 2; }

.subtitle { font-size: 16px; font-weight: 600; }
.log-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.log-count { font-size: 12px; color: #aaa; background: rgba(255,255,255,.06); border: 1px solid #2a2a2a; border-radius: 999px; padding: 2px 8px; }

.table-body { max-height: 460px; overflow: auto; border: 1px solid #2a2a2a; border-radius: 12px; background: #0f0f0f; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl thead th {
  position: sticky; top: 0; background: #161616; color: #aaa; font-weight: 600;
  text-align: left; padding: 8px 12px; border-bottom: 1px solid #2a2a2a;
}
.tbl tbody td { padding: 8px 12px; border-bottom: 1px solid #1c1c1c; }
.tbl tbody tr:last-child td { border-bottom: none; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.dim { color: #9aa0a6; }
.mds { display: inline-block; padding: 1px 8px; border-radius: 6px; background: rgba(122,162,255,.12); color: #9ab4ff; font-weight: 600; }
.thing { display: inline-block; padding: 1px 8px; border-radius: 6px; background: rgba(111,216,180,.12); color: #6fd8b4; font-weight: 600; }
.empty { padding: 18px; color: #9aa0a6; font-size: 13px; }
</style>
