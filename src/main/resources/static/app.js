const state = {
  snapshot: null,
  sourceVisibility: {},
  stream: null,
  initialFetchTimer: null,
  selectedIncidentId: null,
  search: "",
  severityFilter: "all",
  statusFilter: "all",
  runtimeFilter: "all",
};

const SOURCE_BATCH_SIZE = 20;
const SEVERITY_WEIGHT = { HIGH: 3, MEDIUM: 2, LOW: 1, UNKNOWN: 0 };
const STATUS_WEIGHT = { REGRESSED: 5, OPEN: 4, ACKNOWLEDGED: 3, SUPPRESSED: 2, RESOLVED: 1, CLOSED: 0 };

const metricsGrid = document.getElementById("metricsGrid");
const insightStrip = document.getElementById("insightStrip");
const runtimePanels = document.getElementById("runtimePanels");
const runtimeFilterBar = document.getElementById("runtimeFilterBar");
const jobsList = document.getElementById("jobsList");
const incidentGrid = document.getElementById("incidentGrid");
const lastUpdated = document.getElementById("lastUpdated");
const refreshButton = document.getElementById("refreshButton");
const toast = document.getElementById("toast");
const searchInput = document.getElementById("searchInput");
const severityFilter = document.getElementById("severityFilter");
const statusFilter = document.getElementById("statusFilter");
const resetFiltersButton = document.getElementById("resetFiltersButton");

refreshButton.addEventListener("click", () => loadDashboard(true));
searchInput.addEventListener("input", (event) => {
  state.search = event.target.value.trim().toLowerCase();
  render(state.snapshot);
});
severityFilter.addEventListener("change", (event) => {
  state.severityFilter = event.target.value;
  render(state.snapshot);
});
statusFilter.addEventListener("change", (event) => {
  state.statusFilter = event.target.value;
  render(state.snapshot);
});
resetFiltersButton.addEventListener("click", () => {
  state.search = "";
  state.severityFilter = "all";
  state.statusFilter = "all";
  state.runtimeFilter = "all";
  searchInput.value = "";
  severityFilter.value = "all";
  statusFilter.value = "all";
  render(state.snapshot);
});

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

    if (action === "toggle-runtime-filter") {
      state.runtimeFilter = state.runtimeFilter === runtime ? "all" : runtime;
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
  syncSourceVisibility(snapshot.runtimes || []);
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
  if (!snapshot) {
    return;
  }

  const filtered = buildFilteredView(snapshot);
  renderMetrics(snapshot.metrics, filtered);
  renderInsights(snapshot, filtered);
  renderRuntimeFilters(snapshot.runtimes || [], filtered);
  renderRuntimes(filtered.runtimes);
  renderJobs(filtered.jobs);
  renderIncidents(filtered.incidents);
}

function buildFilteredView(snapshot) {
  const runtimeFilter = state.runtimeFilter;
  const search = state.search;
  const runtimes = (snapshot.runtimes || [])
    .map((runtime) => {
      const filteredSources = (runtime.sources || []).filter((source) => matchesSource(runtime, source, search));
      const runtimeMatches = matchesText(runtime.runtimeKey, search);
      const shouldKeep = runtimeFilter === "all" || runtime.runtimeKey === runtimeFilter;
      if (!shouldKeep) {
        return null;
      }
      if (search && !runtimeMatches && filteredSources.length === 0) {
        return null;
      }
      return {
        ...runtime,
        sources: search ? filteredSources : (runtime.sources || []),
        filteredSourceCount: filteredSources.length,
      };
    })
    .filter(Boolean);

  const jobs = (snapshot.jobs || []).filter((job) => {
    if (runtimeFilter !== "all" && job.runtimeKey !== runtimeFilter) {
      return false;
    }
    if (!search) {
      return true;
    }
    return matchesText(job.runtimeKey, search) || matchesText(job.command, search) || (job.sourceIds || []).some((sourceId) => matchesText(sourceId, search));
  });

  const incidents = (snapshot.incidents || [])
    .filter((incident) => {
      if (state.severityFilter !== "all" && incident.severity !== state.severityFilter) {
        return false;
      }
      if (state.statusFilter !== "all" && incident.status !== state.statusFilter) {
        return false;
      }
      if (runtimeFilter !== "all" && !belongsToRuntime(snapshot.runtimes || [], runtimeFilter, incident.sourceId, incident.sourceName)) {
        return false;
      }
      if (!search) {
        return true;
      }
      return [
        incident.title,
        incident.summary,
        incident.sourceName,
        incident.sourceId,
        incident.status,
        incident.severity,
      ].some((value) => matchesText(value, search));
    })
    .map((incident) => ({ ...incident, priorityScore: incidentPriorityScore(incident) }))
    .sort((left, right) => right.priorityScore - left.priorityScore || new Date(right.lastSeenAt || 0) - new Date(left.lastSeenAt || 0));

  return { runtimes, jobs, incidents };
}

function renderMetrics(metrics, filtered) {
  const cards = [
    {
      label: "Connected runtimes",
      value: metrics.connectedRuntimes,
      footer: `${filtered.runtimes.length} visible in current view`,
    },
    {
      label: "Active tail jobs",
      value: metrics.activeJobs,
      footer: `${filtered.jobs.length} shown after filters`,
    },
    {
      label: "Open incidents",
      value: metrics.openIncidents,
      footer: `${filtered.incidents.filter((incident) => incident.status === "OPEN").length} open in focus`,
    },
    {
      label: "Visible incidents",
      value: filtered.incidents.length,
      footer: searchOrFilterApplied() ? "Filtered tactical queue" : "Full tactical queue",
    },
  ];

  metricsGrid.innerHTML = cards.map((card) => `
    <article class="metric-card">
      <p class="meta-label">${card.label}</p>
      <div class="metric-value">${card.value}</div>
      <p class="metric-footer">${card.footer}</p>
    </article>
  `).join("");
}

function renderInsights(snapshot, filtered) {
  const hottestRuntime = filtered.runtimes
    .map((runtime) => ({
      runtimeKey: runtime.runtimeKey,
      score: (runtime.activeSources || 0) * 2 + ((runtime.sources || []).length || 0),
      runningSources: runtime.runningSources,
      activeSources: runtime.activeSources,
    }))
    .sort((left, right) => right.score - left.score)[0];

  const criticalIncident = filtered.incidents[0];
  const coverage = snapshot.metrics.connectedRuntimes === 0
    ? "No runtimes connected"
    : `${sum(filtered.runtimes.map((runtime) => runtime.activeSources || 0))} active tails across ${sum(filtered.runtimes.map((runtime) => runtime.runningSources || 0))} running sources`;
  const pressure = filtered.incidents.length
    ? `${filtered.incidents.filter((incident) => incident.severity === "HIGH").length} high-severity incidents in queue`
    : "No incidents in the current view";

  const cards = [
    {
      kicker: "Runtime focus",
      title: hottestRuntime ? hottestRuntime.runtimeKey.toUpperCase() : "No active runtime",
      body: hottestRuntime
        ? `${hottestRuntime.activeSources}/${hottestRuntime.runningSources} sources actively tailed in the busiest runtime.`
        : "No runtime activity is visible yet.",
    },
    {
      kicker: "Coverage",
      title: coverage,
      body: "This view emphasizes where operators already have active stream coverage.",
    },
    {
      kicker: "Priority queue",
      title: criticalIncident ? criticalIncident.title : "No incident pressure",
      body: criticalIncident
        ? `${pressure}. Most urgent source: ${criticalIncident.sourceName || criticalIncident.sourceId}.`
        : "The incident queue is currently clear.",
    },
  ];

  insightStrip.innerHTML = cards.map((card) => `
    <article class="insight-card">
      <p class="panel-kicker">${escapeHtml(card.kicker)}</p>
      <h3>${escapeHtml(card.title)}</h3>
      <p class="insight-body">${escapeHtml(card.body)}</p>
    </article>
  `).join("");
}

function renderRuntimeFilters(runtimes, filtered) {
  const chips = [
    { key: "all", label: "All runtimes", count: filtered.runtimes.length },
    ...runtimes.map((runtime) => ({
      key: runtime.runtimeKey,
      label: runtime.runtimeKey.toUpperCase(),
      count: runtime.activeSources || 0,
    })),
  ];

  runtimeFilterBar.innerHTML = chips.map((chip) => `
    <button class="runtime-filter ${state.runtimeFilter === chip.key ? "active" : ""}" data-action="toggle-runtime-filter" data-runtime="${escapeAttribute(chip.key)}">
      <span>${escapeHtml(chip.label)}</span>
      <span class="mono">${chip.count}</span>
    </button>
  `).join("");
}

function renderRuntimes(runtimes) {
  if (!runtimes.length) {
    runtimePanels.innerHTML = emptyState("No runtimes match the current filters.");
    return;
  }

  runtimePanels.innerHTML = runtimes.map((runtime) => {
    const sources = runtime.sources || [];
    const visibleCount = Math.min(state.sourceVisibility[runtime.runtimeKey] || SOURCE_BATCH_SIZE, sources.length || SOURCE_BATCH_SIZE);
    const visibleSources = sources.slice(0, visibleCount);
    const idleSources = Math.max((runtime.runningSources || 0) - (runtime.activeSources || 0), 0);
    const sourceMarkup = sources.length
      ? visibleSources.map((source) => `
          <div class="source-row">
            <div class="source-main">
              <strong>${escapeHtml(source.name || source.id)}</strong>
              <span class="mono muted">${escapeHtml(source.id)}</span>
            </div>
            <div class="source-stats">
              <div class="badge-row">
                <span class="badge ${source.active ? "badge-active" : "badge-low"}">${source.active ? "tailing" : "idle"}</span>
                <span class="badge badge-${normalizeBadge(source.status)}">${escapeHtml(source.status)}</span>
              </div>
              <div class="card-actions">
                <button class="button button-secondary" data-action="tail-one" data-runtime="${runtime.runtimeKey}" data-source-id="${escapeAttribute(source.id)}" ${source.active ? "disabled" : ""}>
                  Tail source
                </button>
              </div>
            </div>
          </div>
        `).join("")
      : emptyState("No running sources match this view.");

    const hasMoreSources = sources.length > visibleCount;
    const canCollapse = sources.length > SOURCE_BATCH_SIZE && visibleCount > SOURCE_BATCH_SIZE;

    return `
      <article class="runtime-card">
        <div class="runtime-card-head">
          <div class="runtime-main">
            <p class="panel-kicker">${escapeHtml(runtime.runtimeKey)}</p>
            <h3>${runtime.runningSources} running sources</h3>
          </div>
          <div class="runtime-actions">
            <button class="button button-primary" data-action="tail-all" data-runtime="${runtime.runtimeKey}">
              Tail all
            </button>
          </div>
        </div>

        <div class="runtime-summary">
          <div class="summary-cell">
            <p class="meta-label">Active tails</p>
            <div class="summary-value">${runtime.activeSources}</div>
          </div>
          <div class="summary-cell">
            <p class="meta-label">Idle sources</p>
            <div class="summary-value">${idleSources}</div>
          </div>
          <div class="summary-cell">
            <p class="meta-label">Visible now</p>
            <div class="summary-value">${sources.length}</div>
          </div>
        </div>

        <div class="badge-row">
          <span class="badge badge-running">${runtime.activeSources} active</span>
          <span class="badge badge-low">${idleSources} idle</span>
          <span class="badge badge-low">${visibleSources.length}/${sources.length || 0} shown</span>
        </div>

        <div class="source-list">
          ${sourceMarkup}
        </div>

        <div class="runtime-actions">
          ${hasMoreSources ? `<button class="button button-secondary" data-action="show-more-sources" data-runtime="${runtime.runtimeKey}" data-visible-count="${visibleCount}">Show ${Math.min(SOURCE_BATCH_SIZE, sources.length - visibleCount)} more</button>` : ""}
          ${canCollapse ? `<button class="button button-secondary" data-action="show-less-sources" data-runtime="${runtime.runtimeKey}">Collapse list</button>` : ""}
        </div>
      </article>
    `;
  }).join("");
}

function renderJobs(jobs) {
  if (!jobs.length) {
    jobsList.innerHTML = emptyState("No tail jobs are running in the current view.");
    return;
  }

  jobsList.innerHTML = jobs.map((job) => `
    <article class="job-card">
      <div class="job-card-head">
        <div class="job-main">
          <p class="panel-kicker">${escapeHtml(job.runtimeKey)}</p>
          <h3>#${job.id} ${escapeHtml(job.command)}</h3>
        </div>
        <div class="job-actions">
          <span class="badge badge-running">${escapeHtml(job.status)}</span>
          <button class="button button-danger" data-action="stop-job" data-job-id="${job.id}">Stop</button>
        </div>
      </div>
      <p class="job-body">${job.sourceIds.length} source${job.sourceIds.length === 1 ? "" : "s"} attached</p>
      <div class="badge-row">
        ${job.sourceIds.map((sourceId) => `<span class="badge badge-low mono">${escapeHtml(sourceId)}</span>`).join("")}
      </div>
    </article>
  `).join("");
}

function renderIncidents(incidents) {
  if (!incidents.length) {
    incidentGrid.innerHTML = emptyState("No incidents match the current filters.");
    return;
  }

  incidentGrid.innerHTML = incidents.map((incident, index) => `
    <article class="incident-card ${state.selectedIncidentId === incident.id ? "incident-card-selected" : ""}" data-incident-card data-incident-id="${escapeAttribute(incident.id)}">
      <div class="incident-card-head">
        <div class="incident-main">
          <span class="incident-priority">Priority ${index + 1}</span>
          <p class="panel-kicker">${escapeHtml(incident.sourceName)}</p>
          <h3>${escapeHtml(incident.title)}</h3>
        </div>
        <div class="badge-row">
          <span class="badge badge-${normalizeBadge(incident.severity)}">${escapeHtml(incident.severity)}</span>
          <span class="badge badge-${normalizeBadge(incident.status)}">${escapeHtml(incident.status)}</span>
        </div>
      </div>

      <p class="incident-summary">${escapeHtml(incident.summary || "No summary available yet.")}</p>

      <div class="incident-meta">
        <div class="badge-row">
          <span class="badge badge-low mono">${escapeHtml(incident.sourceId)}</span>
          <span class="badge badge-low">${formatInstant(incident.lastSeenAt)}</span>
        </div>
        <div class="card-actions">
          <button class="button button-secondary" data-action="select-incident" data-incident-id="${escapeAttribute(incident.id)}">
            ${state.selectedIncidentId === incident.id ? "Close panel" : "Act on incident"}
          </button>
        </div>
      </div>

      ${state.selectedIncidentId === incident.id ? `
        <div class="incident-action-panel">
          <label class="field">
            <span class="meta-label">Event type</span>
            <select class="input" data-incident-event-type>
              ${(incident.availableEventTypes || []).map((type) => `<option value="${escapeAttribute(type)}">${escapeHtml(type)}</option>`).join("")}
            </select>
          </label>
          <label class="field">
            <span class="meta-label">Operator note</span>
            <textarea class="input" rows="4" data-incident-note placeholder="Optional note for the incident event"></textarea>
          </label>
          <div class="card-actions">
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

function incidentPriorityScore(incident) {
  return (SEVERITY_WEIGHT[incident.severity] || 0) * 10 + (STATUS_WEIGHT[incident.status] || 0);
}

function belongsToRuntime(runtimes, runtimeKey, sourceId, sourceName) {
  const runtime = (runtimes || []).find((item) => item.runtimeKey === runtimeKey);
  if (!runtime) {
    return false;
  }
  return (runtime.sources || []).some((source) => source.id === sourceId || source.name === sourceName);
}

function matchesSource(runtime, source, search) {
  if (!search) {
    return true;
  }
  return [
    runtime.runtimeKey,
    source.id,
    source.name,
    source.status,
    source.active ? "tailing" : "idle",
  ].some((value) => matchesText(value, search));
}

function matchesText(value, search) {
  return String(value || "").toLowerCase().includes(search);
}

function searchOrFilterApplied() {
  return Boolean(state.search || state.severityFilter !== "all" || state.statusFilter !== "all" || state.runtimeFilter !== "all");
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

function sum(values) {
  return values.reduce((total, value) => total + Number(value || 0), 0);
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
