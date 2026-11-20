#!/usr/bin/env node
/**
 * Manhattan Local MCP Bridge (zero-dependency Node.js stdio JSON-RPC 2.0 server).
 *
 * Exposes Manhattan's Capture context, ProjectStore JSONs, Ledger progress,
 * RuntimeBus JSONL telemetry, and Incident Bundles to any MCP-compatible AI assistant.
 *
 * Configure via environment variable:
 *   MANHATTAN_PROFILE_DIR=<path to Minecraft / Modrinth profile directory>
 */

import fs from "node:fs";
import path from "node:path";
import readline from "node:readline";

const SERVER_INFO = {
  name: "manhattan-mcp",
  version: "1.0.0",
};

const PROTOCOL_VERSION = "2024-11-05";

const VALID_TASK_KINDS = new Set([
  "FILL_WATER",
  "CLEAR_AREA",
  "CLEAR_TREES",
  "SCHEM_BUILD",
  "FLOOD",
  "DRAIN",
  "MINE",
  "DUMP",
  "RESTOCK",
  "SCRIPT",
]);

const VALID_PARTIAL_POLICIES = new Set(["CONTINUE", "WARN_CONTINUE", "PAUSE", "STOP"]);
const VALID_ERROR_POLICIES = new Set(["STOP", "SKIP", "PAUSE", "RETRY"]);

// ------------------------------------------------------------------ path helpers

function resolveProfileDir(args) {
  const raw = (args && typeof args.profile_dir === "string" && args.profile_dir.trim())
    ? args.profile_dir.trim()
    : (process.env.MANHATTAN_PROFILE_DIR || "").trim();
  if (!raw) {
    throw new Error("No profile_dir provided and MANHATTAN_PROFILE_DIR is not set.");
  }
  return path.resolve(raw);
}

function sanitizeId(raw) {
  if (!raw || typeof raw !== "string") return "default";
  const s = raw.trim().toLowerCase()
    .replace(/[^a-z0-9_-]+/g, "_")
    .replace(/_+/g, "_")
    .replace(/^[_-]+|[_-]+$/g, "");
  if (!s || s === "." || s === "..") return "default";
  return s;
}

function sanitizeWorld(raw) {
  if (!raw || typeof raw !== "string" || !raw.trim()) return "default";
  let s = raw.trim().replace(/[^A-Za-z0-9._-]+/g, "_");
  while (s.startsWith(".")) s = s.slice(1);
  s = s.replace(/^_+|_+$/g, "");
  return s || "default";
}

function projectsRoot(profileDir) {
  return path.join(profileDir, "config", "manhattan-projects");
}

function jobsRoot(profileDir) {
  return path.join(profileDir, "config", "manhattan-jobs");
}

function contextRoot(profileDir) {
  return path.join(profileDir, "manhattan-context");
}

function telemetryRoot(profileDir) {
  return path.join(profileDir, "manhattan-telemetry");
}

function incidentsRoot(profileDir) {
  return path.join(profileDir, "manhattan-incidents");
}

function safeReadJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    return null;
  }
}

function listFilesSorted(dir, predicate) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true })
    .filter((d) => d.isFile() && predicate(d.name))
    .map((d) => {
      const full = path.join(dir, d.name);
      const stat = fs.statSync(full);
      return { name: d.name, path: full, mtimeMs: stat.mtimeMs, size: stat.size };
    })
    .sort((a, b) => b.mtimeMs - a.mtimeMs || b.name.localeCompare(a.name));
}

function writeAtomic(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.tmp.${process.pid}`;
  fs.writeFileSync(tmp, text, "utf8");
  fs.renameSync(tmp, file);
}

// ------------------------------------------------------------------ validation

function isPos3(v) {
  return Array.isArray(v) && v.length === 3 && v.every((n) => Number.isInteger(n));
}

function isBox6(v) {
  return Array.isArray(v) && v.length === 6 && v.every((n) => Number.isInteger(n));
}

function validateProject(project) {
  const errors = [];
  const warnings = [];

  if (!project || typeof project !== "object" || Array.isArray(project)) {
    return { valid: false, errors: ["Project must be a JSON object."], warnings };
  }

  if (!project.id || typeof project.id !== "string" || !project.id.trim()) {
    errors.push("Missing or empty 'id'.");
  }
  if (!project.world || typeof project.world !== "string" || !project.world.trim()) {
    warnings.push("Missing 'world'; will default to 'default'.");
  }

  const places = project.places || {};
  for (const key of ["bed", "toolChest", "returnChest", "dumpChest", "scaffoldChest"]) {
    if (places[key] !== undefined && places[key] !== null && !isPos3(places[key])) {
      errors.push(`places.${key} must be a 3-integer [x, y, z] array.`);
    }
  }
  if (!places.bed) warnings.push("places.bed is not set (sleep & respawn safety disabled).");
  if (!places.dumpChest) warnings.push("places.dumpChest is not set (dump trips may fail).");

  if (places.materialChests !== undefined) {
    if (!Array.isArray(places.materialChests)) {
      errors.push("places.materialChests must be an array of [x, y, z].");
    } else {
      places.materialChests.forEach((c, i) => {
        if (!isPos3(c)) errors.push(`places.materialChests[${i}] must be [x, y, z].`);
      });
    }
  }

  if (places.protectedAreas !== undefined) {
    if (typeof places.protectedAreas !== "object" || Array.isArray(places.protectedAreas)) {
      errors.push("places.protectedAreas must be a map of name -> list of [x1,y1,z1,x2,y2,z2].");
    } else {
      for (const [name, boxes] of Object.entries(places.protectedAreas)) {
        if (!Array.isArray(boxes) || !boxes.every(isBox6)) {
          errors.push(`places.protectedAreas.${name} must be an array of 6-integer boxes.`);
        }
      }
    }
  }

  const surv = project.survival || {};
  if (surv.treeZoneDiameter !== undefined && (!Number.isInteger(surv.treeZoneDiameter) || surv.treeZoneDiameter < 3)) {
    errors.push("survival.treeZoneDiameter must be an integer >= 3.");
  }
  if (surv.eatFoodBelow !== undefined && (!Number.isInteger(surv.eatFoodBelow) || surv.eatFoodBelow < 1 || surv.eatFoodBelow > 20)) {
    errors.push("survival.eatFoodBelow must be an integer in [1, 20].");
  }
  if (surv.maxDeaths !== undefined && (!Number.isInteger(surv.maxDeaths) || surv.maxDeaths < 0)) {
    errors.push("survival.maxDeaths must be an integer >= 0.");
  }

  if (!Array.isArray(project.tasks)) {
    errors.push("tasks must be an array.");
  } else {
    const seenIds = new Set();
    project.tasks.forEach((t, idx) => {
      if (!t || typeof t !== "object") {
        errors.push(`tasks[${idx}] must be an object.`);
        return;
      }
      if (!t.id || typeof t.id !== "string") {
        errors.push(`tasks[${idx}] missing string 'id'.`);
      } else if (seenIds.has(t.id)) {
        errors.push(`Duplicate task id '${t.id}'.`);
      } else {
        seenIds.add(t.id);
      }
      if (!VALID_TASK_KINDS.has(t.kind)) {
        errors.push(`tasks[${idx}] has invalid kind '${t.kind}'. Expected one of: ${[...VALID_TASK_KINDS].join(", ")}.`);
      }
      if (t.partialPolicy !== undefined && !VALID_PARTIAL_POLICIES.has(t.partialPolicy)) {
        errors.push(`tasks[${idx}] has invalid partialPolicy '${t.partialPolicy}'.`);
      }
      if (t.errorPolicy !== undefined && !VALID_ERROR_POLICIES.has(t.errorPolicy)) {
        errors.push(`tasks[${idx}] has invalid errorPolicy '${t.errorPolicy}'.`);
      }
      if (["FILL_WATER", "CLEAR_AREA", "CLEAR_TREES", "FLOOD", "DRAIN"].includes(t.kind) && !t.areaName) {
        warnings.push(`tasks[${idx}] (${t.kind}) has no areaName; will rely on active #sel.`);
      }
      if (t.kind === "SCHEM_BUILD" && !t.placement) {
        errors.push(`tasks[${idx}] (SCHEM_BUILD) requires 'placement'.`);
      }
      if (t.kind === "CLEAR_TREES" && t.collectScaffoldAfter) {
        warnings.push(`tasks[${idx}] (CLEAR_TREES) has collectScaffoldAfter=true; clear_trees already removes local cylinder scaffolding.`);
      }
      if (t.treeZoneDiameter !== undefined && t.treeZoneDiameter !== null && (!Number.isInteger(t.treeZoneDiameter) || t.treeZoneDiameter < 3)) {
        errors.push(`tasks[${idx}].treeZoneDiameter must be an integer >= 3.`);
      }
    });
  }

  return { valid: errors.length === 0, errors, warnings };
}

function checkSafeTuningOnly(existing, proposed) {
  const violations = [];
  if (JSON.stringify(existing.places || {}) !== JSON.stringify(proposed.places || {})) {
    violations.push("places coordinates/protectedAreas changed (blocked under safe_tuning_only).");
  }
  const oldTasks = existing.tasks || [];
  const newTasks = proposed.tasks || [];
  if (oldTasks.length !== newTasks.length) {
    violations.push("Task count changed (blocked under safe_tuning_only).");
  } else {
    for (let i = 0; i < oldTasks.length; i++) {
      const a = oldTasks[i];
      const b = newTasks[i];
      for (const field of ["id", "kind", "areaName", "expand", "block", "placement", "scriptPath", "inlineScript"]) {
        if (JSON.stringify(a[field] ?? null) !== JSON.stringify(b[field] ?? null)) {
          violations.push(`tasks[${i}].${field} changed (blocked under safe_tuning_only).`);
        }
      }
    }
  }
  return violations;
}

// ------------------------------------------------------------------ tool implementations

function toolListWorlds(args) {
  const profileDir = resolveProfileDir(args);
  const worlds = new Set();

  const pRoot = projectsRoot(profileDir);
  if (fs.existsSync(pRoot)) {
    for (const d of fs.readdirSync(pRoot, { withFileTypes: true })) {
      if (d.isDirectory()) worlds.add(d.name);
    }
  }

  const cRoot = contextRoot(profileDir);
  if (fs.existsSync(cRoot)) {
    for (const f of fs.readdirSync(cRoot)) {
      if (f.endsWith(".txt")) {
        const idx = f.lastIndexOf("_");
        if (idx > 0) worlds.add(f.slice(0, idx));
      }
    }
  }

  return { profileDir, worlds: [...worlds].sort() };
}

function toolGetWorldContext(args) {
  const profileDir = resolveProfileDir(args);
  const world = args?.world ? sanitizeWorld(args.world) : null;
  const cRoot = contextRoot(profileDir);
  const contextFiles = listFilesSorted(cRoot, (n) => {
    if (!n.endsWith(".txt")) return false;
    return !world || n.startsWith(world + "_") || n.startsWith(world + ".");
  }).slice(0, 10).map((f) => ({
    name: f.name,
    mtimeIso: new Date(f.mtimeMs).toISOString(),
    content: fs.readFileSync(f.path, "utf8"),
  }));

  const scriptsDir = path.join(profileDir, "scripts");
  const scripts = listFilesSorted(scriptsDir, (n) => n.endsWith(".txt")).map((f) => f.name);

  return {
    profileDir,
    world: world || "(all)",
    contextFiles,
    scripts,
  };
}

function toolListProjects(args) {
  const profileDir = resolveProfileDir(args);
  const world = sanitizeWorld(args?.world);
  const dir = path.join(projectsRoot(profileDir), world);
  const ids = listFilesSorted(dir, (n) => n.endsWith(".json") && !n.startsWith("_"))
    .map((f) => f.name.slice(0, -5))
    .sort();
  const activeFile = path.join(dir, "_active.txt");
  const activeId = fs.existsSync(activeFile) ? fs.readFileSync(activeFile, "utf8").trim() || null : null;
  return { world, activeId, projects: ids };
}

function toolGetProject(args) {
  const profileDir = resolveProfileDir(args);
  const world = sanitizeWorld(args?.world);
  const id = sanitizeId(args?.id);
  const file = path.join(projectsRoot(profileDir), world, `${id}.json`);
  if (!fs.existsSync(file)) {
    throw new Error(`Project not found: ${file}`);
  }
  const project = safeReadJson(file);
  if (!project) throw new Error(`Failed to parse JSON: ${file}`);
  return { file, project, validation: validateProject(project) };
}

function toolValidateProject(args) {
  return validateProject(args?.project);
}

function toolSaveProject(args) {
  const profileDir = resolveProfileDir(args);
  const project = args?.project;
  const validation = validateProject(project);
  if (!validation.valid) {
    return { saved: false, validation };
  }

  const world = sanitizeWorld(args?.world || project.world);
  const id = sanitizeId(project.id);
  project.id = id;
  project.world = world;
  if (!project.name) project.name = id;

  const dir = path.join(projectsRoot(profileDir), world);
  const file = path.join(dir, `${id}.json`);

  if (args?.safe_tuning_only && fs.existsSync(file)) {
    const existing = safeReadJson(file);
    if (existing) {
      const violations = checkSafeTuningOnly(existing, project);
      if (violations.length > 0) {
        return { saved: false, blockedByGuardrail: true, violations, validation };
      }
    }
  }

  writeAtomic(file, JSON.stringify(project, null, 2) + "\n");
  if (args?.set_active) {
    writeAtomic(path.join(dir, "_active.txt"), id + "\n");
  }
  return { saved: true, file, validation };
}

function toolGetLedger(args) {
  const profileDir = resolveProfileDir(args);
  const world = args?.world ? sanitizeWorld(args.world) : null;
  const dir = jobsRoot(profileDir);
  const files = listFilesSorted(dir, (n) => n.endsWith(".json") && (!world || n.startsWith(world + "_")));

  const ledgers = files.slice(0, 10).map((f) => {
    const data = safeReadJson(f.path);
    const counts = { DONE: 0, RUNNING: 0, FAILED: 0, DEFERRED: 0, PENDING: 0 };
    const failedEntries = [];
    const entries = data && typeof data === "object" ? (data.entries || data) : {};
    for (const [k, v] of Object.entries(entries)) {
      if (v && typeof v === "object" && typeof v.state === "string") {
        counts[v.state] = (counts[v.state] || 0) + 1;
        if (v.state === "FAILED" || v.state === "DEFERRED") {
          failedEntries.push({ id: k, state: v.state, reason: v.reason || "", tries: v.tries || 0 });
        }
      }
    }
    return {
      file: f.name,
      mtimeIso: new Date(f.mtimeMs).toISOString(),
      counts,
      failedEntries: failedEntries.slice(0, 20),
    };
  });

  return { profileDir, world: world || "(all)", ledgers };
}

function toolGetTelemetry(args) {
  const profileDir = resolveProfileDir(args);
  const dir = telemetryRoot(profileDir);
  const files = listFilesSorted(dir, (n) => n.endsWith(".jsonl"));
  if (files.length === 0) {
    return { profileDir, runs: [], latest: null };
  }

  const chosen = args?.file
    ? files.find((f) => f.name === args.file) || files[0]
    : files[0];
  const limit = Math.max(1, Math.min(500, Number(args?.limit) || 100));
  const rawLines = fs.readFileSync(chosen.path, "utf8").split(/\r?\n/).filter(Boolean);
  const events = [];
  const typeCounts = {};
  const hotspotMap = new Map();

  for (const line of rawLines) {
    try {
      const ev = JSON.parse(line);
      events.push(ev);
      typeCounts[ev.type] = (typeCounts[ev.type] || 0) + 1;
      if (Array.isArray(ev.pos) && (ev.type === "STUCK" || ev.type === "EDGE_BLACKLIST" || ev.type === "DEATH")) {
        const key = `${ev.type}:${Math.floor(ev.pos[0] / 4) * 4}:${Math.floor(ev.pos[2] / 4) * 4}`;
        const prev = hotspotMap.get(key) || { type: ev.type, pos: ev.pos, count: 0, lastMessage: "" };
        prev.count += 1;
        prev.pos = ev.pos;
        prev.lastMessage = ev.message || "";
        hotspotMap.set(key, prev);
      }
    } catch {
      // ignore truncated trailing line
    }
  }

  return {
    profileDir,
    availableRuns: files.slice(0, 10).map((f) => ({ name: f.name, size: f.size, mtimeIso: new Date(f.mtimeMs).toISOString() })),
    selectedRun: chosen.name,
    totalEvents: events.length,
    typeCounts,
    hotspots: [...hotspotMap.values()].sort((a, b) => b.count - a.count).slice(0, 15),
    recentEvents: events.slice(-limit),
  };
}

function toolListIncidents(args) {
  const profileDir = resolveProfileDir(args);
  const dir = incidentsRoot(profileDir);
  const files = listFilesSorted(dir, (n) => n.startsWith("incident-") && n.endsWith(".json"));
  const limit = Math.max(1, Math.min(50, Number(args?.limit) || 15));
  return {
    profileDir,
    incidents: files.slice(0, limit).map((f) => {
      const data = safeReadJson(f.path);
      return {
        file: f.name,
        mtimeIso: new Date(f.mtimeMs).toISOString(),
        world: data?.world || null,
        triggerReason: data?.triggerReason || null,
        detail: data?.detail || null,
        mode: data?.mode || null,
      };
    }),
  };
}

function toolGetLatestIncident(args) {
  const profileDir = resolveProfileDir(args);
  const dir = incidentsRoot(profileDir);
  const files = listFilesSorted(dir, (n) => n.startsWith("incident-") && n.endsWith(".json"));
  if (files.length === 0) {
    return { found: false, incident: null };
  }
  const chosen = args?.file
    ? files.find((f) => f.name === args.file) || files[0]
    : files[0];
  return {
    found: true,
    file: chosen.name,
    path: chosen.path,
    incident: safeReadJson(chosen.path),
  };
}

// ------------------------------------------------------------------ MCP tool registry

const TOOLS = [
  {
    name: "list_worlds",
    description: "List Minecraft worlds that have Manhattan projects or Capture context sheets in the profile.",
    inputSchema: {
      type: "object",
      properties: { profile_dir: { type: "string", description: "Optional override for MANHATTAN_PROFILE_DIR" } },
    },
    handler: toolListWorlds,
  },
  {
    name: "get_world_context",
    description: "Read Capture (K) context sheets and list script tabs for a world.",
    inputSchema: {
      type: "object",
      properties: {
        profile_dir: { type: "string" },
        world: { type: "string", description: "World name (optional; returns all if omitted)" },
      },
    },
    handler: toolGetWorldContext,
  },
  {
    name: "list_projects",
    description: "List saved Manhattan Project JSON IDs and active project for a world.",
    inputSchema: {
      type: "object",
      required: ["world"],
      properties: {
        profile_dir: { type: "string" },
        world: { type: "string" },
      },
    },
    handler: toolListProjects,
  },
  {
    name: "get_project",
    description: "Load a Manhattan Project JSON and run schema/preflight validation on it.",
    inputSchema: {
      type: "object",
      required: ["world", "id"],
      properties: {
        profile_dir: { type: "string" },
        world: { type: "string" },
        id: { type: "string" },
      },
    },
    handler: toolGetProject,
  },
  {
    name: "validate_project",
    description: "Validate a candidate Manhattan Project JSON object against the Project/ProjectTask schema and preflight checks.",
    inputSchema: {
      type: "object",
      required: ["project"],
      properties: {
        project: { type: "object" },
      },
    },
    handler: toolValidateProject,
  },
  {
    name: "save_project",
    description: "Validate and atomically save a Manhattan Project JSON into the profile's ProjectStore. Supports safe_tuning_only guardrail.",
    inputSchema: {
      type: "object",
      required: ["project"],
      properties: {
        profile_dir: { type: "string" },
        world: { type: "string" },
        project: { type: "object" },
        set_active: { type: "boolean", description: "Set this project as _active.txt for the world" },
        safe_tuning_only: {
          type: "boolean",
          description: "If true and the project exists, block coordinate/area/task-structure changes and allow only survival/tuning edits.",
        },
      },
    },
    handler: toolSaveProject,
  },
  {
    name: "get_ledger",
    description: "Inspect Manhattan job/project ledger files (DONE, FAILED, DEFERRED chunks/tasks).",
    inputSchema: {
      type: "object",
      properties: {
        profile_dir: { type: "string" },
        world: { type: "string" },
      },
    },
    handler: toolGetLedger,
  },
  {
    name: "get_telemetry",
    description: "Read RuntimeBus JSONL telemetry stream, summarize event counts, and surface spatial stuck/death hotspots.",
    inputSchema: {
      type: "object",
      properties: {
        profile_dir: { type: "string" },
        file: { type: "string", description: "Optional specific .jsonl filename; defaults to newest run" },
        limit: { type: "integer", description: "Max recent events to return (default 100)" },
      },
    },
    handler: toolGetTelemetry,
  },
  {
    name: "list_incidents",
    description: "List recent diagnostic incident bundles dumped by Manhattan on halt/error/snapshot.",
    inputSchema: {
      type: "object",
      properties: {
        profile_dir: { type: "string" },
        limit: { type: "integer" },
      },
    },
    handler: toolListIncidents,
  },
  {
    name: "get_latest_incident",
    description: "Read the latest (or named) incident-<ts>.json bundle containing active project, player snapshot, ledger, bottlenecks, and last 50 RuntimeBus events.",
    inputSchema: {
      type: "object",
      properties: {
        profile_dir: { type: "string" },
        file: { type: "string" },
      },
    },
    handler: toolGetLatestIncident,
  },
];

const TOOL_MAP = new Map(TOOLS.map((t) => [t.name, t]));

// ------------------------------------------------------------------ JSON-RPC 2.0 stdio transport

function send(msg) {
  process.stdout.write(JSON.stringify(msg) + "\n");
}

function handleMessage(msg) {
  if (!msg || typeof msg !== "object") return;
  const { id, method, params } = msg;

  // Notifications have no id
  if (id === undefined || id === null) {
    return;
  }

  try {
    if (method === "initialize") {
      send({
        jsonrpc: "2.0",
        id,
        result: {
          protocolVersion: PROTOCOL_VERSION,
          capabilities: { tools: {} },
          serverInfo: SERVER_INFO,
        },
      });
      return;
    }

    if (method === "ping") {
      send({ jsonrpc: "2.0", id, result: {} });
      return;
    }

    if (method === "tools/list") {
      send({
        jsonrpc: "2.0",
        id,
        result: {
          tools: TOOLS.map(({ name, description, inputSchema }) => ({ name, description, inputSchema })),
        },
      });
      return;
    }

    if (method === "tools/call") {
      const toolName = params?.name;
      const tool = TOOL_MAP.get(toolName);
      if (!tool) {
        send({
          jsonrpc: "2.0",
          id,
          error: { code: -32601, message: `Unknown tool: ${toolName}` },
        });
        return;
      }
      try {
        const result = tool.handler(params?.arguments || {});
        send({
          jsonrpc: "2.0",
          id,
          result: {
            content: [{ type: "text", text: JSON.stringify(result, null, 2) }],
          },
        });
      } catch (err) {
        send({
          jsonrpc: "2.0",
          id,
          result: {
            isError: true,
            content: [{ type: "text", text: `Error: ${err.message || String(err)}` }],
          },
        });
      }
      return;
    }

    send({
      jsonrpc: "2.0",
      id,
      error: { code: -32601, message: `Method not found: ${method}` },
    });
  } catch (err) {
    send({
      jsonrpc: "2.0",
      id,
      error: { code: -32603, message: err.message || String(err) },
    });
  }
}

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
rl.on("line", (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  try {
    handleMessage(JSON.parse(trimmed));
  } catch (err) {
    send({ jsonrpc: "2.0", id: null, error: { code: -32700, message: `Parse error: ${err.message}` } });
  }
});
