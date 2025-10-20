<script setup lang="ts">
import { ref } from 'vue'
import { initOntology, uploadRdf } from '../api/endpoints'
import type { ApiResponse } from '../api/types'

const initRes = ref<ApiResponse<Record<string, string>> | null>(null)  // ← payload 타입 변경
const initErr = ref<string | null>(null)
const uploading = ref(false)
const uploadRes = ref<ApiResponse<Record<string, any>> | null>(null)   // ← payload 타입 명확화
const uploadErr = ref<string | null>(null)
const files = ref<FileList | null>(null)

async function doInit() {
  initRes.value = null; initErr.value = null
  try {
    initRes.value = await initOntology()
  } catch (e: any) {
    initErr.value = e?.message ?? 'failed'
  }
}

async function doUpload() {
  if (!files.value || files.value.length === 0) return
  uploadRes.value = null; uploadErr.value = null; uploading.value = true
  try {
    const arr = Array.from(files.value)
    uploadRes.value = await uploadRdf(arr)
  } catch (e: any) {
    uploadErr.value = e?.message ?? 'failed'
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <section>
    <h1>관리</h1>

    <div class="card">
      <h3>온톨로지 초기화</h3>
      <button @click="doInit">/api/init 실행</button>
      <p v-if="initErr" style="color:crimson;">{{ initErr }}</p>
      <template v-if="initRes">
        <p>status: {{ initRes.status }} · {{ initRes.timeMs }}ms</p>
        <pre class="pre">{{ JSON.stringify(initRes.data, null, 2) }}</pre>
      </template>
    </div>

    <div class="card">
      <h3>RDF 업로드</h3>
      <input type="file" multiple accept=".rdf,.xml" @change="e => files = (e.target as HTMLInputElement).files" />
      <div style="margin-top:8px;">
        <button :disabled="uploading" @click="doUpload">{{ uploading ? '업로드 중...' : '업로드' }}</button>
      </div>
      <p v-if="uploadErr" style="color:crimson;">{{ uploadErr }}</p>
      <pre v-if="uploadRes" class="pre">{{ JSON.stringify(uploadRes, null, 2) }}</pre>
    </div>
  </section>
</template>
