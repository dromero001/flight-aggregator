import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { SortTabs } from './SortTabs';

test('changes the sort with the arrow keys', async () => {
  const onChange = vi.fn();
  const user = userEvent.setup();
  render(<SortTabs value="price" onChange={onChange} />);

  expect(screen.getByRole('group', { name: 'Sort by' })).toBeInTheDocument();
  expect(screen.getByRole('radio', { name: 'Price' })).toBeChecked();

  await user.click(screen.getByRole('radio', { name: 'Price' }));
  await user.keyboard('{ArrowRight}');

  expect(onChange).toHaveBeenCalledExactlyOnceWith('departure');
  expect(screen.getByRole('radio', { name: 'Departure' })).toHaveFocus();
});
