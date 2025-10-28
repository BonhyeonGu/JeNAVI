<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { browseApi } from '../api/endpoints'
import type { ApiResponse, BrowsePayload, TripleRow } from '../api/types'

const router = useRouter()
const route = useRoute()

const uri = ref('')
const loading = ref(false)
const res = ref<ApiResponse<BrowsePayload> | null>(null)
const err = ref<string | null>(null)
const copiedText = ref<string | null>(null)
let copiedTimer: number | undefined

function shortName(u?: string | null) {
  if (!u) return ''
  const i1 = u.lastIndexOf('/')
  const i2 = u.lastIndexOf('#')
  const idx = Math.max(i1, i2)
  return idx >= 0 && idx < u.length - 1 ? u.slice(idx + 1) : u
}

async function copyToClipboard(full: string) {
  try {
    await navigator.clipboard.writeText(full)
    copiedText.value = full
    if (copiedTimer) window.clearTimeout(copiedTimer)
    copiedTimer = window.setTimeout(() => (copiedText.value = null), 1200)
  } catch {}
}

async function fetchBrowse(u: string) {
  loading.value = true
  err.value = null
  res.value = null
  try {
    res.value = await browseApi({ uri: u })
  } catch (e: any) {
    err.value = e?.message ?? 'failed'
  } finally {
    loading.value = false
  }
}

// 입력창에서 조회 클릭
async function submit() {
  if (!uri.value) return
  router.push({ name: 'Browser', query: { uri: uri.value } })
}

// 표에서 URI 클릭 시 재브라우징
function openResource(u?: string | null) {
  if (!u) return
  router.push({ name: 'Browser', query: { uri: u } })
}

// ✅ 쿼리 파라미터 변화 감지 → 자동 fetch
watch(
  () => route.query.uri,
  (q) => {
    const u = typeof q === 'string' ? q : ''
    if (!u) {
      res.value = null
      return
    }
    uri.value = u
    fetchBrowse(u)
  },
  { immediate: true }
)

const rows = (x?: TripleRow[] | null) => x ?? []
const isUri = (s?: string | null) => !!s && /^https?:\/\//i.test(s)
</script>

<template>
  <section class="section">
    <h1 class="h1">브라우저</h1>

    <!-- 검색 폼 -->
    <form class="form" @submit.prevent="submit()">
      <input
        v-model="uri"
        type="text"
        placeholder="풀 URI 입력 (예: https://github.com/BonhyeonGu/resources/_210812_C1-TYPE_117dong-IFC4x3pre#GML_0EpCosk7n17wPaBSSWQzyzk)"
        class="input"
      />
      <button :disabled="!uri || loading" class="button">
        {{ loading ? '조회중...' : '조회' }}
      </button>
    </form>

    <!-- 복사 토스트 -->
    <div v-if="copiedText" class="toast">복사됨</div>

    <!-- 에러 -->
    <p v-if="err" class="error">{{ err }}</p>

    <!-- 결과 -->
    <div v-if="res?.data">
      <p class="badge">
        URI:
        <span class="mono">{{ shortName(res.data.resourceURI) }}</span>
        <button class="copybtn" @click="copyToClipboard(res.data.resourceURI)">복사</button>
        · {{ res.timeMs }}ms
      </p>

      <!-- 속성인 경우 -->
      <div v-if="res.data.isProperty">
        <h3 class="headline">Property Details</h3>
        <table class="table">
          <thead>
            <tr>
              <th>property</th>
              <th>value</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r,i) in rows(res.data.propertyDetails)" :key="i">
              <td>
                <span class="mono">
                  {{ isUri(r.property) ? shortName(r.property) : r.property }}
                </span>
                <button class="copybtn" @click="copyToClipboard(r.property)">복사</button>
              </td>
              <td>
                <template v-if="isUri(r.value)">
                  <button type="button" class="alink asButton" @click.prevent="openResource(r.value)">
                    {{ shortName(r.value) }}
                  </button>
                  <button class="copybtn" @click="copyToClipboard(r.value)">복사</button>
                </template>
                <template v-else>
                  <span class="mono">{{ r.value }}</span>
                  <button class="copybtn" @click="copyToClipboard(r.value)">복사</button>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 일반 리소스인 경우 -->
      <div v-else>
        <h3 class="headline">Outgoing</h3>
        <table class="table">
          <thead>
            <tr>
              <th>property</th>
              <th>value</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r,i) in rows(res.data.outgoing)" :key="'o'+i">
              <td>
                <span class="mono">
                  {{ isUri(r.property) ? shortName(r.property) : r.property }}
                </span>
                <button class="copybtn" @click="copyToClipboard(r.property)">복사</button>
              </td>
              <td>
                <template v-if="isUri(r.value)">
                  <button class="alink asButton" @click.prevent="openResource(r.value)">
                    {{ shortName(r.value) }}
                  </button>
                  <button class="copybtn" @click="copyToClipboard(r.value)">복사</button>
                </template>
                <template v-else>
                  <span class="mono">{{ r.value }}</span>
                  <button class="copybtn" @click="copyToClipboard(r.value)">복사</button>
                </template>
              </td>
            </tr>
          </tbody>
        </table>

        <h3 class="headline" style="margin-top:16px;">Incoming</h3>
        <table class="table">
          <thead>
            <tr>
              <th>property</th>
              <th>value</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r,i) in rows(res.data.incoming)" :key="'i'+i">
              <td>
                <span class="mono">
                  {{ isUri(r.property) ? shortName(r.property) : r.property }}
                </span>
                <button class="copybtn" @click="copyToClipboard(r.property)">복사</button>
              </td>
              <td>
                <template v-if="isUri(r.value)">
                  <button class="alink asButton" @click.prevent="openResource(r.value)">
                    {{ shortName(r.value) }}
                  </button>
                  <button class="copybtn" @click="copyToClipboard(r.value)">복사</button>
                </template>
                <template v-else>
                  <span class="mono">{{ r.value }}</span>
                  <button class="copybtn" @click="copyToClipboard(r.value)">복사</button>
                </template>
              </td>
            </tr>
          </tbody>
        </table>

        <p class="badge">
          Outgoing: {{ res.data.timeOutgoingMs }}ms · Incoming: {{ res.data.timeIncomingMs }}ms
        </p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.section {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 16px;
  position: relative;
}
.h1 {
  margin: 0 0 12px;
  font-size: 20px; font-weight: 700;
}
.form { display: flex; gap: 8px; margin: 12px 0; }
.input {
  flex: 1; padding: 10px; border-radius: 10px;
  background: var(--surface-2); color: var(--text);
  border: 1px solid var(--border); outline: none;
}
.input::placeholder { color: var(--muted); }
.button {
  padding: 10px 14px; border-radius: 10px; border: 1px solid var(--border);
  background: var(--topbar); color: var(--text); cursor: pointer;
}
.button:disabled { opacity: .6; cursor: not-allowed; }

.toast {
  position: absolute; top: 10px; right: 12px;
  background: rgba(255,255,255,.08);
  border: 1px solid var(--border);
  padding: 6px 10px; border-radius: 999px;
  font-size: 12px; color: var(--text);
}

.badge { color: var(--muted); margin: 8px 0 16px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }

.headline {
  margin: 18px 0 8px; font-size: 16px; font-weight: 600;
  color: var(--text);
  border-left: 4px solid var(--accent);
  padding-left: 8px;
}

.table {
  width: 100%; border-collapse: collapse; overflow: hidden;
  border: 1px solid var(--border); border-radius: 10px;
}
th, td {
  border-bottom: 1px solid var(--border);
  padding: 8px; word-break: break-all;
}
thead th {
  background: #141414;
  color: var(--muted);
  text-align: left; font-weight: 600;
}
tbody tr:hover { background: rgba(255,255,255,.03); }

.copybtn {
  margin-left: 6px;
  padding: 4px 8px;
  font-size: 12px;
  background: var(--topbar);
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 8px;
  cursor: pointer;
}
.copybtn:hover { background: rgba(255,255,255,.06); }

.alink { color: var(--accent); text-decoration: none; }
.alink:hover { text-decoration: underline; }

.error { color: #ff8b8b; }

.asButton {
  all: unset;
  color: var(--accent);
  cursor: pointer;
  display: inline;
}
.asButton:hover { text-decoration: underline; }
.asButton:focus {
  outline: 1px dashed var(--accent);
  outline-offset: 2px;
}
</style>
