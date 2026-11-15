import { useSearchParams } from 'react-router';
import { FilterBar } from './FilterBar';
import styles from './SearchPage.module.css';
import { parseSearch, toSearchParams, type SearchFilters, type SortOrder } from './searchParams';
import { SortTabs } from './SortTabs';

export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const { criteria, errors } = parseSearch(params);

  function search(filters: SearchFilters) {
    // Round-trip through the parser so the URL always holds the normalised values.
    setParams(
      toSearchParams(parseSearch(toSearchParams({ ...filters, sort: criteria.sort })).criteria),
    );
  }

  function sortBy(sort: SortOrder) {
    setParams(toSearchParams({ ...criteria, sort }));
  }

  return (
    <div className={styles.page}>
      <h1>Find flights</h1>
      {/* The inputs are uncontrolled: a new key per filter set remounts them with the URL's values (back, forward), but a sort change leaves unsent typing alone. */}
      <FilterBar
        key={toSearchParams({ ...criteria, sort: 'price' }).toString()}
        criteria={criteria}
        errors={errors}
        onSubmit={search}
      />
      <SortTabs value={criteria.sort} onChange={sortBy} />
    </div>
  );
}
