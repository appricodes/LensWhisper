# LensWhisper Admin Dashboard

Replaces `modir.php`. Same deploy target (upload `backend/admin/` next to the rest of `backend/`),
but hardened and covers everything the backend tracks, not just `users`/`purchase_log`.

## One-time setup

1. Run the new tables from `backend/readme.md`'s schema against your live DB: `admin_config`,
   `admin_login_attempts`, `admin_audit_log`, plus the `users.blocked` column if your `users`
   table predates it. (`install_credits`, `purchase_log`, `ad_revenue_log`, `processed_ssv_events`
   already exist if you're running the ad-credit system — nothing new needed for those.)
2. Deploy `backend/admin/` (this folder) to the server, alongside the rest of `backend/`.
3. Visit `https://your-domain/.../admin/login.php` and log in with the bootstrap password —
   `ADMIN_BOOTSTRAP_PASSWORD` in `backend/config.php` (production value is in
   `backend/config.production.php`; whichever one is actually deployed as `config.php` on the
   server is the one that matters).
4. **Immediately go to Settings and set a real password.** The bootstrap password becomes inert
   after its first successful use (its hash gets written to `admin_config` and checked from then
   on), but don't rely on that alone — change it right away.
5. If you ever get locked out: `admin/reset.php?key=...` using `ADMIN_RECOVERY_KEY` from
   `config.php` sets a brand-new password with no email/SMTP step involved, and signs out every
   other admin session in the process.

Both secrets only exist in `config.php`, which is gitignored — never committed, unlike the old
`modir.php` which had its password inline in the tracked-ish file.

## What's in it

| Page | What it does |
|---|---|
| `index.php` | Dashboard: account/install counts, credit totals, request volume, 30-day COGS, recent signups, recent admin activity. |
| `users.php` | Search/manage logged-in accounts (`users` table) — edit free/purchased credits, verified status, **block** (new — actually rejects login, unlike the old "Verified=0" toggle which `login_verification.php` silently overwrote back to 1 on the user's next sign-in), view that account's full usage log. |
| `installs.php` | Search/manage anonymous ad-credit installs (`install_credits`) — **new**, `modir.php` couldn't touch these at all. Edit credits, view that install's usage log and ad revenue log. |
| `logs.php` | Global `purchase_log` browser — filter by email/install_id/model/date range, paginated (old page was a hardcoded top-500 with no filters), CSV export. |
| `ad_revenue.php` | `ad_revenue_log` aggregates (by currency, by day) plus a reconciliation note against verified `processed_ssv_events` grants — **new**, no visibility into ad revenue existed before. |
| `settings.php` | Change password (online, no file edit), config health check (are the API keys/secrets actually set, without displaying their values), paginated admin activity log. |

## Security notes vs. `modir.php`

- Password is bcrypt-hashed in the DB, not a plaintext `===` compare hardcoded in the file.
- Login and recovery are both rate-limited: 5 failed attempts from one IP locks it out for 15
  minutes.
- CSRF tokens on every form.
- Changing the password (Settings or via recovery) instantly invalidates every other logged-in
  session.
- Every credit/status edit, login, logout, and password change is written to `admin_audit_log`
  with the acting IP.
- Session cookie is `HttpOnly`, `SameSite=Strict`, and `Secure` when served over HTTPS.
- `includes/` (shared code, not a page) is blocked from direct access via `.htaccess`.
- All queries are parameterized (`PDO` prepared statements) — no string-built SQL, same as the
  rest of `backend/`.

## Not carried over on purpose

`modir.php` is left in place (still gitignored, untouched) in case you're mid-deploy — delete it
from the server once you've confirmed `admin/` works and switched over.
