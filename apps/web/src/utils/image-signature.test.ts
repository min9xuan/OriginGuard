import { describe, expect, it } from 'vitest'
import { detectImageType } from './image-signature'

describe('detectImageType', () => {
  it('recognizes JPEG, PNG and WebP by their real signatures', () => {
    expect(detectImageType(Uint8Array.from([0xff, 0xd8, 0xff]))).toBe('image/jpeg')
    expect(detectImageType(Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))).toBe('image/png')
    expect(detectImageType(new TextEncoder().encode('RIFF1234WEBP'))).toBe('image/webp')
  })

  it('does not trust an unsupported byte sequence', () => {
    expect(detectImageType(new TextEncoder().encode('not-an-image'))).toBeNull()
  })
})
