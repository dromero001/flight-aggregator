import { CARRIER_NAMES } from '../itinerary/carriers';
import { AIRPORT_CITIES } from './airports';

/**
 * The dataset's lists of suggestions, each a name by code (airports by city, airlines by name),
 * which `suggestions.test.ts` keeps in step with their sources. Fields that suggest a list share it.
 */
export const SUGGESTIONS = { airports: AIRPORT_CITIES, airlines: CARRIER_NAMES };

export type SuggestionList = keyof typeof SUGGESTIONS;

/**
 * The code that `list` names exactly by `text`, trimmed and in any case ("madrid", " Air Europa ");
 * any other text as it is, for the search's own validation.
 */
export function codeNamed(list: SuggestionList, text: string): string {
  const name = text.trim().toLowerCase();
  for (const [code, candidate] of SUGGESTIONS[list]) {
    if (candidate.toLowerCase() === name) return code;
  }
  return text;
}
