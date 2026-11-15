import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { FilterBar } from './FilterBar';

test('labels every control', () => {
  render(<FilterBar criteria={{ sort: 'price' }} errors={{}} onSubmit={vi.fn()} />);

  for (const name of ['From', 'To', 'Airline', 'Max price']) {
    expect(screen.getByRole('textbox', { name })).toBeInTheDocument();
  }
  expect(screen.getByRole('textbox', { name: 'Max price' })).toHaveAttribute(
    'inputmode',
    'decimal',
  );
  expect(screen.getByRole('button', { name: 'Search' })).toBeInTheDocument();
});

test('submits the four filters', async () => {
  const onSubmit = vi.fn();
  const user = userEvent.setup();
  render(<FilterBar criteria={{ sort: 'price' }} errors={{}} onSubmit={onSubmit} />);

  await user.type(screen.getByLabelText('From'), 'mad');
  await user.type(screen.getByLabelText('To'), 'JFK');
  await user.type(screen.getByLabelText('Airline'), 'IB');
  await user.type(screen.getByLabelText('Max price'), '149.99');
  await user.click(screen.getByRole('button', { name: 'Search' }));

  expect(onSubmit).toHaveBeenCalledExactlyOnceWith({
    origin: 'mad',
    destination: 'JFK',
    carrier: 'IB',
    maxPrice: '149.99',
  });
});
