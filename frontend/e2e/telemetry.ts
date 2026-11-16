// The stack's telemetry, read the way the team reads it: through Grafana's API with no credentials,
// as the anonymous Viewer (spec 6.6), and Prometheus's alerts on its own API, on the addresses
// compose publishes (README, "Observability"). Each environment variable points at a stack
// published on other ports.
const GRAFANA_URL = process.env.GRAFANA_URL ?? 'http://localhost:3001';
const PROMETHEUS_URL = process.env.PROMETHEUS_URL ?? 'http://localhost:9090';
const KEYCLOAK_URL = process.env.KEYCLOAK_URL ?? 'http://localhost:8180';

// The admin-service client's secret, from the variable compose reads, with its demo default (README,
// "Admin access").
const ADMIN_SERVICE_CLIENT_SECRET =
  process.env.ADMIN_SERVICE_CLIENT_SECRET ?? 'demo-admin-service-secret';

/** Telemetry reaches Grafana's backends a few seconds after it is written; give it a minute. */
const POLL_TIMEOUT_MS = 60_000;
const POLL_INTERVAL_MS = 2_000;
const REQUEST_TIMEOUT_MS = 10_000;

/** A token with the ADMIN role: the README's client-credentials request. */
export async function adminToken(): Promise<string> {
  const { access_token: token } = await json<{ access_token: string }>(
    `${KEYCLOAK_URL}/realms/flight-aggregator/protocol/openid-connect/token`,
    {
      method: 'POST',
      body: new URLSearchParams({
        grant_type: 'client_credentials',
        client_id: 'admin-service',
        client_secret: ADMIN_SERVICE_CLIENT_SECRET,
      }),
    },
  );
  return token;
}

// ---- Traces (Tempo) ----

/** One span of a trace, its attributes as strings. */
export interface Span {
  serviceName: string;
  name: string;
  parentSpanId: string | undefined;
  attributes: Record<string, string>;
}

/** Tempo's answer (`/api/v2/traces/<id>`), as far as these tests read it. */
interface TempoAnswer {
  trace: {
    resourceSpans?: {
      resource: { attributes: Attribute[] };
      scopeSpans: { spans: { name: string; parentSpanId?: string; attributes?: Attribute[] }[] }[];
    }[];
  };
}

interface Attribute {
  key: string;
  value: Record<string, unknown>;
}

/**
 * Every span of one trace in Tempo, polling until it holds a span named `holding`. Spans reach
 * Tempo in batches as they end, so a trace is read whole once a span that ends after the others,
 * such as its parent, is there.
 */
export async function tempoTrace(traceId: string, holding: string): Promise<Span[]> {
  return poll(`trace ${traceId} with a span ${holding} in Tempo`, async () => {
    const spans = await readTrace(traceId);
    return spans.some((span) => span.name === holding) ? spans : undefined;
  });
}

async function readTrace(traceId: string): Promise<Span[]> {
  // A trace Tempo does not hold yet is an empty one: {"trace":{}}.
  const { trace } = await grafana<TempoAnswer>(
    `/api/datasources/proxy/uid/tempo/api/v2/traces/${traceId}`,
  );
  return (trace.resourceSpans ?? []).flatMap((resourceSpans) => {
    const serviceName = attributes(resourceSpans.resource.attributes)['service.name'] ?? '';
    return resourceSpans.scopeSpans.flatMap((scope) =>
      scope.spans.map((span) => ({
        serviceName,
        name: span.name,
        parentSpanId: span.parentSpanId ? hex(span.parentSpanId) : undefined,
        attributes: attributes(span.attributes ?? []),
      })),
    );
  });
}

function attributes(list: Attribute[]): Record<string, string> {
  return Object.fromEntries(list.map(({ key, value }) => [key, String(Object.values(value)[0])]));
}

/** Tempo's JSON writes ids in base64; W3C trace context writes them in hex. */
function hex(base64: string): string {
  return Buffer.from(base64, 'base64').toString('hex');
}

// ---- Logs (Loki) ----

/** One log line in Loki, with its labels and structured metadata (`trace_id`, `span_id`, …). */
export interface LogLine {
  line: string;
  fields: Record<string, string>;
}

/** Loki's answer to a log query (`/loki/api/v1/query_range`), as far as these tests read it. */
interface LokiAnswer {
  data: { result: { stream: Record<string, string>; values: [string, string][] }[] };
}

/** The lines of the last hour that a LogQL query selects, polling until there is at least one. */
export async function lokiLines(query: string): Promise<LogLine[]> {
  return poll(`log line for ${query} in Loki`, async () => {
    const parameters = new URLSearchParams({ query, since: '1h' });
    const { data } = await grafana<LokiAnswer>(
      `/api/datasources/proxy/uid/loki/loki/api/v1/query_range?${parameters.toString()}`,
    );
    // Without Loki's categorize-labels flag, a stream's labels hold each line's structured metadata.
    const lines = data.result.flatMap(({ stream, values }) =>
      values.map(([, line]) => ({ line, fields: stream })),
    );
    return lines.length > 0 ? lines : undefined;
  });
}

/** Loki's stream labels, polling until the application's own, `service_name`, is among them. */
export async function lokiLabels(): Promise<string[]> {
  return poll('service_name among the labels of Loki', async () => {
    const { data } = await grafana<{ data?: string[] }>(
      '/api/datasources/proxy/uid/loki/loki/api/v1/labels',
    );
    return data?.includes('service_name') ? data : undefined;
  });
}

/** Grafana's Explore on Loki, over the last hour, with one LogQL query. */
export function lokiExploreUrl(query: string): string {
  const panes = {
    logs: {
      datasource: 'loki',
      queries: [{ refId: 'A', expr: query, datasource: { type: 'loki', uid: 'loki' } }],
      range: { from: 'now-1h', to: 'now' },
    },
  };
  return `${GRAFANA_URL}/explore?schemaVersion=1&orgId=1&panes=${encodeURIComponent(JSON.stringify(panes))}`;
}

// ---- Metrics and alerts (Prometheus) ----

/** One alerting rule as Prometheus evaluates it. */
export interface AlertRule {
  name: string;
  /** `inactive`, `pending` or `firing`. */
  state: string;
  /** `ok`, or `err` when its last evaluation failed. */
  health: string;
}

/**
 * Every alerting rule Prometheus has loaded, as its alerts page shows them (`/api/v1/rules`),
 * polling until each has been evaluated after `since`.
 */
export async function alertRules(since: Date): Promise<AlertRule[]> {
  return poll(`alert rules evaluated after ${since.toISOString()}`, async () => {
    const { data } = await json<{
      data: { groups: { rules: (AlertRule & { lastEvaluation: string })[] }[] };
    }>(`${PROMETHEUS_URL}/api/v1/rules?type=alert`);
    const rules = data.groups.flatMap((group) => group.rules);
    // Before its first evaluation a rule reads 0001-01-01, with health `unknown`.
    const evaluated = rules.every((rule) => new Date(rule.lastEvaluation) > since);
    return evaluated
      ? rules.map(({ name, state, health }) => ({ name, state, health }))
      : undefined;
  });
}

/** A panel of a provisioned dashboard, with the queries it runs. */
export interface Panel {
  title: string;
  datasource: { uid: string };
  targets: ({ refId: string } & Record<string, unknown>)[];
}

/** The panels of a dashboard, as Grafana serves it to the anonymous Viewer. */
export async function dashboardPanels(uid: string): Promise<Panel[]> {
  const { dashboard } = await grafana<{ dashboard: { panels: Panel[] } }>(
    `/api/dashboards/uid/${uid}`,
  );
  return dashboard.panels;
}

/** Grafana's answer to `/api/ds/query`: data frames, each column's values in an array. */
interface QueryAnswer {
  results: Record<string, { error?: string; frames?: { data: { values: unknown[][] } }[] }>;
}

/**
 * Runs one query of a panel through Grafana, as the panel does, over the last hour, polling until
 * it returns a series with at least one value. Answers how many series it returned.
 */
export async function grafanaQuery(datasourceUid: string, query: object): Promise<number> {
  return poll(`data from ${JSON.stringify(query)}`, async () => {
    const { results } = await grafana<QueryAnswer>('/api/ds/query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        from: 'now-1h',
        to: 'now',
        queries: [{ ...query, refId: 'A', datasource: { uid: datasourceUid } }],
      }),
    });
    const { error, frames = [] } = results.A;
    if (error) throw new Error(`Grafana could not run ${JSON.stringify(query)}: ${error}`);
    // A series' last column holds its values; a frame with no row is no data.
    const series = frames.filter(({ data }) => (data.values.at(-1)?.length ?? 0) > 0);
    return series.length > 0 ? series.length : undefined;
  });
}

// ---- HTTP and polling ----

function grafana<T>(path: string, init?: RequestInit): Promise<T> {
  return json<T>(GRAFANA_URL + path, init);
}

/** One request with a 10 s limit; anything but a 2xx answer is an error naming the URL. */
async function json<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { ...init, signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) });
  if (!response.ok) throw new Error(`${url} answered HTTP ${String(response.status)}`);
  return (await response.json()) as T;
}

/** Calls `read` every 2 s until it gives a value, for up to a minute. */
async function poll<T>(what: string, read: () => Promise<T | undefined>): Promise<T> {
  const deadline = Date.now() + POLL_TIMEOUT_MS;
  for (;;) {
    const value = await read();
    if (value !== undefined) return value;
    if (Date.now() > deadline) {
      throw new Error(`No ${what} after ${String(POLL_TIMEOUT_MS / 1000)} s`);
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
  }
}
