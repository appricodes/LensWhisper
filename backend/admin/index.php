<?php
require_once __DIR__ . '/includes/bootstrap.php';
$pdo = admin_pdo();
admin_require_login($pdo);

$stats = [
    'accounts'        => (int)$pdo->query('SELECT COUNT(*) c FROM users')->fetch()['c'],
    'verified'        => (int)$pdo->query('SELECT COUNT(*) c FROM users WHERE verified = 1')->fetch()['c'],
    'blocked'         => (int)$pdo->query('SELECT COUNT(*) c FROM users WHERE blocked = 1')->fetch()['c'],
    'installs'        => (int)$pdo->query('SELECT COUNT(*) c FROM install_credits')->fetch()['c'],
    'account_free'    => (int)$pdo->query('SELECT COALESCE(SUM(free_credits),0) c FROM users')->fetch()['c'],
    'account_paid'    => (int)$pdo->query('SELECT COALESCE(SUM(purchased_credits),0) c FROM users')->fetch()['c'],
    'install_credits' => (int)$pdo->query('SELECT COALESCE(SUM(credits),0) c FROM install_credits')->fetch()['c'],
    'requests_total'  => (int)$pdo->query('SELECT COUNT(*) c FROM purchase_log')->fetch()['c'],
    'requests_7d'     => (int)$pdo->query("SELECT COUNT(*) c FROM purchase_log WHERE created_at > (NOW() - INTERVAL 7 DAY)")->fetch()['c'],
    'cogs_30d'        => (float)$pdo->query("SELECT COALESCE(SUM(cogs),0) c FROM purchase_log WHERE created_at > (NOW() - INTERVAL 30 DAY)")->fetch()['c'],
    'ad_impressions'  => (int)$pdo->query('SELECT COUNT(*) c FROM ad_revenue_log')->fetch()['c'],
    'purchases_total' => (int)$pdo->query('SELECT COUNT(*) c FROM processed_webhook_events')->fetch()['c'],
];

$recentSignups = $pdo->query('SELECT email, created_at FROM users ORDER BY created_at DESC LIMIT 8')->fetchAll();
$recentAudit = $pdo->query('SELECT action, target_type, target_id, ip_address, created_at FROM admin_audit_log ORDER BY id DESC LIMIT 10')->fetchAll();

$pageTitle = 'Dashboard';
$activeNav = 'index';
require __DIR__ . '/includes/layout_header.php';
?>

<div class="row g-3 mb-2">
    <div class="col-6 col-md-3">
        <div class="card stat-card shadow-sm h-100"><div class="card-body">
            <div class="text-muted small">Accounts (logged-in)</div>
            <div class="display-6"><?= $stats['accounts'] ?></div>
            <div class="small text-muted"><?= $stats['verified'] ?> verified · <?= $stats['blocked'] ?> blocked</div>
        </div></div>
    </div>
    <div class="col-6 col-md-3">
        <div class="card stat-card shadow-sm h-100"><div class="card-body">
            <div class="text-muted small">Anonymous Installs</div>
            <div class="display-6"><?= $stats['installs'] ?></div>
            <div class="small text-muted"><?= $stats['install_credits'] ?> credits outstanding</div>
        </div></div>
    </div>
    <div class="col-6 col-md-3">
        <div class="card stat-card shadow-sm h-100"><div class="card-body">
            <div class="text-muted small">AI Requests (7d / total)</div>
            <div class="display-6"><?= $stats['requests_7d'] ?></div>
            <div class="small text-muted"><?= $stats['requests_total'] ?> all-time</div>
        </div></div>
    </div>
    <div class="col-6 col-md-3">
        <div class="card stat-card shadow-sm h-100"><div class="card-body">
            <div class="text-muted small">COGS (30d)</div>
            <div class="display-6">$<?= number_format($stats['cogs_30d'], 2) ?></div>
            <div class="small text-muted"><?= $stats['purchases_total'] ?> purchases processed</div>
        </div></div>
    </div>
</div>

<div class="row g-3 mb-2">
    <div class="col-md-4">
        <div class="card stat-card shadow-sm h-100"><div class="card-body">
            <div class="text-muted small">Account Credits Outstanding</div>
            <div class="fs-4"><span class="text-success fw-bold"><?= $stats['account_free'] ?></span> free /
                <span class="text-primary fw-bold"><?= $stats['account_paid'] ?></span> purchased</div>
        </div></div>
    </div>
    <div class="col-md-4">
        <div class="card stat-card shadow-sm h-100"><div class="card-body">
            <div class="text-muted small">Ad Impressions Logged</div>
            <div class="fs-4"><?= $stats['ad_impressions'] ?></div>
        </div></div>
    </div>
    <div class="col-md-4">
        <div class="card shadow-sm h-100"><div class="card-body">
            <div class="text-muted small mb-2">Quick Links</div>
            <a href="users.php" class="btn btn-sm btn-outline-primary me-1 mb-1">Search accounts</a>
            <a href="installs.php" class="btn btn-sm btn-outline-primary me-1 mb-1">Search installs</a>
            <a href="logs.php" class="btn btn-sm btn-outline-primary mb-1">Browse usage log</a>
        </div></div>
    </div>
</div>

<div class="row g-3">
    <div class="col-lg-6">
        <div class="card shadow-sm h-100">
            <div class="card-header bg-white"><h6 class="mb-0">Recent Signups</h6></div>
            <div class="card-body p-0">
                <table class="table table-sm table-hover mb-0">
                    <tbody>
                    <?php foreach ($recentSignups as $u): ?>
                        <tr>
                            <td><a href="users.php?email=<?= urlencode($u['email']) ?>"><?= htmlspecialchars($u['email']) ?></a></td>
                            <td class="text-muted small text-end"><?= htmlspecialchars($u['created_at']) ?></td>
                        </tr>
                    <?php endforeach; ?>
                    <?php if (!$recentSignups): ?><tr><td class="text-muted p-3">No accounts yet.</td></tr><?php endif; ?>
                    </tbody>
                </table>
            </div>
        </div>
    </div>
    <div class="col-lg-6">
        <div class="card shadow-sm h-100">
            <div class="card-header bg-white"><h6 class="mb-0">Recent Admin Activity</h6></div>
            <div class="card-body p-0">
                <table class="table table-sm table-hover mb-0">
                    <tbody>
                    <?php foreach ($recentAudit as $a): ?>
                        <tr>
                            <td><span class="badge bg-secondary"><?= htmlspecialchars($a['action']) ?></span></td>
                            <td class="small"><?= htmlspecialchars(trim(($a['target_type'] ?? '') . ' ' . ($a['target_id'] ?? ''))) ?></td>
                            <td class="text-muted small text-end"><?= htmlspecialchars($a['created_at']) ?></td>
                        </tr>
                    <?php endforeach; ?>
                    <?php if (!$recentAudit): ?><tr><td class="text-muted p-3">No activity yet.</td></tr><?php endif; ?>
                    </tbody>
                </table>
            </div>
        </div>
    </div>
</div>

<?php require __DIR__ . '/includes/layout_footer.php'; ?>
