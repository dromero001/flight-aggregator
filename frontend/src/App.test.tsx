import { render, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { expect, test } from 'vitest';
import App from './App';
import { server } from './test/server';

test('renders the search page, filled from the address, inside the main landmark', () => {
  // The page searches as it opens.
  server.use(
    http.get('*/api/itineraries', () =>
      HttpResponse.json({ itineraries: [], nextCursor: null, inventoryChanged: false }),
    ),
  );
  window.history.pushState({}, '', '/?origin=MAD&sort=duration');

  render(<App />);

  const main = screen.getByRole('main');
  expect(within(main).getByRole('heading', { level: 1, name: 'Find flights' })).toBeInTheDocument();
  expect(within(main).getByLabelText('From')).toHaveValue('MAD');
  expect(within(main).getByRole('radio', { name: 'Duration' })).toBeChecked();
  expect(screen.getByRole('banner')).toBeInTheDocument();
});
