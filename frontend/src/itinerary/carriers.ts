/**
 * The airline names of the dataset's twelve carriers, by IATA designator, as `mock-gds`'s
 * `StandardCatalogue` names them (plan 6 E2: in the bundle, no API change). A carrier added to
 * the dataset must be added here too, or its flights show the code alone; `suggestions.test.ts`
 * checks it. The search's "Airline" field suggests this list.
 */
export const CARRIER_NAMES: ReadonlyMap<string, string> = new Map([
  ['IB', 'Iberia'],
  ['UX', 'Air Europa'],
  ['BA', 'British Airways'],
  ['AF', 'Air France'],
  ['LH', 'Lufthansa'],
  ['KL', 'KLM'],
  ['TP', 'TAP Air Portugal'],
  ['AZ', 'ITA Airways'],
  ['LX', 'Swiss'],
  ['FR', 'Ryanair'],
  ['U2', 'easyJet'],
  ['VY', 'Vueling'],
]);
