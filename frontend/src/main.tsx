import '@fontsource/barlow/400.css';
import '@fontsource/barlow/600.css';
import '@fontsource/barlow-condensed/600.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './styles/tokens.css';
import './styles/global.css';

const root = document.getElementById('root');
if (!root) {
  throw new Error('index.html has no #root element');
}

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
