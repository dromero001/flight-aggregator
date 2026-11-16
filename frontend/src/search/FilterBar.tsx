import { useId, useState, type SubmitEvent } from 'react';
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
import { codeNamed, SUGGESTIONS, type SuggestionList } from './suggestions';

/**
 * Every filter's control (spec 6.8): a new filter in `FILTER_FIELDS` cannot compile without one.
 * A field with `suggestions` offers that list and searches a name in it by its code.
 */
const CONTROLS: Record<
  FilterField,
  { label: string; inputMode?: 'decimal'; suggestions?: SuggestionList }
> = {
  origin: { label: 'From', suggestions: 'airports' },
  destination: { label: 'To', suggestions: 'airports' },
  carrier: { label: 'Airline', suggestions: 'airlines' },
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
  const listsId = useId();
  // The inputs follow the URL (Back, Forward, a normalised search) without remounting the form, which would drop the focus. A sort change leaves the URL's filters alone, so unsent typing stays.
  if (shownKey !== urlKey) {
    setShownKey(urlKey);
    setDraft(fromUrl);
  }

  function handleSubmit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    const sent = { ...draft };
    for (const field of FILTER_FIELDS) {
      const { suggestions } = CONTROLS[field];
      if (suggestions) sent[field] = codeNamed(suggestions, draft[field]);
    }
    onSubmit(sent);
    // The inputs show what was searched, normalised (" mad " and "madrid" are MAD), as the URL
    // will read it. Not the URL's current values: the router applies a new search's URL in a
    // transition, after this render, so they would show the previous search for a moment.
    const searched = parseSearch(toSearchParams({ ...sent, sort: criteria.sort })).criteria;
    setDraft(draftFrom(searched));
  }

  return (
    <form className={styles.form} onSubmit={handleSubmit}>
      {FILTER_FIELDS.map((field) => {
        const { label, inputMode, suggestions } = CONTROLS[field];
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
                list={suggestions && `${listsId}-${suggestions}`}
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
      {Object.entries(SUGGESTIONS).map(([list, names]) => (
        <datalist key={list} id={`${listsId}-${list}`}>
          {[...names].map(([code, name]) => (
            <option key={code} value={code}>
              {`${code} — ${name}`}
            </option>
          ))}
        </datalist>
      ))}
      <button className={styles.submit} type="submit">
        Search
      </button>
    </form>
  );
}
