<?php
require_once __DIR__ . '/includes/bootstrap.php';
$pdo = admin_pdo();
admin_require_login($pdo);

$message = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['action'] ?? '') === 'update_install') {
    if (!admin_verify_csrf()) {
        $message = "<div class='alert alert-danger py-2'>Session expired, please retry.</div>";
    } else {
        $installId = (string)$_POST['install_id'];
        $credits = (int)$_POST['credits'];

        $before = $pdo->prepare('SELECT credits FROM install_credits WHERE install_id = ?');
        $before->execute([$installId]);
        $beforeRow = $before->fetch();

        $stmt = $pdo->prepare('UPDATE install_credits SET credits = ? WHERE install_id = ?');
        if ($stmt->execute([$credits, $installId])) {
            admin_audit($pdo, 'update_install', 'install', $installId, ['before' => $beforeRow, 'after' => ['credits' => $credits]]);
            $message = "<div class='alert alert-success py-2'>Install updated.</div>";
        } else {
            $message = "<div class='alert alert-danger py-2'>Failed to update install.</div>";
        }
    }
}

$search = trim((string)($_GET['q'] ?? ''));
$viewId = trim((string)($_GET['install_id'] ?? ''));

$pageTitle = 'Anonymous Installs';
$activeNav = 'installs';
require __DIR__ . '/includes/layout_header.php';
?>

<div class="d-flex flex-wrap justify-content-between align-items-center mb-3 gap-2">
    <h5 class="mb-0">Anonymous Installs</h5>
    <form class="d-flex" method="GET" action="installs.php">
        <input class="form-control form-control-sm me-2" type="text" name="q" style="width:300px"
               placeholder="Search by install_id (partial ok)..." value="<?= htmlspecialchars($search) ?>">
        <button class="btn btn-sm btn-primary" type="submit">Search</button>
    </form>
</div>

<?= $message ?>

<?php if ($viewId): ?>
    <?php
    $stmt = $pdo->prepare('SELECT * FROM install_credits WHERE install_id = ? LIMIT 1');
    $stmt->execute([$viewId]);
    $row = $stmt->fetch();
    if (!$row):
    ?>
        <div class="alert alert-warning">Install not found.</div>
        <a href="installs.php" class="btn btn-secondary btn-sm">&larr; Back</a>
    <?php else: ?>
        <div class="row">
            <div class="col-md-4 mb-4">
                <div class="card shadow-sm">
                    <div class="card-header bg-primary text-white"><h6 class="card-title mb-0">Edit Install</h6></div>
                    <div class="card-body">
                        <form method="POST" action="installs.php?install_id=<?= urlencode($viewId) ?>">
                            <?= admin_csrf_field() ?>
                            <input type="hidden" name="action" value="update_install">
                            <input type="hidden" name="install_id" value="<?= htmlspecialchars($row['install_id']) ?>">
                            <div class="mb-3">
                                <label class="form-label text-muted small">Install ID</label>
                                <input type="text" class="form-control" value="<?= htmlspecialchars($row['install_id']) ?>" disabled>
                            </div>
                            <div class="mb-3">
                                <label class="form-label text-muted small">Created / Updated</label>
                                <input type="text" class="form-control" value="<?= htmlspecialchars($row['created_at']) ?> / <?= htmlspecialchars($row['updated_at']) ?>" disabled>
                            </div>
                            <div class="mb-4">
                                <label class="form-label fw-bold text-success">Credits</label>
                                <input type="number" name="credits" class="form-control" value="<?= (int)$row['credits'] ?>" required>
                            </div>
                            <button type="submit" class="btn btn-success w-100">Save Changes</button>
                            <a href="installs.php" class="btn btn-outline-secondary w-100 mt-2">Cancel</a>
                        </form>
                    </div>
                </div>
            </div>
            <div class="col-md-8 mb-4">
                <div class="card shadow-sm h-100">
                    <div class="card-header bg-secondary text-white"><h6 class="card-title mb-0">Usage Log for this Install</h6></div>
                    <div class="card-body p-0">
                        <div class="table-responsive">
                            <table class="table table-hover table-striped mb-0" style="font-size: 0.85rem;">
                                <thead><tr><th>ID</th><th>Prompt</th><th>Model</th><th>Credits (F/P)</th><th>Time</th></tr></thead>
                                <tbody>
                                <?php
                                $logStmt = $pdo->prepare('SELECT * FROM purchase_log WHERE install_id = ? ORDER BY id DESC LIMIT 300');
                                $logStmt->execute([$viewId]);
                                while ($log = $logStmt->fetch()):
                                ?>
                                    <tr>
                                        <td><?= $log['id'] ?></td>
                                        <td><span class="badge bg-info text-dark"><?= htmlspecialchars($log['prompt_id']) ?></span></td>
                                        <td><small><?= htmlspecialchars($log['model_name'] ?? 'N/A') ?></small></td>
                                        <td><span class="text-success fw-bold"><?= $log['cost_free'] ?></span> / <span class="text-primary fw-bold"><?= $log['cost_purchased'] ?></span></td>
                                        <td class="text-muted"><small><?= $log['created_at'] ?? '' ?></small></td>
                                    </tr>
                                <?php endwhile; ?>
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>
                <div class="card shadow-sm h-100 mt-3">
                    <div class="card-header bg-white"><h6 class="card-title mb-0">Ad Revenue Log for this Install</h6></div>
                    <div class="card-body p-0">
                        <div class="table-responsive">
                            <table class="table table-hover table-striped mb-0" style="font-size: 0.85rem;">
                                <thead><tr><th>ID</th><th>Ad Unit</th><th>Value (micros)</th><th>Currency</th><th>Precision</th><th>Time</th></tr></thead>
                                <tbody>
                                <?php
                                $adStmt = $pdo->prepare('SELECT * FROM ad_revenue_log WHERE install_id = ? ORDER BY id DESC LIMIT 200');
                                $adStmt->execute([$viewId]);
                                while ($ad = $adStmt->fetch()):
                                ?>
                                    <tr>
                                        <td><?= $ad['id'] ?></td>
                                        <td><small><?= htmlspecialchars($ad['ad_unit_id'] ?? '-') ?></small></td>
                                        <td><?= (int)$ad['value_micros'] ?></td>
                                        <td><?= htmlspecialchars($ad['currency_code']) ?></td>
                                        <td><small><?= htmlspecialchars($ad['precision_type']) ?></small></td>
                                        <td class="text-muted"><small><?= $ad['created_at'] ?? '' ?></small></td>
                                    </tr>
                                <?php endwhile; ?>
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    <?php endif; ?>
<?php else: ?>
    <div class="card shadow-sm">
        <div class="card-body p-0">
            <div class="table-responsive">
                <table class="table table-hover align-middle mb-0">
                    <thead><tr><th>Install ID</th><th>Credits</th><th>Created</th><th>Updated</th><th>Action</th></tr></thead>
                    <tbody>
                    <?php
                    if ($search !== '') {
                        $stmt = $pdo->prepare('SELECT install_id, credits, created_at, updated_at FROM install_credits WHERE install_id LIKE ? ORDER BY updated_at DESC LIMIT 200');
                        $stmt->execute(['%' . $search . '%']);
                    } else {
                        $stmt = $pdo->query('SELECT install_id, credits, created_at, updated_at FROM install_credits ORDER BY updated_at DESC LIMIT 200');
                    }
                    while ($row = $stmt->fetch()):
                    ?>
                        <tr>
                            <td class="text-break"><small><?= htmlspecialchars($row['install_id']) ?></small></td>
                            <td class="fw-bold text-success"><?= (int)$row['credits'] ?></td>
                            <td class="text-muted small"><?= htmlspecialchars($row['created_at']) ?></td>
                            <td class="text-muted small"><?= htmlspecialchars($row['updated_at']) ?></td>
                            <td><a href="installs.php?install_id=<?= urlencode($row['install_id']) ?>" class="btn btn-sm btn-outline-primary">Manage</a></td>
                        </tr>
                    <?php endwhile; ?>
                    </tbody>
                </table>
            </div>
        </div>
    </div>
    <p class="text-muted small mt-2">Showing up to 200 rows<?= $search !== '' ? ' matching "' . htmlspecialchars($search) . '"' : ', most recently updated first' ?>.</p>
<?php endif; ?>

<?php require __DIR__ . '/includes/layout_footer.php'; ?>
