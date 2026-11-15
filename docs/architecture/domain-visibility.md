# Domain visibility rule

The domain is one flat package, so Java's access modifiers are its only encapsulation. The rule:

- A domain type, constructor, method or constant is `public` only if code **outside** the `com.flightaggregator.domain` package calls it: the application, an adapter or the bootstrap. Everything else is package-private.
- A test in the domain package does not make a member public: tests live in the same package and see package-private members.
- When a later task needs a member from another layer, it widens the visibility in the same change, with the test that needs it.

Applied in plan 4, task 1, to the domain's entry points:

| Member | Visibility | Why |
|---|---|---|
| `LegDeduplicator.deduplicate` | public | The ingestion service calls it (task 2). |
| `ItineraryBuilder.build` | public | The ingestion service calls it (task 2). |
| `ItineraryId.of` | package-private | Only `Itinerary.id()` calls it; the `ItineraryId` record itself stays public because `Itinerary.id()` returns it. |

`ConnectionRules` and `PricingPolicy` were already package-private. Members of the value records that are public today without a caller outside the package are not covered by this task; narrowing them is listed as an extra in the progress report.
