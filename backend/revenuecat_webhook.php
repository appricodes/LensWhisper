<?php
declare(strict_types=1);

/**
 * revenuecat_webhook.php
 *
 * Receives purchase events from RevenueCat and credits the matching user's account. This is the
 * server-authoritative side of a purchase — the app's client-side purchase callback never grants
 * credits directly, since a client can't be trusted to report its own purchases honestly.
 *
 * Configure this URL + REVENUECAT_WEBHOOK_SECRET in the RevenueCat dashboard under
 * Project Settings > Integrations > Webhooks. See REVENUECAT_SETUP.md for full setup steps.
 *
 * Requires a `processed_webhook_events` table for delivery-retry idempotency:
 *   CREATE TABLE processed_webhook_events (
 *     event_id VARCHAR(64) PRIMARY KEY,
 *     processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 *   );
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

// Maps RevenueCat product identifiers (configured in Play Console + mirrored in the RevenueCat
// dashboard) to the number of credits each grants. RevenueCat/Play Console remain the source of
// truth for price and display name — this map only translates a completed purchase into a
// credit amount, so keep the keys in sync with whatever product identifiers you create there.
const PRODUCT_CREDITS_MAP = [
    'credits_50'   => 50,
    'credits_150'  => 150,
    'credits_400'  => 400,
    'credits_1000' => 1000,
];

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(['error' => true, 'message' => 'Method not allowed.'], 405);
}

// Shared-secret check: set the same value as the "Authorization header value" field in the
// RevenueCat dashboard's webhook configuration (Project Settings > Integrations > Webhooks).
$authHeader = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
if (!defined('REVENUECAT_WEBHOOK_SECRET') || REVENUECAT_WEBHOOK_SECRET === '' || !secure_compare($authHeader, REVENUECAT_WEBHOOK_SECRET)) {
    json_response(['error' => true, 'message' => 'Unauthorized.'], 401);
}

$input = json_decode(file_get_contents('php://input'), true);
if (!$input || !isset($input['event'])) {
    json_response(['error' => true, 'message' => 'Invalid payload.'], 400);
}

$event = $input['event'];
$eventId = (string)($event['id'] ?? '');
$eventType = (string)($event['type'] ?? '');
// app_user_id is our own email, set via Purchases.sharedInstance.logIn(email) at login time
// (see LensWhisperApplication.kt / LoginActivity.kt) — this is what ties a RevenueCat purchase
// back to a row in the `users` table.
$appUserId = filter_var($event['app_user_id'] ?? '', FILTER_SANITIZE_EMAIL);
$productId = (string)($event['product_id'] ?? '');

if ($eventId === '' || $appUserId === '') {
    json_response(['error' => true, 'message' => 'Missing event id or app_user_id.'], 400);
}

// Only one-time credit-pack purchases grant credits; other lifecycle events (renewals,
// cancellations, billing issues, etc.) don't apply to consumable credit packs and are ignored.
if ($eventType !== 'NON_RENEWING_PURCHASE') {
    json_response(['error' => false, 'message' => 'Event type ignored.']);
}

$credits = PRODUCT_CREDITS_MAP[$productId] ?? null;
if ($credits === null) {
    error_log("revenuecat_webhook.php: unknown product_id '$productId'");
    json_response(['error' => true, 'message' => 'Unknown product.'], 400);
}

try {
    $pdo = get_pdo_connection();
    $pdo->beginTransaction();

    // Idempotency: RevenueCat retries webhook deliveries that don't get a 2xx response, so the
    // same event id must only ever be credited once. event_id is the table's primary key — a
    // duplicate insert throws, which we treat as "already processed" rather than an error.
    try {
        $pdo->prepare('INSERT INTO processed_webhook_events (event_id) VALUES (:event_id)')
            ->execute([':event_id' => $eventId]);
    } catch (PDOException $e) {
        $pdo->rollBack();
        json_response(['error' => false, 'message' => 'Already processed.']);
    }

    $updateStmt = $pdo->prepare('UPDATE users SET purchased_credits = purchased_credits + :credits, updated_at = CURRENT_TIMESTAMP WHERE email = :email');
    $updateStmt->execute([':credits' => $credits, ':email' => $appUserId]);

    if ($updateStmt->rowCount() === 0) {
        error_log("revenuecat_webhook.php: no user found for app_user_id '$appUserId'");
    }

    $pdo->commit();
    json_response(['error' => false, 'message' => 'Credited.']);
} catch (Throwable $e) {
    if (isset($pdo) && $pdo->inTransaction()) $pdo->rollBack();
    error_log('revenuecat_webhook.php error: ' . $e->getMessage());
    json_response(['error' => true, 'message' => 'Server error.'], 500);
}
