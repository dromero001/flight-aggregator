import { QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { BrowserRouter, Route, Routes } from 'react-router';
import { AdminPage } from './admin/AdminPage';
import styles from './App.module.css';
import { createQueryClient } from './queryClient';
import { SearchPage } from './search/SearchPage';

export default function App() {
  // Created inside the tree, so every mounted app (and every test) has its own cache.
  const [queryClient] = useState(createQueryClient);

  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <header className={styles.topBar}>Flight aggregator</header>
        <main className={styles.main}>
          <Routes>
            <Route path="/" element={<SearchPage />} />
            <Route path="/admin" element={<AdminPage />} />
          </Routes>
        </main>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
