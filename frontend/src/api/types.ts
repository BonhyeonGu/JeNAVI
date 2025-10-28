export type BrowseRequest = { uri: string };

// ApiResponse 제네릭 필드명 변경
export type ApiResponse<T> = {
  status: string;
  timeMs: number;   // ← was executionTimeMs
  data: T | null;
};

// /api/queryRun 응답 DTO들 (서버 스펙과 일치)
export type TableDTO = { vars: string[]; rows: string[][] };
export type BoolDTO  = { value: boolean };
export type GraphDTO = { rdf: string; format: string };
// UpdateAckDTO는 문자열 "UpdateAck"를 내려주므로 그대로 string으로 받습니다.
export type QueryRunData =
  | { kind: 'table'; payload: TableDTO }
  | { kind: 'bool';  payload: BoolDTO }
  | { kind: 'graph'; payload: GraphDTO }
  | { kind: 'update'; payload: 'UpdateAck' };

// /api/browse용 (기존 그대로 사용 가능)
export type TripleRow = { property: string; value: string; link?: string | null; rowspan: number };
export type BrowsePayload = {
  resourceURI: string;
  isProperty: boolean;
  propertyDetails?: TripleRow[] | null;
  outgoing?: TripleRow[] | null;
  incoming?: TripleRow[] | null;
  timePropertyMs?: number | null;
  timeOutgoingMs?: number | null;
  timeIncomingMs?: number | null;
};


export type OntologyStats = {
  totalClassCount: number;
  classWithInstanceCount: number;
  totalInstances: number;
  classRichness: number;
  averagePopulation: number;
};

export type StatusData = {
  storage: 'TDB2' | 'in-memory';
  tdbBytes: number;
  uptimeMs: number;
  heapUsedBytes: number;
  heapCommittedBytes: number;
  heapMaxBytes: number;
  ontologyStats: OntologyStats;
};