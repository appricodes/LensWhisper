<?php
declare(strict_types=1);

/**
 * log_ad_revenue.php
 *
 * Records the per-impression ad revenue AdMob's SDK reports to the app itself
 * (com.google.android.gms.ads.OnPaidEventListener — see RewardedAdManager.kt, which posts here
 * right after it fires). This is the ONLY source of real revenue-per-ad-watch data available at
 * all: admob_ssv_callback.php's Server-Side Verification callback (the trustworthy,
 * server-to-server one that actually grants credits) does NOT carry a monetary value — its
 * `reward_amount` param is the reward *configuration* (e.g. "2 credits"), not money. Google also
 * doesn't expose the SSV callback's `transaction_id` back to the client, so there is no reliable
 * key to join a row here 1:1 with a specific `processed_ssv_events` row — this table is a
 * best-effort revenue log (by install_id + timestamp proximity), not a verified transaction ledger.
 * Treat it as directional/analytics data, never as something to gate crediting on.
 *
 * Required table (create once):
 *   CREATE TABLE IF NOT EXISTS ad_revenue_log (
 *     id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 *     install_id VARCHAR(64) NOT NULL,
 *     ad_unit_id VARCHAR(64) NULL,
 *     value_micros BIGINT NOT NULL,
 *     currency_code VARCHAR(8) NOT NULL,
 *     precision_type VARCHAR(20) NOT NULL,
 *     created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
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

$install_id = (string)($input['install_id'] ?? '');
if (!preg_match('/^[a-fA-F0-9-]{8,64}$/', $install_id)) {
    json_response(['error' => true, 'message' => 'Missing or invalid install_id.'], 400);
}

$value_micros = filter_var($input['value_micros'] ?? null, FILTER_VALIDATE_INT);
if ($value_micros === false || $value_micros === null || $value_micros < 0) {
    json_response(['error' => true, 'message' => 'Missing or invalid value_micros.'], 400);
}

$currency_code = strtoupper(preg_replace('/[^a-zA-Z]/', '', $input['currency_code'] ?? ''));
if (strlen($currency_code) !== 3) {
    json_response(['error' => true, 'message' => 'Missing or invalid currency_code.'], 400);
}

$precision_type = preg_replace('/[^A-Z_]/', '', strtoupper((string)($input['precision_type'] ?? 'UNKNOWN')));
$ad_unit_id = substr(preg_replace('/[^a-zA-Z0-9\/\-_.]/', '', (string)($input['ad_unit_id'] ?? '')), 0, 64);
$ad_unit_id = $ad_unit_id === '' ? null : $ad_unit_id;

try {
    $pdo = get_pdo_connection();
    $stmt = $pdo->prepare('INSERT INTO ad_revenue_log (install_id, ad_unit_id, value_micros, currency_code, precision_type) VALUES (:install_id, :ad_unit_id, :value_micros, :currency_code, :precision_type)');
    $stmt->execute([
        ':install_id' => $install_id,
        ':ad_unit_id' => $ad_unit_id,
        ':value_micros' => $value_micros,
        ':currency_code' => $currency_code,
        ':precision_type' => $precision_type,
    ]);
    json_response(['error' => false]);
} catch (Throwable $e) {
    error_log('log_ad_revenue.php error: ' . $e->getMessage());
    json_response(['error' => true, 'message' => 'Server error.'], 500);
}
