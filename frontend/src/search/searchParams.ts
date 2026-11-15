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

const FILTER_FIELDS: FilterField[] = ['origin', 'destination', 'carrier', 'maxPrice'];

const AIRPORT_ERROR = 'Enter a 3-letter airport code, like MAD';

const RULES: Record<FilterField, { isValid: (value: string) => boolean; message: string }> = {
  origin: { isValid: (value) => /^[A-Z]{3}$/.test(value), message: AIRPORT_ERROR },
  destination: { isValid: (value) => /^[A-Z]{3}$/.test(value), message: AIRPORT_ERROR },
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

export function toSearchParams(criteria: SearchCriteria): URLSearchParams {
  const params = new URLSearchParams();
  for (const field of FILTER_FIELDS) {
    const value = criteria[field];
    if (value) params.set(field, value);
  }
  if (criteria.sort !== 'price') params.set('sort', criteria.sort);
  return params;
}
