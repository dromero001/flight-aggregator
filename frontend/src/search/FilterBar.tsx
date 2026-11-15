import type { SubmitEvent } from 'react';
import styles from './FilterBar.module.css';
import type { FieldErrors, FilterField, SearchCriteria, SearchFilters } from './searchParams';

const CONTROLS: { field: FilterField; label: string; inputMode?: 'decimal' }[] = [
  { field: 'origin', label: 'From' },
  { field: 'destination', label: 'To' },
  { field: 'carrier', label: 'Airline' },
  { field: 'maxPrice', label: 'Max price', inputMode: 'decimal' },
];

interface FilterBarProps {
  criteria: SearchCriteria;
  errors: FieldErrors;
  onSubmit: (filters: SearchFilters) => void;
}

export function FilterBar({ criteria, errors, onSubmit }: FilterBarProps) {
  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const filters: SearchFilters = {};
    for (const { field } of CONTROLS) {
      const value = data.get(field);
      if (typeof value === 'string') filters[field] = value;
    }
    onSubmit(filters);
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit}>
      {CONTROLS.map(({ field, label, inputMode }) => {
        const error = errors[field];
        const errorId = `${field}-error`;
        return (
          <div key={field} className={styles.field}>
            <label className={styles.label}>
              {label}
              <input
                className={styles.input}
                name={field}
                type="text"
                inputMode={inputMode}
                defaultValue={criteria[field]}
                aria-invalid={error ? true : undefined}
                aria-describedby={error ? errorId : undefined}
              />
            </label>
            {error && (
              <p id={errorId} className={styles.error}>
                {error}
              </p>
            )}
          </div>
        );
      })}
      <button className={styles.submit} type="submit">
        Search
      </button>
    </form>
  );
}
