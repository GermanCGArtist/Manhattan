# Manhattan test suite: run final1

Manhattan 2.16.8 (git e65ef83), 32 games at once, started 2026-11-27 00:09:15, finished 2026-11-27 04:24:39 (255 min).

**2477 cases: 2089 pass, 355 fail, 29 flaky, 4 never started or broke in setup** (84% of the started ones pass).

| family | area | cases | pass | fail | flaky | pass % | median s (passing) | median stuck | deaths |
|---|---|---|---|---|---|---|---|---|---|
| drain_flowing | water | 83 | 0 | 83 | 0 | 0 | None | 0 | 0 |
| combo_build | combination | 125 | 49 | 76 | 0 | 39 | 14.0 | 0 | 1 |
| schem_build | building | 167 | 75 | 91 | 1 | 45 | 11.9 | 0.0 | 1 |
| clear_wet | digging | 83 | 54 | 29 | 0 | 65 | 39.0 | 0.0 | 0 |
| terrain_clear | digging | 83 | 61 | 11 | 8 | 73 | 21.2 | 0.0 | 0 |
| nav_descend | navigation | 83 | 63 | 20 | 0 | 76 | 7.5 | 0 | 0 |
| clear_box | digging | 167 | 138 | 29 | 0 | 83 | 111.4 | 0 | 0 |
| terrain_nav | navigation | 125 | 109 | 1 | 14 | 87 | 20.4 | 0 | 0 |
| clear_falling | digging | 83 | 79 | 4 | 0 | 95 | 66.7 | 0 | 0 |
| combo_clear | combination | 167 | 159 | 5 | 3 | 95 | 118.8 | 0 | 0 |
| drown_escape | survival | 65 | 62 | 3 | 0 | 95 | 45.1 | 0.0 | 2 |
| defend_work | survival | 125 | 123 | 2 | 0 | 98 | 47.1 | 0 | 2 |
| clear_tunnel | digging | 83 | 82 | 0 | 1 | 99 | 48.6 | 0 | 0 |
| eat_work | survival | 83 | 82 | 0 | 1 | 99 | 74.0 | 0 | 0 |
| nav_climb | navigation | 83 | 82 | 0 | 1 | 99 | 7.2 | 0 | 0 |
| nav_fluids | navigation | 83 | 82 | 1 | 0 | 99 | 9.6 | 0 | 1 |
| chain_jobs | combination | 83 | 83 | 0 | 0 | 100 | 123.4 | 0 | 0 |
| chest_fetch | logistics | 83 | 83 | 0 | 0 | 100 | 2.5 | 0 | 0 |
| clear_tall | digging | 83 | 83 | 0 | 0 | 100 | 115.1 | 0 | 0 |
| combo_nav_work | combination | 125 | 125 | 0 | 0 | 100 | 25.7 | 0 | 0 |
| dump_chest | logistics | 83 | 83 | 0 | 0 | 100 | 5.4 | 0 | 0 |
| fill_pool | water | 83 | 83 | 0 | 0 | 100 | 15.7 | 0 | 0 |
| nav_gaps | navigation | 83 | 83 | 0 | 0 | 100 | 8.5 | 0 | 0 |
| nav_maze | navigation | 83 | 83 | 0 | 0 | 100 | 8.3 | 0 | 0 |
| nav_walls | navigation | 83 | 83 | 0 | 0 | 100 | 11.0 | 0 | 0 |

## Failures grouped

### schem_build: schematic built: N/N cells wrong (91)

Mostly with: clutter=1 (73 of 91)

Manhattan said: "Respawned at N, N, -N (N blocks away with no bed set) — likely sent to world spawn! Halting for safety." (1); "You died at N, -N, N. Respawning; the Baritone task is gone and your items lie where you died." (1)

- `schem_build/0c0a30bf37` {"shape":"hut","size":"l","palette":"glass_mix","supply":"plenty","clutter":1}: schematic built: 7/239 cells wrong (first 104,-60,103 want minecraft:stone got minecraft:air, client sees minecraft:air)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 0c0a30bf37 -j 1 --no-retry`
- `schem_build/2bc265b293` {"shape":"hut","size":"l","palette":"stone","supply":"plenty","clutter":1}: schematic built: 1/239 cells wrong (first 111,-60,95 want minecraft:stone got minecraft:dirt, client sees minecraft:dirt)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 2bc265b293 -j 1 --no-retry`
- `schem_build/b00d62a7be` {"shape":"hut","size":"l","palette":"stone","supply":"exact","clutter":1}: schematic built: 3/239 cells wrong (first 104,-60,96 want minecraft:stone got minecraft:dirt, client sees minecraft:dirt)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only b00d62a7be -j 1 --no-retry`
- `schem_build/bcc6fc34c9` {"shape":"hut","size":"l","palette":"glass_mix","supply":"plenty","clutter":1}: schematic built: 5/239 cells wrong (first 104,-58,103 want minecraft:glass got minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only bcc6fc34c9 -j 1 --no-retry`
- `schem_build/d057f32741` {"shape":"hut","size":"l","palette":"planks","supply":"exact","clutter":1}: schematic built: 1/239 cells wrong (first 104,-60,98 want minecraft:oak_planks got minecraft:dirt, client sees minecraft:dirt)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only d057f32741 -j 1 --no-retry`
- `schem_build/107ff129c2` {"shape":"floor","size":"l","palette":"mixed","supply":"exact","clutter":1}: schematic built: 5/121 cells wrong (first 104,-60,98 want minecraft:bricks got minecraft:dirt, client sees minecraft:dirt)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 107ff129c2 -j 1 --no-retry`
- ... and 85 more

### drain_flowing: pit dry: N wet cells (81)

- `drain_flowing/16ad3e9753` {"size":7,"sources":1,"block":"cobblestone"}: pit dry: 42 wet cells (first 103,-62,97)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 16ad3e9753 -j 1 --no-retry`
- `drain_flowing/1d31c4458c` {"size":7,"sources":1,"block":"dirt"}: pit dry: 54 wet cells (first 103,-62,97)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 1d31c4458c -j 1 --no-retry`
- `drain_flowing/29e9a1d5fb` {"size":7,"sources":4,"block":"dirt"}: pit dry: 62 wet cells (first 103,-62,97)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 29e9a1d5fb -j 1 --no-retry`
- `drain_flowing/3491ca25e7` {"size":7,"sources":2,"block":"dirt"}: pit dry: 55 wet cells (first 104,-62,97)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 3491ca25e7 -j 1 --no-retry`
- `drain_flowing/4026824c23` {"size":7,"sources":2,"block":"dirt"}: pit dry: 59 wet cells (first 103,-62,97)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 4026824c23 -j 1 --no-retry`
- `drain_flowing/48b5dc98d7` {"size":7,"sources":4,"block":"dirt"}: pit dry: 66 wet cells (first 103,-62,97)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 48b5dc98d7 -j 1 --no-retry`
- ... and 75 more

### combo_build: schematic built: N/N cells wrong (76)

Manhattan said: "Health is low (N/N) while fighting Zombie." (4); "'schem_build' took longer than N s, so I stopped it and go on." (1); "Respawned at -N, N, N (N blocks away with no bed set) — likely sent to world spawn! Halting for safety." (1)

- `combo_build/0027de4bf4` {"shape":"hut","size":"m","hunger":0,"mobs":0,"night":0,"clutter":0}: schematic built: 2/119 cells wrong (first 105,-57,102 want minecraft:oak_log got minecraft:air, client sees minecraft:air)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 0027de4bf4 -j 1 --no-retry`
- `combo_build/103e8de26b` {"shape":"hut","size":"m","hunger":0,"mobs":2,"night":1,"clutter":1}: schematic built: 10/119 cells wrong (first 104,-60,98 want minecraft:oak_planks got minecraft:dirt, client sees minecraft:dirt)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 103e8de26b -j 1 --no-retry`
- `combo_build/323558a9a2` {"shape":"hut","size":"m","hunger":1,"mobs":2,"night":0,"clutter":0}: schematic built: 8/119 cells wrong (first 107,-57,98 want minecraft:oak_log got minecraft:air, client sees minecraft:air)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 323558a9a2 -j 1 --no-retry`
- `combo_build/3738f0510b` {"shape":"hut","size":"m","hunger":0,"mobs":2,"night":0,"clutter":0}: schematic built: 11/119 cells wrong (first 105,-57,98 want minecraft:oak_log got minecraft:air, client sees minecraft:air)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 3738f0510b -j 1 --no-retry`
- `combo_build/474548612b` {"shape":"hut","size":"m","hunger":0,"mobs":2,"night":0,"clutter":1}: schematic built: 7/119 cells wrong (first 104,-60,99 want minecraft:oak_planks got minecraft:dirt, client sees minecraft:dirt)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 474548612b -j 1 --no-retry`
- `combo_build/547074a75d` {"shape":"hut","size":"m","hunger":1,"mobs":0,"night":0,"clutter":0}: schematic built: 4/119 cells wrong (first 105,-57,100 want minecraft:oak_log got minecraft:air, client sees minecraft:air)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 547074a75d -j 1 --no-retry`
- ... and 70 more

### clear_box: area cleared: N/N cells wrong (29)

Mostly with: tool=haste (22 of 29)

Manhattan said: "'clear_area' took longer than N s, so I stopped it and go on." (29)

- `clear_box/441f7f1bc1` {"sx":24,"sz":16,"sy":5,"material":"mixed","tool":"diamond"}: area cleared: 587/1920 cells wrong (first 120,-60,92 minecraft:cobblestone, client sees minecraft:cobblestone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 441f7f1bc1 -j 1 --no-retry`
- `clear_box/6237065450` {"sx":16,"sz":16,"sy":5,"material":"mixed","tool":"iron"}: area cleared: 1280/1280 cells wrong (first 104,-60,92 minecraft:andesite, client sees minecraft:void_air)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 6237065450 -j 1 --no-retry`
- `clear_box/952a9cc741` {"sx":24,"sz":16,"sy":5,"material":"sand","tool":"iron"}: area cleared: 386/1920 cells wrong (first 120,-60,92 minecraft:sand, client sees minecraft:sand)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 952a9cc741 -j 1 --no-retry`
- `clear_box/9e9d5116ea` {"sx":10,"sz":24,"sy":5,"material":"stone","tool":"haste"}: area cleared: 621/1200 cells wrong (first 104,-60,88 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 9e9d5116ea -j 1 --no-retry`
- `clear_box/c85004f15e` {"sx":10,"sz":24,"sy":3,"material":"dirt","tool":"diamond"}: area cleared: 48/720 cells wrong (first 110,-60,88 minecraft:dirt, client sees minecraft:dirt)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only c85004f15e -j 1 --no-retry`
- `clear_box/d2da63f51b` {"sx":24,"sz":24,"sy":1,"material":"gravel","tool":"diamond"}: area cleared: 161/576 cells wrong (first 112,-60,88 minecraft:gravel, client sees minecraft:gravel)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only d2da63f51b -j 1 --no-retry`
- ... and 23 more

### clear_wet: area cleared except the seal: N/N cells wrong (29)

Mostly with: water=pocket (22 of 29)

Manhattan said: "'clear_area' took longer than N s, so I stopped it and go on." (10)

- `clear_wet/077f102b62` {"size":12,"sy":3,"water":"pocket","fluid":"lava"}: area cleared except the seal: 55/432 cells wrong (first 110,-60,94 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 077f102b62 -j 1 --no-retry`
- `clear_wet/1161847031` {"size":12,"sy":3,"water":"s","fluid":"lava"}: area cleared except the seal: 27/432 cells wrong (first 110,-60,94 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 1161847031 -j 1 --no-retry`
- `clear_wet/5884cf35ba` {"size":12,"sy":3,"water":"w","fluid":"water"}: area cleared except the seal: 33/432 cells wrong (first 111,-60,94 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 5884cf35ba -j 1 --no-retry`
- `clear_wet/5bd5f29b1c` {"size":12,"sy":3,"water":"w","fluid":"lava"}: area cleared except the seal: 38/432 cells wrong (first 110,-60,94 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 5bd5f29b1c -j 1 --no-retry`
- `clear_wet/61ca32cdcc` {"size":12,"sy":3,"water":"w","fluid":"water"}: area cleared except the seal: 34/432 cells wrong (first 111,-60,94 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 61ca32cdcc -j 1 --no-retry`
- `clear_wet/6b778616ed` {"size":12,"sy":3,"water":"s","fluid":"lava"}: area cleared except the seal: 27/432 cells wrong (first 110,-60,94 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 6b778616ed -j 1 --no-retry`
- ... and 23 more

### nav_descend: reached target: player N,N blocks from target (20)

Mostly with: landing=stone (12 of 20), dist=25 (17 of 20)

- `nav_descend/184e4336b3` {"height":30,"water_bucket":0,"landing":"grass","dist":25}: reached target: player 19,0 blocks from target (at 124,6 -41,0 100,5)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 184e4336b3 -j 1 --no-retry`
- `nav_descend/2652b75c5e` {"height":30,"water_bucket":0,"landing":"stone","dist":25}: reached target: player 19,0 blocks from target (at 124,7 -41,0 100,6)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 2652b75c5e -j 1 --no-retry`
- `nav_descend/7348e6b097` {"height":30,"water_bucket":0,"landing":"stone","dist":25}: reached target: player 19,0 blocks from target (at 124,6 -41,0 100,5)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 7348e6b097 -j 1 --no-retry`
- `nav_descend/7bfcc44934` {"height":30,"water_bucket":0,"landing":"stone","dist":25}: reached target: player 19,0 blocks from target (at 124,7 -41,0 100,5)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 7bfcc44934 -j 1 --no-retry`
- `nav_descend/82a69c379e` {"height":30,"water_bucket":0,"landing":"stone","dist":25}: reached target: player 19,0 blocks from target (at 124,7 -41,0 100,5)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 82a69c379e -j 1 --no-retry`
- `nav_descend/b2ac6a59f8` {"height":30,"water_bucket":0,"landing":"grass","dist":25}: reached target: player 19,0 blocks from target (at 124,6 -41,0 100,5)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only b2ac6a59f8 -j 1 --no-retry`
- ... and 14 more

### terrain_clear: area cleared: N/N cells wrong (14)

Manhattan said: "'clear_area' took longer than N s, so I stopped it and go on." (1)

- `terrain_clear/d935fea4e9` {"seed":1,"size":8,"depth":3}: area cleared: 4/320 cells wrong (first -4,66,-12 minecraft:leaf_litter, client sees minecraft:leaf_litter)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only d935fea4e9 -j 1 --no-retry`
- `terrain_clear/2e120314de` {"seed":1,"size":8,"depth":2}: area cleared: 7/256 cells wrong (first 8,70,27 minecraft:leaf_litter, client sees minecraft:leaf_litter)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 2e120314de -j 1 --no-retry`
- `terrain_clear/e4f559d6fb` {"seed":3,"size":8,"depth":2}: area cleared: 17/256 cells wrong (first -50,61,-46 minecraft:dirt, client sees minecraft:dirt)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only e4f559d6fb -j 1 --no-retry`
- `terrain_clear/a62584c460` {"seed":3,"size":5,"depth":3}: area cleared: 11/125 cells wrong (first -48,60,-40 minecraft:dirt, client sees minecraft:dirt)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only a62584c460 -j 1 --no-retry`
- `terrain_clear/bf5bb33705` {"seed":6,"size":5,"depth":3}: area cleared: 6/125 cells wrong (first 112,60,-12 minecraft:sand, client sees minecraft:sand)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only bf5bb33705 -j 1 --no-retry`
- `terrain_clear/3fcdbf65b7` {"seed":6,"size":5,"depth":2} (flaky): area cleared: 31/100 cells wrong (first 108,-16,-10 minecraft:deepslate, client sees minecraft:deepslate)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 3fcdbf65b7 -j 1 --no-retry`
- ... and 8 more

### terrain_nav: setup failed: the player started at N, N, N, below y=N (11)

Mostly with: dist=100 (9 of 11)

- `terrain_nav/2116915ce8` {"seed":0,"dist":100,"dir":"n"} (flaky): setup failed: the player started at 100, -60, 100, below y=-40  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 2116915ce8 -j 1 --no-retry`
- `terrain_nav/23886733fe` {"seed":5,"dist":100,"dir":"n"} (flaky): setup failed: the player started at 100, -60, 100, below y=-40  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 23886733fe -j 1 --no-retry`
- `terrain_nav/34a25d8e82` {"seed":6,"dist":100,"dir":"s"} (flaky): setup failed: the player started at 100, -60, 100, below y=-40  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 34a25d8e82 -j 1 --no-retry`
- `terrain_nav/540d00e893` {"seed":2,"dist":100,"dir":"w"} (flaky): setup failed: the player started at 1, -914, -19, below y=-40  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 540d00e893 -j 1 --no-retry`
- `terrain_nav/7375dbe66d` {"seed":1,"dist":100,"dir":"s"} (flaky): setup failed: the player started at -4, -87, 12, below y=-40  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 7375dbe66d -j 1 --no-retry`
- `terrain_nav/8365783463` {"seed":6,"dist":100,"dir":"n"}: setup failed: the player started at 100, -60, 100, below y=-40  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 8365783463 -j 1 --no-retry`
- ... and 5 more

### terrain_clear: setup failed: the player started at N, N, N, below y=N (7)

Mostly with: seed=0 (7 of 7)

Manhattan said: "'clear_area' took longer than N s, so I stopped it and go on." (1)

- `terrain_clear/acbf172bc4` {"seed":0,"size":8,"depth":3}: setup failed: the player started at 100, -60, 100, below y=-40  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only acbf172bc4 -j 1 --no-retry`
- `terrain_clear/45613d86e2` {"seed":0,"size":8,"depth":2}: setup failed: the player started at 100, -60, 100, below y=-40  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 45613d86e2 -j 1 --no-retry`
- `terrain_clear/18afc0d9cb` {"seed":0,"size":5,"depth":3} (flaky): setup failed: the player started at 100, -60, 100, below y=-40  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 18afc0d9cb -j 1 --no-retry`
- `terrain_clear/d0a290986f` {"seed":0,"size":5,"depth":3}: setup failed: the player started at 100, -60, 100, below y=-40  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only d0a290986f -j 1 --no-retry`
- `terrain_clear/fafda1708a` {"seed":0,"size":5,"depth":3} (flaky): setup failed: the player started at 100, -60, 100, below y=-40  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only fafda1708a -j 1 --no-retry`
- `terrain_clear/f5833d3ae0` {"seed":0,"size":5,"depth":2} (flaky): setup failed: the player started at 100, -60, 100, below y=-40  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only f5833d3ae0 -j 1 --no-retry`
- ... and 1 more

### combo_clear: area cleared: N/N cells wrong (7)

Mostly with: size=14 (7 of 7), hunger=0 (7 of 7), tool=haste (7 of 7)

Manhattan said: "'clear_area' took longer than N s, so I stopped it and go on." (5)

- `combo_clear/6428ed6e40` {"size":14,"hunger":0,"mobs":2,"water_side":0,"sand_above":1,"tool":"haste","night":0} (flaky): area cleared: 12/392 cells wrong (first 117,-60,100 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 6428ed6e40 -j 1 --no-retry`
- `combo_clear/72c92905c4` {"size":14,"hunger":0,"mobs":2,"water_side":1,"sand_above":1,"tool":"haste","night":0}: area cleared: 3/392 cells wrong (first 117,-60,105 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 72c92905c4 -j 1 --no-retry`
- `combo_clear/9b56715529` {"size":14,"hunger":0,"mobs":2,"water_side":0,"sand_above":0,"tool":"haste","night":1}: area cleared: 2/392 cells wrong (first 117,-60,106 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 9b56715529 -j 1 --no-retry`
- `combo_clear/a3edb36b5e` {"size":14,"hunger":0,"mobs":0,"water_side":0,"sand_above":1,"tool":"haste","night":1}: area cleared: 8/392 cells wrong (first 117,-60,103 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only a3edb36b5e -j 1 --no-retry`
- `combo_clear/b00199594f` {"size":14,"hunger":0,"mobs":0,"water_side":0,"sand_above":1,"tool":"haste","night":1}: area cleared: 6/392 cells wrong (first 117,-60,104 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only b00199594f -j 1 --no-retry`
- `combo_clear/b3760eeef5` {"size":14,"hunger":0,"mobs":0,"water_side":1,"sand_above":0,"tool":"haste","night":1}: area cleared: 2/392 cells wrong (first 117,-60,106 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only b3760eeef5 -j 1 --no-retry`
- ... and 1 more

### terrain_nav: reached target: player N,N blocks from target (3)

Mostly with: dist=30 (2 of 3), dir=s (3 of 3)

- `terrain_nav/6217f01e74` {"seed":7,"dist":100,"dir":"s"} (flaky): reached target: player 108,3 blocks from target (at 100,5 -60,0 100,5)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 6217f01e74 -j 1 --no-retry`
- `terrain_nav/27a8f8a052` {"seed":4,"dist":30,"dir":"s"} (flaky): reached target: player 125,4 blocks from target (at 100,5 -60,0 100,5)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 27a8f8a052 -j 1 --no-retry`
- `terrain_nav/4cadf281bf` {"seed":2,"dist":30,"dir":"s"} (flaky): reached target: player 2,6 blocks from target (at -4,0 64,0 10,1)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 4cadf281bf -j 1 --no-retry`

### defend_work: died N time(s) (2)

Manhattan said: "You died at N, -N, N. Respawning; the Baritone task is gone and your items lie where you died." (2); "Respawned at -N, N, N (N blocks away with no bed set) — likely sent to world spawn! Halting for safety." (1); "Health is low (N/N) while fighting Husk." (1)

- `defend_work/68dcab8de1` {"mob":"skeleton","count":3,"armor":"none","sword":"diamond","task":"idle"}: died 1 time(s)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 68dcab8de1 -j 1 --no-retry`
- `defend_work/7306a70eea` {"mob":"husk","count":5,"armor":"none","sword":"iron","task":"idle"}: died 1 time(s)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 7306a70eea -j 1 --no-retry`

### clear_falling: script did not pass (2)

Manhattan said: "'clear_area' took longer than N s, so I stopped it and go on." (2)

- `clear_falling/68e3e07e93` {"material":"gravel","layout":"layer_on_top","size":10,"sy":3}: script did not pass  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 68e3e07e93 -j 1 --no-retry`
- `clear_falling/f486fde139` {"material":"sand","layout":"layer_on_top","size":10,"sy":3}: script did not pass  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only f486fde139 -j 1 --no-retry`

### clear_falling: area cleared: N/N cells wrong (2)

Manhattan said: "'clear_area' took longer than N s, so I stopped it and go on." (2)

- `clear_falling/8e5fc943c4` {"material":"sand","layout":"layer_on_top","size":10,"sy":3}: area cleared: 3/300 cells wrong (first 110,-60,95 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 8e5fc943c4 -j 1 --no-retry`
- `clear_falling/ec7002a247` {"material":"sand","layout":"layer_on_top","size":10,"sy":3}: area cleared: 9/300 cells wrong (first 110,-60,95 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only ec7002a247 -j 1 --no-retry`

### drown_escape: died N time(s) (2)

Manhattan said: "You died at N, -N, N. Respawning; the Baritone task is gone and your items lie where you died." (2); "Respawned at N, N, N (N blocks away with no bed set) — likely sent to world spawn! Halting for safety." (1); "Respawned at N, N, -N (N blocks away with no bed set) — likely sent to world spawn! Halting for safety." (1)

- `drown_escape/7ed41f7c86` {"depth":8,"lid":2,"material":"dirt","tool":"none"}: died 1 time(s)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 7ed41f7c86 -j 1 --no-retry`
- `drown_escape/f1efbd4fbe` {"depth":8,"lid":1,"material":"dirt","tool":"none"}: died 1 time(s)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only f1efbd4fbe -j 1 --no-retry`

### terrain_nav: no SUITE_RESULT: [N:N:N] [Render thread/WARN]: Client disconnected with reason: Internal Exception: java.nio.c (1)

- `terrain_nav/60adeb72be` {"seed":4,"dist":100,"dir":"w"}: no SUITE_RESULT: [15:03:13] [Render thread/WARN]: Client disconnected with reason: Internal Exception: java.nio.channels.ClosedChannelException (deming: instance timed out)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 60adeb72be -j 1 --no-retry`

### terrain_nav: no SUITE_RESULT: com.mojang.realmsclient.exception.RealmsServiceException: Realms authentication error with me (1)

- `terrain_nav/66a12ea6b0` {"seed":5,"dist":60,"dir":"s"} (flaky): no SUITE_RESULT: com.mojang.realmsclient.exception.RealmsServiceException: Realms authentication error with message 'java.lang.RuntimeException: Failed to parse into SignedJWT: 0' (deming: success_regex 'SUITE_RESULT id=  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 66a12ea6b0 -j 1 --no-retry`

### combo_clear: limit of N s reached; area cleared: N/N cells wrong (1)

Manhattan said: "Health is low (N/N) while fighting Zombie." (1)

- `combo_clear/06d653c364` {"size":14,"hunger":1,"mobs":2,"water_side":0,"sand_above":0,"tool":"haste","night":1} (flaky): limit of 274 s reached; area cleared: 8/392 cells wrong (first 117,-60,103 minecraft:stone, client sees minecraft:stone)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 06d653c364 -j 1 --no-retry`

### nav_fluids: reached target: player N,N blocks from target (1)

Manhattan said: "Respawned at N, N, -N (N blocks away with no bed set) — likely sent to world spawn! Halting for safety." (1); "You died at N, -N, N. Respawning; the Baritone task is gone and your items lie where you died." (1)

- `nav_fluids/0ff2cd82f0` {"fluid":"lava","width":5,"depth":1,"blocks":64,"dist":50}: reached target: player 198,7 blocks from target (at -3,5 4,0 -7,5)  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 0ff2cd82f0 -j 1 --no-retry`

### drain_flowing: no game log (the game never started?) (deming: Mod error: I/O error: The system cannot find the file specified (1)

- `drain_flowing/600f8a6965` {"size":5,"sources":1,"block":"cobblestone"}: no game log (the game never started?) (deming: Mod error: I/O error: The system cannot find the file specified. (os error 2))  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 600f8a6965 -j 1 --no-retry`

### drain_flowing: no SUITE_RESULT: com.mojang.realmsclient.exception.RealmsServiceException: Realms authentication error with me (1)

- `drain_flowing/95aa30191b` {"size":5,"sources":2,"block":"dirt"}: no SUITE_RESULT: com.mojang.realmsclient.exception.RealmsServiceException: Realms authentication error with message 'java.lang.RuntimeException: Failed to parse into SignedJWT: 0' (deming: failure_regex 'SUITE_RESULT id=  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 95aa30191b -j 1 --no-retry`

### eat_work: ate something: minecraft:golden_carrot in inventory N > N (1)

- `eat_work/c4a5e730a4` {"hunger":20,"food":"golden_carrot","task":"clear","food_count":16} (flaky): ate something: minecraft:golden_carrot in inventory 16 > 15  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only c4a5e730a4 -j 1 --no-retry`

### terrain_clear: no SUITE_RESULT: com.mojang.realmsclient.exception.RealmsServiceException: Realms authentication error with me (1)

- `terrain_clear/3905f9642a` {"seed":1,"size":3,"depth":1} (flaky): no SUITE_RESULT: com.mojang.realmsclient.exception.RealmsServiceException: Realms authentication error with message 'java.lang.RuntimeException: Failed to parse into SignedJWT: 0' (deming: success_regex 'SUITE_RESULT id=  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain4/cases --only 3905f9642a -j 1 --no-retry`

### schem_build: no game log (the game never started?) (deming: Mod error: I/O error: The system cannot find the file specified (1)

- `schem_build/6afc360e6a` {"shape":"wall","size":"s","palette":"glass_mix","supply":"plenty","clutter":1} (flaky): no game log (the game never started?) (deming: Mod error: I/O error: The system cannot find the file specified. (os error 2))  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 6afc360e6a -j 1 --no-retry`

### drown_escape: no SUITE_RESULT: com.mojang.realmsclient.exception.RealmsServiceException: Realms authentication error with me (1)

- `drown_escape/540142de04` {"depth":8,"lid":1,"material":"dirt","tool":"none"}: no SUITE_RESULT: com.mojang.realmsclient.exception.RealmsServiceException: Realms authentication error with message 'java.lang.RuntimeException: Failed to parse into SignedJWT: 0' (deming: failure_regex 'SUITE_RESULT id=  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only 540142de04 -j 1 --no-retry`

### clear_tunnel: no game log (the game never started?) (deming: Mod error: I/O error: Access is denied. (os error N)) (1)

- `clear_tunnel/cb01b3eb32` {"length":16,"w":3,"h":2,"material":"mixed","ores":0} (flaky): no game log (the game never started?) (deming: Mod error: I/O error: Access is denied. (os error 5))  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/full1-fix/cases --only cb01b3eb32 -j 1 --no-retry`

### nav_climb: no game log (the game never started?) (deming: Mod error: I/O error: The system cannot find the file specified (1)

- `nav_climb/219b35d26f` {"height":2,"blocks":0,"dist":20,"approach":"steps"} (flaky): no game log (the game never started?) (deming: Mod error: I/O error: The system cannot find the file specified. (os error 2))  
  repro: `python tests/suite/run.py --cases tmp/suite/runs/terrain3/cases --only 219b35d26f -j 1 --no-retry`

