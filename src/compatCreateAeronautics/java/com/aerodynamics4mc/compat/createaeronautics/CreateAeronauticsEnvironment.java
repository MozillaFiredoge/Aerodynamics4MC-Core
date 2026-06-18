package com.aerodynamics4mc.compat.createaeronautics;

import java.util.ArrayList;
import java.util.List;

public record CreateAeronauticsEnvironment(boolean available, List<String> missingClasses) {
	private static final String[] REQUIRED_CLASSES = {
			"dev.ryanhcode.sable.Sable",
			"dev.ryanhcode.sable.sublevel.ServerSubLevel",
			"dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle",
			"dev.simulated_team.simulated.Simulated",
			"dev.eriksonn.aeronautics.Aeronautics"
	};

	public CreateAeronauticsEnvironment {
		missingClasses = List.copyOf(missingClasses == null ? List.of() : missingClasses);
	}

	public static CreateAeronauticsEnvironment detect() {
		List<String> missing = new ArrayList<>();
		ClassLoader loader = CreateAeronauticsEnvironment.class.getClassLoader();
		for (String className : REQUIRED_CLASSES) {
			if (!classAvailable(loader, className)) {
				missing.add(className);
			}
		}
		return new CreateAeronauticsEnvironment(missing.isEmpty(), missing);
	}

	private static boolean classAvailable(ClassLoader loader, String className) {
		try {
			Class.forName(className, false, loader);
			return true;
		} catch (ClassNotFoundException | LinkageError ignored) {
			return false;
		}
	}
}
