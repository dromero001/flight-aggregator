/**
 * How long a started ingestion run may take to publish. A run takes about a minute, more on a
 * loaded host: CI's took 78 to 86 s (run 43864300834), and one took two and a half minutes on a
 * laptop running a second stack beside this one (plan 7 Task 7's run).
 */
export const RUN_PUBLISHED_WITHIN_MS = 240_000;
