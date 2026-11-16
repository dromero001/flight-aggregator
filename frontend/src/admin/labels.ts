/** Values whose English is not the words of their name. */
const SPELLED = new Map([['HALF_OPEN', 'Half-open']]);

/** "UNKNOWN_AIRPORT" → "Unknown airport": an API enum value as the screen writes it (plan 6, Q7). */
export function label(value: string): string {
  const spelled = SPELLED.get(value);
  if (spelled !== undefined) {
    return spelled;
  }
  const words = value.toLowerCase().replaceAll('_', ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}
