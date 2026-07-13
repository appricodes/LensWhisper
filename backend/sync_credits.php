<?php
declare(strict_types=1);

/**
 * sync_credits.php
 *
 * Returns the caller's current free/purchased credit totals. Polled by the app right after a
 * RevenueCat purchase completes client-side, since the actual grant happens asynchronously via
 * revenuecat_webhook.php — this lets the UI reflect the new total without waiting for the next
 * login or AI request.
 */

error_reporting(E_ALL & ~E_NOTICE & ~E_WARNING);
ini_set('display_errors', '0');
ini_set('log_errors', '1');
header('Content-Type: application/json; charset=utf-8');

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

if ($email === '' || $google_id === '') {
    json_response(['error' => true, 'message' => 'Missing email or google_id.'], 400);
}

try {
    $pdo = get_pdo_connection();
    $stmt = $pdo->prepare('SELECT verified, free_credits, purchased_credits FROM users WHERE email = :email AND google_id = :google_id LIMIT 1');
    $stmt->execute([':email' => $email, ':google_id' => $google_id]);
    $user = $stmt->fetch();

    if (!$user || (int)$user['verified'] <= 0) {
        json_response(['error' => true, 'message' => 'Unauthorized or unverified user.'], 401);
    }

    json_response([
        'error' => false,
        'free_credits' => (int)$user['free_credits'],
        'purchased_credits' => (int)$user['purchased_credits'],
    ]);
} catch (Throwable $e) {
    error_log('sync_credits.php error: ' . $e->getMessage());
    json_response(['error' => true, 'message' => 'Server error.'], 500);
}
