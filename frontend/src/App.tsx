import { QueryClientProvider } from '@tanstack/react-query';
import { createQueryClient } from './queryClient';

const queryClient = createQueryClient();

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <main>
        <h1>Find flights</h1>
      </main>
    </QueryClientProvider>
  );
}
