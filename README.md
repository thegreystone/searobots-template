# SeaRobots Submarine Template

A minimal, self-contained Java 25 starter repository for building an autonomous submarine
for [SeaRobots](https://github.com/thegreystone/searobots) and the SeaRobots League.

The included [StarterSubmarine](src/main/java/com/example/mysub/StarterSubmarine.java)
and [StarterTorpedo](src/main/java/com/example/mysub/StarterTorpedo.java) are
deliberately simple and heavily commented: **clarity and readability are prioritized over
combat strength** so you have a clean starting point to build your own tactics.

---

## Important: How Your Submarine Is Compiled in the League

This repository includes a `pom.xml` purely for **local development, IDE support, and
running tests (`mvn test`)**.

**For security reasons, the SeaRobots League never runs Maven, Gradle, or any build
script from your repository.** Instead, the league fetches your pinned commit and compiles
your Java sources directly:

```bash
javac --release 25 -proc:none -cp searobots-api-<version>.jar -d out $(find src/main/java -name '*.java')
```

That means your submarine under `src/main/java/` must follow these rules:

- **Self-contained Java 25 source only** under `src/main/java/` (no precompiled JARs,
  no native code, no annotation processors).
- **Zero external dependencies** other than `se.hirt.searobots:searobots-api` and the
  standard JDK 25 library.
- **No `searobots-engine` imports** in `src/main/java/` (`searobots-engine` is `test`
  scope only, so your IDE/compiler enforces this automatically).
- **Safe JDK subset only:** no reflection (`java.lang.reflect`), `MethodHandles`/unsafe
  invokes, filesystem (`java.io`/`java.nio`), networking (`java.net`), threads/executors,
  `Runtime`/`ProcessBuilder`, `ClassLoader`, or `System.exit`.
- **Public no-arg constructor** on your `SubmarineController` class.
- **Readable source code** operating in good faith (no obfuscation or stalling).

---

## Quick Start

Requires **JDK 25+** and **Maven 3.9+**.

### 1. Run the starter tests (and record a replay)

```bash
mvn test
```

Maven pulls `se.hirt.searobots:searobots-api` and `se.hirt.searobots:searobots-engine`
directly from Maven Central — no authentication or engine checkout required.

`StarterSubmarineTest` verifies:
1. Your submarine survives solo navigation without crashing, grounding, or leaving the
   battle area.
2. Your submarine fights a headless combat match against `DefaultAttackSub` and writes a
   replay file to `target/replays/starter-vs-default.srl`.
3. Your `src/main/java` sources compile cleanly with bare `javac --release 25 -proc:none`
   against `searobots-api` alone (mirroring the league build).

### 2. Watch your submarine in the 3D viewer

Download `searobots-<version>.jar` from the
[SeaRobots Releases](https://github.com/thegreystone/searobots/releases/latest) page and
open the recorded replay:

```bash
# Linux / Windows
java -XX:+UseZGC -Xmx2g -jar searobots-0.1.2.jar target/replays/starter-vs-default.srl

# macOS
java -XstartOnFirstThread -XX:+UseZGC -Xmx2g -jar searobots-0.1.2.jar target/replays/starter-vs-default.srl
```

Inside the viewer, press **E** (contact estimates), **W** (waypoints), **S** (sonar
pings), **T** (trails), **I** (info panels), and **M** (2D tactical map) to see exactly
what your submarine thought during the match.

### 3. Make it yours

1. Rename the package (`com.example.mysub`), `StarterSubmarine`, and `StarterTorpedo` to
   your own names.
2. Update `name()` (under 20 characters) and `shortName()` (2-character sail code, e.g.
   `"RO"`).
3. Test against tougher built-in opponents in `StarterSubmarineTest` — `searobots-engine`
   includes `DefaultAttackSub`, `ClaudeAttackSub`, `CodexAttackSub`, and `GeminiAttackSub`!
4. Iterate on stealth, thermocline hiding, passive TMA maneuvering, salvo timing,
   evasion, and custom torpedo homing.

---

## Project Layout

```text
searobots-template/
├── pom.xml                                        # Local dev & test build (NOT run by league)
├── src/main/java/com/example/mysub/
│   ├── StarterSubmarine.java                      # Simple, readable SubmarineController
│   └── StarterTorpedo.java                        # Simple, readable TorpedoController
└── src/test/java/com/example/mysub/
    └── StarterSubmarineTest.java                  # Patrol test, combat replay, javac check
```
