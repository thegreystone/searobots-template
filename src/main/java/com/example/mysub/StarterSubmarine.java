package com.example.mysub;

import se.hirt.searobots.api.BattleArea;
import se.hirt.searobots.api.FiringSolution;
import se.hirt.searobots.api.MatchConfig;
import se.hirt.searobots.api.MatchContext;
import se.hirt.searobots.api.MatchResult;
import se.hirt.searobots.api.PathPlanner;
import se.hirt.searobots.api.Pose;
import se.hirt.searobots.api.Purpose;
import se.hirt.searobots.api.SonarContact;
import se.hirt.searobots.api.SonarTrack;
import se.hirt.searobots.api.StrategicWaypoint;
import se.hirt.searobots.api.SubmarineController;
import se.hirt.searobots.api.SubmarineInput;
import se.hirt.searobots.api.SubmarineOutput;
import se.hirt.searobots.api.TerrainMap;
import se.hirt.searobots.api.TorpedoController;
import se.hirt.searobots.api.TorpedoLaunchCommand;
import se.hirt.searobots.api.Vec3;
import se.hirt.searobots.api.Waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A minimal, easy-to-read SeaRobots starter submarine.
 * <p>
 * Readability is prioritized over combat performance so you can quickly understand the API and
 * replace pieces with your own tactics.
 * <p>
 * <b>Coordinate conventions in SeaRobots:</b>
 * <ul>
 * <li>World axes: {@code +x} is east, {@code +y} is north, {@code z} is negative depth in metres
 * ({@code 0} = surface, {@code -200} = 200 m deep).</li>
 * <li>Heading &amp; bearing: radians clockwise from north ({@code 0} = north, {@code pi/2} =
 * east).</li>
 * <li>Actuators: rudder {@code -1} (port) to {@code +1} (starboard), stern planes {@code -1} (dive)
 * to {@code +1} (climb), throttle {@code -1..+1}, ballast {@code 0.5} = neutral buoyancy.</li>
 * </ul>
 */
public class StarterSubmarine implements SubmarineController {

	// ── Tuning constants ─────────────────────────────────────────────

	private static final double CRUISE_DEPTH = -180.0;
	private static final double MIN_SURFACE_DEPTH = -35.0;
	private static final double FLOOR_CLEARANCE = 75.0;
	private static final double BOUNDARY_MARGIN = 1_100.0;

	private static final double PATROL_THROTTLE = 0.45;
	private static final double PURSUE_THROTTLE = 0.70;
	private static final double EVADE_THROTTLE = 1.00;

	private static final double WAYPOINT_ARRIVAL_METERS = 250.0;
	private static final double FIRING_RANGE_METERS = 2_400.0;
	private static final double FIRING_ALIGN_RADIANS = Math.toRadians(22.0);
	private static final double DEFAULT_FUSE_RADIUS = 18.0;
	private static final long TORPEDO_COOLDOWN_TICKS = 600; // 12 seconds at 50 Hz
	private static final long PING_INTERVAL_TICKS = 500; // 10 seconds at 50 Hz
	private static final long EVADE_DURATION_TICKS = 900; // 18 seconds at 50 Hz
	private static final double CONTACT_EXPIRE_SECONDS = 45.0;

	// ── Match state (reset in onMatchStart) ──────────────────────────

	private MatchConfig config;
	private TerrainMap terrain;
	private BattleArea battleArea;
	private PathPlanner pathPlanner;
	private SonarTrack targetTrack;

	private final List<StrategicWaypoint> objectives = new ArrayList<>();
	private int objectiveIndex;

	private List<Vec3> route = List.of();
	private int routeIndex;
	private int patrolPointIndex;

	private double lastKnownTargetDepth = CRUISE_DEPTH;
	private double lastTargetBearing = Double.NaN;
	private long lastTorpedoTick = -TORPEDO_COOLDOWN_TICKS;
	private long lastPingTick = -PING_INTERVAL_TICKS;
	private long evadeUntilTick = -1;
	private double evadeHeading;

	// ── Identity ─────────────────────────────────────────────────────

	@Override
	public String name() {
		return "Starter Sub";
	}

	@Override
	public String shortName() {
		return "ST";
	}

	/**
	 * Returns our custom torpedo controller. You can also return {@code null} to use SeaRobots'
	 * built-in default torpedo AI.
	 */
	@Override
	public TorpedoController createTorpedoController() {
		return new StarterTorpedo();
	}

	// ── Match lifecycle ──────────────────────────────────────────────

	@Override
	public void onMatchStart(MatchContext context) {
		this.config = context.config();
		this.terrain = context.terrain();
		this.battleArea = config.battleArea();

		// Built-in A* path planner: avoids cells shallower than -90 m with a 250 m safety margin.
		this.pathPlanner = new PathPlanner(terrain, -90.0, 250.0, 100.0);

		// Built-in contact tracker: retains and coasts the engine's sonar/TMA position fixes.
		this.targetTrack = new SonarTrack(config.maxSubSpeed());

		this.route = List.of();
		this.routeIndex = 0;
		this.patrolPointIndex = 0;
		this.objectiveIndex = 0;
		this.lastKnownTargetDepth = CRUISE_DEPTH;
		this.lastTargetBearing = Double.NaN;
		this.lastTorpedoTick = -TORPEDO_COOLDOWN_TICKS;
		this.lastPingTick = -PING_INTERVAL_TICKS;
		this.evadeUntilTick = -1;
	}

	@Override
	public void setObjectives(List<StrategicWaypoint> mandatoryObjectives) {
		objectives.clear();
		if (mandatoryObjectives != null) {
			objectives.addAll(mandatoryObjectives);
		}
		objectiveIndex = 0;
		route = List.of();
	}

	@Override
	public void onMatchEnd(MatchResult result) {
		// Nothing to clean up; all per-match state is reset in onMatchStart().
	}

	// ── Main decision loop (called 50 times per second of sim time) ──

	@Override
	public void onTick(SubmarineInput input, SubmarineOutput output) {
		Pose pose = input.self().pose();
		Vec3 pos = pose.position();
		long tick = input.tick();

		// 1. Inspect sonar contacts: separate incoming torpedoes from enemy submarines.
		SonarContact torpedoThreat = findStrongest(input, SonarContact.Classification.TORPEDO);
		SonarContact subContact = findBestSubmarineContact(input);

		// 2. Update our contact track so we remember the enemy between pings.
		if (subContact != null) {
			lastTargetBearing = subContact.bearing();
			if (Double.isFinite(subContact.estimatedDepth())) {
				lastKnownTargetDepth = subContact.estimatedDepth();
			}
		}
		SonarTrack.Estimate track = targetTrack.update(pos, subContact, input.deltaTimeSeconds()).orElse(null);
		if (track != null && track.secondsSinceContact() > CONTACT_EXPIRE_SECONDS) {
			targetTrack.reset();
			track = null;
			lastTargetBearing = Double.NaN;
		}
		if (track != null) {
			double aliveBelief = Math.max(0.1, 1.0 - track.secondsSinceContact() / CONTACT_EXPIRE_SECONDS);
			output.publishContactEstimate(track.toContactEstimate(aliveBelief, "enemy"));
		}

		// 3. Pick a high-level behavior: EVADE -> PURSUE -> PATROL.
		if (torpedoThreat != null) {
			// Turn perpendicular to the incoming torpedo and sprint away.
			evadeHeading = normalizeAngle(torpedoThreat.bearing() + Math.PI / 2.0);
			evadeUntilTick = tick + EVADE_DURATION_TICKS;
		}

		double desiredHeading;
		double desiredDepth = CRUISE_DEPTH;
		double throttle;

		if (tick < evadeUntilTick) {
			output.setStatus("EVADE");
			desiredHeading = evadeHeading;
			desiredDepth = -240.0;
			throttle = EVADE_THROTTLE;
		} else if (track != null) {
			output.setStatus("PURSUE");
			desiredHeading = bearingTo(pos.x(), pos.y(), track.x(), track.y());
			desiredDepth = clampOperatingDepth(lastKnownTargetDepth);
			throttle = PURSUE_THROTTLE;

			// Ping periodically while hunting so we get a crisp range + depth fix.
			maybePing(input, output);
			maybeFireTorpedo(input, output, track);
		} else if (!Double.isNaN(lastTargetBearing)) {
			// We heard a bearing-only passive contact without a range fix yet: turn toward it and ping.
			output.setStatus("TRACK");
			desiredHeading = lastTargetBearing;
			throttle = PURSUE_THROTTLE;
			maybePing(input, output);
		} else {
			output.setStatus("PATROL");
			Vec3 waypoint = nextNavigationWaypoint(pos, output);
			desiredHeading = bearingTo(pos.x(), pos.y(), waypoint.x(), waypoint.y());
			desiredDepth = waypoint.z();
			throttle = PATROL_THROTTLE;
		}

		// 4. Safety overrides: stay inside the battle area and clear of the sea floor.
		desiredHeading = applyBoundarySafety(pos, desiredHeading);
		desiredDepth = applyTerrainSafety(pos, desiredHeading, desiredDepth, input.recommendedTerrainClearance());

		// 5. Convert desired heading & depth into rudder, stern planes, ballast, and throttle.
		steer(pose, desiredHeading, desiredDepth, throttle, output);
	}

	// ── Sonar & weapons helpers ──────────────────────────────────────

	private static SonarContact findStrongest(SubmarineInput input, SonarContact.Classification kind) {
		SonarContact best = null;
		for (SonarContact c : input.activeSonarReturns()) {
			if (c.classification() == kind && (best == null || c.signalExcess() > best.signalExcess())) {
				best = c;
			}
		}
		for (SonarContact c : input.sonarContacts()) {
			if (c.classification() == kind && (best == null || c.signalExcess() > best.signalExcess())) {
				best = c;
			}
		}
		return best;
	}

	private static SonarContact findBestSubmarineContact(SubmarineInput input) {
		// Prefer active sonar returns (they include accurate range and depth).
		SonarContact best = null;
		for (SonarContact c : input.activeSonarReturns()) {
			if (c.classification() != SonarContact.Classification.TORPEDO
					&& (best == null || c.signalExcess() > best.signalExcess())) {
				best = c;
			}
		}
		if (best != null) {
			return best;
		}
		for (SonarContact c : input.sonarContacts()) {
			if (c.classification() != SonarContact.Classification.TORPEDO
					&& (best == null || c.signalExcess() > best.signalExcess())) {
				best = c;
			}
		}
		return best;
	}

	private void maybePing(SubmarineInput input, SubmarineOutput output) {
		if (input.activeSonarCooldownTicks() == 0 && input.tick() - lastPingTick >= PING_INTERVAL_TICKS) {
			output.activeSonarPing();
			lastPingTick = input.tick();
		}
	}

	private void maybeFireTorpedo(SubmarineInput input, SubmarineOutput output, SonarTrack.Estimate track) {
		if (input.self().torpedoesRemaining() <= 0) {
			return;
		}
		if (input.tick() - lastTorpedoTick < TORPEDO_COOLDOWN_TICKS) {
			return;
		}
		if (track.secondsSinceFix() > 15.0) {
			return;
		}

		Pose pose = input.self().pose();
		Vec3 pos = pose.position();
		double dx = track.x() - pos.x();
		double dy = track.y() - pos.y();
		double distance = Math.hypot(dx, dy);
		if (distance > FIRING_RANGE_METERS || distance < 200.0) {
			return;
		}

		double bearing = bearingTo(pos.x(), pos.y(), track.x(), track.y());
		double headingError = Math.abs(shortestAngleDelta(pose.heading(), bearing));
		if (headingError > FIRING_ALIGN_RADIANS) {
			return;
		}

		// Publish the firing solution so it shows up in the 3D viewer and replays.
		output.publishFiringSolution(new FiringSolution(track.x(), track.y(), track.estimatedHeading(),
				track.estimatedSpeed(), track.solutionQuality()));

		// Encode the target position for StarterTorpedo using Locale.ROOT (dot decimal separator).
		double targetZ = clampOperatingDepth(lastKnownTargetDepth);
		String mission = String.format(Locale.ROOT, "%.1f,%.1f,%.1f", track.x(), track.y(), targetZ);
		output.launchTorpedo(new TorpedoLaunchCommand(bearing, 0.0, DEFAULT_FUSE_RADIUS, mission));
		lastTorpedoTick = input.tick();
	}

	// ── Navigation & safety helpers ──────────────────────────────────

	private Vec3 nextNavigationWaypoint(Vec3 pos, SubmarineOutput output) {
		// Advance along mandatory navigation objectives if any were assigned.
		if (objectiveIndex < objectives.size()) {
			StrategicWaypoint obj = objectives.get(objectiveIndex);
			if (Math.hypot(obj.x() - pos.x(), obj.y() - pos.y()) < WAYPOINT_ARRIVAL_METERS) {
				objectiveIndex++;
				route = List.of();
			}
		}

		// Advance along the current A* route.
		while (routeIndex < route.size()) {
			Vec3 wp = route.get(routeIndex);
			if (Math.hypot(wp.x() - pos.x(), wp.y() - pos.y()) < WAYPOINT_ARRIVAL_METERS) {
				routeIndex++;
			} else {
				break;
			}
		}

		// Plan a fresh route whenever we finish the current one.
		if (routeIndex >= route.size()) {
			Vec3 goal = chooseNextGoal();
			route = pathPlanner.findPath(pos.x(), pos.y(), goal.x(), goal.y(), goal.z(), 0.25, 250.0);
			routeIndex = 0;
			if (route.isEmpty()) {
				route = List.of(goal);
			}
		}

		// Publish waypoints for the 3D viewer (toggle with W / G keys).
		for (int i = routeIndex; i < route.size(); i++) {
			Vec3 wp = route.get(i);
			output.publishWaypoint(new Waypoint(wp.x(), wp.y(), wp.z(), i == routeIndex));
		}
		Vec3 finalGoal = route.getLast();
		output.publishStrategicWaypoint(new Waypoint(finalGoal.x(), finalGoal.y(), finalGoal.z(), true),
				Purpose.PATROL);

		return route.get(routeIndex);
	}

	private Vec3 chooseNextGoal() {
		if (objectiveIndex < objectives.size()) {
			StrategicWaypoint obj = objectives.get(objectiveIndex);
			return new Vec3(obj.x(), obj.y(), clampOperatingDepth(obj.preferredDepth()));
		}

		// Simple 4-corner deep-water patrol square well inside the battle area.
		double radius = Math.max(1_000.0, battleArea.extent() * 0.45);
		double[][] corners = {{radius, radius}, {radius, -radius}, {-radius, -radius}, {-radius, radius}};
		double[] corner = corners[patrolPointIndex++ % corners.length];
		return new Vec3(corner[0], corner[1], CRUISE_DEPTH);
	}

	private double applyBoundarySafety(Vec3 pos, double desiredHeading) {
		if (battleArea.distanceToBoundary(pos.x(), pos.y()) < BOUNDARY_MARGIN) {
			// Turn smoothly back toward the center of the map (0, 0).
			return bearingTo(pos.x(), pos.y(), 0.0, 0.0);
		}
		return desiredHeading;
	}

	private double applyTerrainSafety(Vec3 pos, double heading, double desiredDepth, double advisedClearance) {
		double clearance = Math.max(FLOOR_CLEARANCE, advisedClearance);
		double shallowestFloor = terrain.elevationAt(pos.x(), pos.y());

		// Look ahead up to 500 m along our heading so we start climbing before rising terrain.
		double sinH = Math.sin(heading);
		double cosH = Math.cos(heading);
		for (double ahead = 100.0; ahead <= 500.0; ahead += 100.0) {
			double floor = terrain.elevationAt(pos.x() + sinH * ahead, pos.y() + cosH * ahead);
			if (floor > shallowestFloor) {
				shallowestFloor = floor;
			}
		}

		double safeFloorCeiling = shallowestFloor + clearance;
		return clampOperatingDepth(Math.max(desiredDepth, safeFloorCeiling));
	}

	private double clampOperatingDepth(double depth) {
		double maxSafeDepth = config != null ? config.ratedDepth() + 40.0 : -360.0;
		return Math.clamp(depth, maxSafeDepth, MIN_SURFACE_DEPTH);
	}

	private static void steer(Pose pose, double desiredHeading, double desiredDepth, double throttle,
			SubmarineOutput output) {
		// Proportional yaw control: turn toward desiredHeading.
		double headingError = shortestAngleDelta(pose.heading(), desiredHeading);
		double rudder = Math.clamp(headingError / Math.toRadians(35.0), -1.0, 1.0);

		// Proportional depth control: aim for a modest pitch angle toward desiredDepth.
		double depthError = desiredDepth - pose.position().z(); // positive = need to climb
		double targetPitch = Math.clamp(depthError / 120.0, -Math.toRadians(20.0), Math.toRadians(20.0));
		double pitchError = targetPitch - pose.pitch();
		double sternPlanes = Math.clamp(pitchError / Math.toRadians(12.0), -1.0, 1.0);

		output.setEngineClutch(true);
		output.setThrottle(throttle);
		output.setRudder(rudder);
		output.setSternPlanes(sternPlanes);
		output.setBallast(0.5); // neutral buoyancy; let stern planes control depth cleanly
	}

	// ── Angle math helpers ───────────────────────────────────────────

	private static double bearingTo(double fromX, double fromY, double toX, double toY) {
		return normalizeAngle(Math.atan2(toX - fromX, toY - fromY));
	}

	private static double normalizeAngle(double radians) {
		double a = radians % (2.0 * Math.PI);
		return a < 0 ? a + 2.0 * Math.PI : a;
	}

	private static double shortestAngleDelta(double from, double to) {
		double diff = normalizeAngle(to - from);
		return diff > Math.PI ? diff - 2.0 * Math.PI : diff;
	}
}
