import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { axe } from '../test/axe';
import { AIRPORT_CITIES } from './airports';
import { FilterBar } from './FilterBar';

test('has no accessibility violations while a field is invalid', async () => {
  const { container } = render(
    <FilterBar
      criteria={{ origin: 'MADR', sort: 'price' }}
      errors={{ origin: 'Enter a 3-letter airport code, like MAD' }}
      onSubmit={vi.fn()}
    />,
  );

  expect(await axe(container)).toHaveNoViolations();
});

test('labels every control', () => {
  render(<FilterBar criteria={{ sort: 'price' }} errors={{}} onSubmit={vi.fn()} />);

  // A text field with suggestions (a `list`) is a combobox.
  for (const name of ['From', 'To']) {
    expect(screen.getByRole('combobox', { name })).toBeInTheDocument();
  }
  for (const name of ['Airline', 'Max price']) {
    expect(screen.getByRole('textbox', { name })).toBeInTheDocument();
  }
  expect(screen.getByRole('textbox', { name: 'Max price' })).toHaveAttribute(
    'inputmode',
    'decimal',
  );
  expect(screen.getByRole('button', { name: 'Search' })).toBeInTheDocument();
});

/** The value and the text of each option of the field's `<datalist>`. */
function suggestionsOf(field: HTMLElement): [string, string | null][] {
  const list = document.getElementById(field.getAttribute('list') ?? '');
  return [...(list?.querySelectorAll('option') ?? [])].map((option) => [
    option.value,
    option.textContent,
  ]);
}

test("suggests the dataset's airports in From, by code and city", () => {
  render(<FilterBar criteria={{ sort: 'price' }} errors={{}} onSubmit={vi.fn()} />);

  const suggestions = suggestionsOf(screen.getByLabelText('From'));

  expect(suggestions).toHaveLength(AIRPORT_CITIES.size);
  expect(suggestions).toContainEqual(['MAD', 'MAD — Madrid']);
  expect(suggestions).toContainEqual(['JFK', 'JFK — New York']);
});

test("suggests the dataset's airports in To, the same list as From", () => {
  render(<FilterBar criteria={{ sort: 'price' }} errors={{}} onSubmit={vi.fn()} />);

  expect(screen.getByLabelText('To')).toHaveAttribute(
    'list',
    screen.getByLabelText('From').getAttribute('list'),
  );
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

test("sends a city typed in From as its airport's code", async () => {
  const onSubmit = vi.fn();
  const user = userEvent.setup();
  render(<FilterBar criteria={{ sort: 'price' }} errors={{}} onSubmit={onSubmit} />);

  await user.type(screen.getByLabelText('From'), 'madrid');
  await user.click(screen.getByRole('button', { name: 'Search' }));

  expect(onSubmit).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ origin: 'MAD' }));
});

test("sends a city typed in To as its airport's code", async () => {
  const onSubmit = vi.fn();
  const user = userEvent.setup();
  render(<FilterBar criteria={{ sort: 'price' }} errors={{}} onSubmit={onSubmit} />);

  await user.type(screen.getByLabelText('To'), 'New York');
  await user.click(screen.getByRole('button', { name: 'Search' }));

  expect(onSubmit).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ destination: 'JFK' }));
});

test('sends a city typed with spaces around it as its airport code', async () => {
  const onSubmit = vi.fn();
  const user = userEvent.setup();
  render(<FilterBar criteria={{ sort: 'price' }} errors={{}} onSubmit={onSubmit} />);

  await user.type(screen.getByLabelText('From'), ' Mexico City ');
  await user.click(screen.getByRole('button', { name: 'Search' }));

  expect(onSubmit).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ origin: 'MEX' }));
});
