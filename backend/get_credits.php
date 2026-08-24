<?php
declare(strict_types=1);

/**
 * get_credits.php
 *
 * Thin wrapper around config.php's shared `resolve_identity()` — a plain balance check, no AI
 * request attached (see process.php for that). Merged from what used to be two near-identical
 * files (this one, anon-only, and the old sync_credits.php, account-only) once it was clear they
 * differed only in which table they read; the actual identity/lookup logic itself now lives in
 * `resolve_identity()` (config.php) so process.php and this file can't drift out of sync on it.
 *
 * Anonymous path: the app sends its own randomly-generated, per-install UUID (`install_id`, see
 * PreferencesManager.installId on the Android side, stored permanently on-device) — never anything
 * Google-Play/hardware-derived. That same install_id (not a server-assigned id) is what the app
 * hands to AdMob as `ServerSideVerificationOptions.customData` before showing a rewarded ad — see
 * admob_ssv_callback.php, which matches it back against `install_credits` to know whose balance to
 * credit.
 *
 * Account path: mirrors the old sync_credits.php exactly — was originally polled right after a
 * RevenueCat purchase completed client-side, since the actual grant happens asynchronously via
 * revenuecat_webhook.php. Currently dormant — see LOGIN_AND_PURCHASES_REMOVED.md at the repo root.
 *
 * Called by the app: once at process startup (best-effort, see AdCreditsSession.kt), and from the
 * credits dialog's manual "Update Credits" button / its ~10s-after-a-watched-ad re-check (to pick
 * up admob_ssv_callback.php's async credit) — never automatically just from opening that dialog.
 *
 * Required table (create once) — see config.php's resolve_identity() docblock:
 *   CREATE TABLE IF NOT EXISTS install_credits (
 *     id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 *     install_id VARCHAR(64) NOT NULL UNIQUE,
 *     credits INT NOT NULL DEFAULT 0,
 *     created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *     INDEX idx_install_id (install_id)
 *   ) ENGINE=InnoDB;
 */

error_reporting(E_ALL & ~E_NOTICE & ~E_WARNING);
ini_set('display_errors', '0');
ini_set('log_errors', '1');
header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');

require_once __DIR__ . '/config.php';

function json_response(array $data, int $httpCode = 200): void {
    http_response_code($httpCode);
    echo json_encode($data, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(['error' => true, 'message' => 'Method not allowed. Please use POST.'], 405);
}

$input = json_decode(file_get_contents('php://input'), true);
if (!$input) {
    json_response(['error' => true, 'message' => 'Invalid JSON payload received.'], 400);
}

$email = filter_var($input['email'] ?? '', FILTER_SANITIZE_EMAIL);
$google_id = preg_replace('/[^a-zA-Z0-9\-_]/', '', $input['google_id'] ?? '');
$install_id = (string)($input['install_id'] ?? '');

try {
    $pdo = get_pdo_connection();
    $identity = resolve_identity($pdo, $email, $google_id, $install_id);

    if ($identity === null) {
        json_response(['error' => true, 'message' => 'Missing or invalid credentials (need email+google_id, or a valid install_id).'], 401);
    }

    json_response([
        'error' => false,
        'free_credits' => $identity['free_credits'],
        'purchased_credits' => $identity['purchased_credits'],
    ]);
} catch (Throwable $e) {
    error_log('get_credits.php error: ' . $e->getMessage());
    json_response(['error' => true, 'message' => 'Server error.'], 500);
}
