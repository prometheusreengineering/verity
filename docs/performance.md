# Performance

How long Verity takes on GitHub-hosted runners, and what changes that. Every benchmark run so far is listed here,
including the ones that led to the current defaults.

All runs used the standard free-plan runners (`ubuntu-latest` and `windows-latest`: 4 vCPUs, 16 GB RAM) and headless
display with Mesa software rendering. Times are wall-clock minutes from the GitHub API. "Whole run" means from the
first job's start to the last job's end, so it includes queueing between jobs. Unless a row says otherwise, numbers
come from the GitHub API and the saved `report.json` files, re-checked on 2026-11-07. Run IDs link to
`prometheusreengineering/verity` (V) or `prometheusreengineering/minecraft-essential` (E).

## Summary

- **Plan mode is the fastest layout.** With `mode: plan`, all 127 targets on both OSes take **7 minutes warm** and
  **14.4 minutes cold**, against 9.3 and 27–30 minutes with a fixed 10 + 10 runners and one shared cache. See
  [Automatic planning](#automatic-planning-2026-11-07).
- **Clients are CPU-bound.** A runner gets through about 3 targets a minute on Linux and about 2.2 on Windows,
  whether it runs 3 or 7 clients at once. To go faster, add runners (`shard`), not clients.
- **Windows is about 1.5× slower than Linux** at everything: installs, restoring caches and running clients, so the
  planner gives it more runners.
- **Caches per group of Minecraft versions beat one big cache.** Each runner restores only the group it runs, and
  each group's prepare job installs in parallel with the others.
- **127 targets on both OSes fit the free plan's 10 GB cache (9.91 GB)**, now that the shared cache leaves out what
  Verity rebuilds for free. It's close to the limit, and caches from other branches count too. GitHub evicts the
  least recently used cache once a repository goes over.

## Automatic planning (2026-11-07)

The 127 targets from [the benchmark below](#the-127-target-benchmark-2026-11-07), run with the plan, prepare and
test jobs from the README's "Fastest CI" example (`max-runners: 20`, default knee). The plan job chose the runners,
the OS split, the cache groups and the shards itself, learning from the previous run's timings. All runs include the
installer race fix, `-DlibraryDirectory` and Defender off. Every target ran exactly once, and every run passed 126
of 127: only `1.16.1-forge` failed, as [before](#failures) (since fixed in the sample mod).

### Both OSes, with the smaller shared cache

The layout that [PR #7](https://github.com/prometheusreengineering/verity/pull/7) ships: the shared cache leaves out
the per-loader copies of the client jar (3.0 GB), installer jars (0.5 GB) and Forge's `-slim` jars (0.5 GB), all of
which Verity rebuilds in seconds.

| Run | Kind | Whole run | Prepare jobs (slowest), Linux / Windows | Runners, Linux + Windows | Slowest / median test job, Linux | Windows | Caches |
|---|---|---|---|---|---|---|---|
| V [42413640566](https://github.com/prometheusreengineering/verity/actions/runs/42413640566) | cold | **14.4** | 5 (5.3) / 5 (6.8) | 7 + 12 | 6.0 / 5.0 | 5.3 / 4.1 | 9.91 GB |
| V [42414861386](https://github.com/prometheusreengineering/verity/actions/runs/42414861386) | warm | **6.9** | none | 9 + 11 | 5.4 / 3.6 | 5.5 / 3.6 | 9.91 GB |
| V [42415473305](https://github.com/prometheusreengineering/verity/actions/runs/42415473305) | warm | **7.0** | none | 7 + 11 | 5.3 / 4.6 | 5.6 / 4.1 | 9.91 GB |

The plan split the targets into 5 groups of whole Minecraft versions (shared caches of 1.0–2.8 GB) and used 18–20
runners. Warm, no prepare job ran, since every group's cache was there. Build took 1.0–1.2 minutes and the plan job
0.3. Runner minutes for the test jobs were 31–34 on Linux and 43–48 on Windows, less than the fixed 10 + 10 layout
(48 + 68).

### Before the smaller shared cache

The same planner with the full shared cache. Cold runs were already much faster, but the caches came to 12–22 GB
together with other branches' CI, GitHub evicted some between runs, and "warm" runs had to prepare again.

| Run | OSes | Kind | Whole run | Prepare jobs (slowest) | Runners | Notes |
|---|---|---|---|---|---|---|
| V [42404353279](https://github.com/prometheusreengineering/verity/actions/runs/42404353279) | both | cold | **16.3** | 10 (6.3 L / 8.6 W) | 7 + 12 | 7.74 GB of caches |
| V [42405843204](https://github.com/prometheusreengineering/verity/actions/runs/42405843204) | both | warm | 16.2 | 8 (3.2) | 8 + 10 | 13 GB, so caches were evicted |
| V [42407213316](https://github.com/prometheusreengineering/verity/actions/runs/42407213316) | both | warm | 19.9 | 4 (10.2) | 7 + 12 | 22 GB, evicted |
| V [42408849877](https://github.com/prometheusreengineering/verity/actions/runs/42408849877) | Linux | cold | **10.6** | 5 (5.3) | 12 | |
| V [42410860192](https://github.com/prometheusreengineering/verity/actions/runs/42410860192) | Linux | cold | **11.9** | 4 (6.2) | 11 | |
| V [42409827160](https://github.com/prometheusreengineering/verity/actions/runs/42409827160) | Linux | warm | 11.6 | 2 (5.3) | 10 | the group layout changed, so 2 groups were new |
| V [42411868011](https://github.com/prometheusreengineering/verity/actions/runs/42411868011) | Linux | warm | 20.9 | 1 (13.7) | 10 | over the budget, the plan fell back to one group |

For comparison, the fixed layout took 15.1–18.5 minutes cold on Linux ×20.

## The 127-target benchmark (2026-11-07)

Every Fabric, Forge and NeoForge target from 1.7.10 up (48 Fabric, 56 Forge, 23 NeoForge), using the sample mod
(`samples/testmod`) with `-Dverity.sample.delay=15`, 2048 MB per client and a 300 s startup timeout. The workflow was a
build job, a `mode: prepare` job per OS and a shard matrix. Clients per runner were 6 on Linux and 5 on Windows, the
best values from the [parallel test](#parallel-clients-per-runner-2026-11-07). Every run was checked to have run each
target exactly once across its shards.

"Cold" means all Verity caches were deleted first. A run whose prepare failed saves no cache, so the next run is
cold too. Those runs are listed by what actually happened, not by what they were meant to be.

### Warm

| Setup | Run | Whole run | Prepare | Slowest / median test job | Runner minutes (test jobs) |
|---|---|---|---|---|---|
| Linux ×10 + Windows ×10 | V [42361939714](https://github.com/prometheusreengineering/verity/actions/runs/42361939714) | **9.3** | 0.1 / 0.2 | Linux 5.2 / 4.8, Windows 8.5 / 6.6 | 48 + 68 |
| Linux ×10 + Windows ×10 (Windows OS cache missing) | V [42358851898](https://github.com/prometheusreengineering/verity/actions/runs/42358851898) | 13.0 | 0.1 / 3.3 | Linux 5.2 / 4.8, Windows 8.9 / 6.7 | 48 + 70 |
| Windows ×20 | V [42370346604](https://github.com/prometheusreengineering/verity/actions/runs/42370346604) | **8.1** | 0.3 | 7.2 / 4.8 | 102 |
| Windows ×20 | V [42370975347](https://github.com/prometheusreengineering/verity/actions/runs/42370975347) | **8.5** | 0.2 | 7.7 / 4.9 | 100 |
| Linux ×20 | V [42372781077](https://github.com/prometheusreengineering/verity/actions/runs/42372781077) | **4.8** | 0.1 | 4.0 / 3.4 | 68 |
| Linux ×20 | V [42375997842](https://github.com/prometheusreengineering/verity/actions/runs/42375997842) | **4.8** | 0.1 | 4.1 / 3.3 | 67 |

### Cold

| Setup | Run | Whole run | Prepare | Slowest / median test job | Notes |
|---|---|---|---|---|---|
| Linux ×10 + Windows ×10 | V [42356858983](https://github.com/prometheusreengineering/verity/actions/runs/42356858983) | 27.1 | 12.2 / 17.5 | Linux 5.4 / 4.7, Windows 8.8 / 6.7 | Windows prepare failed (installer race) |
| Linux ×10 + Windows ×10 | V [42359812538](https://github.com/prometheusreengineering/verity/actions/runs/42359812538) | **29.9** | 16.6 / 21.1 | Linux 5.4 / 4.8, Windows 7.7 / 6.6 | |
| Windows ×20 | V [42364635674](https://github.com/prometheusreengineering/verity/actions/runs/42364635674) | **30.1** | 23.3 | 6.0 / 4.9 | |
| Windows ×20 | V [42368474524](https://github.com/prometheusreengineering/verity/actions/runs/42368474524) | **24.1** | 16.8 | 6.4 / 4.8 | |
| Windows ×20 | V [42362796177](https://github.com/prometheusreengineering/verity/actions/runs/42362796177) | 24.6 | 17.2 | 4.3 / 3.4 | prepare failed, so shards installed their own targets |
| Windows ×20 | V [42366792681](https://github.com/prometheusreengineering/verity/actions/runs/42366792681) | 22.8 | 17.6 | 4.5 / 3.2 | prepare failed, so shards installed their own targets |
| Linux ×20 | V [42371611902](https://github.com/prometheusreengineering/verity/actions/runs/42371611902) | **15.1** | 10.0 | 4.4 / 3.4 | |
| Linux ×20 | V [42374397272](https://github.com/prometheusreengineering/verity/actions/runs/42374397272) | **18.5** | 13.4 | 4.0 / 3.4 | |
| Linux ×20 | V [42373205327](https://github.com/prometheusreengineering/verity/actions/runs/42373205327) | 16.1 | 12.3 | 3.2 / 2.5 | prepare failed, so shards installed their own targets |

Build took 0.5–1.1 minutes in every run.

### Where a warm shard's time goes (20 runners)

From the first five shards of a warm run on each OS, in seconds:

| | Restore shared cache (9.15 GB) | Restore OS cache | Run the games |
|---|---|---|---|
| Linux (V 42375997842) | 60–114 | 4–5 | 88–122 |
| Windows (V 42370346604) | 126–168 | 15–20 | 95–166 |

Each shard downloads the files for all 127 targets but uses 6 or 7 of them. That's why cold shards whose prepare
failed and that installed only their own targets finished *faster* (median 3.2–3.4 min on Windows) than warm ones
(4.8–4.9 min). At this many shards, a cache per shard or a restore of only the needed files would be faster than one
shared cache. With 10 runners per OS, each shard runs 12–13 targets and the restore matters less.

### Caches

| Cache | Size |
|---|---|
| shared (game files, libraries, installers; both OSes) | 9.15 GB |
| Linux (runtimes, natives, Xvfb packages) | 0.50 GB |
| Windows (runtimes, natives, Mesa, 7-Zip) | 1.12 GB |

The free plan allows 10 GB per repository. Linux alone (9.65 GB) fits. Windows alone (10.27 GB) and both OSes
(10.77 GB) don't. GitHub didn't evict anything during these runs, because eviction isn't immediate, but a repository
that keeps running all 127 targets on Windows should expect to lose a cache now and then.

### Failures

These failed in every run, on both OSes:

| Target | Error | Cause |
|---|---|---|
| `1.20.3-forge` (Forge 49.0.2) | `Library directory: libraries does not exist. Set it via the libraryDirectory system property.` | This early Forge build needs `-DlibraryDirectory`, which Verity didn't pass. Fixed in [#6](https://github.com/prometheusreengineering/verity/pull/6). |
| `1.16.1-forge` (Forge 32.0.108) | `NullPointerException` in `ResourcePackLoader.getPackNames` | The sample mod had no `pack.mcmeta`. Vanilla returns no pack info for it, and this Forge build calls `getPackInfo()` on it anyway; later builds handle that. Fixed by adding `pack.mcmeta` to the sample mod. |

Occasional failures:

- **An installer race on cold prepares.** `1.20.1-neoforge` and `1.20.1-forge` both write the same
  `mcp_config`/`client` mappings files, and installing them at once can fail with `Could not delete output file`,
  `FileAlreadyExistsException` or `Processor … produced unexpected outputs`. It broke 4 of the 9 cold prepares,
  on both OSes, and also hit `1.14.3-forge` in shard jobs installing on their own. Verity locked each install but not the
  library files that installers share. Fixed in [#6](https://github.com/prometheusreengineering/verity/pull/6): the
  processors of one Minecraft version run one install at a time, and clients wait until every install of their version
  is done. (`1.14.3-forge` was a separate bug: its processors need vanilla's realms jar, now downloaded first.)
- `1.20.4-forge` crashed once on Windows (`RuntimeException: java.util.concurrent…`) and passed in every other run.

## Parallel clients per runner (2026-11-07)

Essential 1.5.2 on 34 targets (14 Fabric, 13 Forge, 7 NeoForge; 32 more skipped by the version pin), one job per
loader and OS, warm caches, 2 runs per level. "Job" is the test job's wall time, and memory and CPU are sampled during
the run. Windows 7 wasn't tested, since Windows already got slower at 6.

| Clients | Linux, slowest job (avg) | Windows, slowest job (avg) |
|---|---|---|
| 3 | 6.8 | 9.4 |
| 4 | 6.3 | 8.7 |
| 5 | 6.4 | **8.4** |
| 6 | **5.7** | 8.7 |
| 7 | 6.5 | — |

Per loader (minutes per job, average of 2 runs):

| OS | Loader | 3 | 4 | 5 | 6 | 7 |
|---|---|---|---|---|---|---|
| Linux | Fabric | 6.8 | 6.3 | 5.6 | 5.5 | 6.3 |
| Linux | Forge | 6.6 | 6.1 | 6.4 | 5.7 | 6.5 |
| Linux | NeoForge | 3.5 | 4.0 | 3.9 | 3.7 | 3.4 |
| Windows | Fabric | 9.4 | 7.0 | 8.1 | 8.7 | — |
| Windows | Forge | 9.0 | 8.7 | 8.4 | 8.1 | — |
| Windows | NeoForge | 5.0 | 4.7 | 5.0 | 5.6 | — |

CPU averaged 71–93% at every level, and the lowest available memory went from about 10 GB (3 clients) to about
6 GB (7 clients). Nothing swapped on Linux, and Windows used under 20 MB of swap. Nothing failed. The differences
between 4 and 7 clients are within run-to-run noise: the runner is CPU-bound, and more clients only share it. Verity
uses 6 on Linux and 5 on Windows.

Runs (E): 3 clients [42337456527](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42337456527),
[42338735509](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42338735509); 4
[42339477360](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42339477360),
[42340202534](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42340202534); 5
[42340889950](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42340889950),
[42341589817](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42341589817); 6
[42342276420](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42342276420),
[42343000015](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42343000015); 7 (Linux only)
[42343730680](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42343730680),
[42344267946](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42344267946).

## Shards (2026-11-07)

The same 34 Essential targets split across N runners per OS with `shard`, warm, 6 clients on Linux and 5 on Windows,
2 runs each. The unsharded layout (one job per loader, 3 per OS) is shown for comparison.

| Runners per OS | Targets per shard | Linux, slowest job | Windows, slowest job | Runner minutes, Linux / Windows |
|---|---|---|---|---|
| 3 (one per loader, unsharded) | 7–14 | 5.7 | 8.4 | — |
| 4 | 8–9 | 4.4 | 6.1 | 16 / 23 |
| 6 | 5–6 | 3.7 | 5.1 | 19 / 27 |
| 9 | 3–4 | 3.0 | 4.4 | 24 / 33 |

Going from 4 to 9 runners saves about 1.5 minutes per OS and costs about 50% more runner minutes, since each job
spends a fixed couple of minutes on setup and cache restore. One Windows client crashed in the second 9-shard run (the
`1.16.5-fabric` `0xC0000005` access violation, with no crash report and no known cause; it hasn't been seen in any
other run, including the 127-target ones).

Runs (E): 4 [42349700710](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42349700710),
[42350617130](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42350617130); 6
[42351193262](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42351193262),
[42351640061](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42351640061); 9
[42352155490](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42352155490),
[42352655808](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42352655808).

## Cache layouts (2026-11-07)

Essential's 34 targets, one job per loader and OS, before the clients per runner were tuned. Each layout was run cold (no Verity
cache) then warm. All runs are attempts of E [42323792763](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42323792763).
The baseline is E [42321576623](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42321576623),
the first run with the old layout of one cache per job, all of them empty.

| Layout | Attempt | Whole run | Prepare L / W | Fabric L / W | Forge L / W | NeoForge L / W |
|---|---|---|---|---|---|---|
| One cache per job, cold (baseline, no prepare) | — | 11.4 | — | 6.2 / 8.4 | 8.4 / 10.8 | 4.3 / 4.8 |
| One per OS, with the mod cache (Linux cache evicted) | 1 | 14.4 | 3.9 / 5.5 | 5.1 / 8.4 | 6.3 / 7.3 | 4.0 / 5.0 |
| One per OS, cold | 2 | 14.4 | 3.0 / 5.0 | 6.0 / 8.8 | 5.8 / 9.0 | 3.8 / 5.3 |
| One per OS, warm | 3 | 11.6 | 1.0 / 1.5 | 6.3 / 7.6 | 5.5 / 9.7 | 3.7 / 4.6 |
| One per OS without Mesa, cold | 4 | 13.3 | 3.1 / 4.3 | 6.5 / 7.8 | 5.8 / 8.6 | 3.5 / 4.5 |
| One per OS without Mesa, warm | 5 | 10.5 | 0.9 / 1.6 | 6.8 / 7.2 | 6.3 / 8.6 | 3.9 / 5.0 |
| Three caches (shared + per OS), cold | 9 | 14.7 | 3.5 / 5.8 | 6.1 / 6.6 | 6.6 / 8.3 | 3.9 / 4.3 |
| Three caches, warm | 10 | **10.1** | 0.1 / 0.3 | 6.4 / 9.3 | 5.4 / 8.6 | 3.8 / 5.0 |

Attempts 6–8 were fixes for bugs in the three-cache layout (a failed Linux prepare, then a duplicate shared cache
from CRLF line endings) and aren't comparable. Attempt 1's Linux cache was evicted because the total went over 10 GB.

What this shows:
- Caching barely changes test job times, because the test jobs restore a cache either way and their time goes to
  running games. What it changes is the prepare job: warm, it drops to under 20 seconds once nothing is missing.
- The baseline has no prepare job, so its whole run is shorter than a cold run with one. It isn't a fair "no cache"
  comparison: every job installed only its own loader's targets, in parallel.
- Essential's 34 targets use about 4.2 GB per OS in one cache per OS. In the three-cache layout, the shared part is
  stored once for both OSes.

With 6 Linux and 5 Windows clients, the three-cache layout ran cold in 13.6 minutes
(E [42344862596](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42344862596)).

### Install times

Per target, from attempt 2's cold prepare, with all 34 targets installing at once:

| Loader | Linux, median | Windows, median | Slowest |
|---|---|---|---|
| Fabric | 35 s | 52 s | 52 s |
| NeoForge | 50 s | 79 s | 198 s |
| Forge | 91 s | 128 s | 208 s |

Forge and NeoForge run their installer's processors, which is most of their install time. This was taken from the run's
reports at the time, and those per-target times weren't re-derived for this document.

## OS comparison before caching (up to 2026-11-06)

Median job time over the last 5 nightly runs of `full-matrix.yml` (`samples/testmod/verity-full.json`: the newest
patch of every minor version per loader, about 34 targets, one job per loader and OS, no Verity cache). Runs:
V 42207186414, 42016046005, 41818470351, 41758285345, 41752388070.

| Job | Linux | Windows | Windows / Linux |
|---|---|---|---|
| Fabric | 3.1 | 4.3 | 1.4× |
| Forge | 5.4 | 7.8 | 1.4× |
| NeoForge | 2.5 | 3.9 | 1.6× |

The PR matrix in `ci.yml` (7 targets, single run V 42315271932) took 3.6 minutes on Linux and 4.7 on Windows.

## macOS (2026-11-07, not supported)

Short probes on `macos-latest` (Apple Silicon) and `macos-13` (Intel), windowed, since macOS runners have no
virtual display. The runners have no GPU that OpenGL can use, so whether a version works depends on how it opens its
window.

| Versions | Apple Silicon | Intel |
|---|---|---|
| 1.8.9, 1.12.2 (LWJGL 2) | No Java 8 for arm64 (Adoptium 404) | Crash: `Could not create pixel format` |
| 1.20.1, 1.21.1 (GLFW) | The sample mod passes, but **falsely**: it reports from a background thread while the game is stuck before opening a window. With Essential, these time out. | Same false passes |
| 26.2 (GLFW, Vulkan) | Fabric passes (Vulkan through MoltenVK); Forge and NeoForge time out with Essential | Crash, exit code 134 |
| 26.3 (SDL3) | Passes with Fabric, Forge and NeoForge, with Essential too (Apple's software renderer) | Crash, exit code 134 |

Clients that don't crash take minutes per target (110–250 s for the newest versions on Apple Silicon), and the free
plan allows only 5 macOS jobs at once. Runs: V [42349356132](https://github.com/prometheusreengineering/verity/actions/runs/42349356132),
V [42351036706](https://github.com/prometheusreengineering/verity/actions/runs/42351036706),
E [42351595070](https://github.com/prometheusreengineering/minecraft-essential/actions/runs/42351595070) (Essential).
Each is a single run.

## Local (Windows desktop)

A 4-target Essential run on a Windows desktop (single run, not timed) found the `1.21.7-fabric` crash that led to
pinning Essential 1.5.2. It's listed for completeness. Desktop numbers depend too much on the machine to compare.
