<?php
require_once __DIR__ . '/includes/bootstrap.php';
if (!empty($_SESSION['admin_logged_in'])) {
    $pdo = admin_pdo();
    admin_audit($pdo, 'logout');
}
session_unset();
session_destroy();
header('Location: login.php');
exit;
