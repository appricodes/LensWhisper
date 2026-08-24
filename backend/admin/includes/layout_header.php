<?php
/** Shared chrome for every logged-in admin page. Expects $pageTitle and $activeNav to be set
 *  by the including page before requiring this file. */
$activeNav = $activeNav ?? '';
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title><?= htmlspecialchars($pageTitle ?? 'Admin') ?> - LensWhisper Admin</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
    <style>
        body { background: #f4f6f9; }
        .table-responsive { max-height: 650px; overflow-y: auto; }
        thead th { position: sticky; top: 0; background-color: #f8f9fa; z-index: 1; }
        .json-pre { font-size: 0.7rem; max-width: 220px; overflow-x: auto; margin-bottom: 0; }
        details summary { cursor: pointer; user-select: none; font-weight: 500; }
        .stat-card .display-6 { font-weight: 600; }
        .navbar-brand { font-weight: 600; }
    </style>
</head>
<body>
<nav class="navbar navbar-expand-lg navbar-dark bg-dark mb-4">
    <div class="container-fluid">
        <a class="navbar-brand" href="index.php">LensWhisper Admin</a>
        <button class="navbar-toggler" type="button" data-bs-toggle="collapse" data-bs-target="#navMain">
            <span class="navbar-toggler-icon"></span>
        </button>
        <div class="collapse navbar-collapse" id="navMain">
            <ul class="navbar-nav me-auto">
                <li class="nav-item"><a class="nav-link <?= $activeNav === 'index' ? 'active fw-bold' : '' ?>" href="index.php">Dashboard</a></li>
                <li class="nav-item"><a class="nav-link <?= $activeNav === 'users' ? 'active fw-bold' : '' ?>" href="users.php">Accounts</a></li>
                <li class="nav-item"><a class="nav-link <?= $activeNav === 'installs' ? 'active fw-bold' : '' ?>" href="installs.php">Anonymous Installs</a></li>
                <li class="nav-item"><a class="nav-link <?= $activeNav === 'logs' ? 'active fw-bold' : '' ?>" href="logs.php">Usage Log</a></li>
                <li class="nav-item"><a class="nav-link <?= $activeNav === 'ad_revenue' ? 'active fw-bold' : '' ?>" href="ad_revenue.php">Ad Revenue</a></li>
                <li class="nav-item"><a class="nav-link <?= $activeNav === 'settings' ? 'active fw-bold' : '' ?>" href="settings.php">Settings</a></li>
            </ul>
            <a href="logout.php" class="btn btn-outline-light btn-sm">Logout</a>
        </div>
    </div>
</nav>
<div class="container-fluid px-4 pb-5">
