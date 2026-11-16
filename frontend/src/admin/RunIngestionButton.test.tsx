import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, expect, test } from 'vitest';
import { axe } from '../test/axe';
import { statusReport } from '../test/fixtures';
import { renderAsAdmin } from '../test/renderAsAdmin';
import { server } from '../test/server';
import { StatusPage } from './StatusPage';

const STATUS = '*/api/admin/status';
const INGESTIONS = '*/api/admin/ingestions';

/** An RFC 9457 answer, as the API sends its 4xx. */
function problem(status: number, detail: string) {
  return HttpResponse.json(
    { type: 'about:blank', title: 'Conflict', status, detail },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

let statusRequests: number;

beforeEach(() => {
  statusRequests = 0;
  server.use(
    http.get(STATUS, () => {
      statusRequests += 1;
      return HttpResponse.json(statusReport());
    }),
  );
});

test('has no accessibility violations once a run is refused', async () => {
  server.use(http.post(INGESTIONS, () => problem(409, 'An ingestion is already running.')));
  const { container } = renderAsAdmin(<StatusPage />);
  await userEvent.click(await screen.findByRole('button', { name: 'Run ingestion now' }));
  await screen.findByRole('alert');

  expect(await axe(container)).toHaveNoViolations();
});

// The button lives on the status screen, whose status it refreshes once a run has started.
test('starts an ingestion', async () => {
  let runs = 0;
  server.use(
    http.post(INGESTIONS, () => {
      runs += 1;
      return new HttpResponse(null, { status: 202 });
    }),
  );
  renderAsAdmin(<StatusPage />);

  await userEvent.click(await screen.findByRole('button', { name: 'Run ingestion now' }));

  expect(await screen.findByText('Ingestion started.')).toBeInTheDocument();
  expect(runs).toBe(1);
  await waitFor(() => {
    expect(statusRequests).toBe(2);
  });
});

test('says when an ingestion is already running', async () => {
  server.use(http.post(INGESTIONS, () => problem(409, 'An ingestion is already running.')));
  renderAsAdmin(<StatusPage />);

  await userEvent.click(await screen.findByRole('button', { name: 'Run ingestion now' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('An ingestion is already running.');
  expect(screen.queryByText('Ingestion started.')).not.toBeInTheDocument();
});

test('cannot be pressed again while the ingestion is starting', async () => {
  let answer: () => void = () => undefined;
  const answered = new Promise<void>((resolve) => {
    answer = resolve;
  });
  server.use(
    http.post(INGESTIONS, async () => {
      await answered;
      return new HttpResponse(null, { status: 202 });
    }),
  );
  renderAsAdmin(<StatusPage />);
  const button = await screen.findByRole('button', { name: 'Run ingestion now' });

  await userEvent.click(button);

  expect(button).toBeDisabled();
  answer();
  expect(await screen.findByText('Ingestion started.')).toBeInTheDocument();
  expect(button).toBeEnabled();
});

test('shows the missing admin role', async () => {
  server.use(http.post(INGESTIONS, () => new HttpResponse(null, { status: 403 })));
  renderAsAdmin(<StatusPage />);

  await userEvent.click(await screen.findByRole('button', { name: 'Run ingestion now' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'This account does not have the admin role.',
  );
});

test('shows a failure to start an ingestion', async () => {
  // nginx's own page while the app is down: no problem body to show.
  server.use(
    http.post(
      INGESTIONS,
      () =>
        new HttpResponse('<html>502 Bad Gateway</html>', {
          status: 502,
          headers: { 'Content-Type': 'text/html' },
        }),
    ),
  );
  renderAsAdmin(<StatusPage />);

  await userEvent.click(await screen.findByRole('button', { name: 'Run ingestion now' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'The ingestion could not be started. Try again in a moment.',
  );
});
