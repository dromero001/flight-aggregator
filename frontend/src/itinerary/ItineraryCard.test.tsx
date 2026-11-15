import { render, screen, within } from '@testing-library/react';
import { expect, test } from 'vitest';
import { axe } from '../test/axe';
import { connecting, direct } from '../test/fixtures';
import { ItineraryCard } from './ItineraryCard';
import styles from './ItineraryCard.module.css';

test('has no accessibility violations', async () => {
  const { container } = render(
    <ItineraryCard itinerary={connecting()} now={new Date('2026-11-15T09:05:00Z')} />,
  );

  expect(await axe(container)).toHaveNoViolations();
});

test('shows a direct flight', () => {
  // Five minutes after the fixture's quote.
  render(<ItineraryCard itinerary={direct()} now={new Date('2026-11-15T09:05:00Z')} />);

  const card = within(screen.getByRole('article'));
  expect(card.getByText('08:15')).toHaveAttribute('datetime', '2026-11-20T08:15:00+01:00');
  expect(card.getByText('09:40')).toHaveAttribute('datetime', '2026-11-20T09:40:00Z');
  expect(card.getByText('Fri 20 Nov')).toHaveAttribute('datetime', '2026-11-20T08:15:00+01:00');
  expect(card.getByText('MAD')).toBeInTheDocument();
  expect(card.getByText('LHR')).toBeInTheDocument();
  expect(card.getByText('2h 25m')).toBeInTheDocument();
  expect(card.getByText('Direct')).toBeInTheDocument();
  expect(card.getByText('IB 3166')).toBeInTheDocument();
  expect(card.getByText('€129.90')).toBeInTheDocument();
  expect(card.getByText('Updated 5 min ago')).toBeInTheDocument();
  expect(card.queryByText('Multiple airlines')).not.toBeInTheDocument();
});

test('shows each stop with its airport and layover', () => {
  const now = new Date('2026-11-15T09:05:00Z');
  const oneStop = render(<ItineraryCard itinerary={connecting()} now={now} />);

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
  const twoStops = render(<ItineraryCard itinerary={toBoston} now={now} />);

  expect(screen.getByText('2 stops: LHR, 1h 10m; JFK, 1h 20m')).toBeInTheDocument();
  expect(twoStops.container.getElementsByClassName(styles.stop)).toHaveLength(2);
});

test('marks a multi-airline itinerary', () => {
  render(<ItineraryCard itinerary={connecting()} now={new Date('2026-11-15T09:05:00Z')} />);

  const card = within(screen.getByRole('article'));
  expect(card.getByText('IB 3166, BA 117')).toBeInTheDocument();
  expect(card.getByText('Multiple airlines')).toBeInTheDocument();
});
