import styles from './SortTabs.module.css';
import type { SortOrder } from './searchParams';

const OPTIONS: { value: SortOrder; label: string }[] = [
  { value: 'price', label: 'Price' },
  { value: 'departure', label: 'Departure' },
  { value: 'duration', label: 'Duration' },
];

interface SortTabsProps {
  value: SortOrder;
  onChange: (sort: SortOrder) => void;
}

export function SortTabs({ value, onChange }: SortTabsProps) {
  return (
    <fieldset className={styles.group}>
      <legend className={styles.legend}>Sort by</legend>
      {OPTIONS.map((option) => (
        <label key={option.value} className={styles.option}>
          <input
            className={styles.radio}
            type="radio"
            name="sort"
            value={option.value}
            checked={value === option.value}
            onChange={() => {
              onChange(option.value);
            }}
          />
          <span className={styles.tab}>{option.label}</span>
        </label>
      ))}
    </fieldset>
  );
}
