import { useSearchParams } from 'react-router';
import { FilterBar } from './FilterBar';
import { ResultsList } from './ResultsList';
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
      <FilterBar criteria={criteria} errors={errors} onSubmit={search} />
      <SortTabs value={criteria.sort} onChange={sortBy} />
      {/* The API would reject an invalid filter; its field error already says why. */}
      <ResultsList criteria={criteria} enabled={Object.keys(errors).length === 0} />
    </div>
  );
}
