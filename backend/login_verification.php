<?php
declare(strict_types=1);
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

// ... [sanitize_string, validate_email, validate_google_id, verify_google_id_token remain identical] ...
function sanitize_string(string $input, int $maxLen): string {
    $clean = preg_replace('/[\x00-\x1F\x7F]/u', '', trim($input));
    if (strlen($clean) > $maxLen) {
        $clean = substr($clean, 0, $maxLen);
    }
    return $clean;
}

function validate_email(string $email): ?string {
    $email = filter_var($email, FILTER_SANITIZE_EMAIL);
    if (filter_var($email, FILTER_VALIDATE_EMAIL) && strlen($email) <= 254 && strlen($email) >= 3) {
        return $email;
    }
    return null;
}

function validate_google_id(string $id): ?string {
    $id = sanitize_string($id, 128);
    if (preg_match('/^[a-zA-Z0-9\-_]{10,128}$/', $id)) {
        return $id;
    }
    return null;
}

function verify_google_id_token(string $idToken): ?array {
    if (strlen($idToken) < MIN_TOKEN_LENGTH || strlen($idToken) > MAX_TOKEN_LENGTH) {
        return null;
    }
    $url = 'https://oauth2.googleapis.com/tokeninfo?id_token=' . urlencode($idToken);
    $context = stream_context_create([
        'http' => [
            'method'  => 'GET',
            'timeout' => 8,
            'header'  => "User-Agent: LenseWhisper/1.0\r\nAccept: application/json\r\n"
        ],
        'ssl' => [
            'verify_peer'      => true,
            'verify_peer_name' => true,
        ]
    ]);

    $response = @file_get_contents($url, false, $context);
    if ($response === false) { return null; }

    $payload = json_decode($response, true);
    if (!is_array($payload)) { return null; }

    $required = ['aud', 'iss', 'sub', 'email', 'email_verified', 'exp'];
    foreach ($required as $key) {
        if (!isset($payload[$key])) { return null; }
    }

    if (!secure_compare($payload['aud'], GOOGLE_CLIENT_ID)) { return null; }

    $validIssuers = ['accounts.google.com', 'https://accounts.google.com'];
    if (!in_array($payload['iss'], $validIssuers, true)) { return null; }

    $emailVerified = $payload['email_verified'];
    if ($emailVerified !== true && $emailVerified !== 'true' && $emailVerified !== 1 && $emailVerified !== '1') {
        return null;
    }

    if ((int)$payload['exp'] < time()) { return null; }

    $email = validate_email((string)$payload['email']);
    $googleId = validate_google_id((string)$payload['sub']);

    if ($email === null || $googleId === null) { return null; }

    return ['email' => $email, 'google_id' => $googleId];
}


if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(['success' => false, 'verified' => 0, 'message' => 'Method not allowed. Please use POST.'], 405);
}

$idToken = sanitize_string($_POST['id_token'] ?? '', MAX_TOKEN_LENGTH);
$verifiedData = verify_google_id_token($idToken);

if ($verifiedData === null) {
    json_response(['success' => false, 'verified' => 0, 'message' => 'Google security token verification failed or expired.'], 401);
}

$email     = $verifiedData['email'];
$googleId  = $verifiedData['google_id'];

try {
    $pdo = get_pdo_connection();
    $stmt = $pdo->prepare('SELECT email, verified, blocked, free_credits, purchased_credits FROM users WHERE email = :email LIMIT 1');
    $stmt->execute([':email' => $email]);
    $user = $stmt->fetch();

    if ($user && (int)($user['blocked'] ?? 0) > 0) {
        // Admin-blocked account (see backend/admin/users.php) — reject outright, don't silently
        // re-verify it the way the branch below would.
        json_response(['success' => false, 'verified' => 0, 'message' => 'This account has been blocked.'], 403);
    }

    $freeCredits = 0;
    $purchasedCredits = 0;

    if ($user) {
        if ((int)$user['verified'] > 0) {
            $freeCredits = (int)$user['free_credits'];
            $purchasedCredits = (int)$user['purchased_credits'];
        } else {
            $update = $pdo->prepare('UPDATE users SET verified = 1, google_id = :google_id, updated_at = CURRENT_TIMESTAMP WHERE email = :email');
            $update->execute([':google_id' => $googleId, ':email' => $email]);
            $stmt2 = $pdo->prepare('SELECT free_credits, purchased_credits FROM users WHERE email = :email');
            $stmt2->execute([':email' => $email]);
            $row = $stmt2->fetch();
            $freeCredits = (int)($row['free_credits'] ?? 0);
            $purchasedCredits = (int)($row['purchased_credits'] ?? 0);
        }
    } else {
        $insert = $pdo->prepare('INSERT INTO users (email, google_id, verified, free_credits, purchased_credits, created_at, updated_at) VALUES (:email, :google_id, 1, 100, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)');
        $insert->execute([':email' => $email, ':google_id' => $googleId]);
        $freeCredits = 100;
        $purchasedCredits = 0;
    }

    json_response([
        'success'            => true,
        'verified'           => 1,
        'email'              => $email,
        'google_id'          => $googleId,
        'free_credits'       => $freeCredits,
        'purchased_credits'  => $purchasedCredits,
        'message'            => 'Login verified successfully'
    ]);

} catch (Throwable $e) {
    error_log('Login verification DB error: ' . $e->getMessage());
    json_response(['success' => false, 'verified' => 0, 'message' => 'Internal server database error.'], 500);
}
?>