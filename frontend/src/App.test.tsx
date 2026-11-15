import { render, screen, within } from '@testing-library/react';
import { expect, test } from 'vitest';
import App from './App';

test('renders the search page, filled from the address, inside the main landmark', () => {
  window.history.pushState({}, '', '/?origin=MAD&sort=duration');

  render(<App />);

  const main = screen.getByRole('main');
  expect(within(main).getByRole('heading', { level: 1, name: 'Find flights' })).toBeInTheDocument();
  expect(within(main).getByLabelText('From')).toHaveValue('MAD');
  expect(within(main).getByRole('radio', { name: 'Duration' })).toBeChecked();
  expect(screen.getByRole('banner')).toBeInTheDocument();
});
