const state = {
  snapshot: null,
  sourceVisibility: {},
  stream: null,
  initialFetchTimer: null,
  selectedIncidentId: null,
};

const SOURCE_BATCH_SIZE = 20;

const metricsGrid = document.getElementById("metricsGrid");
const runtimePanels = document.getElementById("runtimePanels");
const jobsList = document.getElementById("jobsList");
const incidentGrid = document.getElementById("incidentGrid");
const lastUpdated = document.getElementById("lastUpdated");
const refreshButton = document.getElementById("refreshButton");
const toast = document.getElementById("toast");

refreshButton.addEventListener("click", () => loadDashboard(true));

document.addEventListener("click", async (event) => {
  const button = event.target.closest("[data-action]");
  if (!button) {
    return;
  }

  const action = button.dataset.action;
  const runtime = button.dataset.runtime;
  const sourceId = button.dataset.sourceId;
  const jobId = button.dataset.jobId;
  const incidentId = button.dataset.incidentId;
  const visibleCount = Number(button.dataset.visibleCount || SOURCE_BATCH_SIZE);

  try {
    if (action === "show-more-sources") {
      state.sourceVisibility[runtime] = visibleCount + SOURCE_BATCH_SIZE;
      render(state.snapshot);
      return;
    }

    if (action === "show-less-sources") {
      state.sourceVisibility[runtime] = SOURCE_BATCH_SIZE;
      render(state.snapshot);
      return;
    }

    if (action === "select-incident") {
      state.selectedIncidentId = state.selectedIncidentId === incidentId ? null : incidentId;
      render(state.snapshot);
      return;
    }

    if (action === "tail-all") {
      await api(`/api/runtimes/${encodeURIComponent(runtime)}/tail-all`, { method: "POST" });
      showToast(`Started tail-all for ${runtime}`);
    }

    if (action === "tail-one") {
      const params = new URLSearchParams({ sourceId });
      await api(`/api/runtimes/${encodeURIComponent(runtime)}/tail-one?${params.toString()}`, { method: "POST" });
      showToast(`Started tail-one for ${sourceId}`);
    }

    if (action === "stop-job") {
      await api(`/api/jobs/${jobId}`, { method: "DELETE" });
      showToast(`Stopped job #${jobId}`);
    }

    if (action === "apply-incident-event") {
      const card = button.closest("[data-incident-card]");
      const eventType = card.querySelector("[data-incident-event-type]").value;
      const note = card.querySelector("[data-incident-note]").value;
      const params = new URLSearchParams({ type: eventType });
      if (note.trim()) {
        params.set("note", note.trim());
      }
      const updatedIncident = await api(`/api/incidents/${encodeURIComponent(incidentId)}/event-type?${params.toString()}`, { method: "POST" });
      replaceIncident(updatedIncident);
      state.selectedIncidentId = null;
      render(state.snapshot);
      showToast(`Incident updated to ${eventType}`);
      return;
    }

    await loadDashboard(false);
  } catch (error) {
    showToast(error.message || "Request failed", true);
  }
});

async function loadDashboard(announce) {
  try {
    const snapshot = await api("/api/dashboard");
    applySnapshot(snapshot);
    if (announce) {
      showToast("Dashboard refreshed");
    }
  } catch (error) {
    showToast(error.message || "Unable to load dashboard", true);
  }
}

function connectDashboardStream() {
  if (state.stream) {
    state.stream.close();
  }

  window.clearTimeout(state.initialFetchTimer);
  state.initialFetchTimer = window.setTimeout(() => {
    if (!state.snapshot) {
      loadDashboard(false);
    }
  }, 1200);

  const stream = new EventSource("/api/dashboard/stream");
  state.stream = stream;

  stream.onmessage = (event) => {
    try {
      window.clearTimeout(state.initialFetchTimer);
      applySnapshot(JSON.parse(event.data));
    } catch (_error) {
      showToast("Invalid live dashboard update", true);
    }
  };

  stream.onerror = () => {
    lastUpdated.textContent = "Live sync interrupted, retrying";
  };
}

function applySnapshot(snapshot) {
  state.snapshot = snapshot;
  syncSourceVisibility(snapshot.runtimes);
  render(snapshot);
  const now = new Date();
  lastUpdated.textContent = `Live ${now.toLocaleTimeString()}`;
}

function syncSourceVisibility(runtimes) {
  runtimes.forEach((runtime) => {
    const current = state.sourceVisibility[runtime.runtimeKey];
    const total = (runtime.sources || []).length;
    if (!current) {
      state.sourceVisibility[runtime.runtimeKey] = SOURCE_BATCH_SIZE;
      return;
    }
    state.sourceVisibility[runtime.runtimeKey] = Math.min(Math.max(current, SOURCE_BATCH_SIZE), Math.max(total, SOURCE_BATCH_SIZE));
  });
}

function render(snapshot) {
  renderMetrics(snapshot.metrics);
  renderRuntimes(snapshot.runtimes);
  renderJobs(snapshot.jobs);
  renderIncidents(snapshot.incidents);
}

function renderMetrics(metrics) {
  const cards = [
    ["Connected runtimes", metrics.connectedRuntimes],
    ["Active tail jobs", metrics.activeJobs],
    ["Open incidents", metrics.openIncidents],
    ["Visible incidents", metrics.visibleIncidents],
  ];

  metricsGrid.innerHTML = cards.map(([label, value]) => `
    <article class="metric-card">
      <p class="meta-label">${label}</p>
      <strong>${value}</strong>
      <span>Current platform signal</span>
    </article>
  `).join("");
}

function renderRuntimes(runtimes) {
  if (!runtimes.length) {
    runtimePanels.innerHTML = emptyState("No runtimes are registered.");
    return;
  }

  const sections = runtimes.map((runtime) => {
    const sources = runtime.sources || [];
    const visibleCount = Math.min(state.sourceVisibility[runtime.runtimeKey] || SOURCE_BATCH_SIZE, sources.length || SOURCE_BATCH_SIZE);
    const visibleSources = sources.slice(0, visibleCount);
    const sourceMarkup = sources.length
      ? visibleSources.map((source) => `
          <div class="source-row">
            <div class="source-main">
              <strong>${escapeHtml(source.name || source.id)}</strong>
              <span class="mono muted">${escapeHtml(source.id)}</span>
            </div>
            <div>
              <div class="badge-row">
                <span class="badge ${source.active ? "badge-active" : "badge-low"}">${source.active ? "tailing" : "idle"}</span>
                <span class="badge badge-${normalizeBadge(source.status)}">${escapeHtml(source.status)}</span>
              </div>
              <div class="runtime-actions" style="margin-top:0.75rem">
                <button class="button button-secondary" data-action="tail-one" data-runtime="${runtime.runtimeKey}" data-source-id="${escapeAttribute(source.id)}" ${source.active ? "disabled" : ""}>
                  Tail source
                </button>
              </div>
            </div>
          </div>
        `).join("")
      : emptyState("No running sources found.");
    const hasMoreSources = sources.length > visibleCount;
    const canCollapse = sources.length > SOURCE_BATCH_SIZE && visibleCount > SOURCE_BATCH_SIZE;
    const sourceControls = sources.length
      ? `
          <div class="runtime-actions" style="margin-top:1rem">
            ${hasMoreSources ? `<button class="button button-secondary" data-action="show-more-sources" data-runtime="${runtime.runtimeKey}" data-visible-count="${visibleCount}">Show ${Math.min(SOURCE_BATCH_SIZE, sources.length - visibleCount)} more</button>` : ""}
            ${canCollapse ? `<button class="button button-secondary" data-action="show-less-sources" data-runtime="${runtime.runtimeKey}">Show less</button>` : ""}
          </div>
        `
      : "";

    return `
      <article class="runtime-card">
        <div class="runtime-card-head">
          <div>
            <p class="panel-kicker">${runtime.runtimeKey}</p>
            <h3>${runtime.runningSources} running sources</h3>
          </div>
          <div class="runtime-actions">
            <button class="button button-primary" data-action="tail-all" data-runtime="${runtime.runtimeKey}">
              Tail all
            </button>
          </div>
        </div>
        <div class="badge-row">
          <span class="badge badge-running">${runtime.activeSources} active</span>
          <span class="badge badge-low">${runtime.runningSources - runtime.activeSources} idle</span>
          ${sources.length ? `<span class="badge badge-low">${visibleSources.length}/${sources.length} shown</span>` : ""}
        </div>
        <div class="source-list" style="margin-top:1rem">
          ${sourceMarkup}
        </div>
        ${sourceControls}
      </article>
    `;
  });

  runtimePanels.innerHTML = sections.join("");
}

function renderJobs(jobs) {
  if (!jobs.length) {
    jobsList.innerHTML = emptyState("No tail jobs are running.");
    return;
  }

  jobsList.innerHTML = jobs.map((job) => `
    <article class="job-card">
      <div class="job-card-head">
        <div>
          <p class="panel-kicker">${job.runtimeKey}</p>
          <h3>#${job.id} ${job.command}</h3>
        </div>
        <div class="job-actions">
          <span class="badge badge-running">${job.status}</span>
          <button class="button button-danger" data-action="stop-job" data-job-id="${job.id}">
            Stop
          </button>
        </div>
      </div>
      <p>${job.sourceIds.length} source${job.sourceIds.length === 1 ? "" : "s"} attached</p>
      <div class="badge-row">
        ${job.sourceIds.map((sourceId) => `<span class="badge badge-low mono">${escapeHtml(sourceId)}</span>`).join("")}
      </div>
    </article>
  `).join("");
}

function renderIncidents(incidents) {
  if (!incidents.length) {
    incidentGrid.innerHTML = emptyState("No incidents are available yet.");
    return;
  }

  incidentGrid.innerHTML = incidents.map((incident) => `
    <article class="incident-card ${state.selectedIncidentId === incident.id ? "incident-card-selected" : ""}" data-incident-card data-incident-id="${escapeAttribute(incident.id)}">
      <div class="incident-card-head">
        <div>
          <p class="panel-kicker">${escapeHtml(incident.sourceName)}</p>
          <h3>${escapeHtml(incident.title)}</h3>
        </div>
        <div class="badge-row">
          <span class="badge badge-${normalizeBadge(incident.severity)}">${escapeHtml(incident.severity)}</span>
          <span class="badge badge-${normalizeBadge(incident.status)}">${escapeHtml(incident.status)}</span>
        </div>
      </div>
      <p>${escapeHtml(incident.summary || "No summary available yet.")}</p>
      <div class="badge-row">
        <span class="badge badge-low mono">${escapeHtml(incident.sourceId)}</span>
        <span class="badge badge-low">${formatInstant(incident.lastSeenAt)}</span>
      </div>
      <div class="runtime-actions" style="margin-top:1rem">
        <button class="button button-secondary" data-action="select-incident" data-incident-id="${escapeAttribute(incident.id)}">
          ${state.selectedIncidentId === incident.id ? "Cancel" : "Select"}
        </button>
      </div>
      ${state.selectedIncidentId === incident.id ? `
        <div class="incident-action-panel">
          <label class="incident-field">
            <span class="meta-label">Event type</span>
            <select class="incident-select" data-incident-event-type>
              ${(incident.availableEventTypes || []).map((type) => `<option value="${escapeAttribute(type)}">${escapeHtml(type)}</option>`).join("")}
            </select>
          </label>
          <label class="incident-field">
            <span class="meta-label">Note</span>
            <textarea class="incident-note" rows="3" data-incident-note placeholder="Optional note for the incident event"></textarea>
          </label>
          <div class="runtime-actions">
            <button class="button button-primary" data-action="apply-incident-event" data-incident-id="${escapeAttribute(incident.id)}">
              Apply event
            </button>
          </div>
        </div>
      ` : ""}
    </article>
  `).join("");
}

function replaceIncident(updatedIncident) {
  if (!state.snapshot) {
    return;
  }

  const incidents = state.snapshot.incidents || [];
  const nextIncidents = incidents.map((incident) => incident.id === updatedIncident.id ? updatedIncident : incident);
  const openIncidents = nextIncidents.filter((incident) => incident.status === "OPEN").length;

  state.snapshot = {
    ...state.snapshot,
    incidents: nextIncidents,
    metrics: {
      ...state.snapshot.metrics,
      openIncidents,
      visibleIncidents: nextIncidents.length,
    },
  };
}

async function api(url, options = {}) {
  const response = await fetch(url, options);
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(payload.error || `HTTP ${response.status}`);
  }
  return payload;
}

function normalizeBadge(value) {
  return String(value || "unknown").trim().toLowerCase().replace(/[^a-z0-9]+/g, "-");
}

function formatInstant(value) {
  if (!value) {
    return "No timestamp";
  }
  return new Date(value).toLocaleString();
}

function emptyState(message) {
  return `<div class="empty-state">${escapeHtml(message)}</div>`;
}

function showToast(message, isError = false) {
  toast.classList.remove("hidden");
  toast.textContent = message;
  toast.classList.toggle("badge-error", isError);
  window.clearTimeout(showToast.timeoutId);
  showToast.timeoutId = window.setTimeout(() => toast.classList.add("hidden"), 2600);
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\"", "&quot;")
    .replaceAll("'", "&#39;");
}

function escapeAttribute(value) {
  return escapeHtml(value);
}

connectDashboardStream();
