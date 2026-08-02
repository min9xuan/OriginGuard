import { describe, expect, it } from 'vitest'
import { hasEveryPermission } from './permissions'

describe('hasEveryPermission', () => {
  it('requires every requested permission', () => {
    expect(hasEveryPermission(['case:read', 'case:create'], ['case:read'])).toBe(true)
    expect(hasEveryPermission(['case:read'], ['case:read', 'case:create'])).toBe(false)
  })
})

