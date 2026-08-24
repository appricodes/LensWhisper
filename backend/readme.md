# Lense Whisper - Secure PHP Backend (Login & AI Processing)

This backend handles Google Sign-In verification and securely proxies AI requests to x.AI for the Android app "LensWhisper".

## Streaming (process.php)

`process.php` supports two response modes on the same endpoint, chosen by the request:

- **Default** (no `streaming` field, or `streaming: false`): one JSON object, exactly as before —
  `{"error": false, "model_response": "...", "cost": N, "free_credits": N, "purchased_credits": N}`.
- **`streaming: true`** in the request body: the upstream provider's own SSE stream (xAI/Gemini are
  both OpenAI-compatible Chat Completions APIs) is relayed live via a `CURLOPT_WRITEFUNCTION`
  callback — content deltas go out to the client the moment they arrive from upstream, not after
  the whole response is buffered. The response body switches to **NDJSON** (one JSON object per
  line, `Content-Type: application/x-ndjson`):
  ```
  {"delta": "The "}
  {"delta": "scene shows "}
  {"delta": "a quiet room."}
  {"done": true, "cost": 2, "free_credits": 8, "purchased_credits": 0}
  ```
  or, if the upstream call fails (possibly after some deltas already went out — the client just
  discards whatever it received in that case):
  ```
  {"error": true, "model_response": "Error communicating with AI server. Please try again."}
  ```
  Explore (`prompt_id: "explore"`) always ignores `streaming` and answers non-streamed regardless —
  its structured grid-JSON response isn't sentence-shaped.

  Credit deduction/`purchase_log` logging happens exactly once, after the stream finishes, via the
  shared `deduct_credits_and_log()` helper both response modes call — the flat per-frame `$cost` a
  request is charged never depended on token usage in the first place, only `cogs` (an internal
  analytics figure) does, and that's computed from whatever `usage` object the final SSE chunk
  happened to carry (`stream_options.include_usage: true` is requested from upstream, but treated
  as best-effort — same tolerance the non-streaming path already had for a provider that omits it).

  **Buffering**: `header('X-Accel-Buffering: no')` is what actually gets nginx to stop buffering the
  response (this is honored per-response, no nginx.conf change needed) — plus `ob_implicit_flush`
  and disabling PHP's own output buffering. If deltas ever seem to arrive in one lump instead of
  incrementally after touching this file, check that header made it out before any content did.

  **Client**: see `XaiVisionEngine`'s `*Streaming` functions (Android) — reads the NDJSON response
  body line-by-line via OkHttp's `BufferedSource`, not a full-body read. Sent only when
  `PreferencesManager.streamingEnabled` is on (Settings, default off).

## Database Schema

```sql
CREATE DATABASE IF NOT EXISTS lense CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE lense;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    google_id VARCHAR(128) NOT NULL,
    verified TINYINT(1) NOT NULL DEFAULT 0,
    blocked TINYINT(1) NOT NULL DEFAULT 0,
    free_credits INT NOT NULL DEFAULT 0,
    purchased_credits INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_email (email),
    INDEX idx_google_id (google_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS purchase_log (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(254) NULL,
    install_id VARCHAR(64) NULL,
    prompt_id VARCHAR(50) NOT NULL,
    cost_free INT NOT NULL DEFAULT 0,
    cost_purchased INT NOT NULL DEFAULT 0,
    model_name VARCHAR(64) NULL,
    input_tokens INT NULL,
    output_tokens INT NULL,
    stats_json TEXT NULL,
    cogs DECIMAL(10,6) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_email (email),
    INDEX idx_install_id (install_id)
) ENGINE=InnoDB;

-- Anonymous (no-login) credit accounts, one row per app install. See get_credits.php.
CREATE TABLE IF NOT EXISTS install_credits (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    install_id VARCHAR(64) NOT NULL UNIQUE,
    credits INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_install_id (install_id)
) ENGINE=InnoDB;

-- AdMob rewarded-ad Server-Side Verification delivery idempotency. See admob_ssv_callback.php.
CREATE TABLE IF NOT EXISTS processed_ssv_events (
    transaction_id VARCHAR(128) PRIMARY KEY,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- RevenueCat purchase-webhook delivery idempotency. See revenuecat_webhook.php. (Purchases are
-- currently disconnected from the app's UI — see LOGIN_AND_PURCHASES_REMOVED.md at the repo root —
-- but this table and revenuecat_webhook.php are left intact for when that's restored.)
CREATE TABLE IF NOT EXISTS processed_webhook_events (
    event_id VARCHAR(64) PRIMARY KEY,
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Per-impression ad revenue as reported by the app's OnPaidEventListener — NOT a verified figure
-- (Google's trustworthy, server-to-server SSV callback carries no monetary value at all), just the
-- best available directional data. See log_ad_revenue.php.
CREATE TABLE IF NOT EXISTS ad_revenue_log (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    install_id VARCHAR(64) NOT NULL,
    ad_unit_id VARCHAR(64) NULL,
    value_micros BIGINT NOT NULL,
    currency_code VARCHAR(8) NOT NULL,
    precision_type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_install_id (install_id)
) ENGINE=InnoDB;

-- Admin dashboard (backend/admin/) key/value store — holds the bcrypt-hashed admin password
-- ('admin_password_hash') and a session-invalidation counter ('session_version') so the password
-- can be changed from the dashboard itself, online, with no file edit needed. See
-- backend/admin/includes/bootstrap.php.
CREATE TABLE IF NOT EXISTS admin_config (
    `key` VARCHAR(64) PRIMARY KEY,
    `value` TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- Rate-limits admin login/recovery attempts per IP (5 failures / 15 min lockout). See
-- backend/admin/includes/bootstrap.php.
CREATE TABLE IF NOT EXISTS admin_login_attempts (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    ip_address VARCHAR(45) NOT NULL,
    success TINYINT(1) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ip_time (ip_address, created_at)
) ENGINE=InnoDB;

-- Every credit/status change and login/logout made from the admin dashboard, for accountability.
-- See backend/admin/includes/bootstrap.php's admin_audit().
CREATE TABLE IF NOT EXISTS admin_audit_log (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NULL,
    target_id VARCHAR(128) NULL,
    details TEXT NULL,
    ip_address VARCHAR(45) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created (created_at)
) ENGINE=InnoDB;
```

> If your *live* `users` table predates the `blocked` column above:
> ```sql
> ALTER TABLE users ADD COLUMN blocked TINYINT(1) NOT NULL DEFAULT 0 AFTER verified;
> ```

> Note: if your *live* `purchase_log` table predates the columns above, migrate it instead of
> relying on `CREATE TABLE IF NOT EXISTS` (which only applies to a table that doesn't exist yet):
> ```sql
> ALTER TABLE purchase_log
>   MODIFY email VARCHAR(254) NULL,
>   ADD COLUMN install_id VARCHAR(64) NULL AFTER email,
>   ADD COLUMN model_name VARCHAR(64) NULL,
>   ADD COLUMN input_tokens INT NULL,
>   ADD COLUMN output_tokens INT NULL,
>   ADD COLUMN stats_json TEXT NULL,
>   ADD COLUMN cogs DECIMAL(10,6) NULL,
>   ADD INDEX idx_install_id (install_id);
> ```

## Security Hardening (2026-08-24)

- **`process.php` had `$debug = true` hardcoded** — live on whatever server this was deployed to.
  That turned on PHP error/PDO-error output straight into HTTP responses and disabled the JSON
  `Content-Type` header. Fixed to `$debug = false`; flip it locally only, never commit/deploy it
  `true`. Also widened its `catch` from `Exception` to `Throwable` so a fatal `Error` (not just an
  `Exception`) can't fall through to PHP's own error output instead of the JSON error response.
- Added `X-Content-Type-Options: nosniff` / `X-Frame-Options: DENY` to every endpoint that was
  missing them (`process.php`, `get_credits.php`, `log_ad_revenue.php`, `revenuecat_webhook.php`,
  `admob_ssv_callback.php`) — `login_verification.php` already had these.
- Added `backend/.htaccess` denying direct HTTP access to `config.php`, `config.production.php`,
  and `modir.php` (Apache only — see the file's own comment for the nginx equivalent).
- See "Admin Dashboard" below for the `users.blocked` fix (an admin "block" used to be silently
  undone by the next successful login) and the new rate-limited/audited admin login.

## Admin Dashboard

`backend/admin/` is a multi-page replacement for the old single-file `modir.php` (which had a
hardcoded plaintext password and only covered `users`/`purchase_log`). See
`backend/admin/README.md` for setup, the full feature list, and how login/password recovery work.
Deploy the whole `admin/` folder alongside the rest of `backend/` — it reads the same `config.php`.