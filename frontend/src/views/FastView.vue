<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { queryRun } from '../api/endpoints'

const STA = 'https://paper.9bon.org/ontologies/sensorthings/1.3#'

const router = useRouter()
const individuals = ref<string[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

const things = ref<{ uri: string; name: string; active: boolean; count: number }[]>([])
const loadingThings = ref(true)
const errorThings = ref<string | null>(null)

const defBusy = ref(false)
const defMsg = ref<string | null>(null)

// 정의부 로드(sta:Thing ABox 적재) 후 Thing 목록 재조회
async function loadDefs(source: 'experiment' | 'real') {
  defBusy.value = true
  try {
    const r = await fetch(`/definitions/load?source=${source}`)
    const text = await r.text()
    if (!r.ok) throw new Error(text || `HTTP ${r.status}`)
    defMsg.value = text
    loadingThings.value = true
    await fetchThings()
  } catch (e: any) {
    defMsg.value = '정의 로드 실패: ' + (e?.message ?? 'error')
  } finally {
    defBusy.value = false
  }
}

// URI에서 마지막 부분만 추출
function getLastPart(uri: string) {
  const hash = uri.lastIndexOf('#')
  const slash = uri.lastIndexOf('/')
  const idx = Math.max(hash, slash)
  return idx >= 0 ? uri.substring(idx + 1) : uri
}

// SPARQL 질의 실행
async function fetchData() {
  const query = `
  PREFIX core: <https://dataset-dl.liris.cnrs.fr/rdf-owl-urban-data-ontologies/Ontologies/CityGML/2.0/core#>
  SELECT ?s WHERE { ?s a core:CityModel . }
  `
  try {
    const res = await queryRun(query)
    if (res.status === 'ok' && Array.isArray(res.data)) {
      individuals.value = res.data
        .map(row => row[0])
        .filter((x): x is string => typeof x === 'string' && x.length > 0)
    } else {
      error.value = '결과 형식이 잘못되었습니다.'
    }
  } catch (e: any) {
    error.value = e?.message ?? 'SPARQL 질의 실패'
  } finally {
    loading.value = false
  }
}

// Thing 목록 조회 (정의 ABox 미적재 시 빈 목록)
async function fetchThings() {
  const query = `
  PREFIX sta: <${STA}>
  SELECT ?s ?name (MAX(?count) AS ?mx) WHERE {
    ?s a sta:Thing .
    OPTIONAL { ?s sta:hasName ?name }
    OPTIONAL { ?s sta:hasMultiDatastream/sta:hasIndexPoint/sta:hasWindowedCount ?count }
  } GROUP BY ?s ?name
  `
  try {
    const res = await queryRun(query)
    if (res.status === 'ok' && Array.isArray(res.data)) {
      const list = res.data
        .filter(row => typeof row[0] === 'string' && (row[0] as string).length > 0)
        .map(row => {
          const uri = row[0] as string
          const nm = row[1]
          const count = Number(row[2] ?? 0) || 0
          return { uri, name: nm && nm.length ? nm : getLastPart(uri), active: count > 0, count }
        })
      // 갱신 중(active)을 위로, 그다음 이름순
      list.sort((a, b) => (Number(b.active) - Number(a.active)) || a.name.localeCompare(b.name))
      things.value = list
    } else {
      errorThings.value = '결과 형식이 잘못되었습니다.'
    }
  } catch (e: any) {
    errorThings.value = e?.message ?? 'SPARQL 질의 실패'
  } finally {
    loadingThings.value = false
  }
}

// BrowserView로 이동
function goToBrowser(uri: string) {
  router.push({ name: 'Browser', query: { uri } })
}

let thingTimer: number | null = null
onMounted(() => {
  fetchData()
  fetchThings()
  thingTimer = window.setInterval(fetchThings, 3000)  // 상태 라이브 갱신
})
onUnmounted(() => { if (thingTimer) clearInterval(thingTimer) })
</script>

<template>
  <div class="h-full flex flex-col bg-gray-900 text-gray-200 p-4">
    <h2 class="text-xl font-semibold mb-4">빠른 시작</h2>

    <div class="space-y-6 overflow-y-auto max-h-[85vh]">
      <!-- 🏙 CityModel 섹션 -->
      <div>
        <h3 class="text-base font-semibold mb-2 text-gray-300 border-b border-gray-700 pb-1">
          CityModel
        </h3>

        <div v-if="loading" class="text-sm text-gray-400">불러오는 중...</div>
        <div v-else-if="error" class="text-sm text-red-400">{{ error }}</div>

        <ul v-else class="grid grid-cols-1 gap-2">
          <li
            v-for="item in individuals"
            :key="item"
            @click="goToBrowser(item)"
            class="px-3 py-2 rounded-md border border-gray-700 hover:border-blue-500 hover:bg-gray-800 transition-colors cursor-pointer text-sm select-none"
          >
            {{ getLastPart(item) }}
          </li>
        </ul>

        <p v-if="!loading && individuals.length === 0 && !error" class="text-sm text-gray-400 mt-3">
          결과가 없습니다.
        </p>
      </div>

      <!-- 🧩 Thing 섹션 -->
      <div>
        <h3 class="text-base font-semibold mb-2 text-gray-300 border-b border-gray-700 pb-1">
          Thing
        </h3>

        <div class="def-controls">
          <button class="def-btn exp" :disabled="defBusy" @click="loadDefs('experiment')">실험 정의 로드</button>
          <button class="def-btn real" :disabled="defBusy" @click="loadDefs('real')">실제(aidtlab) 정의 로드</button>
          <span v-if="defBusy" class="text-sm text-gray-400">로드 중...</span>
        </div>
        <div v-if="defMsg" class="text-xs text-gray-400 mb-2">{{ defMsg }}</div>

        <div v-if="loadingThings" class="text-sm text-gray-400">불러오는 중...</div>
        <div v-else-if="errorThings" class="text-sm text-red-400">{{ errorThings }}</div>

        <ul v-else class="grid grid-cols-1 gap-2">
          <li
            v-for="item in things"
            :key="item.uri"
            @click="goToBrowser(item.uri)"
            class="px-3 py-2 rounded-md border border-gray-700 hover:border-blue-500 hover:bg-gray-800 transition-colors cursor-pointer text-sm select-none flex items-center gap-2"
          >
            <span :class="['dot', item.active ? 'on' : 'off']"></span>
            <span class="flex-1">{{ item.name }}</span>
            <span v-if="item.active" class="cnt">{{ item.count }}</span>
          </li>
        </ul>

        <p v-if="!loadingThings && things.length === 0 && !errorThings" class="text-sm text-gray-400 mt-3">
          결과가 없습니다. (정의 ABox 미적재)
        </p>
      </div>

      <!-- 🔧 다른 섹션 (예: Sensors, Buildings 등) 추가 예정 -->
      <!-- <div>
        <h3 class="text-base font-semibold mb-2 text-gray-300 border-b border-gray-700 pb-1">
          SensorThings
        </h3>
        <p class="text-sm text-gray-500">이곳에 센서 관련 항목이 표시될 예정입니다.</p>
      </div> -->
    </div>
  </div>
</template>

<style scoped>
ul {
  list-style: none;
  padding: 0;
  margin: 0;
}

li {
  background-color: rgba(255, 255, 255, 0.02);
}

li:hover {
  background-color: rgba(255, 255, 255, 0.06);
  border-color: var(--accent, #3b82f6);
}

.def-controls { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
.def-btn {
  border: 1px solid #4a4a4a; background: #1a1a1a; color: #e7e7e7;
  border-radius: 8px; padding: 5px 12px; font-size: 12px; cursor: pointer; transition: all .2s ease;
}
.def-btn:hover:not(:disabled) { background: #2a2a2a; color: #fff; }
.def-btn:disabled { opacity: .45; cursor: not-allowed; }
.def-btn.exp { border-color: #2f7a55; background: #1c3a2c; color: #aef0c8; }
.def-btn.exp:hover:not(:disabled) { background: #2f7a55; color: #fff; }
.def-btn.real { border-color: #2a3cff; background: #182052; color: #c2ccff; }
.def-btn.real:hover:not(:disabled) { background: #2a3cff; color: #fff; }

.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; flex: none; }
.dot.on { background: #46e08a; box-shadow: 0 0 6px rgba(70, 224, 138, .6); }
.dot.off { background: #555; }
.cnt { font-size: 11px; color: #7bf1a8; font-family: ui-monospace, Consolas, monospace; }
</style>
