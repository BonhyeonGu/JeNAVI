<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { queryRun } from '../api/endpoints'
import type { ApiResponse } from '../api/types'

type HistoryEntry = {
  timestamp: number
  query: string
  timeMs?: number | null
  data?: string[][]
  status?: string
}

const STORAGE_KEY = 'ont_query_history_v1'

const q = ref(`SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 10`)
const loading = ref(false)
const res = ref<ApiResponse<string[][]> | null>(null)
const err = ref<string | null>(null)
const history = ref<HistoryEntry[]>([])
const flashNewest = ref<number | null>(null) // timestamp of newest for flash

function saveHistory() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(history.value))
  } catch {}
}

function loadHistory() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    history.value = raw ? (JSON.parse(raw) as HistoryEntry[]) : []
  } catch {
    history.value = []
  }
}

function summaryLine(text: string): string {
  const m = text.match(/(?:select|insert|ask|construct|delete)\b[\s\S]*/i)
  const base = (m ? m[0] : text).replace(/\s+/g, ' ').trim()
  return base.slice(0, 200)
}

async function run() {
  if (!q.value.trim()) return
  loading.value = true
  err.value = null
  res.value = null
  try {
    const r = await queryRun(q.value) // POST /api/queryRun  { query }
    res.value = r

    // 기록 저장 (최신이 뒤에 오도록 push)
    const entry: HistoryEntry = {
      timestamp: Date.now(),
      query: q.value,
      timeMs: r.timeMs,
      data: r.data ?? [],
      status: r.status,
    }
    history.value.push(entry)
    // 기록 길이 제한(원하면 조정)
    if (history.value.length > 200) history.value.shift()
    saveHistory()

    // Flash 하이라이트용
    flashNewest.value = entry.timestamp
    setTimeout(() => (flashNewest.value = null), 1400)
  } catch (e: any) {
    err.value = e?.message ?? 'request failed'
  } finally {
    loading.value = false
  }
}

function clearHistory() {
  if (!confirm('기록을 모두 삭제하시겠습니까?')) return
  history.value = []
  saveHistory()
}

function deleteEntry(ts: number) {
  history.value = history.value.filter(h => h.timestamp !== ts)
  saveHistory()
}

function retryFrom(ts: number) {
  const entry = history.value.find(h => h.timestamp === ts)
  if (!entry) return
  q.value = entry.query
  // 실행은 사용자가 버튼으로 다시 누르게 두거나,
  // 아래 줄 주석 해제 시 즉시 실행됩니다.
  // run()
}

onMounted(loadHistory)
</script>

<template>
  <section class="wrap">
    <h1 class="h1">질의기</h1>

    <div class="card">
      <textarea
        v-model="q"
        class="textarea"
        placeholder="SPARQL query here..."
        rows="12"
      ></textarea>

      <div class="actions">
        <button class="btn" :disabled="loading" @click="run">
          {{ loading ? '실행중...' : '실행' }}
        </button>
      </div>

      <p v-if="err" class="error">{{ err }}</p>

      <div v-if="res" class="meta">
        <span>Status: {{ res.status }}</span>
        <span v-if="res.timeMs !== null && res.timeMs !== undefined" class="dot">•</span>
        <span v-if="res.timeMs !== null && res.timeMs !== undefined">
          {{ res.timeMs }} ms
        </span>
      </div>

      <table v-if="res?.data?.length" class="table">
        <tbody>
          <tr v-for="(row, i) in res.data" :key="i">
            <td v-for="(col, j) in row" :key="j">{{ col }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="histHead">
      <h2 class="h2">질의 기록</h2>
      <button class="btn ghost" @click="clearHistory">전체 삭제</button>
    </div>

    <div class="grid">
      <div
        v-for="(h) in [...history].slice().reverse()"
        :key="h.timestamp"
        class="cell"
        :class="{ flash: flashNewest === h.timestamp }"
      >
        <div class="cellHdr">
          <div class="ts">{{ new Date(h.timestamp).toLocaleString() }}</div>
          <div class="cellBtns">
            <button class="btn xs" @click="retryFrom(h.timestamp)">Retry</button>
            <button class="btn xs danger" @click="deleteEntry(h.timestamp)">삭제</button>
          </div>
        </div>

        <div class="sum">
          <code>{{ summaryLine(h.query) }}...</code>
        </div>

        <div class="meta row">
          <span class="ok" v-if="h.status">{{ h.status }}</span>
          <span v-if="h.timeMs !== null && h.timeMs !== undefined">Exec: {{ h.timeMs }} ms</span>
          <span v-if="h.data?.length" class="muted">· rows: {{ h.data.length }}</span>
        </div>

        <div class="results">
          <template v-if="h.data?.length">
            <div
              v-for="(row, idx) in (h.data.length > 4 ? h.data.slice(0,4) : h.data)"
              :key="idx"
              class="bullet"
            >
              🔹 {{ JSON.stringify(row) }}
            </div>
            <div v-if="h.data.length > 4" class="omitted">
              ... 외 {{ h.data.length - 4 }}건 생략됨
            </div>
          </template>
          <em v-else class="muted">No parsed results</em>
        </div>

        <details class="raw">
          <summary>Raw Query & Response</summary>
          <div class="rawBox">
            <div class="rawTitle">Query with actual newlines:</div>
            <pre class="pre">{{ h.query }}</pre>
            <div class="rawTitle">Full JSON Response:</div>
            <pre class="pre">{{ JSON.stringify(h, null, 2) }}</pre>
          </div>
        </details>
      </div>
    </div>
  </section>
</template>

<style scoped>
.wrap {
  color: var(--text);
}
.h1 { margin: 0 0 12px; font-size: 20px; font-weight: 700; }
.h2 { margin: 4px 0 8px; font-size: 18px; font-weight: 700; }

.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 16px;
  margin-bottom: 18px;
}

.textarea {
  width: 100%;
  min-height: 220px;
  background: var(--surface-2);
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.actions { margin-top: 10px; display: flex; gap: 8px; }
.btn {
  padding: 10px 14px; border-radius: 10px; border: 1px solid var(--border);
  background: var(--topbar); color: var(--text); cursor: pointer;
}
.btn:disabled { opacity: .6; cursor: not-allowed; }
.btn.ghost {
  background: transparent;
}
.btn.xs { padding: 6px 10px; font-size: 12px; }
.btn.danger { border-color: #5b2a2a; background: #311; color: #ffb5b5; }

.meta {
  color: var(--muted);
  margin-top: 8px;
  display: flex; gap: 8px; align-items: center;
}
.row { margin-top: 6px; }
.dot { opacity: .6; margin: 0 4px; }
.ok { color: #80d49b; }

.table {
  width: 100%; border-collapse: collapse; overflow: hidden;
  border: 1px solid var(--border); border-radius: 10px; margin-top: 10px;
}
td { border-bottom: 1px solid var(--border); padding: 8px; word-break: break-all; }

.histHead {
  display: flex; justify-content: space-between; align-items: center;
  margin: 12px 0 6px;
}

.grid {
  display: grid; gap: 12px;
  grid-template-columns: 1fr; /* 필요시 repeat(2,1fr) */
}
.cell {
  position: relative;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 12px 12px 10px;
}
.cellHdr {
  display: flex; justify-content: space-between; gap: 8px; align-items: center;
}
.ts { font-size: 12px; color: var(--muted); }
.cellBtns { display: flex; gap: 6px; }

.sum {
  color: #7aa2ff;
  font-weight: 600;
  margin-top: 6px;
  word-break: break-all;
}

.results { margin-top: 8px; }
.bullet { color: #ddd; }
.omitted { color: var(--muted); font-style: italic; margin-top: 4px; }

.raw { margin-top: 8px; }
.rawBox { margin-top: 6px; }
.rawTitle { color: var(--muted); font-size: 12px; margin: 6px 0; }
.pre {
  background: var(--surface-2);
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px; overflow: auto; font-size: 12px; white-space: pre-wrap;
}

/* 최신 항목 Flash 효과 */
.flash {
  animation: flash 1.2s ease-out 1;
}
@keyframes flash {
  0%   { box-shadow: 0 0 0 0 rgba(122,162,255,.5); }
  100% { box-shadow: 0 0 0 10px rgba(122,162,255,0); }
}

.error { color: #ff8b8b; margin-top: 8px; }
.muted { color: var(--muted); }
</style>
