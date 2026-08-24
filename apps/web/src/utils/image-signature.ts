export type SupportedImageType = 'image/jpeg' | 'image/png' | 'image/webp'

export function detectImageType(bytes: Uint8Array): SupportedImageType | null {
  if (bytes.length >= 8
    && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47
    && bytes[4] === 0x0d && bytes[5] === 0x0a && bytes[6] === 0x1a && bytes[7] === 0x0a) {
    return 'image/png'
  }
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return 'image/jpeg'
  }
  if (bytes.length >= 12
    && ascii(bytes, 0, 4) === 'RIFF'
    && ascii(bytes, 8, 12) === 'WEBP') {
    return 'image/webp'
  }
  return null
}

export async function detectImageFileType(file: File): Promise<SupportedImageType | null> {
  return detectImageType(new Uint8Array(await file.slice(0, 12).arrayBuffer()))
}

function ascii(bytes: Uint8Array, start: number, end: number) {
  return String.fromCharCode(...bytes.slice(start, end))
}
