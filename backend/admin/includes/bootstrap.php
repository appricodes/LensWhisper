<?php
/**
 * backend/admin/includes/bootstrap.php
 *
 * Shared session/auth/security plumbing for every page under backend/admin/. No secrets live in
 * this file (or any other file under admin/) — the two that matter, ADMIN_BOOTSTRAP_PASSWORD and
 * ADMIN_RECOVERY_KEY, are constants in backend/config.php, which is gitignored. That's deliberate:
 * unlike the old modir.php (password hardcoded inline, excluded from git entirely), everything
 * under admin/ is safe to commit.
 *
 * Auth model: ONE shared admin password (this is a single-operator tool, not multi-user).
 *   - The real password lives as a bcrypt hash in the `admin_config` table (key='admin_password_hash'),
 *     so it can be changed from settings.php without touching any file.
 *   - Until that row exists, login.php accepts ADMIN_BOOTSTRAP_PASSWORD from config.php once, and
 *     immediately persists its hash to `admin_config` — so the bootstrap password is single-use in
 *     practice even if it's left in config.php.
 *   - `admin_config` also holds 'session_version': a counter bumped on every password change/reset.
 *     Each session stores the version it was issued under; require_admin() checks it still matches,
 *     so changing the password instantly logs out every other session.
 *   - Locked out entirely? backend/admin/reset.php?key=ADMIN_RECOVERY_KEY sets a brand new password
 *     without needing the old one — no email/SMTP involved.
 */

declare(strict_types=1);
error_reporting(E_ALL & ~E_NOTICE & ~E_WARNING);
ini_set('display_errors', '0');

require_once __DIR__ . '/../../config.php';

// --- Session hardening: must happen before session_start() ---
if (session_status() === PHP_SESSION_NONE) {
    session_set_cookie_params([
        'lifetime' => 0,
        'path'     => '/',
        'secure'   => !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off',
        'httponly' => true,
        'samesite' => 'Strict',
    ]);
    session_name('lw_admin_sess');
    session_start();
}

header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Referrer-Policy: no-referrer');

// === admin_config key/value helpers ===

function admin_config_get(PDO $pdo, string $key): ?string {
    $stmt = $pdo->prepare('SELECT `value` FROM admin_config WHERE `key` = :k LIMIT 1');
    $stmt->execute([':k' => $key]);
    $row = $stmt->fetch();
    return $row ? (string)$row['value'] : null;
}

function admin_config_set(PDO $pdo, string $key, string $value): void {
    $stmt = $pdo->prepare(
        'INSERT INTO admin_config (`key`, `value`) VALUES (:k, :v)
         ON DUPLICATE KEY UPDATE `value` = :v2'
    );
    $stmt->execute([':k' => $key, ':v' => $value, ':v2' => $value]);
}

function admin_session_version(PDO $pdo): int {
    return (int)(admin_config_get($pdo, 'session_version') ?? '0');
}

function admin_bump_session_version(PDO $pdo): void {
    admin_config_set($pdo, 'session_version', (string)(admin_session_version($pdo) + 1));
}

// === Rate limiting (shared bucket per IP across login + reset — both are password-guessing surfaces) ===

function admin_client_ip(): string {
    return $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';
}

function admin_is_rate_limited(PDO $pdo): bool {
    $stmt = $pdo->prepare(
        "SELECT COUNT(*) AS c FROM admin_login_attempts
         WHERE ip_address = :ip AND success = 0 AND created_at > (NOW() - INTERVAL 15 MINUTE)"
    );
    $stmt->execute([':ip' => admin_client_ip()]);
    return (int)$stmt->fetch()['c'] >= 5;
}

function admin_record_attempt(PDO $pdo, bool $success): void {
    $stmt = $pdo->prepare('INSERT INTO admin_login_attempts (ip_address, success) VALUES (:ip, :s)');
    $stmt->execute([':ip' => admin_client_ip(), ':s' => $success ? 1 : 0]);
    // On success, old failed attempts from this IP no longer matter — trim so the table doesn't grow forever.
    if ($success) {
        $pdo->prepare('DELETE FROM admin_login_attempts WHERE ip_address = :ip AND created_at < (NOW() - INTERVAL 1 DAY)')
            ->execute([':ip' => admin_client_ip()]);
    }
}

// === CSRF ===

function admin_csrf_token(): string {
    if (empty($_SESSION['csrf_token'])) {
        $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
    }
    return $_SESSION['csrf_token'];
}

function admin_csrf_field(): string {
    return '<input type="hidden" name="csrf_token" value="' . htmlspecialchars(admin_csrf_token()) . '">';
}

function admin_verify_csrf(): bool {
    $token = $_POST['csrf_token'] ?? '';
    return is_string($token) && $token !== '' && !empty($_SESSION['csrf_token'])
        && hash_equals($_SESSION['csrf_token'], $token);
}

// === Audit log ===

function admin_audit(PDO $pdo, string $action, ?string $targetType = null, ?string $targetId = null, ?array $details = null): void {
    $stmt = $pdo->prepare(
        'INSERT INTO admin_audit_log (action, target_type, target_id, details, ip_address) VALUES (:a, :tt, :ti, :d, :ip)'
    );
    $stmt->execute([
        ':a'  => $action,
        ':tt' => $targetType,
        ':ti' => $targetId,
        ':d'  => $details !== null ? json_encode($details, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE) : null,
        ':ip' => admin_client_ip(),
    ]);
}

// === Auth guard ===

/** Call at the top of every protected admin page. Redirects to login.php if not authenticated,
 *  or if the password was changed/reset elsewhere since this session was issued. */
function admin_require_login(PDO $pdo): void {
    if (empty($_SESSION['admin_logged_in'])) {
        header('Location: login.php');
        exit;
    }
    if ((int)($_SESSION['admin_session_version'] ?? -1) !== admin_session_version($pdo)) {
        session_unset();
        session_destroy();
        header('Location: login.php?expired=1');
        exit;
    }
}

function admin_pdo(): PDO {
    try {
        return get_pdo_connection();
    } catch (Throwable $e) {
        http_response_code(500);
        die('Database connection failed.');
    }
}
