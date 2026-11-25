# SeaRobots Submarine Template

A minimal, self-contained Java 25 starter repository for building an autonomous submarine
for [SeaRobots](https://github.com/thegreystone/searobots) and the SeaRobots Monthly League.

The included [StarterSubmarine](src/main/java/com/example/mysub/StarterSubmarine.java)
and [StarterTorpedo](src/main/java/com/example/mysub/StarterTorpedo.java) are
deliberately simple and heavily commented: **clarity and readability are prioritized over
combat strength** so you have a clean starting point to build your own tactics.

---

## Quick Start

Requires **JDK 25+** and **Maven 3.9+**.

1. Click **Use this template** on GitHub (or clone this repository).
2. Run `mvn test`. It compiles your sub the same way the league does and plays a short
   practice match.
3. Run `mvn -Pwatch` to watch your sub fight the built-in `Claude` submarine in the 3D viewer.

Then read `StarterSubmarine.onTick(...)` from top to bottom. That one method is the whole
"brain": listen, decide (evade, pursue, or patrol), stay safe, and steer.

---

## Important: How Your Submarine Is Compiled in the League

This repository includes a `pom.xml` purely for **local development, IDE support,
sparring, 3D visualization, and unit tests**.

**For security reasons, the SeaRobots League never runs Maven, Gradle, or any build
script from your repository.** Instead, the league fetches your pinned commit and compiles
your Java sources directly:

```bash
javac --release 25 -proc:none -cp searobots-api-<version>.jar -d out $(find src/main/java -name '*.java')
```

That means your submarine under `src/main/java/` must follow these rules:

- **Self-contained Java 25 source only** under `src/main/java/` (no precompiled JARs,
  no native code, no annotation processors), at most 64 `.java` files and 512 KB in total.
- **Zero external dependencies** other than `se.hirt.searobots:searobots-api` and the
  standard JDK 25 library.
- **No `searobots-engine` or `searobots-viewer` imports** in `src/main/java/` (both are
  `test` scope / dev profiles only, so your IDE and compiler enforce this automatically).
- **Safe JDK subset only:** no reflection (`java.lang.reflect`), `MethodHandles`/unsafe
  invokes, filesystem (`java.io`/`java.nio`), networking (`java.net`), threads/executors,
  `Runtime`/`ProcessBuilder`, `ClassLoader`, `System.out`, or `System.exit`.
- **Public no-arg constructor** on your `SubmarineController` class.
- **Readable source code** operating in good faith (no obfuscation or stalling).

`mvn -Pcheck` runs these checks locally, and CI runs them on every push.

---

## Developer Workflow (One-Line Maven Commands)

| Command | What It Does |
|---|---|
| `mvn test` | Runs fast unit tests, bare-`javac` compile check, and records a starter replay |
| `mvn -Pwatch` | Launches the **interactive 3D viewer** pitting your sub live against `Claude` |
| `mvn -Pwatch -Dopponent=codex -Dseed=2c` | Watch your sub live in 3D against `claude`, `codex`, `gemini`, `default`, `drone`, or another FQCN |
| `mvn -Pmatch33` | Runs **33 parallel headless league-rules matches** (alternating spawns + CPU guardrails) vs `Claude`, saving loss replays to `target/replays/` |
| `mvn -Pmatch33 -Dopponent=all` | Runs a **full gauntlet** (33 games each vs `Default`, `Gemini`, `Claude`, and `Codex`) with a combined scoreboard |
| `mvn -Pmatch33 -Dopponent=codex -Dseed=202612` | Reproducible 33-game A/B benchmark on fixed maps to measure whether a tweak improved win rate |
| `mvn -Preplay -Dreplay=target/replays/<file>.srl` | Opens any recorded `.srl` replay straight in the 3D visualizer |
| `mvn -Pcheck` | Runs the **league pre-flight check** (bare `javac -proc:none`, bytecode rules scan, 2-seed guarded smoke test) |
| `mvn -Pmanifest` | Prints a ready-to-paste `entries/<slug>.json` registration manifest using your current Git commit SHA |

Plain `mvn -Preplay` opens the practice match recorded by `mvn test`. Every live
`mvn -Pwatch` match is also recorded to `replays/` so you can rewatch it later.

> **macOS:** `-XstartOnFirstThread` is added automatically by the OS-activated Maven profile —
> Windows, Linux, and macOS all run the exact same `mvn -Pwatch` and `mvn -Preplay` commands.

### Sparring Against Another Local Submarine Repo

You can also test your sub head-to-head against a second submarine repository on your machine.
Build that repository first (`mvn compile` there), then point `-DextraCp` at its classes:

```bash
mvn -Pmatch33 -Dopponent=se.hirt.searobots.sub.geminineon.GeminiAttackSub \
  -DextraCp=../searobots-gemini-neon/target/classes

mvn -Pwatch -Dopponent=se.hirt.searobots.sub.geminineon.GeminiAttackSub \
  -DextraCp=../searobots-gemini-neon/target/classes
```

---

## Visualizing Your Submarine's "Brain" in 3D

`StarterSubmarine` publishes tactical telemetry via `output.publishContactEstimate(...)`,
`output.publishWaypoint(...)`, `output.publishStrategicWaypoint(...)`,
`output.publishFiringSolution(...)`, and `output.setStatus(...)`.

Inside `mvn -Pwatch` or `mvn -Preplay`, use these viewer hotkeys to inspect your submarine's
decisions in real time:

- **H**: Show all viewer controls
- **P**: Pause / resume (**N** steps forward a single tick)
- **1–6 / 0**: Simulation speed / maximum speed (**.** fast-forwards to the next pause event)
- **M**: 2D tactical map overlay
- **E**: Estimated enemy contact positions & uncertainty rings
- **W / G**: Planned A\* waypoints & strategic goals
- **S**: Active sonar ping rings
- **T**: Hull & torpedo trails
- **I**: Info HUD with your `setStatus` label and scores (hidden by default)
- **D / F / L**: Toggle pause on death / firing solution / torpedo launch
- **Space**: New random map (**Ctrl+R** re-runs the current seed, **Ctrl+C** copies it)
- **F2**: Simulation settings

---

## Making It Yours & Submitting to the League

1. Rename the package (`com.example.mysub`), `StarterSubmarine`, and `StarterTorpedo` to
   your own names. Then update the two places that name your controller class:
   `<sub.class>` in `pom.xml`, and
   `src/main/resources/META-INF/services/se.hirt.searobots.api.SubmarineController`
   (this lets the 3D viewer list your sub in its ship picker).
2. Update `name()` (2–24 characters) and `shortName()` (2-character sail code, `[A-Z0-9]{2}`).
3. Choose your license: update `LICENSE` and the matching SPDX id in `<license>` in `pom.xml`.
4. Run `mvn -Pmatch33 -Dopponent=all` and inspect any losses with `mvn -Preplay -Dreplay=...`.
5. Run `mvn -Pcheck` to catch league rule violations early.
6. Commit and push to your **public** GitHub repository.
7. Run `mvn -Pmanifest`, check the printed `githubUser` and `repo` fields, then open a
   Pull Request on [searobots-league](https://github.com/thegreystone/searobots-league)
   adding it as your `<YEAR>/<MONTH>/entries/<slug>.json` file.

---

## Where to Start Improving

The starter is kept simple on purpose, so it has some obvious weaknesses that make good
first projects:

- **Terrain:** only the patrol mode plans a route around shallow water. While evading,
  tracking, or pursuing it steers straight and only changes *depth* to clear the sea floor,
  so it can run aground. Try using `PathPlanner` in every mode.
- **Torpedoes:** `StarterTorpedo` homes on the strongest active sonar return. Torpedoes have
  no friend-or-foe identification, so that return could be your own submarine.
- **Evasion:** it simply turns 90° away from an incoming torpedo and sprints at full speed.

---

## Project Layout

```text
searobots-template/
├── pom.xml                                        # Local dev, viewer, sparring & test profiles (NOT run by league)
├── LICENSE                                        # BSD-3-Clause; replace with your own choice
├── .github/workflows/ci.yml                       # Automatic CI on push/PR (tests + league pre-flight)
├── src/main/java/com/example/mysub/
│   ├── StarterSubmarine.java                      # Simple, readable SubmarineController
│   └── StarterTorpedo.java                        # Simple, readable TorpedoController
├── src/main/resources/META-INF/services/
│   └── se.hirt.searobots.api.SubmarineController  # Names your controller class for the viewer
└── src/test/java/com/example/mysub/
    └── StarterSubmarineTest.java                  # Patrol test, combat replay, bare-javac check
```
