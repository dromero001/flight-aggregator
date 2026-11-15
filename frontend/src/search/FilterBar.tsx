import { useState, type SubmitEvent } from 'react';
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

type Draft = Record<FilterField, string>;

function draftFrom(criteria: SearchCriteria): Draft {
  return {
    origin: criteria.origin ?? '',
    destination: criteria.destination ?? '',
    carrier: criteria.carrier ?? '',
    maxPrice: criteria.maxPrice ?? '',
  };
}

export function FilterBar({ criteria, errors, onSubmit }: FilterBarProps) {
  const fromUrl = draftFrom(criteria);
  const urlKey = JSON.stringify(fromUrl);
  const [draft, setDraft] = useState(fromUrl);
  const [shownKey, setShownKey] = useState(urlKey);
  // The inputs follow the URL (Back, Forward, a normalised search) without remounting the form, which would drop the focus. A sort change leaves the URL's filters alone, so unsent typing stays.
  if (shownKey !== urlKey) {
    setShownKey(urlKey);
    setDraft(fromUrl);
  }

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    onSubmit(draft);
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
                value={draft[field]}
                onChange={(event) => {
                  setDraft({ ...draft, [field]: event.target.value });
                }}
                aria-invalid={error ? true : undefined}
                aria-describedby={error ? errorId : undefined}
              />
            </label>
            {/* The region is always in the page, so a message added after a submit is announced. */}
            <div id={errorId} className={styles.error} aria-live="polite">
              {error}
            </div>
          </div>
        );
      })}
      <button className={styles.submit} type="submit">
        Search
      </button>
    </form>
  );
}
