<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { queryRun } from '../api/endpoints'

const router = useRouter()
const individuals = ref<string[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

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

// BrowserView로 이동
function goToBrowser(uri: string) {
  router.push({ name: 'Browser', query: { uri } })
}

onMounted(fetchData)
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
</style>
