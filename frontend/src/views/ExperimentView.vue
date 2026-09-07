<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'

type Row = {
  w: number; k: number
  naiveQueryMs: number   // O(W) 스캔+group-by (ms)
  indexedQueryUs: number // O(k) 유지값 읽기 (µs)
  speedup: number
  buildMs: number
}
type Report = { reps: number; warmup: number; rows: Row[] }

const wInput = ref('200,500,1000,2000,4000,8000')
const reps = ref(21)
const k = ref(10)
const running = ref(false)
const err = ref<string | null>(null)
const report = ref<Report | null>(null)

const rows = computed(() => report.value?.rows ?? [])
const maxNaive = computed(() => Math.max(1, ...rows.value.map(r => r.naiveQueryMs)))
const suggested = ref<string | null>(null)

// 현재 로드된 레인(실험/aidtlab 무관)의 규모에서 W 스윕·k 자동 로드
async function loadSuggest() {
  try {
    const r = await fetch('/experiment/suggest')
    if (!r.ok) return
    const s = await r.json()
    if (Array.isArray(s.wList) && s.wList.length) {
      wInput.value = s.wList.join(',')
      suggested.value = `자동: base=${s.base} (로드된 result 컬럼), k=${s.k}`
    }
    if (s.k) k.value = s.k
  } catch { /* 무시: 기본값 유지 */ }
}
onMounted(loadSuggest)

async function run() {
  running.value = true
  err.value = null
  try {
    const url = `/experiment/run?w=${encodeURIComponent(wInput.value)}&reps=${reps.value}&k=${k.value}`
    const r = await fetch(url)
    if (!r.ok) throw new Error(await r.text() || `HTTP ${r.status}`)
    report.value = await r.json()
  } catch (e: any) {
    err.value = e?.message ?? '실험 실패'
  } finally {
    running.value = false
  }
}
</script>

<template>
  <div class="exp-wrap">
    <section class="card">
      <h2 class="title">벤치마크: IndexPoint O(k) vs 전체 스캔 O(W)</h2>
      <p class="desc">
        격리 실행 — W마다 신선한 모델을 새로 만들어(캐시/추론/순서 오염 배제) 워밍업 후 반복 측정의 <b>중앙값</b>.
        두 방식은 동일한 k개 집계를 산출합니다. IndexPoint는 유지값을 읽고(O(k)), 스캔은 전 Result를 group-by(O(W)).
      </p>

      <div class="controls">
        <label>W 목록 <input v-model="wInput" class="in wide" placeholder="200,500,1000,..." /></label>
        <label>반복 <input v-model.number="reps" type="number" min="1" max="201" class="in" /></label>
        <label>k(그룹) <input v-model.number="k" type="number" min="1" max="200" class="in" /></label>
        <button class="btn primary" :disabled="running" @click="run">{{ running ? '실행 중…' : '실험 실행' }}</button>
        <button class="btn" :disabled="running" @click="loadSuggest">W 자동</button>
      </div>
      <div v-if="suggested" class="hint">{{ suggested }}</div>
      <div v-if="err" class="err">{{ err }}</div>
    </section>

    <section class="card" v-if="rows.length">
      <div class="head">
        <h3 class="subtitle">결과 (reps={{ report?.reps }}, warmup={{ report?.warmup }})</h3>
      </div>
      <table class="tbl">
        <thead>
          <tr>
            <th>W (윈도우 거주)</th>
            <th>스캔 O(W) ms</th>
            <th>IndexPoint O(k) µs</th>
            <th>배속</th>
            <th class="barcol">스캔 비용 (상대)</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.w">
            <td class="mono">{{ r.w.toLocaleString() }}</td>
            <td class="mono naive">{{ r.naiveQueryMs }}</td>
            <td class="mono idx">{{ r.indexedQueryUs }}</td>
            <td class="mono">{{ r.speedup > 0 ? r.speedup + '×' : '-' }}</td>
            <td class="barcol">
              <div class="bar" :style="{ width: (r.naiveQueryMs / maxNaive * 100) + '%' }"></div>
            </td>
          </tr>
        </tbody>
      </table>
      <p class="note">
        기대: <b>스캔은 W에 비례 상승</b>, <b>IndexPoint는 ~평탄</b> → 배속이 W와 함께 커집니다.
        (IndexPoint는 삽입 시 O(Δ) 유지 비용을 분산 지불하고 질의는 O(k). 질의가 잦을수록 유리.)
      </p>
    </section>
  </div>
</template>

<style scoped>
.exp-wrap { display: flex; flex-direction: column; gap: 20px; }
.card { background: #111; border: 1px solid #2a2a2a; border-radius: 16px; padding: 16px; color: #e7e7e7; }
.title { font-size: 18px; font-weight: 700; margin-bottom: 6px; }
.subtitle { font-size: 15px; font-weight: 600; }
.desc { font-size: 12.5px; color: #aaa; line-height: 1.5; margin-bottom: 12px; }
.controls { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.controls label { font-size: 12px; color: #bbb; display: flex; align-items: center; gap: 6px; }
.in { background: #1a1a1a; border: 1px solid #3a3a3a; color: #eee; border-radius: 8px; padding: 5px 8px; font-size: 13px; width: 70px; }
.in.wide { width: 260px; font-family: ui-monospace, Consolas, monospace; }
.btn { border: 1px solid #4a4a4a; background: #1a1a1a; color: #e7e7e7; border-radius: 10px; padding: 6px 16px; cursor: pointer; font-size: 13px; }
.btn.primary { border-color: #2a3cff; background: #2a3cff; color: #fff; }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.err { color: #ff6b6b; font-size: 13px; margin-top: 10px; }
.hint { color: #7bf1a8; font-size: 12px; margin-top: 8px; }
.head { margin-bottom: 10px; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th { text-align: left; color: #aaa; font-weight: 600; padding: 8px 10px; border-bottom: 1px solid #2a2a2a; }
.tbl td { padding: 8px 10px; border-bottom: 1px solid #1c1c1c; }
.mono { font-family: ui-monospace, Consolas, monospace; }
.naive { color: #ff9b9b; }
.idx { color: #7bf1a8; }
.barcol { width: 40%; }
.bar { height: 12px; background: linear-gradient(90deg, #a33636, #ff9b9b); border-radius: 4px; min-width: 2px; }
.note { font-size: 12px; color: #9aa0a6; margin-top: 12px; line-height: 1.5; }
</style>
