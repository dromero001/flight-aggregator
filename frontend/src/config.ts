/** The dashboard's settings for the admin sign-in (plan 6, Q3-A). */
export interface AppConfig {
  oidcAuthority: string;
  oidcClientId: string;
}

/**
 * Reads `/config.json`, which nginx renders from the container's environment (and `public/` holds
 * for `npm run dev`), so one image serves every environment. Only the admin screen reads it.
 */
export async function loadConfig(): Promise<AppConfig> {
  const response = await fetch(new URL('/config.json', window.location.origin));
  const settings = (await response.json()) as Partial<Record<keyof AppConfig, unknown>>;
  return {
    oidcAuthority: required(settings, 'oidcAuthority'),
    oidcClientId: required(settings, 'oidcClientId'),
  };
}

function required(settings: Partial<Record<keyof AppConfig, unknown>>, name: keyof AppConfig) {
  const value = settings[name];
  if (typeof value !== 'string' || value === '') {
    throw new Error(`/config.json has no ${name}`);
  }
  return value;
}
