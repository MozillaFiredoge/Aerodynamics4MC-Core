package com.aerodynamics4mc.compat.createaeronautics;

import com.aerodynamics4mc.api.A4mcId;
import com.aerodynamics4mc.api.AeroAirfoilDefinition;
import com.aerodynamics4mc.api.AeroAirfoilPresets;
import com.aerodynamics4mc.api.AeroAirfoilProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CreateAeronauticsAirfoilLibrary {
	private static final Map<A4mcId, AeroAirfoilDefinition> DEFINITIONS = new LinkedHashMap<>();
	private static A4mcId selectedId = AeroAirfoilPresets.NACA_0012.id();
	private static long revision = 1L;

	static {
		for (AeroAirfoilDefinition definition : AeroAirfoilPresets.defaults()) {
			DEFINITIONS.put(definition.id(), definition);
		}
	}

	private CreateAeronauticsAirfoilLibrary() {
	}

	public static synchronized List<AeroAirfoilDefinition> definitions() {
		return List.copyOf(new ArrayList<>(DEFINITIONS.values()));
	}

	public static synchronized Optional<AeroAirfoilDefinition> find(A4mcId id) {
		return Optional.ofNullable(DEFINITIONS.get(id));
	}

	public static synchronized AeroAirfoilDefinition register(AeroAirfoilDefinition definition) {
		DEFINITIONS.put(definition.id(), definition);
		if (!DEFINITIONS.containsKey(selectedId)) {
			selectedId = definition.id();
		}
		revision++;
		return definition;
	}

	public static synchronized boolean select(A4mcId id) {
		if (!DEFINITIONS.containsKey(id)) {
			return false;
		}
		if (!selectedId.equals(id)) {
			revision++;
		}
		selectedId = id;
		return true;
	}

	public static synchronized A4mcId selectedId() {
		return selectedId;
	}

	public static synchronized AeroAirfoilDefinition selectedDefinition() {
		AeroAirfoilDefinition definition = DEFINITIONS.get(selectedId);
		if (definition != null) {
			return definition;
		}
		return AeroAirfoilPresets.NACA_0012;
	}

	public static synchronized AeroAirfoilProfile selectedProfile() {
		return selectedDefinition().profile();
	}

	public static synchronized AeroAirfoilDefinition definitionOrDefault(A4mcId id) {
		AeroAirfoilDefinition definition = DEFINITIONS.get(id);
		return definition == null ? selectedDefinition() : definition;
	}

	public static synchronized AeroAirfoilProfile profileOrSelected(A4mcId id) {
		return definitionOrDefault(id).profile();
	}

	public static synchronized long revision() {
		return revision;
	}

	public static synchronized void applySynchronizedState(
			List<AeroAirfoilDefinition> definitions,
			A4mcId selected,
			long remoteRevision
	) {
		DEFINITIONS.clear();
		List<AeroAirfoilDefinition> safeDefinitions = definitions == null || definitions.isEmpty()
				? AeroAirfoilPresets.defaults()
				: definitions;
		for (AeroAirfoilDefinition definition : safeDefinitions) {
			DEFINITIONS.put(definition.id(), definition);
		}
		if (selected != null && DEFINITIONS.containsKey(selected)) {
			selectedId = selected;
		} else if (DEFINITIONS.containsKey(AeroAirfoilPresets.NACA_0012.id())) {
			selectedId = AeroAirfoilPresets.NACA_0012.id();
		} else {
			selectedId = DEFINITIONS.keySet().iterator().next();
		}
		revision = Math.max(revision, remoteRevision);
	}
}
