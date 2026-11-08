# Releasing

A release is a `vX.Y.Z` tag pushed from `main`. Everything else is automated by
[`release.yml`](../.github/workflows/release.yml).

## Picking the version

Verity follows semantic versioning. Users depend on four things, and a release that breaks any of them is a major one:

| Surface | Breaking (major) | Additive (minor) |
|---|---|---|
| `verity-api` (shaded into mods) | Renamed package, removed or changed method, a new required system property, a different Java target | A new method or constant |
| The action's inputs and outputs | A removed or renamed input, a changed default that alters results, a different `job` format for plan mode | A new input with a default that keeps today's behavior |
| Profile JSON (`verity.json`) | A removed or renamed field, a stricter validation that rejects profiles that used to work, a changed default that makes passing targets fail | A new optional field |
| CLI | A removed command or flag, a changed exit code | A new command or flag |

- **Patch** (`v1.0.1`): bug fixes only, no new options. It includes fixes that make a failing target pass, such as
  launcher or installer fixes, or a new Minecraft version that already matches a profile's selectors.
- **Minor** (`v1.1.0`): new features that leave existing profiles, workflows and mods working as before.
- **Major** (`v2.0.0`): anything in the "Breaking" column above.

A change to the reports (`report.json`, JUnit, HTML) counts as minor if it only adds fields, and as major if it
removes or renames one, since people parse them.

## Cutting a release

1. Make sure CI is green on the `main` commit you're releasing. For anything bigger than a small patch, also run
   **All targets** (Actions → All targets → Run workflow) and wait for it to pass.
2. Tag and push:

   ```bash
   git switch main && git pull
   git tag v1.2.0
   git push origin v1.2.0
   ```

3. Watch the **Release** workflow. It:
   1. builds and tests on Linux and Windows, with the version taken from the tag (`gradle.properties` is ignored);
   2. deploys `verity-api` to `prometheusreengineering/maven` and GitHub Packages;
   3. creates the GitHub release with `verity-cli.jar` and `verity-api-<version>.jar`, plus generated notes, and
      marks it as the latest release if it's the highest version;
   4. moves the major tag (`v1`) to the release, so `prometheusreengineering/verity@v1` picks it up. If GitHub
      refuses to let the job's token move it, the step leaves a warning with the command to run yourself; the
      release is complete apart from that.
4. Edit the release on GitHub and rewrite the generated notes. They only list merged PRs, so they miss commits pushed
   to `main` directly. Group changes under **Breaking changes**, **New**, **Fixes** and **Known issues**.
5. Check it worked: in any workflow that uses `prometheusreengineering/verity@v1` (no `jar` input), the **Get Verity**
   step should print `Verity CLI: v1.2.0`.

You don't need to bump `gradle.properties`. It only names local builds (`1.0.0-SNAPSHOT`), and you can bump it
whenever convenient.

### Patch

Tag `vX.Y.(Z+1)` as above. Users on `@v1` get it on their next run, and users pinned to `@v1.Y.Z` stay where they are.

### Minor

Tag `vX.(Y+1).0` as above. Document new inputs, fields or flags in the README in the same change that adds them,
not at release time.

### Major

Before tagging `v2.0.0`:

1. Change every `prometheusreengineering/verity@v1` in `README.md` and `docs/` to `@v2`.
2. Bump `verity-api`'s version in the Maven example in `docs/integrating-minecraft-essential.md`.
3. Write the breaking changes and how to migrate at the top of the release notes.

Then tag `v2.0.0`. The release job creates the `v2` tag. `v1` stays on the last 1.x release, so existing users don't
break, and they move to `@v2` when they're ready. A fix for 1.x users goes on a branch from the last `v1.Y.Z` tag
and is tagged `v1.Y.(Z+1)`. The release job then moves `v1` to it, while `v2` is unaffected. It also doesn't become
GitHub's latest release, because the job only marks the highest version as latest.

## Pre-releases

A tag with a suffix, such as `v1.2.0-rc1`, runs the same workflow. The GitHub release is marked as a pre-release,
`v1` doesn't move, and neither `@v1` nor the latest release picks it up. Anyone testing it pins it with
`@v1.2.0-rc1`, which runs exactly that CLI. Its `verity-api` is published too, under the suffixed version.

## How the action finds its CLI

The action downloads the CLI from the release matching the ref it was used at:

| Used as | Runs |
|---|---|
| `@v1.2.0` or `@v1.2.0-rc1` | Exactly that release |
| `@v1` | The newest `v1.x.y` release (not pre-releases) |
| `@main`, a branch or a commit SHA | The latest release (the highest version, not pre-releases) |
| `version: v1.1.0` input | `v1.1.0`, whatever the ref |
| `jar:` input | That jar (Verity's own CI uses this) |

This is why a pinned action keeps working: `@v1.0.0` never runs a CLI from a later release that its `action.yml`
doesn't know about. Releases before 1.0.0 always took the latest CLI.

## When a release fails

- **Build or tests fail:** nothing was published. Delete the tag, fix the problem on `main`, then tag again:

  ```bash
  git push origin :refs/tags/v1.2.0 && git tag -d v1.2.0
  ```

- **The deploy or release job fails:** use **Re-run failed jobs**, not "Re-run all jobs". The Maven repository
  step skips a version that's already there, but GitHub Packages rejects a version it already has, so a full re-run
  fails once the publish job has succeeded.
- **A bad release went out:** don't move or delete its tag, since people may have pinned it. Fix the problem and
  release the next patch. If it's harmful, mark the GitHub release as a pre-release so `@v1` and "latest" skip it,
  and then make sure `v1` points at the previous good release:

  ```bash
  git tag -f v1 v1.1.0 && git push -f origin refs/tags/v1
  ```

Never push a moving tag (`v1`) by hand except to repair it like this. The release job owns it.
