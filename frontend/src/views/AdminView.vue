<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import type { ApiResponse, StatusData } from '../api/types'
import { getStatus, initOntology, uploadRdf } from '../api/endpoints'

/* ─────────────── 상태 관련 ─────────────── */
const avStatusRes = ref<ApiResponse<StatusData> | null>(null)
const avStatusErr = ref<string | null>(null)
const avLoadingStatus = ref(false)
const avAutoRefresh = ref(true)
let avTimer: number | null = null

onMounted(() => {
  avLoadStatus()
  avStartAutoRefresh()
})
onUnmounted(() => avStopAutoRefresh())

function avStartAutoRefresh() {
  if (!avAutoRefresh.value || avTimer) return
  avTimer = window.setInterval(() => avLoadStatus(), 10000)
}
function avStopAutoRefresh() {
  if (avTimer) { clearInterval(avTimer); avTimer = null }
}

async function avLoadStatus() {
  avLoadingStatus.value = true
  avStatusErr.value = null
  const startedAt = Date.now()
  try {
    const res = await getStatus()
    avStatusRes.value = res
    pushLog({
      label: 'GET /status',
      ok: true,
      ms: res.timeMs ?? (Date.now() - startedAt),
      json: res,
    })
  } catch (e: any) {
    const errMsg = e?.message ?? 'Failed to load status'
    avStatusErr.value = errMsg
    pushLog({
      label: 'GET /status',
      ok: false,
      ms: Date.now() - startedAt,
      error: errMsg,
    })
  } finally {
    avLoadingStatus.value = false
  }
}

const avData = computed<StatusData | null>(() => avStatusRes.value?.data ?? null)
const avStats = computed(() => avData.value?.ontologyStats ?? null)

/* ─────────────── Formatter ─────────────── */
function avFmtBytes(n?: number) {
  if (n == null) return '-'
  const units = ['B','KB','MB','GB','TB']
  let i = 0, v = n
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(1)} ${units[i]}`
}
function avFmtDuration(ms?: number) {
  if (ms == null) return '-'
  const s = Math.floor(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = s % 60
  return `${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(ss).padStart(2,'0')}`
}
function avPct(v?: number) {
  if (v == null || Number.isNaN(v)) return '-'
  return (v * 100).toFixed(1) + '%'
}
function avNum(n?: number, digits = 0) {
  if (n == null) return '-'
  return n.toLocaleString(undefined, { maximumFractionDigits: digits })
}

/* ─────────────── Maintenance ─────────────── */
const initRes = ref<ApiResponse<Record<string, any>> | null>(null)
const initErr = ref<string | null>(null)
const initPending = ref(false)

const uploading = ref(false)
const uploadRes = ref<ApiResponse<Record<string, any>> | null>(null)
const uploadErr = ref<string | null>(null)
const files = ref<FileList | null>(null)

const selectedFileNames = computed(() =>
  files.value ? Array.from(files.value).map(f => f.name) : []
)

async function doInit() {
  initRes.value = null; initErr.value = null; initPending.value = true
  const startedAt = Date.now()
  try {
    const res = await initOntology()
    initRes.value = res
    pushLog({
      label: 'POST /init-ontology',
      ok: true,
      ms: res.timeMs ?? (Date.now() - startedAt),
      json: res,
    })
    await avLoadStatus()
  } catch (e: any) {
    const errMsg = e?.message ?? 'failed'
    initErr.value = errMsg
    pushLog({
      label: 'POST /init-ontology',
      ok: false,
      ms: Date.now() - startedAt,
      error: errMsg,
    })
  } finally {
    initPending.value = false
  }
}

async function doUpload() {
  if (!files.value || files.value.length === 0) return
  uploadRes.value = null; uploadErr.value = null; uploading.value = true
  const startedAt = Date.now()
  try {
    const arr = Array.from(files.value)
    const res = await uploadRdf(arr)
    uploadRes.value = res
    pushLog({
      label: 'POST /upload-rdf',
      ok: true,
      ms: res.timeMs ?? (Date.now() - startedAt),
      json: { ...res, fileNames: arr.map(f => f.name) },
    })
    await avLoadStatus()
  } catch (e: any) {
    const errMsg = e?.message ?? 'failed'
    uploadErr.value = errMsg
    pushLog({
      label: 'POST /upload-rdf',
      ok: false,
      ms: Date.now() - startedAt,
      error: errMsg,
    })
  } finally {
    uploading.value = false
  }
}

function onFilesChange(ev: Event) {
  const input = ev.target as HTMLInputElement | null
  files.value = input?.files ?? null
}

/* ─────────────── 응답 로그 ─────────────── */
type LogEntry = {
  time: string
  label: string
  ok: boolean
  ms?: number
  json?: unknown
  error?: string
}

const responseLog = ref<LogEntry[]>([])

function nowIsoTime() {
  const d = new Date()
  return d.toLocaleTimeString([], { hour12: false })
}
function pushLog(e: Omit<LogEntry, 'time'>) {
  responseLog.value.unshift({ time: nowIsoTime(), ...e })
  if (responseLog.value.length > 200) responseLog.value.length = 200
}
function clearLog() {
  responseLog.value = []
}
</script>

<template>
  <div class="admin-wrap">
    <!-- ── 상단 상태 카드 ─────────────────────────────── -->
    <section class="av-card">
      <div class="av-card-head">
        <h2 class="av-title">Server &amp; Ontology Status</h2>
        <div class="av-head-actions">
          <label class="av-checkbox">
            <input type="checkbox" v-model="avAutoRefresh" @change="avAutoRefresh ? avStartAutoRefresh() : avStopAutoRefresh()" />
            <span>Auto refresh (10s)</span>
          </label>
          <button class="av-btn refresh-btn" @click="avLoadStatus" :disabled="avLoadingStatus">
            Refresh
          </button>
        </div>
      </div>

      <!-- 정보 내용 -->
      <div class="av-info-box">
        <div v-if="avStatusErr" class="av-error">{{ avStatusErr }}</div>

        <div v-else-if="avData" class="av-grid">
          <div class="av-subcard">
            <div class="av-subhead">Storage</div>
            <div class="av-value">{{ avData.storage }}</div>
            <div class="av-row"><span>Uptime</span><span>{{ avFmtDuration(avData.uptimeMs) }}</span></div>
            <div class="av-row"><span>TDB Size</span><span>{{ avFmtBytes(avData.tdbBytes) }}</span></div>
          </div>

          <div class="av-subcard">
            <div class="av-subhead">JVM Heap</div>
            <div class="av-row"><span>Used</span><span>{{ avFmtBytes(avData.heapUsedBytes) }}</span></div>
            <div class="av-row"><span>Committed</span><span>{{ avFmtBytes(avData.heapCommittedBytes) }}</span></div>
            <div class="av-row"><span>Max</span><span>{{ avFmtBytes(avData.heapMaxBytes) }}</span></div>
          </div>

          <div class="av-subcard" v-if="avStats">
            <div class="av-subhead">Ontology Stats</div>
            <div class="av-row"><span>Classes</span><span>{{ avNum(avStats.totalClassCount) }}</span></div>
            <div class="av-row"><span>Instances</span><span>{{ avNum(avStats.totalInstances) }}</span></div>
            <div class="av-row"><span>Richness</span><span>{{ avPct(avStats.classRichness) }}</span></div>
          </div>
          <div class="av-subcard av-muted" v-else>
            <div class="av-subhead">Ontology Stats</div>
            <div class="av-row"><span>Unavailable</span><span>—</span></div>
          </div>
        </div>

        <div v-else class="av-muted">Loading…</div>
      </div>

      <!-- 갱신중 텍스트 (하단 고정 영역) -->
      <div class="refresh-status">
        <transition name="fade">
          <div v-if="avLoadingStatus" class="inline-loading">
            <span class="spinner"></span><span>Loading latest status…</span>
          </div>
        </transition>
      </div>
    </section>

    <!-- ── Maintenance ─────────────────────────────── -->
    <section class="card">
      <h3 class="subtitle">Maintenance</h3>

      <div class="maint-row">
        <div class="maint-label">
          <h4>Reinitialize Ontology</h4>
          <p class="hint">Deletes and rebuilds ontology storage.</p>
        </div>
        <button class="av-btn primary" @click="doInit" :disabled="initPending || uploading">
          {{ initPending ? 'Working…' : 'Execute' }}
        </button>
      </div>
      <div v-if="initErr" class="error">{{ initErr }}</div>

      <div class="divider"></div>

      <div class="maint-row upload-section">
        <div class="maint-label">
          <h4>Upload RDF</h4>
          <p class="hint">Import RDF files into ontology model.</p>
        </div>
        <div class="upload-buttons">
          <input id="rdfFiles" type="file" multiple @change="onFilesChange" class="file-input-hidden" />
          <label for="rdfFiles" class="av-btn select-btn">Select Files</label>
          <button class="av-btn primary"
            @click="doUpload"
            :disabled="uploading || initPending || !files || files.length === 0">
            {{ uploading ? 'Uploading…' : 'Upload' }}
          </button>
        </div>
      </div>
      <div v-if="uploadErr" class="error">{{ uploadErr }}</div>

      <div v-if="selectedFileNames.length > 0" class="file-summary">
        {{ selectedFileNames.length }} file(s) selected
      </div>

      <div v-if="uploading" class="inline-loading mt-2">
        <span class="spinner"></span><span>Uploading files…</span>
      </div>
    </section>

    <!-- ── 응답 로그 ─────────────────────────────── -->
    <section class="log-card">
      <div class="log-head">
        <h3 class="subtitle">API Responses</h3>
        <div class="log-actions">
          <span class="log-count">{{ responseLog.length }}</span>
          <button class="av-btn sm danger" @click="clearLog" :disabled="responseLog.length === 0">
            Clear
          </button>
        </div>
      </div>

      <div class="log-body">
        <div v-if="responseLog.length === 0" class="log-empty">응답 로그가 없습니다.</div>

        <ul v-else class="log-list">
          <li v-for="(it, idx) in responseLog" :key="idx" class="log-item" :class="{ ok: it.ok, fail: !it.ok }">
            <div class="log-line">
              <span class="t">{{ it.time }}</span>
              <span class="l">{{ it.label }}</span>
              <span class="ms" v-if="it.ms != null">{{ it.ms }} ms</span>
              <span class="state" :aria-label="it.ok ? 'success' : 'fail'">
                {{ it.ok ? 'OK' : 'FAIL' }}
              </span>
            </div>
            <pre v-if="it.error" class="log-pre error-pre">{{ it.error }}</pre>
            <pre v-else-if="it.json" class="log-pre">{{ JSON.stringify(it.json, null, 2) }}</pre>
          </li>
        </ul>
      </div>
    </section>
  </div>
</template>

<style scoped>
.admin-wrap { display: flex; flex-direction: column; gap: 24px; }

/* ─────────────── 상태 카드 ─────────────── */
.av-card {
  background: #111;
  border: 1px solid #2a2a2a;
  border-radius: 16px;
  padding: 16px;
  color: #e7e7e7;
}
.av-card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.av-title { font-size: 18px; font-weight: 600; }
.av-head-actions { display: flex; align-items: center; gap: 10px; }
.av-checkbox { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #aaa; }

.av-btn {
  border: 1px solid #4a4a4a;
  background: #1a1a1a;
  color: #e7e7e7;
  border-radius: 10px;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s ease;
}
.av-btn:hover { background: #2a2a2a; border-color: #5a7cff; color: #fff; }
.av-btn.primary { border-color: #2a3cff; background: #2a3cff; color: #fff; }
.av-btn.primary:hover { background: #364cff; }
.refresh-btn { border-color: #555; background: #222; }
.refresh-btn:hover { background: #2a3cff; border-color: #2a3cff; color: #fff; }

/* 작은 버튼/위험 버튼 변형 */
.av-btn.sm { padding: 4px 10px; font-size: 12px; border-radius: 8px; }
.av-btn.danger { border-color: #7a2a2a; background: #2a1a1a; }
.av-btn.danger:hover { background: #a33636; border-color: #a33636; color: #fff; }

/* 정보박스(들썩임 방지, 줄인 높이) */
.av-info-box { min-height: 230px; transition: all 0.2s ease; }

/* 갱신 텍스트 공간(항상 확보) */
.refresh-status {
  min-height: 36px;
  display: flex;
  align-items: center;
  margin-top: 4px;
}

/* 하단 로딩 */
.inline-loading {
  display: inline-flex; align-items: center; gap: 8px;
  font-size: 13px; color: #c8c8c8;
  padding: 6px 8px;
  background: rgba(255,255,255,.04);
  border: 1px solid #2a2a2a;
  border-radius: 8px;
}

/* Grid */
.av-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; }
.av-subcard { background: #161616; border: 1px solid #333; border-radius: 12px; padding: 12px; }
.av-subhead { font-size: 12px; color: #aaa; margin-bottom: 6px; }
.av-value { font-size: 16px; font-weight: 600; margin-bottom: 8px; }
.av-row { display: flex; justify-content: space-between; font-size: 13px; }
.av-row span:first-child { color: #aaa; }

.av-error { color: #ff6b6b; font-size: 13px; }

/* ─────────────── Maintenance ─────────────── */
.card { background: #111; border: 1px solid #2a2a2a; border-radius: 16px; padding: 16px; }
.subtitle { font-size: 16px; font-weight: 600; margin-bottom: 12px; }
.maint-row { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 10px; }
.maint-label h4 { margin: 0; font-size: 15px; font-weight: 600; }
.maint-label p { margin: 2px 0 0; font-size: 12px; color: #aaa; }

.upload-buttons {
  display: flex;
  align-items: center;
  gap: 20px;
}
.file-input-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); }
.file-summary { font-size: 13px; color: #aaa; margin-top: 6px; }
.error { color: #ff6b6b; font-size: 13px; margin-top: 6px; }

/* ─────────────── 응답 로그 ─────────────── */
.log-card {
  background: #111;
  border: 1px solid #2a2a2a;
  border-radius: 16px;
  padding: 16px;
  color: #e7e7e7;
}
.log-head {
  display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px;
}
.log-actions { display: flex; align-items: center; gap: 8px; }
.log-count {
  font-size: 12px; color: #aaa; background: rgba(255,255,255,.06);
  border: 1px solid #2a2a2a; border-radius: 999px; padding: 2px 8px;
}
.log-body {
  max-height: 320px;
  overflow: auto;
  border: 1px solid #2a2a2a;
  border-radius: 12px;
  background: #0f0f0f;
}
.log-empty { padding: 16px; color: #9aa0a6; font-size: 13px; }
.log-list { list-style: none; margin: 0; padding: 0; }
.log-item { border-bottom: 1px solid #202020; padding: 10px 12px; }
.log-item:last-child { border-bottom: none; }
.log-item.ok .state { color: #7bf1a8; }
.log-item.fail .state { color: #ff8b8b; }
.log-line { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; }
.log-line .t { color: #9aa0a6; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
.log-line .l { font-weight: 600; color: #e7e7e7; }
.log-line .ms { color: #9aa0a6; }
.log-line .state { margin-left: auto; font-weight: 700; }

.log-pre {
  margin-top: 8px;
  padding: 8px;
  background: #0b0b0b;
  border: 1px solid #2a2a2a;
  border-radius: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  overflow: auto;
}
.error-pre { color: #ff8b8b; }

/* fade transition */
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
