'use strict';

const state = {
  models: [],
  selectedModelId: null,
  selectedBackend: 'cpu',
  thinkingBudget: 50,
  activeDownloads: new Set(), // model ids currently being polled for download progress
  passwordSet: false, // gates the Start button — the server refuses to start without one anyway
};

function $(id) { return document.getElementById(id); }

function formatBytes(n) {
  if (!n || n <= 0) return '0 MB';
  const mb = n / 1_000_000;
  return mb >= 1000 ? (mb / 1000).toFixed(2) + ' GB' : mb.toFixed(1) + ' MB';
}

function formatUptime(ms) {
  if (!ms || ms < 0) return '—';
  const totalSeconds = Math.floor(ms / 1000);
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

// Only touches the DOM (and thus only lets a screen reader announce something) when the text
// actually changed — avoids chatty live-region announcements from a naive 1Hz full refresh.
function updateIfChanged(id, text) {
  const el = $(id);
  if (el.textContent !== text) el.textContent = text;
}

function showError(message) {
  const el = $('error-region');
  el.textContent = '';
  // Small delay so repeating the same error text still registers as a fresh change for
  // assistive tech (two distinct mutations instead of one no-op).
  setTimeout(() => { el.textContent = message; }, 30);
}

function clearError() {
  $('error-region').textContent = '';
}

async function loadModels() {
  const res = await fetch('/api/models');
  state.models = await res.json();
  if (!state.selectedModelId && state.models.length > 0) {
    state.selectedModelId = state.models[0].id;
  }
  renderModelList();
}

function renderModelList() {
  const container = $('model-list');
  container.innerHTML = '';
  for (const model of state.models) {
    const item = document.createElement('div');
    item.className = 'model-item';

    const metaId = `model-meta-${model.id}`;
    const statusId = `model-status-${model.id}`;

    const label = document.createElement('label');
    label.className = 'radio-label';
    const radio = document.createElement('input');
    radio.type = 'radio';
    radio.name = 'model';
    radio.value = model.id;
    radio.checked = model.id === state.selectedModelId;
    radio.setAttribute('aria-describedby', `${metaId} ${statusId}`);
    radio.addEventListener('change', () => { state.selectedModelId = model.id; });
    label.appendChild(radio);
    label.appendChild(document.createTextNode(` ${model.displayName}`));
    item.appendChild(label);

    const meta = document.createElement('div');
    meta.className = 'model-meta';
    meta.id = metaId;
    meta.textContent = `${model.description} (${formatBytes(model.modelSizeBytes + model.mmprojSizeBytes)})`;
    item.appendChild(meta);

    const status = document.createElement('span');
    status.id = statusId;
    status.className = 'model-status' + (model.downloaded ? ' downloaded' : '');
    status.textContent = model.downloaded ? 'Downloaded' : 'Not downloaded';
    item.appendChild(status);

    if (!model.downloaded) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.textContent = 'Download';
      btn.addEventListener('click', () => startDownload(model.id));
      item.appendChild(document.createTextNode(' '));
      item.appendChild(btn);

      const progressRow = document.createElement('div');
      progressRow.className = 'progress-row';
      progressRow.id = `progress-row-${model.id}`;
      progressRow.hidden = true;
      const progress = document.createElement('progress');
      progress.max = 100;
      progress.value = 0;
      progress.id = `progress-${model.id}`;
      const progressText = document.createElement('span');
      progressText.id = `progress-text-${model.id}`;
      progressText.setAttribute('aria-live', 'polite');
      progressRow.appendChild(progress);
      progressRow.appendChild(progressText);
      item.appendChild(progressRow);
    }

    container.appendChild(item);
  }
}

async function startDownload(modelId) {
  clearError();
  const res = await fetch(`/api/models/${encodeURIComponent(modelId)}/download`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ backend: state.selectedBackend }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    showError(body.model_response || 'Failed to start download.');
    return;
  }
  state.activeDownloads.add(modelId);
  const row = $(`progress-row-${modelId}`);
  if (row) row.hidden = false;
}

async function pollDownloads() {
  if (state.activeDownloads.size === 0) return;
  for (const modelId of Array.from(state.activeDownloads)) {
    const res = await fetch(`/api/models/${encodeURIComponent(modelId)}/download/status`);
    const s = await res.json();
    const progress = $(`progress-${modelId}`);
    const text = $(`progress-text-${modelId}`);
    if (s.state === 'downloading') {
      const pct = s.totalBytes > 0 ? Math.round((s.bytesDownloaded / s.totalBytes) * 100) : 0;
      if (progress) progress.value = pct;
      const label = `Downloading ${s.currentFile || ''}: ${pct}% (${formatBytes(s.bytesDownloaded)} / ${formatBytes(s.totalBytes)})`;
      if (text && text.textContent !== label) text.textContent = label;
    } else if (s.state === 'done') {
      state.activeDownloads.delete(modelId);
      if (text) text.textContent = 'Download complete.';
      await loadModels();
    } else if (s.state === 'error') {
      state.activeDownloads.delete(modelId);
      showError(`Download failed: ${s.error || 'unknown error'}`);
      if (text) text.textContent = 'Download failed.';
    }
  }
}

async function probeGpu() {
  try {
    const res = await fetch('/api/gpu/probe');
    const s = await res.json();
    $('gpu-hint').textContent = s.available ? `GPU detected: ${s.raw}` : `No compatible GPU detected yet (${s.raw})`;
  } catch (e) {
    $('gpu-hint').textContent = '';
  }
}

async function loadPasswordStatus() {
  try {
    const res = await fetch('/api/auth/status');
    const s = await res.json();
    state.passwordSet = s.enabled;
    $('password-status').textContent = s.enabled
      ? 'Password is set — this server can start, and every request/response is encrypted with it.'
      : 'No password set yet — set one below before you can start the server.';
    $('clear-password-btn').disabled = !s.enabled;
  } catch (e) {
    $('password-status').textContent = '';
  }
}

async function pollStatus() {
  const res = await fetch('/api/status');
  const s = await res.json();

  updateIfChanged('stat-status', s.running ? 'Running' : 'Stopped');
  updateIfChanged('stat-model', s.modelId ? `${s.modelDisplayName || s.modelId} / ${s.backend}` : '—');
  updateIfChanged('stat-thinking-budget', s.running && s.reasoningBudget !== null && s.reasoningBudget !== undefined ? String(s.reasoningBudget) : '—');
  // Note: this is the control panel's own address (where /process.php is actually routed) —
  // NOT s.port, which is llama-server's internal port used only between this app and the engine.
  updateIfChanged('stat-address', s.running ? location.host : '—');
  updateIfChanged('stat-uptime', formatUptime(s.uptimeMs));
  updateIfChanged('stat-requests', `${s.stats.requestsSucceeded} / ${s.stats.requestsFailed}`);
  updateIfChanged('stat-gen-time', `${(s.stats.totalGenerationMs / 1000).toFixed(1)}s`);
  updateIfChanged('stat-tokens', `${s.stats.totalPromptTokens} / ${s.stats.totalCompletionTokens}`);
  updateIfChanged('stat-tps', s.stats.tokensPerSecond.toFixed(1));

  $('start-btn').setAttribute('aria-pressed', String(s.running));
  // Gated on both: never startable mid-run, and never startable without a password (the server
  // refuses that anyway — this just avoids a round-trip to discover it).
  $('start-btn').disabled = s.running || !state.passwordSet;
  $('stop-btn').disabled = !s.running;
}

function setupControls() {
  $('backend-select').addEventListener('change', () => {
    state.selectedBackend = $('backend-select').value;
    if (state.selectedBackend === 'gpu') probeGpu();
    else $('gpu-hint').textContent = '';
  });

  $('thinking-budget-select').addEventListener('change', () => {
    state.thinkingBudget = Number($('thinking-budget-select').value);
  });

  $('set-password-btn').addEventListener('click', async () => {
    clearError();
    const value = $('password-input').value;
    if (!value) { showError('Enter a password first, or use "Remove password" to disable protection.'); return; }
    const res = await fetch('/api/auth/set', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: value }),
    });
    const body = await res.json();
    if (!res.ok) {
      showError(body.model_response || 'Failed to set password.');
      return;
    }
    $('password-input').value = '';
    await loadPasswordStatus();
  });

  $('clear-password-btn').addEventListener('click', async () => {
    clearError();
    await fetch('/api/auth/clear', { method: 'POST' });
    await loadPasswordStatus();
  });

  $('start-btn').addEventListener('click', async () => {
    clearError();
    if (!state.selectedModelId) { showError('Choose a model first.'); return; }
    try {
      const res = await fetch('/api/server/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ modelId: state.selectedModelId, backend: state.selectedBackend, reasoningBudget: state.thinkingBudget }),
      });
      const body = await res.json();
      if (!res.ok) {
        showError(body.model_response || 'Failed to start server.');
      } else {
        await pollStatus();
      }
    } catch (e) {
      showError('Could not reach the control panel server.');
    }
  });

  $('stop-btn').addEventListener('click', async () => {
    clearError();
    await fetch('/api/server/stop', { method: 'POST' });
    await pollStatus();
  });

  $('quit-btn').addEventListener('click', async () => {
    try {
      await fetch('/api/quit', { method: 'POST' });
    } catch (e) {
      // Expected — the process exits shortly after responding, the connection may drop first.
    }
    $('stat-status').textContent = 'Application closed.';
  });
}

setupControls();
loadModels();
probeGpu();
loadPasswordStatus();
pollStatus();
setInterval(pollStatus, 1000);
setInterval(pollDownloads, 1000);
