// api/endpoints.ts
import type {
  ApiResponse,
  BrowsePayload,
  BrowseRequest,
  OntologyStats,
  StatusData,
} from './types';

const BASE = '/api'; // 모든 엔드포인트는 /api 하위

// ---------- /api/browse (POST) ----------
export async function browseApi(body: BrowseRequest): Promise<ApiResponse<BrowsePayload>> {
  const r = await fetch(`${BASE}/browse`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

// ---------- /api/query (POST) ----------
/** 서버의 /api/query 응답을 string[][]로 어댑트 (기존 QueryView 유지) */
export async function queryRun(query: string): Promise<ApiResponse<string[][]>> {
  const r = await fetch(`${BASE}/query`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query }),
  });
  if (!r.ok) throw new Error(await r.text());

  const raw = (await r.json()) as ApiResponse<any>;
  const out: ApiResponse<string[][]> = { status: raw.status, timeMs: raw.timeMs, data: [] };

  const d = raw.data;
  if (d == null) return out;

  if (typeof d === 'object') {
    if ('vars' in d && 'rows' in d) {
      // TableDTO
      out.data = (d as { rows: string[][] }).rows;
    } else if ('value' in d) {
      // BoolDTO
      out.data = [[String((d as { value: boolean }).value)]];
    } else if ('rdf' in d && 'format' in d) {
      // GraphDTO
      const g = d as { rdf: string; format: string };
      out.data = [[`[${g.format}]`], [g.rdf]];
    } else {
      // 알 수 없는 객체 형태 방어
      out.data = [[JSON.stringify(d)]];
    }
  } else if (typeof d === 'string') {
    // UpdateAck ("UpdateAck")
    out.data = [[d]];
  } else {
    out.data = [[String(d)]];
  }

  return out;
}

// ---------- 관리/관리자 영역 ----------

// /api/init (GET)
export async function initOntology(): Promise<ApiResponse<Record<string, string>>> {
  const r = await fetch(`${BASE}/init`, { method: 'GET' });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function getStatus(): Promise<ApiResponse<StatusData>> {
  const res = await fetch('/api/status', { method: 'GET' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return await res.json();
}

// /api/vali (GET)
export async function vali(): Promise<ApiResponse<Record<string, string>>> {
  const r = await fetch(`${BASE}/vali`, { method: 'GET' });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

// /api/dump (GET)
export async function dumpRdf(): Promise<ApiResponse<string>> {
  const r = await fetch(`${BASE}/dump`, { method: 'GET' });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

// /api/export/ngsi-ld (GET) — 온톨로지 내용을 NGSI-LD(JSON-LD)로 변환·다운로드
export async function exportNgsiLd(type?: string): Promise<{ filename: string; sizeBytes: number }> {
  const qs = type && type.trim() ? `?type=${encodeURIComponent(type.trim())}` : '';
  const r = await fetch(`${BASE}/export/ngsi-ld${qs}`, { method: 'GET' });
  if (!r.ok) throw new Error((await r.text()) || `HTTP ${r.status}`);
  const blob = await r.blob();
  const cd = r.headers.get('Content-Disposition') ?? '';
  const match = cd.match(/filename="?([^"]+)"?/);
  const filename = match?.[1] ?? 'jenavi-ngsi-ld.jsonld';
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
  return { filename, sizeBytes: blob.size };
}

// /api/ingest/updates (POST)
export async function ingestUpdates(body: { dir?: string; format?: string; deleteAfter?: boolean })
: Promise<ApiResponse<Record<string, any>>> {
  const r = await fetch(`${BASE}/ingest/updates`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

// /api/ingest/definitions (POST)
export async function ingestDefinitions(body: { dir?: string; format?: string; deleteAfter?: boolean })
: Promise<ApiResponse<Record<string, any>>> {
  const r = await fetch(`${BASE}/ingest/definitions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

// /api/ingest/dir (POST) — 쿼리스트링 방식 유지
export async function ingestFromDir(params: { dir: string; deleteAfter?: boolean; format?: string })
: Promise<ApiResponse<Record<string, any>>> {
  const { dir, deleteAfter = true, format = 'RDF/XML' } = params;
  const qs = new URLSearchParams({ dir, deleteAfter: String(deleteAfter), format }).toString();
  const r = await fetch(`${BASE}/ingest/dir?${qs}`, { method: 'POST' });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

// /api/upload/rdf (POST, multipart/form-data)
export async function uploadRdf(files: File[], format = 'RDF/XML')
: Promise<ApiResponse<Record<string, any>>> {
  const form = new FormData();
  files.forEach(f => form.append('files', f));
  form.append('format', format);

  const r = await fetch(`${BASE}/upload/rdf`, {
    method: 'POST',
    body: form,
  });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}
