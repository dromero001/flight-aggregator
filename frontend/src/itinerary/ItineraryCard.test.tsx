import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, expect, test } from 'vitest';
import type { RepriceResponse } from '../api/types';
import { axe } from '../test/axe';
import { connecting, direct } from '../test/fixtures';
import { renderWithClient } from '../test/renderWithClient';
import { server } from '../test/server';
import { ItineraryCard } from './ItineraryCard';
import dayMarkStyles from './DayMark.module.css';
import styles from './ItineraryCard.module.css';

const REPRICE = '*/api/itineraries/:id/reprice';

/** The API's answer when the chosen itinerary is confirmed at its price. */
function confirmed(itinerary = connecting()): RepriceResponse {
  return { outcome: 'CONFIRMED', itinerary };
}

beforeEach(() => {
  // Opening a card re-prices it; a test that cares about the answer overrides this one.
  server.use(http.post(REPRICE, () => HttpResponse.json(confirmed())));
});

test('has no accessibility violations', async () => {
  const { container } = renderWithClient(
    <ItineraryCard itinerary={connecting()} now={new Date('2026-11-15T09:05:00Z')} />,
  );

  expect(await axe(container)).toHaveNoViolations();
});

test('shows a direct flight', () => {
  // Five minutes after the fixture's quote.
  renderWithClient(<ItineraryCard itinerary={direct()} now={new Date('2026-11-15T09:05:00Z')} />);

  const card = within(screen.getByRole('article'));
  expect(card.getByText('08:15')).toHaveAttribute('datetime', '2026-11-20T08:15:00+01:00');
  expect(card.getByText('09:40')).toHaveAttribute('datetime', '2026-11-20T09:40:00Z');
  expect(card.getByText('Fri 20 Nov')).toHaveAttribute('datetime', '2026-11-20T08:15:00+01:00');
  expect(card.getByText('MAD')).toBeInTheDocument();
  expect(card.getByText('LHR')).toBeInTheDocument();
  expect(card.getByText('2h 25m')).toBeInTheDocument();
  expect(card.getByText('Direct')).toBeInTheDocument();
  expect(card.getByText('Iberia IB 3166')).toBeInTheDocument();
  expect(card.getByText('€129.90')).toBeInTheDocument();
  expect(card.getByText('Updated 5 min ago')).toBeInTheDocument();
  expect(card.queryByText('Multiple airlines')).not.toBeInTheDocument();
});

test('shows each stop with its airport and layover', () => {
  const now = new Date('2026-11-15T09:05:00Z');
  const oneStop = renderWithClient(<ItineraryCard itinerary={connecting()} now={now} />);

  expect(screen.getByText('1 stop: LHR, 1h 10m')).toBeInTheDocument();
  // The route line holds one dot per stop (Q6).
  expect(oneStop.container.getElementsByClassName(styles.stop)).toHaveLength(1);
  oneStop.unmount();

  // On to Boston: 1 h 20 min at JFK.
  const toBoston = connecting({
    destination: 'BOS',
    arrival: '2026-11-20T16:15:00-05:00',
    stops: 2,
    legs: [
      ...connecting().legs,
      {
        carrier: 'BA',
        flightNumber: 4890,
        origin: 'JFK',
        destination: 'BOS',
        departure: '2026-11-20T15:00:00-05:00',
        arrival: '2026-11-20T16:15:00-05:00',
      },
    ],
  });
  const twoStops = renderWithClient(<ItineraryCard itinerary={toBoston} now={now} />);

  expect(screen.getByText('2 stops: LHR, 1h 10m; JFK, 1h 20m')).toBeInTheDocument();
  expect(twoStops.container.getElementsByClassName(styles.stop)).toHaveLength(2);
});

test('marks a multi-airline itinerary', () => {
  renderWithClient(
    <ItineraryCard itinerary={connecting()} now={new Date('2026-11-15T09:05:00Z')} />,
  );

  const card = within(screen.getByRole('article'));
  expect(card.getByText('Iberia IB 3166, British Airways BA 117')).toBeInTheDocument();
  expect(card.getByText('Multiple airlines')).toBeInTheDocument();
});

test('re-prices when a card is opened, once per opening', async () => {
  const user = userEvent.setup();
  const repriced: string[] = [];
  server.use(
    http.post(REPRICE, ({ params }) => {
      repriced.push(String(params.id));
      return HttpResponse.json(confirmed());
    }),
  );
  renderWithClient(
    <ItineraryCard itinerary={connecting()} now={new Date('2026-11-15T09:05:00Z')} />,
  );

  const summary = screen.getByRole('button', { expanded: false });
  await user.click(summary);
  await waitFor(() => {
    expect(repriced).toEqual([connecting().id]);
  });

  await user.click(summary);
  expect(summary).toHaveAttribute('aria-expanded', 'false');
  expect(repriced).toHaveLength(1);

  await user.click(summary);
  await waitFor(() => {
    expect(repriced).toEqual([connecting().id, connecting().id]);
  });
});

test('shows each leg when opened', async () => {
  const user = userEvent.setup();
  renderWithClient(
    <ItineraryCard itinerary={connecting()} now={new Date('2026-11-15T09:05:00Z')} />,
  );
  const summary = screen.getByRole('button', { expanded: false });
  expect(screen.queryByRole('list', { name: 'Legs' })).not.toBeInTheDocument();

  await user.click(summary);

  const legs = screen.getByRole('list', { name: 'Legs' });
  expect(summary).toHaveAttribute('aria-controls', legs.parentElement?.id);
  const [first, second] = within(legs).getAllByRole('listitem');
  expect(first).toHaveTextContent('Iberia IB 3166 MAD 08:15 to LHR 09:40');
  expect(second).toHaveTextContent('British Airways BA 117 LHR 10:50 to JFK 13:40');

  await user.click(summary);
  expect(screen.queryByRole('list', { name: 'Legs' })).not.toBeInTheDocument();
});

test('opens and closes a card with the keyboard', async () => {
  const user = userEvent.setup();
  renderWithClient(
    <ItineraryCard itinerary={connecting()} now={new Date('2026-11-15T09:05:00Z')} />,
  );

  await user.tab();
  const summary = screen.getByRole('button', { expanded: false });
  expect(summary).toHaveFocus();

  await user.keyboard('{Enter}');
  expect(summary).toHaveAttribute('aria-expanded', 'true');
  expect(screen.getByRole('list', { name: 'Legs' })).toBeVisible();

  await user.keyboard(' ');
  expect(summary).toHaveAttribute('aria-expanded', 'false');
  expect(screen.queryByRole('list', { name: 'Legs' })).not.toBeInTheDocument();
});

test('marks an arrival on the next local day', () => {
  const now = new Date('2026-11-15T09:05:00Z');
  const sameDay = renderWithClient(<ItineraryCard itinerary={direct()} now={now} />);
  expect(screen.queryByText('+1 day')).not.toBeInTheDocument();
  sameDay.unmount();

  // 22:40 in Madrid, 06:15 the next morning in London.
  const overnight = direct({
    departure: '2026-11-20T22:40:00+01:00',
    arrival: '2026-11-21T06:15:00Z',
    durationMinutes: 515,
  });
  renderWithClient(<ItineraryCard itinerary={overnight} now={now} />);

  expect(within(screen.getByRole('article')).getByText('+1 day')).toBeInTheDocument();
});

test('marks each leg that lands on the next local day', async () => {
  const user = userEvent.setup();
  // 22:40 in Madrid, 06:15 the next morning in London, then on to New York the same day.
  const overnight = connecting({
    departure: '2026-11-20T22:40:00+01:00',
    legs: [
      {
        carrier: 'IB',
        flightNumber: 3166,
        origin: 'MAD',
        destination: 'LHR',
        departure: '2026-11-20T22:40:00+01:00',
        arrival: '2026-11-21T06:15:00Z',
      },
      {
        carrier: 'BA',
        flightNumber: 117,
        origin: 'LHR',
        destination: 'JFK',
        departure: '2026-11-21T10:50:00Z',
        arrival: '2026-11-21T13:40:00-05:00',
      },
    ],
    arrival: '2026-11-21T13:40:00-05:00',
  });
  renderWithClient(<ItineraryCard itinerary={overnight} now={new Date('2026-11-15T09:05:00Z')} />);

  await user.click(screen.getByRole('button', { expanded: false }));

  const [first, second] = within(screen.getByRole('list', { name: 'Legs' })).getAllByRole(
    'listitem',
  );
  expect(first).toHaveTextContent('Iberia IB 3166 MAD 22:40 to LHR 06:15 +1 day');
  expect(second).toHaveTextContent('British Airways BA 117 LHR 10:50 to JFK 13:40');
  expect(second).not.toHaveTextContent('+1 day');
});

test("styles a leg's day mark as the summary's", async () => {
  const user = userEvent.setup();
  // 22:40 in Madrid, 06:15 the next morning in London.
  const overnight = direct({
    departure: '2026-11-20T22:40:00+01:00',
    arrival: '2026-11-21T06:15:00Z',
    durationMinutes: 515,
    legs: [
      {
        carrier: 'IB',
        flightNumber: 3166,
        origin: 'MAD',
        destination: 'LHR',
        departure: '2026-11-20T22:40:00+01:00',
        arrival: '2026-11-21T06:15:00Z',
      },
    ],
  });
  renderWithClient(<ItineraryCard itinerary={overnight} now={new Date('2026-11-15T09:05:00Z')} />);
  const summary = screen.getByRole('button', { expanded: false });

  await user.click(summary);

  const legMark = within(screen.getByRole('list', { name: 'Legs' })).getByText('+1 day');
  expect(legMark).toHaveClass(dayMarkStyles.mark);
  expect(within(summary).getByText('+1 day')).toHaveClass(dayMarkStyles.mark);
});
