<?php
require_once __DIR__ . '/includes/bootstrap.php';
$pdo = admin_pdo();
admin_require_login($pdo);

// --- Build filter clause shared by the page view and the CSV export ---
$email = trim((string)($_GET['email'] ?? ''));
$installId = trim((string)($_GET['install_id'] ?? ''));
$model = trim((string)($_GET['model'] ?? ''));
$dateFrom = trim((string)($_GET['from'] ?? ''));
$dateTo = trim((string)($_GET['to'] ?? ''));

$where = [];
$params = [];
if ($email !== '') { $where[] = 'email LIKE :email'; $params[':email'] = '%' . $email . '%'; }
if ($installId !== '') { $where[] = 'install_id LIKE :install_id'; $params[':install_id'] = '%' . $installId . '%'; }
if ($model !== '') { $where[] = 'model_name LIKE :model'; $params[':model'] = '%' . $model . '%'; }
if ($dateFrom !== '') { $where[] = 'created_at >= :from'; $params[':from'] = $dateFrom . ' 00:00:00'; }
if ($dateTo !== '') { $where[] = 'created_at <= :to'; $params[':to'] = $dateTo . ' 23:59:59'; }
$whereSql = $where ? ('WHERE ' . implode(' AND ', $where)) : '';

// --- CSV export ---
if (isset($_GET['export']) && $_GET['export'] === 'csv') {
    admin_audit($pdo, 'export_csv', 'logs', null, ['filters' => $_GET]);
    $stmt = $pdo->prepare("SELECT id, email, install_id, prompt_id, model_name, cost_free, cost_purchased, input_tokens, output_tokens, cogs, created_at FROM purchase_log $whereSql ORDER BY id DESC LIMIT 10000");
    $stmt->execute($params);

    header('Content-Type: text/csv; charset=utf-8');
    header('Content-Disposition: attachment; filename="usage_log.csv"');
    $out = fopen('php://output', 'w');
    fputcsv($out, ['id', 'email', 'install_id', 'prompt_id', 'model_name', 'cost_free', 'cost_purchased', 'input_tokens', 'output_tokens', 'cogs', 'created_at']);
    while ($row = $stmt->fetch()) { fputcsv($out, $row); }
    fclose($out);
    exit;
}

$page = max(1, (int)($_GET['page'] ?? 1));
$perPage = 100;
$offset = ($page - 1) * $perPage;

$countStmt = $pdo->prepare("SELECT COUNT(*) c FROM purchase_log $whereSql");
$countStmt->execute($params);
$total = (int)$countStmt->fetch()['c'];

$stmt = $pdo->prepare("SELECT * FROM purchase_log $whereSql ORDER BY id DESC LIMIT $perPage OFFSET $offset");
$stmt->execute($params);
$rows = $stmt->fetchAll();

$pageTitle = 'Usage Log';
$activeNav = 'logs';
require __DIR__ . '/includes/layout_header.php';
?>

<h5 class="mb-3">Usage Log <span class="text-muted small">(<?= $total ?> matching)</span></h5>

<form class="row g-2 mb-3" method="GET" action="logs.php">
    <div class="col-md-3"><input class="form-control form-control-sm" type="text" name="email" placeholder="Email contains..." value="<?= htmlspecialchars($email) ?>"></div>
    <div class="col-md-3"><input class="form-control form-control-sm" type="text" name="install_id" placeholder="Install ID contains..." value="<?= htmlspecialchars($installId) ?>"></div>
    <div class="col-md-2"><input class="form-control form-control-sm" type="text" name="model" placeholder="Model contains..." value="<?= htmlspecialchars($model) ?>"></div>
    <div class="col-md-2"><input class="form-control form-control-sm" type="date" name="from" value="<?= htmlspecialchars($dateFrom) ?>"></div>
    <div class="col-md-2"><input class="form-control form-control-sm" type="date" name="to" value="<?= htmlspecialchars($dateTo) ?>"></div>
    <div class="col-12">
        <button class="btn btn-sm btn-primary" type="submit">Filter</button>
        <a class="btn btn-sm btn-outline-secondary" href="logs.php">Clear</a>
        <a class="btn btn-sm btn-outline-success" href="logs.php?<?= http_build_query(array_merge($_GET, ['export' => 'csv'])) ?>">Export CSV (up to 10k rows)</a>
    </div>
</form>

<div class="card shadow-sm">
    <div class="card-body p-0">
        <div class="table-responsive">
            <table class="table table-striped table-hover mb-0" style="font-size: 0.85rem;">
                <thead>
                    <tr><th>ID</th><th>Identity</th><th>Prompt</th><th>Model</th><th>Credits (F/P)</th><th>COGS</th><th>Tokens</th><th>Stats</th><th>Date</th></tr>
                </thead>
                <tbody>
                <?php foreach ($rows as $log): ?>
                    <tr>
                        <td><?= $log['id'] ?></td>
                        <td class="text-truncate" style="max-width: 160px;">
                            <?php if (!empty($log['email'])): ?>
                                <a href="users.php?email=<?= urlencode($log['email']) ?>"><?= htmlspecialchars($log['email']) ?></a>
                            <?php elseif (!empty($log['install_id'])): ?>
                                <a href="installs.php?install_id=<?= urlencode($log['install_id']) ?>" class="text-muted"><small><?= htmlspecialchars(substr($log['install_id'], 0, 13)) ?>&hellip;</small></a>
                            <?php else: ?>-<?php endif; ?>
                        </td>
                        <td><span class="badge bg-info text-dark"><?= htmlspecialchars($log['prompt_id']) ?></span></td>
                        <td><?= htmlspecialchars($log['model_name'] ?? 'N/A') ?></td>
                        <td><span class="text-success fw-bold"><?= $log['cost_free'] ?></span> / <span class="text-primary fw-bold"><?= $log['cost_purchased'] ?></span></td>
                        <td class="text-danger"><small><?= isset($log['cogs']) ? '$' . number_format((float)$log['cogs'] * 1000, 3) : '-' ?></small></td>
                        <td><small><?= $log['input_tokens'] ?? '-' ?> / <?= $log['output_tokens'] ?? '-' ?></small></td>
                        <td><?php if (!empty($log['stats_json'])): ?><details><summary class="text-primary">JSON</summary><pre class="bg-white p-1 mt-1 border rounded json-pre"><?= htmlspecialchars($log['stats_json']) ?></pre></details><?php else: ?>-<?php endif; ?></td>
                        <td class="text-muted"><?= $log['created_at'] ?? '' ?></td>
                    </tr>
                <?php endforeach; ?>
                <?php if (!$rows): ?><tr><td colspan="9" class="text-center text-muted p-4">No matching rows.</td></tr><?php endif; ?>
                </tbody>
            </table>
        </div>
    </div>
</div>

<?php $totalPages = max(1, (int)ceil($total / $perPage)); if ($totalPages > 1): ?>
<nav class="mt-3">
    <ul class="pagination pagination-sm">
        <?php for ($p = 1; $p <= $totalPages; $p++): ?>
            <li class="page-item <?= $p === $page ? 'active' : '' ?>">
                <a class="page-link" href="logs.php?<?= http_build_query(array_merge($_GET, ['page' => $p])) ?>"><?= $p ?></a>
            </li>
        <?php endfor; ?>
    </ul>
</nav>
<?php endif; ?>

<?php require __DIR__ . '/includes/layout_footer.php'; ?>
