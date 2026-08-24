<?php
require_once __DIR__ . '/includes/bootstrap.php';
$pdo = admin_pdo();

if (!empty($_SESSION['admin_logged_in']) && (int)($_SESSION['admin_session_version'] ?? -1) === admin_session_version($pdo)) {
    header('Location: index.php');
    exit;
}

$error = '';
$locked = admin_is_rate_limited($pdo);

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    if (!admin_verify_csrf()) {
        $error = 'Session expired, please try again.';
    } elseif ($locked) {
        $error = 'Too many failed attempts. Try again in 15 minutes.';
    } else {
        $password = (string)($_POST['admin_pass'] ?? '');
        $hash = admin_config_get($pdo, 'admin_password_hash');
        $ok = false;

        if ($hash !== null) {
            $ok = password_verify($password, $hash);
        } elseif ($password !== '' && hash_equals(ADMIN_BOOTSTRAP_PASSWORD, $password)) {
            // First-ever login: persist the bootstrap password as a real hash so it's used exactly once.
            admin_config_set($pdo, 'admin_password_hash', password_hash($password, PASSWORD_DEFAULT));
            $ok = true;
        }

        admin_record_attempt($pdo, $ok);

        if ($ok) {
            session_regenerate_id(true);
            $_SESSION['admin_logged_in'] = true;
            $_SESSION['admin_session_version'] = admin_session_version($pdo);
            admin_audit($pdo, 'login');
            header('Location: index.php');
            exit;
        }
        $error = 'Incorrect password.';
        $locked = admin_is_rate_limited($pdo);
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Admin Login - LensWhisper</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
</head>
<body class="bg-light d-flex align-items-center justify-content-center" style="height: 100vh;">
    <div class="card p-4 shadow-sm" style="width: 360px;">
        <h4 class="text-center mb-1">LensWhisper Admin</h4>
        <p class="text-center text-muted small mb-4">Sign in to continue</p>
        <?php if (isset($_GET['expired'])): ?>
            <div class="alert alert-warning py-2 small">Your session ended (password was changed elsewhere).</div>
        <?php endif; ?>
        <?php if ($error): ?>
            <div class="alert alert-danger py-2"><?= htmlspecialchars($error) ?></div>
        <?php endif; ?>
        <form method="POST">
            <?= admin_csrf_field() ?>
            <div class="mb-3">
                <label class="form-label">Admin Password</label>
                <input type="password" name="admin_pass" class="form-control" required autofocus <?= $locked ? 'disabled' : '' ?>>
            </div>
            <button type="submit" class="btn btn-primary w-100" <?= $locked ? 'disabled' : '' ?>>Login</button>
        </form>
        <p class="text-center text-muted small mt-3 mb-0">
            <a href="reset.php" class="text-decoration-none">Forgot password?</a>
        </p>
    </div>
</body>
</html>
