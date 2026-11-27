# Fleets: many bots, one job

A **fleet** is several Manhattan clients on one server that share the work of a project. The server runs the
companion mod [manhattan-fleet-server](https://github.com/GermanCGArtist/manhattan-fleet-server); without it,
Manhattan stays a solo bot and nothing here applies.

## Setting it up

1. Put `manhattan-fleet-server-<version>.jar` (and Fabric API) on the server. Fleet servers 0.2.0+ need
   Manhattan 2.15.0+ on every client, and the other way round (protocol v2; an older peer gets a clear message
   naming both versions).
2. On each bot, open the workspace (J) → Fleet, and pick a **Client Mode**:
   - **Worker** claims chunks and works on them, also while no editor is online;
   - **Editor** submits and steers jobs (needs op level 2, or an entry in `editors` of `config/manhattan-fleet.json`);
   - **Editor + Worker** does both; **Local** ignores the fleet.
3. As editor, open a project, set the assignment (explicit bots, or a pool tag with *max bots* and *max distance*)
   and press **Submit Project to Fleet**. The project is cut into chunks (16 x 16 by default, embedded in the job
   so every worker cuts the same way) and the workers start at once.

The Fleet panel shows what each worker does, the chunks on the map, and on a worker how many reports are still
waiting for the server.

## Adding and removing workers while a job runs

The queue scales on the fly; nothing has to be resubmitted (needs fleet server 0.2.2+ and Manhattan 2.16.9+).

- **New bots join by themselves.** A bot that connects in Worker mode picks up chunks of every job it is allowed
  on, as long as the job has room (pool jobs: fewer than *max bots* working on it).
- **Max bots `-` / `+`** under a pool job in the Server Job Queue changes how many bots may work on it at once.
  Raising it lets waiting bots start at once. Lowering it is graceful: the extra bots finish the chunk they are on
  and then get no new one from that job.
- **Add worker...** puts a bot on the job: explicit jobs add it to their list; pool jobs let it in even without the
  pool tag and raise *max bots* when the pool is full. A held bot is released. A bot busy on another job finishes
  its chunk there first.
- **Remove worker...** takes a bot off the job at once: its chunk goes back to the queue for the others and it gets
  no more chunks of that job (it stays free for other jobs).
- Per bot in the roster: **Stop / Hold** takes a bot off all work until **Resume**; **Assign to job...** moves it.

Server admins can do the same from the console: `/fleet workers`, `/fleet scale <jobId> <maxBots>`,
`/fleet add <jobId> <bot>`, `/fleet remove <jobId> <bot>`, `/fleet hold <bot>`, `/fleet unhold <bot>`.

## What the fleet guarantees

- **A chunk is finished once.** Every lease carries an *epoch*. When a worker's lease runs out (no heartbeat for
  30 s) and another worker takes the chunk, the first worker's late report is refused, so it can never overwrite
  the new owner's work. A lease that ran out while nobody took it can still be reported: the work counts.
- **No report gets lost.** Chunk reports go into an outbox (`config/manhattan-fleet-outbox.json`) and are resent
  with backoff until the server acknowledges them, also after a disconnect or a restart of the game. The server
  remembers the last message per chunk, so a resent report is answered, never applied twice.
- **A dropped bot gets its chunk back.** On a disconnect the chunk waits 60 s for its owner (rejoin grace); an idle
  worker may take it over when nothing else is left. A bot that dies loses its chunks at once (they go back to the
  queue) and its chest locks and reservations are released.
- **The server survives its own restart.** The state (`<world>/data/manhattan-fleet.json`) is written by a
  background thread; the previous good file is kept as `.bak`. A file that cannot be read is moved aside
  (`.corrupt-<time>`), the backup is used, and nothing is overwritten. Projects are stored once per hash in
  `manhattan-fleet-projects/`. Held workers, tags, shared hazards and chest stock come back after a restart.
- **No idling between chunks.** A finished chunk is answered with the next lease in the same round trip.

## Bots that see each other

Midtown knows where the other bots are: the players the client sees, plus the next steps of every nearby bot's
path, which the fleet server relays (path intents, at most 4 a second, within 160 blocks).

- Paths avoid cells another bot stands in (expensive) and cells on its intended path (a little more expensive),
  so bots spread out instead of queuing in one lane.
- A block another bot stands on, or a cell its body fills, is never broken or built into, also by `clear_area`.
- When another bot stands on the next step, a bot waits instead of walking into it. The bot whose id sorts first
  has the right of way and waits up to 2.5 s; the other gives way after 0.6 s and routes around. Detours around
  bots do not count as failed replans, and a bot that is blocked for good falls back to the normal stuck handling.

## Sharing what one bot learns

- **Deaths:** where a bot died is shared with the whole fleet; every planner avoids that place for three hours,
  exactly like the bot's own deaths.
- **Chest stock:** what a bot sees inside a chest, barrel or shulker box it opens is shared. Before a supply trip
  a bot reserves what it is going to take (90 s), and bots pick chests that still have unreserved stock first, so
  four bots do not all walk to the same last stack.
- **Chest state and locks:** empty/full chests are shared, and only one bot at a time uses a chest.

## For developers

- The wire contract is `dev.manhattan.fleet.wire.FleetWire`, kept byte-identical in both repositories; both test
  suites compare it with the sibling checkout. Change it in both places and raise `PROTOCOL_VERSION` when an older
  peer could misread a message.
- Client: `FleetClient` (connection, leases, outbox, relays), `FleetOutbox` (pure Java), `FleetWorker` (the worker
  state machine: DISABLED, IDLE, STARTING, WORKING, COOLDOWN), `FleetChestWatcher` (stock), `PeerTraffic`
  (`dev.midtown.plan`, what planners and the yield rule read).
- Server: `FleetManager` (jobs, leases, epochs, owner index, hazards), `ChestCoordinator` (locks, stock,
  reservations), `FleetStore` (persistence), `FleetServerMod` (networking).
- Tests: `FleetServerTest` / `FleetReliabilityTest` in the server repo (including a seeded chaos simulation of up
  to 32 bots with message loss, duplication, reordering, disconnects, deaths, stalls and server restarts),
  `FleetClientTest` and `MidtownPeerTest` here, and the Deming fleet scenarios (`tests/deming/bench-fleet-*.json`,
  see [DEMING_BENCHMARKS.md](DEMING_BENCHMARKS.md)).
