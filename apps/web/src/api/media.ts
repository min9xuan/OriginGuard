import type { MediaAsset, RegisterAssetRequest } from '../types/business'
import { apiRequest } from './http'

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
}
