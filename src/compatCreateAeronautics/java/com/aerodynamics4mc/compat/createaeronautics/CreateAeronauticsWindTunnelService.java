package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.api.AeroL2Request;
import com.aerodynamics4mc.api.AeroL2Result;
import com.aerodynamics4mc.api.AeroWindApi;

import java.util.Objects;

public final class CreateAeronauticsWindTunnelService {
	public static final CreateAeronauticsWindTunnelService INSTANCE = new CreateAeronauticsWindTunnelService();

	private Object activeWorldKey;
	private State state = State.IDLE;
	private AeroL2Result lastResult;
	private String lastMessage = "";

	private CreateAeronauticsWindTunnelService() {
	}

	public synchronized State state() {
		return state;
	}

	public synchronized AeroL2Result lastResult() {
		return lastResult;
	}

	public synchronized String lastMessage() {
		return lastMessage;
	}

	public synchronized SubmissionResult submit(Object worldKey, AeroL2Request request) {
		Objects.requireNonNull(worldKey, "worldKey");
		Objects.requireNonNull(request, "request");
		if (state == State.SOLVING && !worldKey.equals(activeWorldKey)) {
			return SubmissionResult.busy(activeWorldKey);
		}
		activeWorldKey = worldKey;
		state = State.SOLVING;
		lastMessage = "solving";
		AeroL2Result result = AeroWindApi.runL2(request);
		lastResult = result;
		state = result.succeeded() ? State.READY : State.FAILED;
		lastMessage = result.succeeded() ? "ready" : result.message();
		return SubmissionResult.accepted(result);
	}

	public synchronized void clear(Object worldKey) {
		if (worldKey == null || worldKey.equals(activeWorldKey)) {
			activeWorldKey = null;
			state = State.IDLE;
			lastResult = null;
			lastMessage = "";
		}
	}

	public void tick(Object server) {
		// Reserved for the async one-world-one-wind-tunnel scheduler.
	}

	public enum State {
		IDLE,
		SCANNING,
		SOLVING,
		READY,
		FAILED
	}

	public record SubmissionResult(boolean accepted, Object busyWorldKey, AeroL2Result result) {
		public static SubmissionResult accepted(AeroL2Result result) {
			return new SubmissionResult(true, null, result);
		}

		public static SubmissionResult busy(Object busyWorldKey) {
			return new SubmissionResult(false, busyWorldKey, null);
		}
	}
}
