// Creates helpful indexes for PoC (TTL + query helpers)
db = db.getSiblingDB('logguardian');

// Log events collection
db.createCollection('log_events');

// TTL index on ingestedAt (defaults to 7 days retention; adjust in app if needed)
db.log_events.createIndex(
  { ingestedAt: 1 },
  { expireAfterSeconds: 60 * 60 * 24 * 7, name: "ttl_ingestedAt_7d" }
);

// Query indexes
db.log_events.createIndex({ service: 1, ingestedAt: -1 }, { name: "svc_time" });
db.log_events.createIndex({ fingerprint: 1, ingestedAt: -1 }, { name: "fp_time" });
db.log_events.createIndex({ namespace: 1, pod: 1, ingestedAt: -1 }, { name: "pod_time" });

// Incidents collection
db.createCollection('incidents');
db.incidents.createIndex({ status: 1, startedAt: -1 }, { name: "status_started" });
db.incidents.createIndex({ service: 1, startedAt: -1 }, { name: "svc_started" });
