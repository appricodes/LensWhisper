<?php
declare(strict_types=1);

/**
 * admob_ssv_callback.php
 *
 * Server-Side Verification (SSV) endpoint for AdMob rewarded ads. Google's ad servers call this
 * URL directly (server-to-server, not through the app) once a user has genuinely earned a reward,
 * so — unlike the client-side OnUserEarnedRewardListener callback the app also gets — this can't
 * be spoofed by a modified/rooted client. This is the ONLY place that actually grants ad-watch
 * credits; the app's own reward callback (see RewardedAdManager.kt) never touches the balance.
 *
 * ONE-TIME SETUP REQUIRED (not done by this file):
 *   AdMob console > your rewarded ad unit > "Server-side verification" > set the callback URL to
 *   this file's public URL (e.g. https://www.wikiponder.com/lense_whisper/admob_ssv_callback.php).
 *   AdMob's dashboard has a "Send test callback" button there to exercise this file once deployed
 *   — that's the only realistic way to confirm the signature verification below actually matches
 *   Google's live signing key, since it can't be triggered locally/from an emulator.
 *
 * How the app ties a reward to an account: before showing the ad, the app sets
 * ServerSideVerificationOptions.customData to PreferencesManager.installId — its own randomly
 * generated, permanently-stored per-install UUID (see RewardedAdManager.kt / CreditsDialog.kt).
 * Google echoes that value back unchanged in the `custom_data` query param below, which is how we
 * know whose `install_credits` row to credit.
 *
 * Required table (create once):
 *   CREATE TABLE IF NOT EXISTS processed_ssv_events (
 *     transaction_id VARCHAR(128) PRIMARY KEY,
 *     processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 *   ) ENGINE=InnoDB;
 * (uses the same `install_credits` table get_credits.php creates rows in.)
 */

error_reporting(E_ALL & ~E_NOTICE & ~E_WARNING);
ini_set('display_errors', '0');
ini_set('log_errors', '1');
header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');

require_once __DIR__ . '/config.php';

// Credits granted per verified ad watch. Change this single number to adjust the reward.
const CREDITS_PER_AD = 2;

// Google publishes its current SSV signing keys here; the doc recommends caching rather than
// fetching on every callback. A simple file-based TTL cache is enough for our traffic volume —
// swap for something fancier only if this endpoint ever gets hit hard enough to matter.
// Note the required "www." — bare gstatic.com 301-redirects here, and curl (below) doesn't
// follow redirects, so the bare host silently produced an empty key list on every fetch (every
// key_id looked "unknown" as a result, not just a stale/rotated one) until this was corrected.
const VERIFIER_KEYS_URL = 'https://www.gstatic.com/admob/reward/verifier-keys.json';
const VERIFIER_KEYS_CACHE_FILE = __DIR__ . '/.admob_verifier_keys_cache.json';
const VERIFIER_KEYS_CACHE_TTL_SECONDS = 3600;

function respond_ok(): void {
    // Google doesn't parse the response body — any 2xx tells it delivery succeeded and it won't
    // retry. Non-2xx (or a timeout) makes Google retry the same transaction_id later, which the
    // processed_ssv_events idempotency check below is specifically there to handle safely.
    http_response_code(200);
    echo 'OK';
    exit;
}

function respond_reject(int $httpCode, string $reason): void {
    error_log("admob_ssv_callback.php rejected: $reason");
    http_response_code($httpCode);
    echo 'Rejected';
    exit;
}

function base64url_decode(string $data): string {
    $padded = str_pad(strtr($data, '-_', '+/'), strlen($data) % 4 === 0 ? strlen($data) : strlen($data) + (4 - strlen($data) % 4), '=');
    return base64_decode($padded) ?: '';
}

/** @return array<int,string> keyId => PEM public key */
function fetch_verifier_keys(): array {
    if (is_file(VERIFIER_KEYS_CACHE_FILE) && (time() - filemtime(VERIFIER_KEYS_CACHE_FILE)) < VERIFIER_KEYS_CACHE_TTL_SECONDS) {
        $cached = json_decode((string)file_get_contents(VERIFIER_KEYS_CACHE_FILE), true);
        if (is_array($cached) && !empty($cached)) return $cached;
    }

    $ch = curl_init(VERIFIER_KEYS_URL);
    curl_setopt_array($ch, [CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 10, CURLOPT_FOLLOWLOCATION => true]);
    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $curlError = curl_error($ch);
    curl_close($ch);

    if ($httpCode !== 200 || !$response) {
        // Log why — this used to fail silently (wrong host redirecting, a transient outage, etc.
        // all looked identical to callers: every key_id just came back "unknown"), which made the
        // real cause invisible from the rejection message alone.
        error_log("admob_ssv_callback.php: verifier key fetch failed (http=$httpCode, curl_error='$curlError')");
        // Fall back to a stale cache rather than failing every callback during a transient
        // gstatic outage — better to verify against a slightly-old key set than reject everything.
        if (is_file(VERIFIER_KEYS_CACHE_FILE)) {
            $cached = json_decode((string)file_get_contents(VERIFIER_KEYS_CACHE_FILE), true);
            if (is_array($cached) && !empty($cached)) return $cached;
        }
        return [];
    }

    $parsed = json_decode($response, true);
    $keys = [];
    foreach (($parsed['keys'] ?? []) as $entry) {
        if (isset($entry['keyId'], $entry['pem'])) $keys[(int)$entry['keyId']] = (string)$entry['pem'];
    }
    if (!empty($keys)) @file_put_contents(VERIFIER_KEYS_CACHE_FILE, json_encode($keys));
    return $keys;
}

// 1. Pull the raw query string apart. Signature verification MUST run over the exact raw bytes
//    Google sent — not a re-serialized version — so this reads $_SERVER['QUERY_STRING'] directly
//    rather than trusting re-encoded $_GET values for the data-to-verify step.
$rawQuery = $_SERVER['QUERY_STRING'] ?? '';
parse_str($rawQuery, $params);

$signature = (string)($params['signature'] ?? '');
$keyId = isset($params['key_id']) ? (int)$params['key_id'] : null;
$customData = (string)($params['custom_data'] ?? '');
$transactionId = (string)($params['transaction_id'] ?? '');
$rewardAmount = (int)($params['reward_amount'] ?? 0);

// custom_data is deliberately NOT checked here — AdMob console's "Send test callback" button
// (see the docblock above) omits it, since it's a synthetic ping with no real ad session behind
// it, and that button is the only way to exercise the signature check below outside a live ad
// watch. A real ad watch always includes custom_data (RewardedAdManager.kt sets it before
// showing the ad), so this doesn't weaken anything for production traffic — the format check
// further down (before it's used as an install_id) still rejects it if empty or malformed.
if ($signature === '' || $keyId === null || $transactionId === '') {
    respond_reject(400, 'Missing required query params.');
}

// Per Google's documented SSV format, signature + key_id are always the last two params Google
// appends — the data to verify is everything before "&signature=".
$sigPos = strpos($rawQuery, '&signature=');
if ($sigPos === false) {
    respond_reject(400, 'Could not locate signature boundary in query string.');
}
$dataToVerify = substr($rawQuery, 0, $sigPos);

$keys = fetch_verifier_keys();
$publicKeyPem = $keys[$keyId] ?? null;
if ($publicKeyPem === null) {
    respond_reject(400, "Unknown key_id $keyId (verifier key list may need a forced refresh).");
}

$decodedSignature = base64url_decode($signature);
$verifyResult = openssl_verify($dataToVerify, $decodedSignature, $publicKeyPem, OPENSSL_ALGO_SHA256);
if ($verifyResult !== 1) {
    respond_reject(403, "Signature verification failed for transaction_id=$transactionId (openssl_verify=$verifyResult).");
}

// Signature checked out, but there's no custom_data — this is AdMob console's "Send test
// callback" button (see the docblock above), which pings with no real ad session attached, so
// there's genuinely no install_id to credit. That's expected, not an error: acknowledge with 200
// so the console shows success, and skip straight to respond_ok() without touching the database.
if ($customData === '') {
    error_log("admob_ssv_callback.php: signature OK, no custom_data (test callback) for transaction_id=$transactionId");
    respond_ok();
}

// custom_data is PreferencesManager.installId — the app's own random per-install UUID — which we
// handed AdMob before showing the ad (see RewardedAdManager.kt). Same format check as
// get_credits.php's input validation. Unlike the empty case above, a present-but-malformed value
// is a genuine anomaly (not the known test-button behavior), so it's still rejected.
if (!preg_match('/^[a-fA-F0-9-]{8,64}$/', $customData)) {
    respond_reject(400, "custom_data '$customData' is not a recognizable install_id.");
}
$installId = $customData;

try {
    $pdo = get_pdo_connection();
    $pdo->beginTransaction();

    // Idempotency: Google retries SSV callbacks that don't get a 2xx response, and in principle
    // could redeliver an already-acknowledged one — transaction_id must only ever be credited once.
    try {
        $pdo->prepare('INSERT INTO processed_ssv_events (transaction_id) VALUES (:tid)')
            ->execute([':tid' => $transactionId]);
    } catch (PDOException $e) {
        $pdo->rollBack();
        respond_ok(); // already processed — tell Google delivery succeeded, don't credit again
    }

    $updateStmt = $pdo->prepare('UPDATE install_credits SET credits = credits + :credits, updated_at = CURRENT_TIMESTAMP WHERE install_id = :install_id');
    $updateStmt->execute([':credits' => CREDITS_PER_AD, ':install_id' => $installId]);

    if ($updateStmt->rowCount() === 0) {
        error_log("admob_ssv_callback.php: no install_credits row for install_id $installId (transaction_id=$transactionId)");
    }

    $pdo->commit();
    respond_ok();
} catch (Throwable $e) {
    if (isset($pdo) && $pdo->inTransaction()) $pdo->rollBack();
    error_log('admob_ssv_callback.php error: ' . $e->getMessage());
    respond_reject(500, 'Server error: ' . $e->getMessage());
}
