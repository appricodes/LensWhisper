<?php
require_once __DIR__ . '/includes/bootstrap.php';
$pdo = admin_pdo();
admin_require_login($pdo);

$message = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['action'] ?? '') === 'change_password') {
    if (!admin_verify_csrf()) {
        $message = "<div class='alert alert-danger py-2'>Session expired, please retry.</div>";
    } else {
        $current = (string)($_POST['current_password'] ?? '');
        $new = (string)($_POST['new_password'] ?? '');
        $confirm = (string)($_POST['confirm_password'] ?? '');
        $hash = admin_config_get($pdo, 'admin_password_hash');

        if ($hash === null || !password_verify($current, $hash)) {
            $message = "<div class='alert alert-danger py-2'>Current password is incorrect.</div>";
        } elseif (strlen($new) < 10) {
            $message = "<div class='alert alert-danger py-2'>New password must be at least 10 characters.</div>";
        } elseif ($new !== $confirm) {
            $message = "<div class='alert alert-danger py-2'>New passwords do not match.</div>";
        } else {
            admin_config_set($pdo, 'admin_password_hash', password_hash($new, PASSWORD_DEFAULT));
            admin_bump_session_version($pdo);
            // Re-issue this session under the new version so the admin who just changed it isn't
            // logged out too — every OTHER session still gets invalidated by admin_require_login().
            $_SESSION['admin_session_version'] = admin_session_version($pdo);
            admin_audit($pdo, 'password_changed');
            $message = "<div class='alert alert-success py-2'>Password updated. Other sessions were signed out.</div>";
        }
    }
}

$auditPage = max(1, (int)($_GET['audit_page'] ?? 1));
$auditPerPage = 50;
$auditOffset = ($auditPage - 1) * $auditPerPage;
$auditTotal = (int)$pdo->query('SELECT COUNT(*) c FROM admin_audit_log')->fetch()['c'];
$auditStmt = $pdo->query("SELECT * FROM admin_audit_log ORDER BY id DESC LIMIT $auditPerPage OFFSET $auditOffset");
$auditRows = $auditStmt->fetchAll();

$configChecks = [
    'XAI_API_KEY' => defined('XAI_API_KEY') && XAI_API_KEY !== '',
    'GEMINI_API_KEY' => defined('GEMINI_API_KEY') && GEMINI_API_KEY !== '',
    'GOOGLE_CLIENT_ID' => defined('GOOGLE_CLIENT_ID') && GOOGLE_CLIENT_ID !== '',
    'REVENUECAT_WEBHOOK_SECRET (not placeholder)' => defined('REVENUECAT_WEBHOOK_SECRET') && REVENUECAT_WEBHOOK_SECRET !== 'REPLACE_ME_WITH_A_LONG_RANDOM_SECRET',
    'ADMIN_RECOVERY_KEY' => defined('ADMIN_RECOVERY_KEY') && strlen(ADMIN_RECOVERY_KEY) >= 32,
];

$pageTitle = 'Settings';
$activeNav = 'settings';
require __DIR__ . '/includes/layout_header.php';
?>

<div class="row g-3">
    <div class="col-md-5">
        <div class="card shadow-sm mb-3">
            <div class="card-header bg-white"><h6 class="mb-0">Change Admin Password</h6></div>
            <div class="card-body">
                <?= $message ?>
                <form method="POST">
                    <?= admin_csrf_field() ?>
                    <input type="hidden" name="action" value="change_password">
                    <div class="mb-3">
                        <label class="form-label">Current Password</label>
                        <input type="password" name="current_password" class="form-control" required>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">New Password</label>
                        <input type="password" name="new_password" class="form-control" required minlength="10">
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Confirm New Password</label>
                        <input type="password" name="confirm_password" class="form-control" required minlength="10">
                    </div>
                    <button type="submit" class="btn btn-primary w-100">Update Password</button>
                </form>
                <p class="text-muted small mt-3 mb-0">
                    Locked out instead? Use <code>reset.php?key=ADMIN_RECOVERY_KEY</code> (the key lives in
                    <code>backend/config.php</code>, never in git).
                </p>
            </div>
        </div>

        <div class="card shadow-sm">
            <div class="card-header bg-white"><h6 class="mb-0">Config Health Check</h6></div>
            <div class="card-body p-0">
                <table class="table table-sm mb-0">
                    <tbody>
                    <?php foreach ($configChecks as $label => $ok): ?>
                        <tr>
                            <td><?= htmlspecialchars($label) ?></td>
                            <td class="text-end"><?= $ok ? '<span class="badge bg-success">OK</span>' : '<span class="badge bg-danger">Missing/placeholder</span>' ?></td>
                        </tr>
                    <?php endforeach; ?>
                    </tbody>
                </table>
                <p class="text-muted small p-3 mb-0">Values themselves are never shown here — only whether they look configured.</p>
            </div>
        </div>
    </div>

    <div class="col-md-7">
        <div class="card shadow-sm">
            <div class="card-header bg-white d-flex justify-content-between align-items-center">
                <h6 class="mb-0">Admin Activity Log</h6>
                <span class="text-muted small"><?= $auditTotal ?> total</span>
            </div>
            <div class="card-body p-0">
                <div class="table-responsive">
                    <table class="table table-sm table-striped mb-0">
                        <thead><tr><th>Action</th><th>Target</th><th>IP</th><th>Time</th></tr></thead>
                        <tbody>
                        <?php foreach ($auditRows as $a): ?>
                            <tr>
                                <td><span class="badge bg-secondary"><?= htmlspecialchars($a['action']) ?></span></td>
                                <td class="small"><?= htmlspecialchars(trim(($a['target_type'] ?? '') . ' ' . ($a['target_id'] ?? ''))) ?></td>
                                <td class="small text-muted"><?= htmlspecialchars($a['ip_address'] ?? '') ?></td>
                                <td class="small text-muted"><?= htmlspecialchars($a['created_at']) ?></td>
                            </tr>
                        <?php endforeach; ?>
                        <?php if (!$auditRows): ?><tr><td colspan="4" class="text-center text-muted p-4">No activity yet.</td></tr><?php endif; ?>
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
        <?php $totalAuditPages = max(1, (int)ceil($auditTotal / $auditPerPage)); if ($totalAuditPages > 1): ?>
        <nav class="mt-2">
            <ul class="pagination pagination-sm">
                <?php for ($p = 1; $p <= min($totalAuditPages, 20); $p++): ?>
                    <li class="page-item <?= $p === $auditPage ? 'active' : '' ?>"><a class="page-link" href="settings.php?audit_page=<?= $p ?>"><?= $p ?></a></li>
                <?php endfor; ?>
            </ul>
        </nav>
        <?php endif; ?>
    </div>
</div>

<?php require __DIR__ . '/includes/layout_footer.php'; ?>
