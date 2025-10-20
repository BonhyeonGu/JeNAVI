import { api } from './client'
import type { ApiResponse, BrowsePayload, BrowseRequest } from './types'

export async function browse(req: BrowseRequest) {
  const { data } = await api.post<ApiResponse<BrowsePayload>>('/api/browse', req)
  return data
}
