/**
 * The browsers the end-to-end specs run in: those `E2E_BROWSERS` names, comma-separated, in the
 * order of `all`; every one of them when it is not set. A name not in `all` stops the run, rather
 * than letting it pass with fewer browsers than asked.
 */
export function selectBrowsers<T extends string>(
  value: string | undefined,
  all: readonly T[],
): T[] {
  if (value === undefined) {
    return [...all];
  }
  const named = value.split(',').map((name) => name.trim());
  const unknown = named.find((name) => !(all as readonly string[]).includes(name));
  if (unknown !== undefined) {
    throw new Error(
      `E2E_BROWSERS names an unknown browser, "${unknown}"; the valid ones are ${all.join(', ')}`,
    );
  }
  return all.filter((browser) => named.includes(browser));
}
