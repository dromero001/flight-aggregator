import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse, type HttpResponseResolver } from 'msw';
import { expect, test } from 'vitest';
import { axe } from '../test/axe';
import { connecting } from '../test/fixtures';
import { renderWithClient } from '../test/renderWithClient';
import { server } from '../test/server';
import { ItineraryCard } from './ItineraryCard';

const REPRICE = '*/api/itineraries/:id/reprice';

/** §6.5's notice, as the API sends it with an alternative. */
const ALTERNATIVE_NOTICE =
  "We couldn't confirm this flight. This is the best alternative we found.";

/** An RFC 9457 answer, as the API sends its errors. */
function problem(status: number, detail: string, headers: Record<string, string> = {}) {
  return HttpResponse.json(
    { type: 'about:blank', title: 'Error', status, detail },
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
  );
}

/** The best alternative to the connecting itinerary: a later flight on from London. */
function alternative() {
  const [toLondon] = connecting().legs;
  return connecting({
    id: '5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e',
    arrival: '2026-11-20T16:05:00-05:00',
    durationMinutes: 830,
    price: { amount: '398.20', currency: 'EUR' },
    legs: [
      toLondon,
      {
        carrier: 'BA',
        flightNumber: 175,
        origin: 'LHR',
        destination: 'JFK',
        departure: '2026-11-20T13:15:00Z',
        arrival: '2026-11-20T16:05:00-05:00',
      },
    ],
  });
}

/** Opens the card of `itinerary`, the re-price answered by `answer`; returns its panel. */
async function openCard(answer: HttpResponseResolver, itinerary = connecting()) {
  server.use(http.post(REPRICE, answer));
  renderWithClient(<ItineraryCard itinerary={itinerary} now={new Date('2026-11-15T09:05:00Z')} />);
  await userEvent.click(screen.getByRole('button', { expanded: false }));
  return screen.getByRole('status');
}

test('has no accessibility violations with an alternative open', async () => {
  const panel = await openCard(() =>
    HttpResponse.json({
      outcome: 'ALTERNATIVE',
      itinerary: alternative(),
      notice: ALTERNATIVE_NOTICE,
    }),
  );
  await within(panel).findByText(ALTERNATIVE_NOTICE);

  expect(await axe(document.body)).toHaveNoViolations();
});

test('shows a confirmed price with Select', async () => {
  const panel = await openCard(() =>
    HttpResponse.json({ outcome: 'CONFIRMED', itinerary: connecting() }),
  );

  expect(await within(panel).findByText('Price confirmed: €412.60')).toBeInTheDocument();
  expect(within(panel).getByRole('button', { name: 'Select' })).toBeInTheDocument();
});

test('says the price changed when the confirmed price is not the one the card showed', async () => {
  // A cached search page still shows €412.60; another customer's re-price already stored €431.00,
  // so the API confirms €431.00 against its own row (final review, code I2).
  const panel = await openCard(() =>
    HttpResponse.json({
      outcome: 'CONFIRMED',
      itinerary: connecting({ price: { amount: '431.00', currency: 'EUR' } }),
    }),
  );

  expect(
    await within(panel).findByText('Price changed: was €412.60, now €431.00'),
  ).toBeInTheDocument();
});

test('shows how fresh a confirmed price is', async () => {
  // The search quoted it ten minutes before `now`; the re-price confirmed it at `now`.
  await openCard(() =>
    HttpResponse.json({
      outcome: 'CONFIRMED',
      itinerary: connecting({ quotedAt: '2026-11-15T09:05:00Z' }),
    }),
  );

  const summary = within(screen.getByRole('button', { expanded: true }));
  expect(await summary.findByText('Updated just now')).toBeInTheDocument();
});

test('shows a changed price and updates the card', async () => {
  const panel = await openCard(() =>
    HttpResponse.json({
      outcome: 'PRICE_CHANGED',
      itinerary: connecting({ price: { amount: '431.00', currency: 'EUR' } }),
      previousPrice: { amount: '412.60', currency: 'EUR' },
    }),
  );

  expect(
    await within(panel).findByText('Price changed: was €412.60, now €431.00'),
  ).toBeInTheDocument();
  expect(within(panel).getByRole('button', { name: 'Select' })).toBeInTheDocument();
  const summary = within(screen.getByRole('button', { expanded: true }));
  expect(summary.getByText('€431.00')).toBeInTheDocument();
  expect(summary.queryByText('€412.60')).not.toBeInTheDocument();
});

test('says a changed price was the one the card showed, not the stored one', async () => {
  // A cached search page still shows €500.00; the API compares with the €520.00 it stored since.
  const panel = await openCard(
    () =>
      HttpResponse.json({
        outcome: 'PRICE_CHANGED',
        itinerary: connecting({ price: { amount: '540.00', currency: 'EUR' } }),
        previousPrice: { amount: '520.00', currency: 'EUR' },
      }),
    connecting({ price: { amount: '500.00', currency: 'EUR' } }),
  );

  expect(
    await within(panel).findByText('Price changed: was €500.00, now €540.00'),
  ).toBeInTheDocument();
});

test('confirms a changed price that is the one the card showed', async () => {
  // A cached search page still shows €500.00; the API stored €520.00 since, and quotes €500.00 now.
  const panel = await openCard(
    () =>
      HttpResponse.json({
        outcome: 'PRICE_CHANGED',
        itinerary: connecting({ price: { amount: '500.00', currency: 'EUR' } }),
        previousPrice: { amount: '520.00', currency: 'EUR' },
      }),
    connecting({ price: { amount: '500.00', currency: 'EUR' } }),
  );

  expect(await within(panel).findByText('Price confirmed: €500.00')).toBeInTheDocument();
});

test('shows how fresh a changed price is', async () => {
  // The search quoted it ten minutes before `now`; the re-price, at `now`.
  await openCard(() =>
    HttpResponse.json({
      outcome: 'PRICE_CHANGED',
      itinerary: connecting({
        price: { amount: '431.00', currency: 'EUR' },
        quotedAt: '2026-11-15T09:05:00Z',
      }),
      previousPrice: { amount: '412.60', currency: 'EUR' },
    }),
  );

  const summary = within(screen.getByRole('button', { expanded: true }));
  expect(await summary.findByText('Updated just now')).toBeInTheDocument();
});

test('keeps a changed price on the card while it is checked again', async () => {
  const user = userEvent.setup();
  let answered = 0;
  const panel = await openCard(async () => {
    answered += 1;
    // The second opening's check never answers within the test.
    if (answered > 1) await delay('infinite');
    return HttpResponse.json({
      outcome: 'PRICE_CHANGED',
      itinerary: connecting({ price: { amount: '431.00', currency: 'EUR' } }),
      previousPrice: { amount: '412.60', currency: 'EUR' },
    });
  });
  await within(panel).findByText('Price changed: was €412.60, now €431.00');
  const summary = screen.getByRole('button', { expanded: true });

  await user.click(summary);
  await user.click(summary);

  // Closing the card unmounted the first panel; this is the new opening's.
  expect(await within(screen.getByRole('status')).findByText('Checking the price…')).toBeVisible();
  expect(within(summary).getByText('€431.00')).toBeInTheDocument();
});

test('confirms the price an earlier re-price put on the card', async () => {
  const user = userEvent.setup();
  let answered = 0;
  const panel = await openCard(() => {
    answered += 1;
    const itinerary = connecting({ price: { amount: '431.00', currency: 'EUR' } });
    return answered === 1
      ? HttpResponse.json({
          outcome: 'PRICE_CHANGED',
          itinerary,
          previousPrice: { amount: '412.60', currency: 'EUR' },
        })
      : HttpResponse.json({ outcome: 'CONFIRMED', itinerary });
  });
  await within(panel).findByText('Price changed: was €412.60, now €431.00');
  const summary = screen.getByRole('button', { expanded: true });

  // The stub now shows €431.00, so the second opening's €431.00 is the price the card showed.
  await user.click(summary);
  await user.click(summary);

  expect(
    await within(screen.getByRole('status')).findByText('Price confirmed: €431.00'),
  ).toBeInTheDocument();
});

test('shows a newer search quote than the last re-price', async () => {
  const user = userEvent.setup();
  server.use(
    http.post(REPRICE, () =>
      HttpResponse.json({
        outcome: 'PRICE_CHANGED',
        itinerary: connecting({ price: { amount: '431.00', currency: 'EUR' } }),
        previousPrice: { amount: '412.60', currency: 'EUR' },
      }),
    ),
  );
  const now = new Date('2026-11-15T09:05:00Z');
  const { rerender } = renderWithClient(<ItineraryCard itinerary={connecting()} now={now} />);
  await user.click(screen.getByRole('button', { expanded: false }));
  await screen.findByText('Price changed: was €412.60, now €431.00');

  // "Reload" reads the search again: the same itinerary, quoted after the re-price.
  rerender(
    <ItineraryCard
      itinerary={connecting({
        price: { amount: '440.00', currency: 'EUR' },
        quotedAt: '2026-11-15T09:04:00Z',
      })}
      now={now}
    />,
  );

  expect(within(screen.getByRole('button', { expanded: true })).getByText('€440.00')).toBeVisible();
});

test('shows the alternative inside the card with its notice', async () => {
  const panel = await openCard(() =>
    HttpResponse.json({
      outcome: 'ALTERNATIVE',
      itinerary: alternative(),
      notice: ALTERNATIVE_NOTICE,
    }),
  );

  expect(await within(panel).findByText(ALTERNATIVE_NOTICE)).toBeInTheDocument();
  const [first, second] = within(
    within(panel).getByRole('list', { name: 'Alternative legs' }),
  ).getAllByRole('listitem');
  expect(first).toHaveTextContent('Iberia IB 3166 MAD 08:15 to LHR 09:40');
  expect(second).toHaveTextContent('British Airways BA 175 LHR 13:15 to JFK 16:05');
  expect(within(panel).getByText('Price confirmed: €398.20')).toBeInTheDocument();
  expect(within(panel).getByRole('button', { name: 'Select' })).toBeInTheDocument();
  // The chosen card keeps its own flights and price; the alternative does not replace it (Q5-A).
  const summary = within(screen.getByRole('button', { expanded: true }));
  expect(summary.getByText('€412.60')).toBeInTheDocument();
});

test('shows the notice of spec 6.5 when an alternative comes without one', async () => {
  // The contract marks `notice` optional.
  const panel = await openCard(() =>
    HttpResponse.json({ outcome: 'ALTERNATIVE', itinerary: alternative() }),
  );

  expect(await within(panel).findByText(ALTERNATIVE_NOTICE)).toBeInTheDocument();
});

test('shows the 503 message', async () => {
  const panel = await openCard(() =>
    problem(503, 'Nothing could be confirmed.', { 'Retry-After': '120' }),
  );

  expect(
    await within(panel).findByText(
      "We can't confirm the price right now. Please try again in a few minutes.",
    ),
  ).toBeInTheDocument();
  expect(within(panel).queryByRole('button', { name: 'Select' })).not.toBeInTheDocument();
});

test('says when a flight is no longer available', async () => {
  const panel = await openCard(() => problem(404, 'No itinerary with this id is on sale.'));

  expect(
    await within(panel).findByText(
      'This flight is no longer available. Search again for current flights.',
    ),
  ).toBeInTheDocument();
});

test('shows the re-price limit with its seconds', async () => {
  const panel = await openCard(() => problem(429, 'Too many requests.', { 'Retry-After': '30' }));

  expect(
    await within(panel).findByText(
      'Too many price checks from your connection. Try again in 30 seconds.',
    ),
  ).toBeInTheDocument();
});

test('shows the failure for a re-price limit without a wait', async () => {
  // A proxy could answer 429 without Retry-After.
  const panel = await openCard(() => problem(429, 'Too many requests.'));

  expect(
    await within(panel).findByText(
      "We can't confirm the price right now. Please try again in a few minutes.",
    ),
  ).toBeInTheDocument();
});

test('says the price is being checked', async () => {
  const panel = await openCard(async () => {
    await delay('infinite');
    return HttpResponse.json({ outcome: 'CONFIRMED', itinerary: connecting() });
  });

  expect(within(panel).getByText('Checking the price…')).toBeInTheDocument();
  expect(within(panel).queryByRole('button', { name: 'Select' })).not.toBeInTheDocument();
});

test('says booking is not part of the demo when Select is pressed', async () => {
  const panel = await openCard(() =>
    HttpResponse.json({ outcome: 'CONFIRMED', itinerary: connecting() }),
  );
  const select = await within(panel).findByRole('button', { name: 'Select' });
  expect(within(panel).queryByText('Booking is not part of this demo.')).not.toBeInTheDocument();

  await userEvent.click(select);

  expect(within(panel).getByText('Booking is not part of this demo.')).toBeInTheDocument();
});
