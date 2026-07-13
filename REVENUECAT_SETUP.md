# RevenueCat Setup

The app and backend are already wired for RevenueCat-managed credit purchases. Everything below
is the one-time dashboard/console configuration needed before purchases actually work — none of
it can be done from code.

## What's already implemented

- **Client**: `app/src/main/java/com/appricodes/lens_whisper/LensWhisperApplication.kt` configures
  the RevenueCat SDK on process start. `LoginActivity.kt` calls `Purchases.sharedInstance.logIn(email)`
  after a successful login, so RevenueCat's `app_user_id` is your account's email.
- **Purchase UI**: `ui/components/ModelCreditsHeader.kt` fetches live offerings from RevenueCat
  and lets the user buy a package; it does **not** grant credits itself.
- **Server**: `backend/revenuecat_webhook.php` is the source of truth for granting credits — it's
  called by RevenueCat, not by the app, so a purchase can't be spoofed by a modified client.
  `backend/sync_credits.php` lets the app poll for the updated total right after a purchase
  (the webhook can lag the client's purchase callback by a few seconds).

## 1. Create a RevenueCat project

1. Sign up at [app.revenuecat.com](https://app.revenuecat.com) and create a new **Project**.
2. Add an **App** to the project, platform **Google Play Store**, package name
   `com.appricodes.lens_whisper`.
3. RevenueCat needs a Google Play **service account** JSON key to verify purchases server-side —
   follow RevenueCat's "Generate a service account and JSON key" instructions under the app's
   Play Store integration settings. This is separate from the app's own Google Sign-In client ID.

## 2. Create the products in Google Play Console

In Play Console → your app → Monetize → Products → **In-app products** (not Subscriptions —
credit packs are one-time consumables), create 4 products with these exact Product IDs (the
backend's `PRODUCT_CREDITS_MAP` in `backend/revenuecat_webhook.php` expects them; change both
sides together if you use different IDs):

| Product ID     | Suggested price | Credits |
|----------------|------------------|---------|
| `credits_50`   | $1.99            | 50      |
| `credits_150`  | $4.99            | 150     |
| `credits_400`  | $9.99            | 400     |
| `credits_1000` | $19.99           | 1000    |

These need a signed release build uploaded to at least Internal Testing before Play Console will
let you activate in-app products.

## 3. Import products into RevenueCat and build an Offering

1. In RevenueCat, go to **Products** and import the 4 products above (RevenueCat reads them
   from Play Console once they're active).
2. Go to **Offerings**, create an offering (e.g. `default`), and add all 4 products as
   **Packages** inside it.
3. Mark that offering as the **Current** offering — the app fetches
   `offerings.current?.availablePackages`, so purchases won't show up until you do this.

## 4. Get the public SDK key into the app

1. RevenueCat dashboard → **Project Settings → API keys → App specific keys** → your Android app
   → copy the **Public app-specific key**.
2. Paste it into `REVENUECAT_PUBLIC_SDK_KEY` in
   `app/src/main/java/com/appricodes/lens_whisper/LensWhisperApplication.kt`. This key is safe to
   ship in the app — it's not the same as the secret key used below.

## 5. Configure the webhook (this is what actually grants credits)

1. Create the idempotency table once, via your MySQL client:
   ```sql
   CREATE TABLE processed_webhook_events (
     event_id VARCHAR(64) PRIMARY KEY,
     processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
   );
   ```
2. Generate a long random secret (e.g. `openssl rand -hex 32`) and set it as
   `REVENUECAT_WEBHOOK_SECRET` in `backend/config.php`.
3. In RevenueCat: **Project Settings → Integrations → Webhooks**, add:
   - URL: `https://www.wikiponder.com/lense_whisper/revenuecat_webhook.php`
   - Authorization header value: the same secret from step 2
4. Deploy `backend/revenuecat_webhook.php`, `backend/sync_credits.php`, and the updated
   `backend/config.php` to the server.

## 6. Test it

RevenueCat's Play Store integration supports **sandbox/test purchases** without real charges —
use a Play Console license tester account on the test device. Buy a package in the app, then
check `processed_webhook_events` and the `users` table's `purchased_credits` column to confirm
the webhook fired and credited the right row.

## Notes

- Credit packs are configured as one-time **non-renewing** products (not subscriptions) — the
  webhook only acts on `NON_RENEWING_PURCHASE` events and ignores subscription lifecycle events.
- RevenueCat auto-consumes non-subscription Play Billing purchases, so the same pack can be
  bought repeatedly without extra client-side "consume" calls.
- If you ever rename or add packages, update both the RevenueCat Offering and
  `PRODUCT_CREDITS_MAP` in `backend/revenuecat_webhook.php` — they're two independent places that
  need to agree on product ID → credit amount.
