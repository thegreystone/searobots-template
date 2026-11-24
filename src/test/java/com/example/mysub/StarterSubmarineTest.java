package com.example.mysub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import se.hirt.searobots.api.MatchConfig;
import se.hirt.searobots.api.SubmarineController;
import se.hirt.searobots.api.VehicleConfig;
import se.hirt.searobots.engine.SimulationListener;
import se.hirt.searobots.engine.SimulationLoop;
import se.hirt.searobots.engine.SimulationLoop.ControllerLimits;
import se.hirt.searobots.engine.SimulationListeners;
import se.hirt.searobots.engine.SubmarineSnapshot;
import se.hirt.searobots.engine.TorpedoSnapshot;
import se.hirt.searobots.engine.WorldGenerator;
import se.hirt.searobots.engine.replay.ReplayWriter;
import se.hirt.searobots.engine.ships.DefaultAttackSub;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local test harness for developing and verifying your submarine before entering the league.
 */
class StarterSubmarineTest {

	/**
	 * Verifies that src/main/java compiles cleanly using bare javac (--release 25 -proc:none)
	 * against searobots-api alone — matching how the SeaRobots League builds submissions without
	 * executing Maven.
	 */
	@Test
	void compilesStandaloneWithOnlySeaRobotsApi() throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertTrue(compiler != null, "Tests must run on a JDK (not a JRE)");

		Path apiLocation = Path.of(SubmarineController.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		Path sourceRoot = Path.of("src", "main", "java");
		List<String> sources;
		try (var stream = Files.walk(sourceRoot)) {
			sources = stream.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList();
		}
		assertFalse(sources.isEmpty(), "Expected Java sources under src/main/java");

		Path outDir = Files.createTempDirectory("searobots-javac-check");
		var args = new ArrayList<String>();
		args.add("--release");
		args.add("25");
		args.add("-proc:none");
		args.add("-cp");
		args.add(apiLocation.toString());
		args.add("-d");
		args.add(outDir.toString());
		args.addAll(sources);

		var err = new ByteArrayOutputStream();
		int exitCode = compiler.run(null, null, err, args.toArray(String[]::new));
		assertEquals(0, exitCode, "Standalone javac against searobots-api only failed:\n"
				+ err.toString(StandardCharsets.UTF_8));
	}

	/**
	 * Verifies the submarine patrols safely for 5 minutes of simulation time without crashing,
	 * throwing exceptions, or leaving the battle area.
	 */
	@Test
	void survivesSoloPatrolUnderLeagueLimits() {
		long seed = 0x2cL;
		var config = MatchConfig.withDefaults(seed).withMatchDurationTicks(5 * 60 * 50); // 5 min at 50 Hz
		var world = new WorldGenerator().generate(config);

		var sim = new SimulationLoop();
		sim.setSpeedMultiplier(1e9);
		sim.setControllerLimits(ControllerLimits.leagueDefaults());

		var survived = new AtomicBoolean(true);
		var finalHp = new AtomicInteger();

		sim.run(world, List.of(new StarterSubmarine()), List.of(VehicleConfig.submarine()), new SimulationListener() {
			@Override
			public void onTick(long tick, List<SubmarineSnapshot> subs, List<TorpedoSnapshot> torps) {
				var self = subs.getFirst();
				finalHp.set(self.hp());
				if (self.hp() <= 0 || self.forfeited()) {
					survived.set(false);
					sim.stop();
				}
			}

			@Override
			public void onMatchEnd() {
			}
		});

		assertEquals("", sim.forfeitReason(0), "Controller should not trigger league guardrails");
		assertTrue(survived.get(), "StarterSubmarine should survive solo patrol (final HP=" + finalHp.get() + ")");
	}

	/**
	 * Runs a quick combat match against DefaultAttackSub and writes a replay to
	 * target/replays/starter-vs-default.srl so you can inspect it in the 3D viewer.
	 */
	@Test
	void runsCombatMatchAndRecordsReplay() throws Exception {
		long seed = 0x13L;
		var config = MatchConfig.withDefaults(seed).withMatchDurationTicks(15 * 60 * 50); // 15 min at 50 Hz
		var world = new WorldGenerator().generate(config);

		var sim = new SimulationLoop();
		sim.setSpeedMultiplier(1e9);
		sim.setControllerLimits(ControllerLimits.leagueDefaults());
		sim.setTicksAfterDecision(10 * 50); // 10 s aftermath for replay viewing

		Path replayPath = Path.of("target", "replays", "starter-vs-default.srl");
		var frames = new AtomicInteger();
		var counter = new SimulationListener() {
			@Override
			public void onTick(long tick, List<SubmarineSnapshot> subs, List<TorpedoSnapshot> torps) {
				frames.incrementAndGet();
			}

			@Override
			public void onMatchEnd() {
			}
		};

		try (var writer = new ReplayWriter(config, world.spawnPoints(), replayPath)) {
			sim.run(world, List.of(new StarterSubmarine(), new DefaultAttackSub()),
					List.of(VehicleConfig.submarine(), VehicleConfig.submarine()),
					SimulationListeners.composite(counter, writer));
		}

		assertEquals("", sim.forfeitReason(0), "StarterSubmarine must not violate league guardrails");
		assertTrue(frames.get() > 100, "Expected simulation to run");
		assertTrue(Files.isRegularFile(replayPath) && Files.size(replayPath) > 0,
				"Expected replay written to " + replayPath);
	}
}
