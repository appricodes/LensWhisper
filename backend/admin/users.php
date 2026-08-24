<?php
require_once __DIR__ . '/includes/bootstrap.php';
$pdo = admin_pdo();
admin_require_login($pdo);

$message = '';

if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['action'] ?? '') === 'update_user') {
    if (!admin_verify_csrf()) {
        $message = "<div class='alert alert-danger py-2'>Session expired, please retry.</div>";
    } else {
        $editEmail = (string)$_POST['email'];
        $freeCredits = (int)$_POST['free_credits'];
        $purchasedCredits = (int)$_POST['purchased_credits'];
        $verified = (int)$_POST['verified'];
        $blocked = isset($_POST['blocked']) ? 1 : 0;

        $before = $pdo->prepare('SELECT free_credits, purchased_credits, verified, blocked FROM users WHERE email = ?');
        $before->execute([$editEmail]);
        $beforeRow = $before->fetch();

        $stmt = $pdo->prepare('UPDATE users SET free_credits = ?, purchased_credits = ?, verified = ?, blocked = ? WHERE email = ?');
        if ($stmt->execute([$freeCredits, $purchasedCredits, $verified, $blocked, $editEmail])) {
            admin_audit($pdo, 'update_user', 'account', $editEmail, ['before' => $beforeRow, 'after' => [
                'free_credits' => $freeCredits, 'purchased_credits' => $purchasedCredits,
                'verified' => $verified, 'blocked' => $blocked,
            ]]);
            $message = "<div class='alert alert-success py-2'>User " . htmlspecialchars($editEmail) . " updated.</div>";
        } else {
            $message = "<div class='alert alert-danger py-2'>Failed to update user.</div>";
        }
    }
}

$search = trim((string)($_GET['email'] ?? ($_GET['q'] ?? '')));
$viewEmail = trim((string)($_GET['email'] ?? ''));

$pageTitle = 'Accounts';
$activeNav = 'users';
require __DIR__ . '/includes/layout_header.php';
?>

<div class="d-flex flex-wrap justify-content-between align-items-center mb-3 gap-2">
    <h5 class="mb-0">Accounts</h5>
    <form class="d-flex" method="GET" action="users.php">
        <input class="form-control form-control-sm me-2" type="text" name="q" style="width:260px"
               placeholder="Search by email (partial ok)..." value="<?= htmlspecialchars($search) ?>">
        <button class="btn btn-sm btn-primary" type="submit">Search</button>
    </form>
</div>

<?= $message ?>

<?php if ($viewEmail): ?>
    <?php
    $stmt = $pdo->prepare('SELECT * FROM users WHERE email = ? LIMIT 1');
    $stmt->execute([$viewEmail]);
    $user = $stmt->fetch();
    if (!$user):
    ?>
        <div class="alert alert-warning">User not found.</div>
        <a href="users.php" class="btn btn-secondary btn-sm">&larr; Back</a>
    <?php else: ?>
        <div class="row">
            <div class="col-md-4 mb-4">
                <div class="card shadow-sm">
                    <div class="card-header bg-primary text-white"><h6 class="card-title mb-0">Edit Account</h6></div>
                    <div class="card-body">
                        <form method="POST" action="users.php?email=<?= urlencode($viewEmail) ?>">
                            <?= admin_csrf_field() ?>
                            <input type="hidden" name="action" value="update_user">
                            <input type="hidden" name="email" value="<?= htmlspecialchars($user['email']) ?>">
                            <div class="mb-3">
                                <label class="form-label text-muted small">Email</label>
                                <input type="text" class="form-control" value="<?= htmlspecialchars($user['email']) ?>" disabled>
                            </div>
                            <div class="mb-3">
                                <label class="form-label text-muted small">Google ID</label>
                                <input type="text" class="form-control" value="<?= htmlspecialchars($user['google_id']) ?>" disabled>
                            </div>
                            <div class="mb-3">
                                <label class="form-label text-muted small">Created / Updated</label>
                                <input type="text" class="form-control" value="<?= htmlspecialchars($user['created_at']) ?> / <?= htmlspecialchars($user['updated_at']) ?>" disabled>
                            </div>
                            <div class="mb-3">
                                <label class="form-label fw-bold">Verified</label>
                                <select name="verified" class="form-select">
                                    <option value="1" <?= $user['verified'] == 1 ? 'selected' : '' ?>>1 - Verified</option>
                                    <option value="0" <?= $user['verified'] == 0 ? 'selected' : '' ?>>0 - Not verified</option>
                                </select>
                            </div>
                            <div class="mb-3 form-check">
                                <input type="checkbox" class="form-check-input" id="blocked" name="blocked" <?= (int)($user['blocked'] ?? 0) === 1 ? 'checked' : '' ?>>
                                <label class="form-check-label text-danger fw-bold" for="blocked">Blocked (rejects login even if re-verified)</label>
                            </div>
                            <div class="mb-3">
                                <label class="form-label fw-bold text-success">Free Credits</label>
                                <input type="number" name="free_credits" class="form-control" value="<?= (int)$user['free_credits'] ?>" required>
                            </div>
                            <div class="mb-4">
                                <label class="form-label fw-bold text-primary">Purchased Credits</label>
                                <input type="number" name="purchased_credits" class="form-control" value="<?= (int)$user['purchased_credits'] ?>" required>
                            </div>
                            <button type="submit" class="btn btn-success w-100">Save Changes</button>
                            <a href="users.php" class="btn btn-outline-secondary w-100 mt-2">Cancel</a>
                        </form>
                    </div>
                </div>
            </div>

            <div class="col-md-8 mb-4">
                <div class="card shadow-sm h-100">
                    <div class="card-header bg-secondary text-white"><h6 class="card-title mb-0">Usage Log for <?= htmlspecialchars($user['email']) ?></h6></div>
                    <div class="card-body p-0">
                        <div class="table-responsive">
                            <table class="table table-hover table-striped mb-0" style="font-size: 0.85rem;">
                                <thead>
                                    <tr><th>ID</th><th>Prompt</th><th>Model</th><th>Credits (F/P)</th><th>COGS</th><th>Tokens</th><th>Stats</th><th>Time</th></tr>
                                </thead>
                                <tbody>
                                <?php
                                $logStmt = $pdo->prepare('SELECT * FROM purchase_log WHERE email = ? ORDER BY id DESC LIMIT 500');
                                $logStmt->execute([$viewEmail]);
                                while ($log = $logStmt->fetch()):
                                ?>
                                    <tr>
                                        <td><?= $log['id'] ?></td>
                                        <td><span class="badge bg-info text-dark"><?= htmlspecialchars($log['prompt_id']) ?></span></td>
                                        <td><small><?= htmlspecialchars($log['model_name'] ?? 'N/A') ?></small></td>
                                        <td><span class="text-success fw-bold"><?= $log['cost_free'] ?></span> / <span class="text-primary fw-bold"><?= $log['cost_purchased'] ?></span></td>
                                        <td class="text-danger"><small><?= isset($log['cogs']) ? '$' . number_format((float)$log['cogs'] * 1000, 3) : '-' ?></small></td>
                                        <td><small><?= $log['input_tokens'] ?? '-' ?> / <?= $log['output_tokens'] ?? '-' ?></small></td>
                                        <td><?php if (!empty($log['stats_json'])): ?><details><summary class="text-primary">View</summary><pre class="bg-white p-1 mt-1 border rounded json-pre"><?= htmlspecialchars($log['stats_json']) ?></pre></details><?php else: ?>-<?php endif; ?></td>
                                        <td class="text-muted"><small><?= $log['created_at'] ?? '' ?></small></td>
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
                    <thead>
                        <tr><th>Email</th><th>Status</th><th>Credits (F/P)</th><th>Created</th><th>Action</th></tr>
                    </thead>
                    <tbody>
                    <?php
                    if ($search !== '') {
                        $usersStmt = $pdo->prepare('SELECT email, verified, blocked, free_credits, purchased_credits, created_at FROM users WHERE email LIKE ? ORDER BY updated_at DESC LIMIT 200');
                        $usersStmt->execute(['%' . $search . '%']);
                    } else {
                        $usersStmt = $pdo->query('SELECT email, verified, blocked, free_credits, purchased_credits, created_at FROM users ORDER BY updated_at DESC LIMIT 200');
                    }
                    while ($u = $usersStmt->fetch()):
                    ?>
                        <tr>
                            <td class="text-break fw-bold"><?= htmlspecialchars($u['email']) ?></td>
                            <td>
                                <?php if ((int)($u['blocked'] ?? 0) === 1): ?><span class="badge bg-dark">Blocked</span>
                                <?php elseif ($u['verified'] == 1): ?><span class="badge bg-success">Verified</span>
                                <?php else: ?><span class="badge bg-secondary">Unverified</span><?php endif; ?>
                            </td>
                            <td><span class="text-success fw-bold"><?= (int)$u['free_credits'] ?></span> / <span class="text-primary fw-bold"><?= (int)$u['purchased_credits'] ?></span></td>
                            <td class="text-muted small"><?= htmlspecialchars($u['created_at']) ?></td>
                            <td><a href="users.php?email=<?= urlencode($u['email']) ?>" class="btn btn-sm btn-outline-primary">Manage</a></td>
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
