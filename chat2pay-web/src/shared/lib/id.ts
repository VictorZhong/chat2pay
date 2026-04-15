export function createId(prefix: string) {
  const randomValue =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID().replaceAll('-', '').slice(0, 16)
      : Math.random().toString(16).slice(2, 18);

  return `${prefix}_${randomValue}`;
}
