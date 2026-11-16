export type SortOrder = 'price' | 'departure' | 'duration';

export interface SearchCriteria {
  origin?: string;
  destination?: string;
  carrier?: string;
  maxPrice?: string;
  sort: SortOrder;
}

export type SearchFilters = Omit<SearchCriteria, 'sort'>;

export type FilterField = 'origin' | 'destination' | 'carrier' | 'maxPrice';
export type FieldErrors = Partial<Record<FilterField, string>>;

/** The brief's four filters, in the order the form shows them. */
export const FILTER_FIELDS: readonly FilterField[] = [
  'origin',
  'destination',
  'carrier',
  'maxPrice',
];

interface Rule {
  isValid: (value: string) => boolean;
  message: string;
}

const AIRPORT: Rule = {
  isValid: (value) => /^[A-Z]{3}$/.test(value),
  message: 'Enter a 3-letter airport code, like MAD',
};

const RULES: Record<FilterField, Rule> = {
  origin: AIRPORT,
  destination: AIRPORT,
  carrier: {
    isValid: (value) => /^[A-Z0-9]{2}$/.test(value),
    message: 'Enter a 2-character airline code, like IB',
  },
  maxPrice: {
    // The amount stays a string: it only has to be well formed and above zero.
    isValid: (value) => /^[0-9]{1,9}(\.[0-9]{1,2})?$/.test(value) && /[1-9]/.test(value),
    message: 'Enter an amount in euros above 0, like 150 or 149.99',
  },
};

function normalise(field: FilterField, raw: string | null): string {
  const value = (raw ?? '').trim();
  return field === 'maxPrice' ? value : value.toUpperCase();
}

export function parseSearch(params: URLSearchParams): {
  criteria: SearchCriteria;
  errors: FieldErrors;
} {
  const criteria: SearchCriteria = { sort: 'price' };
  const errors: FieldErrors = {};
  for (const field of FILTER_FIELDS) {
    const value = normalise(field, params.get(field));
    if (!value) continue;
    criteria[field] = value;
    if (!RULES[field].isValid(value)) errors[field] = RULES[field].message;
  }
  const sort = params.get('sort');
  if (sort === 'departure' || sort === 'duration') criteria.sort = sort;
  return { criteria, errors };
}

/** The URL of a search, its values normalised as `parseSearch` reads them; empty ones left out. */
export function toSearchParams(criteria: SearchCriteria): URLSearchParams {
  const params = new URLSearchParams();
  for (const field of FILTER_FIELDS) {
    const value = normalise(field, criteria[field] ?? null);
    if (value) params.set(field, value);
  }
  if (criteria.sort !== 'price') params.set('sort', criteria.sort);
  return params;
}
