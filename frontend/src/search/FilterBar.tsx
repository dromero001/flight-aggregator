import { useId, useState, type SubmitEvent } from 'react';
import { AIRPORT_CITIES, airportCodeOf } from './airports';
import styles from './FilterBar.module.css';
import {
  FILTER_FIELDS,
  parseSearch,
  toSearchParams,
  type FieldErrors,
  type FilterField,
  type SearchCriteria,
  type SearchFilters,
} from './searchParams';

/** Every filter's control (spec 6.8): a new filter in `FILTER_FIELDS` cannot compile without one. */
const CONTROLS: Record<
  FilterField,
  { label: string; inputMode?: 'decimal'; suggestsAirports?: true }
> = {
  origin: { label: 'From', suggestsAirports: true },
  destination: { label: 'To', suggestsAirports: true },
  carrier: { label: 'Airline' },
  maxPrice: { label: 'Max price', inputMode: 'decimal' },
};

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
  const airportsId = useId();
  // The inputs follow the URL (Back, Forward, a normalised search) without remounting the form, which would drop the focus. A sort change leaves the URL's filters alone, so unsent typing stays.
  if (shownKey !== urlKey) {
    setShownKey(urlKey);
    setDraft(fromUrl);
  }

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const sent = {
      ...draft,
      origin: airportCodeOf(draft.origin),
      destination: airportCodeOf(draft.destination),
    };
    onSubmit(sent);
    // The inputs show what was searched, normalised (" mad " is MAD), as the URL will read it.
    // Not the URL's current values: the router applies a new search's URL in a transition, after
    // this render, so they would show the previous search for a moment.
    const searched = parseSearch(toSearchParams({ ...sent, sort: criteria.sort })).criteria;
    setDraft(draftFrom(searched));
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit}>
      {FILTER_FIELDS.map((field) => {
        const { label, inputMode, suggestsAirports } = CONTROLS[field];
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
                list={suggestsAirports ? airportsId : undefined}
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
      <datalist id={airportsId}>
        {[...AIRPORT_CITIES].map(([code, city]) => (
          <option key={code} value={code}>
            {`${code} — ${city}`}
          </option>
        ))}
      </datalist>
      <button className={styles.submit} type="submit">
        Search
      </button>
    </form>
  );
}
