# CLAUDE.md

Verity launches many Minecraft clients (vanilla, Fabric, Forge, NeoForge; 1.7.10 → year-versioned 26.x) in parallel,
headless or windowed, to test version/loader-independent mods. The mod reports results through `verity-api`. See
`README.md` for user-facing docs and `docs/integrating-minecraft-essential.md` for the reference integration.

## Build and test

```bash
./gradlew build                        # all modules + unit tests (Java 21 toolchain)
./gradlew :verity-core:test --tests '*RunOrchestratorTest*'
./gradlew :verity-cli:shadowJar        # verity-cli/build/libs/verity-cli-<v>-all.jar (released as verity-cli.jar; used by the action)
./gradlew :samples:testmod:shadowJar :samples:testagent:shadowJar   # sample mod + agent used by the e2e matrices
```

- Tests tagged `e2e` are excluded unless `-Pe2e` is passed. `XvfbDisplayTest` skips itself without Xvfb.
- `RunOrchestratorTest` runs the whole pipeline against fake game JVMs (`FakeGame`) from a pre-seeded home, so it
  needs no network. Use that pattern for anything touching install/launch/session logic.
- Real Minecraft is only exercised on CI, through the repo's own `action.yml` in plan mode, on Linux and Windows.
  `ci.yml` runs `samples/testmod/verity.json` (the newest patch of every minor version, vanilla and each loader)
  on every push and PR, with cached installs. `all-targets.yml` runs `samples/testmod/verity-all.json` (every
  version, ~127 targets) nightly and cold: `cache: false` and no prepare jobs, since older patches and first installs
  are where launcher bugs hide. Locally, keep `-p` at about 3; each client plus Mesa takes several GB. Cloud sandboxes usually can't reach
  Mojang/Fabric/Forge/NeoForge hosts, so check CI logs (GitHub MCP `get_job_logs`) to verify real launches.
- Maven Central sometimes returns HTTP 429. Retry with `--max-workers=2` and a short backoff.
- Pushing a `vX.Y.Z` tag releases: `release.yml` takes the version from the tag, deploys `verity-api` to
  `prometheusreengineering/maven` (needs the `MAVEN_REPO_DEPLOY_KEY` secret) and GitHub Packages, creates the
  GitHub release that `action.yml` downloads from, then moves the major tag (`v1`) to it unless it's a pre-release.
  The action runs the CLI of the ref it was used at (`@v1.0.0` exactly, `@v1` the newest 1.x.y, else latest), read
  from `github.action_path`, so a pinned action never runs a CLI from a later, incompatible release.
- `action.yml` caches: one shared across OSes plus one per OS, keyed by a line-ending-free profile hash (`hashFiles` sees
  CRLF on Windows) and the week. Keep the cache's `path` lists identical everywhere, as they're part of its identity. A
  matched directory goes to tar whole, ignoring exclusions in it, so libraries are matched by file type. The installer
  marker means a missing generated library is never rebuilt, so add new library file types to the lists and the check.
  A skipped step's output is null, and `null == '0'` is true in an expression, so check `outcome` too.
- Plan mode (`verity matrix`) gives each job a matrix entry that the action takes whole as `job`. Planned jobs key their
  caches by their cache group (`CiPlanner.key`: profile hash plus the group's target ids), not the profile. Groups are
  split by built-in costs only, so learned timings (from `verity-timing-*` artifacts of the workflow's last run) never move
  them and invalidate caches. `CostModel`'s numbers come from docs/performance.md; update both together.

## Layout

| Module | Notes |
|---|---|
| `verity-api` | **Java 8 (`--release 8`), zero dependencies.** Mods shade and relocate it. Never add deps or newer APIs. |
| `verity-core` | Engine. Packages: `meta` (Mojang/Fabric/Forge/NeoForge metadata), `install` (vanilla/Fabric/Forge processors, Java runtimes), `launch` (command builder, natives, instance layout, log parsing), `display` (window, Xvfb, Win32 hidden desktop + Mesa), `mods` (jar inspection, `ModPlanner`: per-target assignment of local + Modrinth mods), `modrinth` (API client, per-target file/dependency picking), `profile`, `hook` (socket server for verity-api), `run` (orchestrator, `ClientSession` state machine, `Shard`), `ci` (`CiPlanner`/`CostModel`: runner count, OS split, shards and cache groups for `verity matrix` and the action's plan mode), `report` (JSON/JUnit/HTML), `auth`, `net`, `home`, `util` |
| `verity-cli` | picocli. Exit codes: 0 pass, 1 test failures, 2 config error (`ProfileException`), 3 internal error |
| `samples/testmod` | One Java 8 jar for Fabric, FML 1.7.10, Forge 1.8–1.12, Forge 1.13+, NeoForge, built against compile-only stubs in `src/stubs`. Only reports "loaded via"; with the agent attached, the agent finishes |
| `samples/testagent` | Java 8 `-javaagent` (profile mod entry with `"agent": true`) on every target. Passes once the render thread (the one running `net.minecraft.client.main.Main.main`) shows a title-screen frame: Fabric `class_442`, Forge MCP `GuiMainMenu`/`MainMenuScreen`, Mojang `TitleScreen`, vanilla's obfuscated name from `verity.mappings` (1.14.4+) or hardcoded (1.14–1.14.3). Vanilla before 1.14 has no names: `Minecraft.run()` (a default-package method staying under `Main.main`) leaving `startGame` (1.7.10–1.12.2) or just running (1.13, which loads in the constructor). Set `-Dverity.sample.trace=true` to log the render thread |

## Conventions and gotchas

- **Never touch the user's `.minecraft`.** Everything lives under the Verity home (`VerityHome`). Each client gets
  `instances/<profile>/<target>/{game,natives,home}`, and `run.isolateHome` sandboxes HOME/APPDATA/user.home.
- Minecraft version ordering uses `McVersion` (numeric), never string compare. Version ranges go through
  `VersionPredicate` (Fabric, Maven/mods.toml and selector syntaxes).
- Downloads go through `Downloader` (sha1/size verify, mirrors, atomic moves). Small metadata goes through `MetaCache`,
  which serves stale copies when offline. Installs are serialized with `PathLocks` (in-process + cross-process).
- Forge/NeoForge: `ForgeInstaller` runs installer processors itself. `replaceTokens` mirrors Forge's installer
  semantics exactly; keep it that way. NeoForge versions come from the Reposilite API first, then maven-metadata.xml.
- Forge processors run on Java 17+ (Forge's expected output hashes come from a modern JVM; Mojang's 8u51 writes
  different zip bytes), with `-Xmx2048M` and at most 2 at once. Installs aren't limited by `maxParallel`. All Forge
  and NeoForge installs of one Minecraft version write the same mappings and client jars, so their processors take the
  `forge-processors-<mc>` lock, and their clients wait (`InstallBarrier`) until every such install of the version in the
  run is done, since a second installer rewrites those files under a running client. Vanilla's libraries are
  downloaded before the processors run (1.14.x reads realms).
- `VersionResolver.merge` dedupes libraries only across the inheritance chain. Within one JSON, keep duplicates:
  1.14-1.18 list LWJGL twice (macOS 3.2.1, others 3.2.2), and rules pick one.
- Mojang's Java 16.0.1/17.0.1 runtimes (`java-runtime-alpha`/`beta`, MC 1.17-1.18) crash in the JIT on AVX-512 CPUs,
  so `LaunchCommandBuilder` adds `-XX:UseAVX=2` for them (x86 only).
- Minecraft 26.3+ opens its window with SDL3 and asks for an sRGB-capable GL framebuffer, which Xvfb's GLX visuals
  lack, so `XvfbDisplay` sets `SDL_VIDEO_FORCE_EGL=1`. Without a working OpenGL or Vulkan backend the game hangs on a
  dialog (and a mod that connects early would still pass), so `ClientSession` reports CRASHED when the log shows both
  failed.
- Cached files can be torn by a crash or power loss: right size, garbage content. Installer jars have no checksum
  (they're re-downloaded if they don't open as a zip), and child jars and installer-extracted libraries are compared
  by content, not size. Java runtimes are re-hashed against Mojang's manifest once per process (a zeroed `lib/modules`
  makes every processor die with heap corruption), and Windows Mesa runtime copies are compared with their source.
- Child version jars are copied to `versions/<id>/<id>.jar`, because Forge's `ignoreList` relies on `${version_name}.jar`.
- Windows: Mesa (mesa-dist-win) is unpacked with 7-Zip (`7z.exe` or a downloaded `7zr.exe`), because the archives use
  BCJ2, which commons-compress can't decode. Some releases ship builds without OpenGL (26.2.6 MSVC), so `MesaGl`
  tries MSVC then MinGW across recent releases. With `softwareGl=auto`, a Mesa failure falls back to the GPU, which
  CI runners lack, so a failed Mesa download fails the client. With `GITHUB_TOKEN` set, archives come through the
  asset API first (anonymous release downloads get refused after many cold runs); `Http.redirectTarget` keeps the
  token off the storage host.
- `Status.SKIPPED` (from `run.onlyTargetsWithAllMods`) is not a failure: use `Status.failure()`, not `!success()`,
  when deciding pass/fail. Modrinth tests use `FakeModrinth` (local HttpServer); point clients at it with the
  `verity.modrinth.api` system property.
- Don't name boolean helpers on profile POJOs `isX()`/`getX()` for an existing field `x`: Jackson merges them and a
  `@JsonIgnore` on the helper drops the field (see `ModEntry.fromModrinth()`).
- Tests must be path-separator agnostic (Windows CI runs them): build expected paths with `Path.of(...).toString()`.
  Never put glob characters through `Path.of` (Windows rejects `*`); see `ProfileStore.absolutize`.
- Code style: match the surrounding code (Java 21 records/switch expressions in core, Java 8 in api/testmod/testagent),
  sparse comments that explain why, no new dependencies without need (versions in `gradle/libs.versions.toml`).
