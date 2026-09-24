# Nearby restaurant and menu integration

The Android client is prepared, but no live restaurant provider is connected. Sample dishes remain explicitly labelled. They are not claimed to be restaurants near the saved address.

## Recommended approach

Use Google Places API (New) for restaurant and fast-food discovery by general delivery area. Its nearby search supports restaurant categories and place details, but it is not a complete priced menu-item catalogue. Do not attach arbitrary place photos to menu items.

- Discovery: https://developers.google.com/maps/documentation/places/web-service/nearby-search
- Menu data: use restaurant-authorized menu feeds or a licensed menu supplier with confirmed Edmonton/Canadian coverage and item-level image rights.
- Yelp Insights is another vendor to discuss for food/drink data, but access depends on a partnership agreement and should not be assumed to include full current priced menus: https://docs.developer.yelp.com/docs/yelp-insights

Before buying a menu service, ask for a sample covering actual Edmonton restaurants, prices, item image URLs, attribution, permitted caching/display, and refresh cadence. No billing plan or vendor subscription was enabled in this change.

## Client integration

Set `oliveCatalogEndpoint=https://YOUR-SERVER/catalog` in local Gradle properties after implementing the authenticated HTTPS proxy. Do not put vendor API keys in the Android APK.

The Android client sends a Firebase bearer ID token plus `city`, `region`, and `country` query fields. It does not send the street address, DOB, situation, trigger, PIN or safe contacts. The proxy must verify the ID token, apply rate limits, validate input and avoid logging bearer tokens or personal data. General city/region search is intentionally coarse; finer matching requires an explicit geocoding design.

Return at most 100 menu items as `{ "items": [...] }` with fields:

```json
{
  "id": "provider-restaurant-item-id",
  "name": "Actual menu item",
  "restaurant": "Actual restaurant",
  "category": "Burgers",
  "description": "Provider-approved item description",
  "priceCents": 1299,
  "currency": "CAD",
  "minutes": "20–30",
  "rating": "4.5",
  "imageUrl": "https://licensed-image-host/item.jpg",
  "imageCredit": "Required provider or photographer credit",
  "sourceUrl": "https://restaurant.example/menu",
  "itemImageVerified": true
}
```

The proxy must verify that the image depicts the particular item. Missing/incorrect images must not be replaced with an unrelated restaurant photo. The client rejects responses without the verification flag or HTTPS image/source links, limits response/image sizes, and loads images into memory. CAD pricing is currently supported; other currencies require a follow-up. Use empty menus or an error when data is unavailable, rather than inventing restaurants, ratings, delivery times or prices.

This endpoint is read-only catalogue browsing. There is no order/payment endpoint. Checkout always declines locally.
