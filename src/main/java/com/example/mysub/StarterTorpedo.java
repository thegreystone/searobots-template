package com.example.mysub;

import se.hirt.searobots.api.Pose;
import se.hirt.searobots.api.SonarContact;
import se.hirt.searobots.api.TerrainMap;
import se.hirt.searobots.api.TorpedoController;
import se.hirt.searobots.api.TorpedoInput;
import se.hirt.searobots.api.TorpedoLaunchContext;
import se.hirt.searobots.api.TorpedoOutput;
import se.hirt.searobots.api.Vec3;

/**
 * A minimal, easy-to-read autonomous torpedo controller.
 * <p>
 * After launch, a torpedo has no communication link to its parent submarine and no friend-or-foe
 * identification (FFI). This starter controller simply:
 * <ol>
 * <li>Reads the initial target coordinates {@code x,y,z} from {@code missionData}.</li>
 * <li>Runs toward that point at full throttle while clearing terrain.</li>
 * <li>Turns on active sonar once within acquisition range and homes directly on the strongest
 * submarine return.</li>
 * </ol>
 */
public class StarterTorpedo implements TorpedoController {

	private static final double ACQUISITION_RANGE_METERS = 1_600.0;
	private static final double FLOOR_CLEARANCE_METERS = 35.0;
	private static final double MIN_SURFACE_DEPTH = -12.0;

	private TerrainMap terrain;
	private double targetX;
	private double targetY;
	private double targetZ = -150.0;
	private boolean hasTarget;

	@Override
	public void onLaunch(TorpedoLaunchContext context) {
		this.terrain = context.terrain();
		String data = context.missionData();
		if (data == null || data.isBlank()) {
			return;
		}
		String[] parts = data.split(",");
		if (parts.length >= 2) {
			try {
				targetX = Double.parseDouble(parts[0].trim());
				targetY = Double.parseDouble(parts[1].trim());
				if (parts.length >= 3) {
					targetZ = Double.parseDouble(parts[2].trim());
				}
				hasTarget = Double.isFinite(targetX) && Double.isFinite(targetY) && Double.isFinite(targetZ);
			} catch (NumberFormatException ignored) {
				hasTarget = false;
			}
		}
	}

	@Override
	public void onTick(TorpedoInput input, TorpedoOutput output) {
		Pose pose = input.self();
		Vec3 pos = pose.position();

		// Keep full throttle so the negatively buoyant torpedo maintains lift and control authority.
		output.setThrottle(1.0);

		// 1. Update our aim point if we got an active sonar return this tick.
		SonarContact activeHit = findBestActiveReturn(input);
		if (activeHit != null && activeHit.range() > 0) {
			targetX = pos.x() + Math.sin(activeHit.bearing()) * activeHit.range();
			targetY = pos.y() + Math.cos(activeHit.bearing()) * activeHit.range();
			if (Double.isFinite(activeHit.estimatedDepth())) {
				targetZ = activeHit.estimatedDepth();
			}
			hasTarget = true;
		}

		// 2. Ping with active sonar once near the target area (or if we have no target yet).
		double distToTarget = hasTarget ? Math.hypot(targetX - pos.x(), targetY - pos.y()) : 0.0;
		if (input.activeSonarCooldownTicks() == 0 && (!hasTarget || distToTarget < ACQUISITION_RANGE_METERS)) {
			output.activeSonarPing();
		}

		if (!hasTarget) {
			output.setRudder(0.0);
			output.setSternPlanes(0.0);
			return;
		}

		// 3. Publish our current aim point for the 3D viewer and replays.
		output.publishTarget(targetX, targetY, targetZ);

		// 4. Steer toward the target while staying above the sea floor and below the surface.
		double desiredHeading = normalizeAngle(Math.atan2(targetX - pos.x(), targetY - pos.y()));
		double headingError = shortestAngleDelta(pose.heading(), desiredHeading);
		double rudder = Math.clamp(headingError / Math.toRadians(25.0), -1.0, 1.0);

		double safeDepth = applyTerrainClearance(pos, pose.heading(), targetZ);
		double depthError = safeDepth - pos.z();
		double desiredPitch = Math.clamp(Math.atan2(depthError, Math.max(distToTarget, 60.0)), -Math.toRadians(35.0),
				Math.toRadians(35.0));
		double pitchError = desiredPitch - pose.pitch();
		double sternPlanes = Math.clamp(pitchError / Math.toRadians(15.0), -1.0, 1.0);

		output.setRudder(rudder);
		output.setSternPlanes(sternPlanes);
	}

	private static SonarContact findBestActiveReturn(TorpedoInput input) {
		SonarContact best = null;
		for (SonarContact c : input.activeSonarReturns()) {
			if (c.classification() != SonarContact.Classification.TORPEDO
					&& (best == null || c.signalExcess() > best.signalExcess())) {
				best = c;
			}
		}
		return best;
	}

	private double applyTerrainClearance(Vec3 pos, double heading, double desiredDepth) {
		if (terrain == null) {
			return Math.min(desiredDepth, MIN_SURFACE_DEPTH);
		}
		double floorHere = terrain.elevationAt(pos.x(), pos.y());
		double floorAhead = terrain.elevationAt(pos.x() + Math.sin(heading) * 150.0,
				pos.y() + Math.cos(heading) * 150.0);
		double safeFloor = Math.max(floorHere, floorAhead) + FLOOR_CLEARANCE_METERS;
		return Math.min(Math.max(desiredDepth, safeFloor), MIN_SURFACE_DEPTH);
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
