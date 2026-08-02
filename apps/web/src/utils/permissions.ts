export function hasEveryPermission(granted: readonly string[], required: readonly string[]): boolean {
  const available = new Set(granted)
  return required.every((permission) => available.has(permission))
}

