import type { MediaAsset, RegisterAssetRequest } from '../types/business'
import { apiBlobRequest, apiRequest } from './http'

export const mediaApi = {
  list(accessToken: string) {
    return apiRequest<MediaAsset[]>('/assets', {}, accessToken)
  },
  register(request: RegisterAssetRequest, accessToken: string) {
    return apiRequest<MediaAsset>(
      '/assets',
      { method: 'POST', body: JSON.stringify(request) },
      accessToken,
    )
  },
  upload(file: File, sha256: string, accessToken: string) {
    const body = new FormData()
    body.append('file', file)
    body.append('sha256', sha256)
    return apiRequest<MediaAsset>('/assets/upload', { method: 'POST', body }, accessToken)
  },
  content(assetId: string, accessToken: string) {
    return apiBlobRequest(`/assets/${assetId}/content`, accessToken)
  },
}
