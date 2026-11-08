# Integrating Verity into minecraft-essential

[minecraft-essential](https://github.com/prometheusreengineering/minecraft-essential) is a single Java 8 jar that loads
on Forge 1.8+ (LaunchWrapper `TweakClass` + `mcmod.info`), Fabric 1.16+ (`fabric.mod.json`) and NeoForge 1.20+. It
patches the Essential mod, which ships a different jar per version and loader. This guide sets it up so that every
change is tested on all of them at once.

## 1. Add the test hook

`verity-api` is one dependency-free class. Choose one option:

**A. Vendor it (simplest with Maven).** Copy
[`Verity.java`](../verity-api/src/main/java/ing/prometheusreengineer/verity/api/Verity.java) into your sources under your own package
(for example `studio.dreamys.prometheus.verity.Verity`). Nothing else changes.

**B. Shade it.** Each release deploys `verity-api` to `https://maven.prometheusreengineer.ing/`, which needs no token
(GitHub Packages has it too, but requires one even to read). Add the repository, then shade it *with relocation*:

```xml
<repository>
  <id>prometheus</id>
  <url>https://maven.prometheusreengineer.ing/</url>
</repository>
...
<dependency>
  <groupId>ing.prometheusreengineer.verity</groupId>
  <artifactId>verity-api</artifactId>
  <version>0.1.0</version>
</dependency>
...
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <version>3.6.0</version>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>shade</goal></goals>
      <configuration>
        <artifactSet><includes><include>ing.prometheusreengineer.verity:verity-api</include></includes></artifactSet>
        <relocations>
          <relocation>
            <pattern>ing.prometheusreengineer.verity.api</pattern>
            <shadedPattern>studio.dreamys.prometheus.shadow.verity</shadedPattern>
          </relocation>
        </relocations>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Relocation matters because Forge 1.17+ and NeoForge refuse to load two jars that contain the same package.

## 2. Report what matters

Call the API where the patch can prove that it worked. The calls do nothing outside Verity, so they can stay in
release builds:

```java
// after the mixins/patches are applied and Essential's cosmetics are loaded
Verity.test("essential detected", () -> {
    if (!EssentialBridge.isLoaded()) throw new AssertionError("Essential classes not found");
});
Verity.test("cosmetics unlocked", () -> {
    int unlocked = CosmeticsPatch.unlockedCount();
    if (unlocked == 0) throw new AssertionError("no cosmetics unlocked");
});
Verity.test("dump written", () -> {
    if (!Files.exists(DumpWriter.cosmeticsDump())) throw new AssertionError("dump missing");
});
Verity.attach("cosmetics.json", Files.readAllBytes(DumpWriter.cosmeticsDump()));
Verity.finish();
```

If one hook can't cover everything, you can also call `pass`/`fail` from several places and call `finish()` once from
the last one.

## 3. Add a profile to the repository

`verity.json` at the repository root:

```json
{
  "name": "minecraft-essential",
  "targets": [
    { "select": { "minecraft": "[1.8.9,1.12.2]", "loaders": ["forge"], "latestPatchOnly": true } },
    { "select": { "minecraft": ">=1.16", "loaders": ["fabric"], "latestPatchOnly": true } },
    { "select": { "minecraft": ">=1.20", "loaders": ["neoforge"], "latestPatchOnly": true } }
  ],
  "mods": [
    { "path": "target/prometheus-*-essential.jar" },
    { "path": "verity/essential/" }
  ],
  "account": { "type": "msa" },
  "test": { "startupTimeoutSec": 900, "timeoutSec": 1200 }
}
```

- `verity/essential/` holds the Essential jars (one per version/loader, as downloaded from essential.gg or Modrinth).
  Verity reads their `fabric.mod.json` / `mcmod.info` / `mods.toml` and installs each one only on the targets it
  supports. Check the result with `verity plan verity.json -v`.
- The ranges pick up new Minecraft releases automatically. Use explicit `{ "minecraft": "1.8.9", "loader": "forge" }`
  entries to pin a fixed set instead.
- Essential needs an online account for cosmetics, hence `"type": "msa"`. Run `verity account login` once locally. On
  CI, store `verity account ci-token <name>` as the `VERITY_MSA_REFRESH_TOKEN` secret.

## 4. Run it

Locally:

```bash
mvn -q package && java -jar verity-cli.jar run verity.json
```

On every change, rebuild and run it again: the glob picks up the new jar.

On CI (`.github/workflows/verity.yml`):

```yaml
name: Verity
on: [push, pull_request]
jobs:
  verity:
    strategy:
      fail-fast: false
      matrix:
        os: [ubuntu-latest, windows-latest]
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "8" }
      - run: mvn -B -q package
      - uses: prometheusreengineering/verity@v0.1.1
        env:
          VERITY_MSA_REFRESH_TOKEN: ${{ secrets.VERITY_MSA_REFRESH_TOKEN }}
        with:
          profile: verity.json
          parallel: "3"
```
