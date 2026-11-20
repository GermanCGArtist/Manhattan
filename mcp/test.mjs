#!/usr/bin/env node
/**
 * End-to-end stdio JSON-RPC test suite for Manhattan Local MCP Bridge (server.mjs).
 */

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import readline from "node:readline";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SERVER_PATH = path.join(__dirname, "server.mjs");

function createTempProfile() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "manhattan-mcp-test-"));
  fs.mkdirSync(path.join(dir, "config", "manhattan-projects", "smp_world"), { recursive: true });
  fs.mkdirSync(path.join(dir, "config", "manhattan-jobs"), { recursive: true });
  fs.mkdirSync(path.join(dir, "manhattan-context"), { recursive: true });
  fs.mkdirSync(path.join(dir, "manhattan-telemetry"), { recursive: true });
  fs.mkdirSync(path.join(dir, "manhattan-incidents"), { recursive: true });

  fs.writeFileSync(
    path.join(dir, "manhattan-context", "smp_world_default.txt"),
    "let bed = pos(10, 64, 20)\nlet dump_chest = pos(14, 64, 20)\n",
    "utf8",
  );
  fs.writeFileSync(
    path.join(dir, "config", "manhattan-jobs", "smp_world_project_canal.json"),
    JSON.stringify({
      entries: {
        "t1/0,0": { state: "DONE", tries: 1 },
        "t1/1,0": { state: "FAILED", tries: 3, reason: "water leak" },
      },
    }),
    "utf8",
  );
  fs.writeFileSync(
    path.join(dir, "manhattan-telemetry", "project-canal-1.jsonl"),
    [
      JSON.stringify({ seq: 1, ts: 1000, type: "RUN_START", category: "SYSTEM", message: "Started" }),
      JSON.stringify({ seq: 2, ts: 2000, type: "STUCK", category: "STUCK", pos: [100, 64, -50], message: "edge blocked" }),
      JSON.stringify({ seq: 3, ts: 3000, type: "STUCK", category: "STUCK", pos: [101, 64, -49], message: "edge blocked again" }),
    ].join("\n") + "\n",
    "utf8",
  );
  fs.writeFileSync(
    path.join(dir, "manhattan-incidents", "incident-20261120-230000-000.json"),
    JSON.stringify({
      version: 1,
      world: "smp_world",
      triggerReason: "queue_failed",
      detail: "chunk failed",
      mode: "project",
    }),
    "utf8",
  );
  return dir;
}

async function run() {
  const profileDir = createTempProfile();
  const child = spawn(process.execPath, [SERVER_PATH], {
    env: { ...process.env, MANHATTAN_PROFILE_DIR: profileDir },
    stdio: ["pipe", "pipe", "inherit"],
  });

  const rl = readline.createInterface({ input: child.stdout });
  const pending = new Map();
  rl.on("line", (line) => {
    const msg = JSON.parse(line);
    const resolve = pending.get(msg.id);
    if (resolve) {
      pending.delete(msg.id);
      resolve(msg);
    }
  });

  let nextId = 1;
  function rpc(method, params) {
    const id = nextId++;
    return new Promise((resolve) => {
      pending.set(id, resolve);
      child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");
    });
  }

  async function callTool(name, args = {}) {
    const res = await rpc("tools/call", { name, arguments: args });
    assert.ok(res.result && !res.result.isError, `Tool ${name} failed: ${JSON.stringify(res)}`);
    return JSON.parse(res.result.content[0].text);
  }

  try {
    const init = await rpc("initialize", {});
    assert.equal(init.result.serverInfo.name, "manhattan-mcp");

    const listed = await rpc("tools/list", {});
    assert.ok(listed.result.tools.length >= 10, "expected >= 10 tools");

    const worlds = await callTool("list_worlds");
    assert.deepEqual(worlds.worlds, ["smp_world"]);

    const ctx = await callTool("get_world_context", { world: "smp_world" });
    assert.equal(ctx.contextFiles.length, 1);
    assert.ok(ctx.contextFiles[0].content.includes("dump_chest"));

    const sampleProject = {
      version: 1,
      id: "canal",
      name: "Canal Dig",
      world: "smp_world",
      places: { bed: [10, 64, 20], dumpChest: [14, 64, 20] },
      survival: { treeZoneDiameter: 9, eatFoodBelow: 18, maxDeaths: 3 },
      tasks: [{ id: "t1", kind: "CLEAR_AREA", areaName: "canal_box" }],
    };

    const saved = await callTool("save_project", { project: sampleProject, set_active: true });
    assert.equal(saved.saved, true);

    const projs = await callTool("list_projects", { world: "smp_world" });
    assert.equal(projs.activeId, "canal");
    assert.deepEqual(projs.projects, ["canal"]);

    // Guardrail test: safe_tuning_only allows survival change, blocks coordinate change
    const tuned = structuredClone(sampleProject);
    tuned.survival.eatFoodBelow = 14;
    const tunedRes = await callTool("save_project", { project: tuned, safe_tuning_only: true });
    assert.equal(tunedRes.saved, true);

    const unsafe = structuredClone(tuned);
    unsafe.places.bed = [999, 64, 999];
    const blocked = await callTool("save_project", { project: unsafe, safe_tuning_only: true });
    assert.equal(blocked.saved, false);
    assert.equal(blocked.blockedByGuardrail, true);

    const ledger = await callTool("get_ledger", { world: "smp_world" });
    assert.equal(ledger.ledgers[0].counts.DONE, 1);
    assert.equal(ledger.ledgers[0].counts.FAILED, 1);

    const tel = await callTool("get_telemetry");
    assert.equal(tel.totalEvents, 3);
    assert.equal(tel.hotspots.length, 1);
    assert.equal(tel.hotspots[0].count, 2);

    const incidents = await callTool("list_incidents");
    assert.equal(incidents.incidents.length, 1);

    const latest = await callTool("get_latest_incident");
    assert.equal(latest.found, true);
    assert.equal(latest.incident.triggerReason, "queue_failed");

    console.log("manhattan-mcp test suite: all assertions passed.");
  } finally {
    child.kill();
    fs.rmSync(profileDir, { recursive: true, force: true });
  }
}

run().catch((err) => {
  console.error(err);
  process.exit(1);
});
