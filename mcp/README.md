# Manhattan Local MCP Bridge

Zero-dependency Node.js MCP server (`stdio` JSON-RPC 2.0) that connects AI assistants (Antigravity, Claude Code, Cursor, etc.) directly to a local Minecraft/Modrinth profile running **Manhattan**.

## Capabilities

| Tool | Purpose |
|---|---|
| `list_worlds` | Discover worlds with Projects or Capture sheets |
| `get_world_context` | Read Capture (`K`) context sheets and list script tabs |
| `list_projects` / `get_project` | Inspect saved `Project` JSONs and active project |
| `validate_project` | Schema + preflight check against `Project.java` / `ProjectTask.java` |
| `save_project` | Validate & atomically write a `Project` JSON (supports `safe_tuning_only` guardrail) |
| `get_ledger` | Inspect DONE / FAILED / DEFERRED chunk and task state |
| `get_telemetry` | Summarize `manhattan-telemetry/*.jsonl` runs and spatial stuck/death hotspots |
| `list_incidents` / `get_latest_incident` | Inspect `manhattan-incidents/incident-*.json` diagnostic bundles |

## Client Configuration

Point `MANHATTAN_PROFILE_DIR` to your Minecraft or Modrinth profile folder:

```json
{
  "mcpServers": {
    "manhattan": {
      "command": "node",
      "args": ["f:/workflow/Manhattan/mcp/server.mjs"],
      "env": {
        "MANHATTAN_PROFILE_DIR": "C:/Users/<you>/AppData/Roaming/ModrinthApp/profiles/26.1.1 AFK"
      }
    }
  }
}
```

## Self-Test

```bash
node mcp/test.mjs
```
