import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation, useNavigate } from 'react-router';
import { expect, test } from 'vitest';
import { SearchPage } from './SearchPage';

function Location() {
  const { search } = useLocation();
  const navigate = useNavigate();
  return (
    <>
      <output aria-label="location">{search}</output>
      <button
        type="button"
        onClick={() => {
          void navigate(-1);
        }}
      >
        Back
      </button>
    </>
  );
}

function renderAt(url: string) {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <SearchPage />
      <Location />
    </MemoryRouter>,
  );
}

test('restores the previous search on back', async () => {
  const user = userEvent.setup();
  renderAt('/?origin=MAD&sort=departure');

  await user.clear(screen.getByLabelText('From'));
  await user.type(screen.getByLabelText('From'), 'bcn');
  await user.click(screen.getByRole('button', { name: 'Search' }));
  expect(screen.getByLabelText('location')).toHaveTextContent('?origin=BCN&sort=departure');

  await user.click(screen.getByRole('button', { name: 'Back' }));

  expect(screen.getByLabelText('location')).toHaveTextContent('?origin=MAD&sort=departure');
  expect(screen.getByLabelText('From')).toHaveValue('MAD');
  expect(screen.getByRole('radio', { name: 'Departure' })).toBeChecked();
});

test('restores the previous sort on back', async () => {
  const user = userEvent.setup();
  renderAt('/?origin=MAD');

  await user.click(screen.getByRole('radio', { name: 'Duration' }));
  expect(screen.getByLabelText('location')).toHaveTextContent('?origin=MAD&sort=duration');

  await user.click(screen.getByRole('button', { name: 'Back' }));

  expect(screen.getByLabelText('location')).toHaveTextContent(/^\?origin=MAD$/);
  expect(screen.getByRole('radio', { name: 'Price' })).toBeChecked();
});

test('keeps the focus in the form after a search changes the URL', async () => {
  const user = userEvent.setup();
  renderAt('/');

  await user.type(screen.getByLabelText('From'), 'mad{Enter}');

  expect(screen.getByLabelText('location')).toHaveTextContent('?origin=MAD');
  expect(screen.getByLabelText('From')).toHaveFocus();
  expect(screen.getByLabelText('From')).toHaveValue('MAD');
});

test('announces an error that appears after a submit', async () => {
  const user = userEvent.setup();
  renderAt('/');

  await user.type(screen.getByLabelText('From'), 'madr{Enter}');

  const message = screen.getByText('Enter a 3-letter airport code, like MAD');
  expect(message.closest('[aria-live="polite"]')).not.toBeNull();
});

test('shows invalid URL values as field errors', () => {
  renderAt('/?origin=MADR&maxPrice=-1&sort=best');

  const origin = screen.getByLabelText('From');
  expect(origin).toBeInvalid();
  expect(origin).toHaveAccessibleDescription('Enter a 3-letter airport code, like MAD');
  expect(origin).toHaveValue('MADR');
  expect(screen.getByLabelText('Max price')).toHaveAccessibleDescription(
    'Enter an amount in euros above 0, like 150 or 149.99',
  );
  expect(screen.getByLabelText('To')).toBeValid();
  expect(screen.getByLabelText('To')).not.toHaveAccessibleDescription();
  expect(screen.getByRole('radio', { name: 'Price' })).toBeChecked();
});

test('writes a submitted search to the URL', async () => {
  const user = userEvent.setup();
  renderAt('/');

  await user.type(screen.getByLabelText('From'), 'mad');
  await user.type(screen.getByLabelText('To'), 'JFK');
  await user.type(screen.getByLabelText('Max price'), '150');
  await user.click(screen.getByRole('button', { name: 'Search' }));
  await user.click(screen.getByRole('radio', { name: 'Duration' }));

  expect(screen.getByLabelText('location')).toHaveTextContent(
    '?origin=MAD&destination=JFK&maxPrice=150&sort=duration',
  );
});
