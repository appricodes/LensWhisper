<?php
require_once __DIR__ . '/includes/bootstrap.php';
$pdo = admin_pdo();
admin_require_login($pdo);

$totalImpressions = (int)$pdo->query('SELECT COUNT(*) c FROM ad_revenue_log')->fetch()['c'];
$verifiedRewards = (int)$pdo->query('SELECT COUNT(*) c FROM processed_ssv_events')->fetch()['c'];

// Revenue is only directionally useful (see ad_revenue_log's own doc comment — impression-reported,
// not the trustworthy SSV signal), so it's grouped by currency rather than force-summed into one number.
$byCurrency = $pdo->query(
    'SELECT currency_code, COUNT(*) n, SUM(value_micros) total_micros FROM ad_revenue_log GROUP BY currency_code ORDER BY n DESC'
)->fetchAll();

$daily = $pdo->query(
    "SELECT DATE(created_at) d, COUNT(*) n FROM ad_revenue_log
     WHERE created_at > (NOW() - INTERVAL 14 DAY) GROUP BY DATE(created_at) ORDER BY d DESC"
)->fetchAll();

$recent = $pdo->query('SELECT * FROM ad_revenue_log ORDER BY id DESC LIMIT 200')->fetchAll();

$pageTitle = 'Ad Revenue';
$activeNav = 'ad_revenue';
require __DIR__ . '/includes/layout_header.php';
?>

<h5 class="mb-3">Ad Revenue</h5>

<div class="row g-3 mb-3">
    <div class="col-md-4">
        <div class="card stat-card shadow-sm h-100"><div class="card-body">
            <div class="text-muted small">Impressions Logged (client-reported)</div>
            <div class="display-6"><?= $totalImpressions ?></div>
        </div></div>
    </div>
    <div class="col-md-4">
        <div class="card stat-card shadow-sm h-100"><div class="card-body">
            <div class="text-muted small">Verified Reward Grants (server-side, trustworthy)</div>
            <div class="display-6"><?= $verifiedRewards ?></div>
            <div class="small text-muted">2 credits each, via admob_ssv_callback.php</div>
        </div></div>
    </div>
    <div class="col-md-4">
        <div class="card stat-card shadow-sm h-100"><div class="card-body">
            <div class="text-muted small">Reconciliation</div>
            <div class="small text-muted">
                Impressions and verified grants come from separate signals and won't match 1:1
                (a shown ad doesn't always complete; SSV can lag or occasionally be skipped). A
                verified-grant count near zero while impressions are non-zero for a while is worth
                investigating — check the AdMob console's SSV setup.
            </div>
        </div></div>
    </div>
</div>

<div class="row g-3 mb-3">
    <div class="col-md-5">
        <div class="card shadow-sm h-100">
            <div class="card-header bg-white"><h6 class="mb-0">By Currency (directional only)</h6></div>
            <div class="card-body p-0">
                <table class="table table-sm table-hover mb-0">
                    <thead><tr><th>Currency</th><th>Impressions</th><th>Total (micros)</th></tr></thead>
                    <tbody>
                    <?php foreach ($byCurrency as $row): ?>
                        <tr>
                            <td><?= htmlspecialchars($row['currency_code']) ?></td>
                            <td><?= (int)$row['n'] ?></td>
                            <td><?= number_format((int)$row['total_micros']) ?></td>
                        </tr>
                    <?php endforeach; ?>
                    <?php if (!$byCurrency): ?><tr><td colspan="3" class="text-muted p-3">No data yet.</td></tr><?php endif; ?>
                    </tbody>
                </table>
            </div>
        </div>
    </div>
    <div class="col-md-7">
        <div class="card shadow-sm h-100">
            <div class="card-header bg-white"><h6 class="mb-0">Impressions per Day (last 14 days)</h6></div>
            <div class="card-body p-0">
                <table class="table table-sm table-hover mb-0">
                    <thead><tr><th>Date</th><th>Impressions</th></tr></thead>
                    <tbody>
                    <?php foreach ($daily as $row): ?>
                        <tr><td><?= htmlspecialchars($row['d']) ?></td><td><?= (int)$row['n'] ?></td></tr>
                    <?php endforeach; ?>
                    <?php if (!$daily): ?><tr><td colspan="2" class="text-muted p-3">No data yet.</td></tr><?php endif; ?>
                    </tbody>
                </table>
            </div>
        </div>
    </div>
</div>

<div class="card shadow-sm">
    <div class="card-header bg-white"><h6 class="mb-0">Recent Impressions</h6></div>
    <div class="card-body p-0">
        <div class="table-responsive">
            <table class="table table-striped table-hover mb-0" style="font-size: 0.85rem;">
                <thead><tr><th>ID</th><th>Install</th><th>Ad Unit</th><th>Value (micros)</th><th>Currency</th><th>Precision</th><th>Time</th></tr></thead>
                <tbody>
                <?php foreach ($recent as $ad): ?>
                    <tr>
                        <td><?= $ad['id'] ?></td>
                        <td><a href="installs.php?install_id=<?= urlencode($ad['install_id']) ?>" class="text-muted"><small><?= htmlspecialchars(substr($ad['install_id'], 0, 13)) ?>&hellip;</small></a></td>
                        <td><small><?= htmlspecialchars($ad['ad_unit_id'] ?? '-') ?></small></td>
                        <td><?= (int)$ad['value_micros'] ?></td>
                        <td><?= htmlspecialchars($ad['currency_code']) ?></td>
                        <td><small><?= htmlspecialchars($ad['precision_type']) ?></small></td>
                        <td class="text-muted"><small><?= $ad['created_at'] ?? '' ?></small></td>
                    </tr>
                <?php endforeach; ?>
                <?php if (!$recent): ?><tr><td colspan="7" class="text-center text-muted p-4">No data yet.</td></tr><?php endif; ?>
                </tbody>
            </table>
        </div>
    </div>
</div>

<?php require __DIR__ . '/includes/layout_footer.php'; ?>
