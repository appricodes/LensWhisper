<?php
/**
 * Password recovery with no email/SMTP dependency: whoever can supply ADMIN_RECOVERY_KEY (a long
 * random secret living only in backend/config.php, never in git) can set a brand-new admin
 * password here. Rate-limited the same as login.php. Setting a new password bumps
 * `session_version`, which instantly logs out every other admin session (including whichever one
 * you may have gotten locked out of).
 */
require_once __DIR__ . '/includes/bootstrap.php';
$pdo = admin_pdo();

$key = (string)($_GET['key'] ?? $_POST['key'] ?? '');
$keyOk = $key !== '' && hash_equals(ADMIN_RECOVERY_KEY, $key);
$locked = admin_is_rate_limited($pdo);
$error = '';
$success = false;

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    if (!admin_verify_csrf()) {
        $error = 'Session expired, please reload the page.';
    } elseif ($locked) {
        $error = 'Too many failed attempts. Try again in 15 minutes.';
    } elseif (!$keyOk) {
        admin_record_attempt($pdo, false);
        $error = 'Invalid or missing recovery key.';
    } else {
        $newPassword = (string)($_POST['new_password'] ?? '');
        $confirm = (string)($_POST['confirm_password'] ?? '');
        if (strlen($newPassword) < 10) {
            $error = 'New password must be at least 10 characters.';
        } elseif ($newPassword !== $confirm) {
            $error = 'Passwords do not match.';
        } else {
            admin_config_set($pdo, 'admin_password_hash', password_hash($newPassword, PASSWORD_DEFAULT));
            admin_bump_session_version($pdo);
            admin_record_attempt($pdo, true);
            admin_audit($pdo, 'password_reset_via_recovery_key');
            session_unset();
            session_destroy();
            $success = true;
        }
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Recover Admin Password - LensWhisper</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
</head>
<body class="bg-light d-flex align-items-center justify-content-center" style="height: 100vh;">
    <div class="card p-4 shadow-sm" style="width: 400px;">
        <h4 class="text-center mb-1">Password Recovery</h4>
        <p class="text-center text-muted small mb-4">Requires the recovery key from <code>config.php</code></p>

        <?php if ($success): ?>
            <div class="alert alert-success py-2">Password updated. All existing admin sessions were signed out.</div>
            <a href="login.php" class="btn btn-primary w-100">Go to login</a>
        <?php else: ?>
            <?php if ($error): ?><div class="alert alert-danger py-2"><?= htmlspecialchars($error) ?></div><?php endif; ?>
            <form method="POST">
                <?= admin_csrf_field() ?>
                <div class="mb-3">
                    <label class="form-label">Recovery Key</label>
                    <input type="text" name="key" class="form-control" value="<?= htmlspecialchars($key) ?>" required <?= $locked ? 'disabled' : '' ?>>
                </div>
                <div class="mb-3">
                    <label class="form-label">New Password</label>
                    <input type="password" name="new_password" class="form-control" required minlength="10" <?= $locked ? 'disabled' : '' ?>>
                </div>
                <div class="mb-4">
                    <label class="form-label">Confirm New Password</label>
                    <input type="password" name="confirm_password" class="form-control" required minlength="10" <?= $locked ? 'disabled' : '' ?>>
                </div>
                <button type="submit" class="btn btn-primary w-100" <?= $locked ? 'disabled' : '' ?>>Set New Password</button>
            </form>
        <?php endif; ?>
        <p class="text-center text-muted small mt-3 mb-0"><a href="login.php" class="text-decoration-none">Back to login</a></p>
    </div>
</body>
</html>
