package com.aerodynamics4mc.runtime;

import com.aerodynamics4mc.api.AeroL2ForceMoment;
import com.aerodynamics4mc.api.AeroL2Request;
import com.aerodynamics4mc.api.AeroL2Result;

public final class AeroL2WindTunnel {
    private AeroL2WindTunnel() {
    }

    public static AeroL2Result run(AeroL2Request request) {
        if (request == null) {
            return AeroL2Result.failure(null, "request must not be null", "not_started");
        }
        NativeSimulationBridge bridge = new NativeSimulationBridge();
        try (AerodynamicSolver solver = new AerodynamicSolver(
                bridge,
                request.nx(),
                request.ny(),
                request.nz(),
                request.dxMeters(),
                request.dtSeconds()
        )) {
            if (request.hasSolidMask()) {
                solver.setSolidMask(request.solidMask());
            }
            if (request.hasInitialFlowState()) {
                solver.setFlowState(request.initialFlowState());
            }
            solver.advance(
                request.steps(),
                request.inletVx(),
                request.inletVy(),
                request.inletVz(),
                request.densityKgM3(),
                request.kinematicViscosityM2S()
            );
            float[] flowAtlas = null;
            if (request.outputFlowAtlas()) {
                flowAtlas = new float[solver.flowAtlasValueCount(request.sampleStride())];
                solver.extractFlowAtlas(request.sampleStride(), flowAtlas);
            }
            AeroL2ForceMoment forceMoment = request.computeForceMoment()
                ? forceMoment(solver, request)
                : null;
            return AeroL2Result.success(request, flowAtlas, forceMoment, bridge.runtimeInfo());
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
            return AeroL2Result.failure(request, message, bridge.runtimeInfo());
        }
    }

    private static AeroL2ForceMoment forceMoment(AerodynamicSolver solver, AeroL2Request request) {
        NativeSimulationBridge.WindTunnelForceMoment forceMoment = solver.forceMoment(
            request.referenceX(),
            request.referenceY(),
            request.referenceZ()
        );
        return new AeroL2ForceMoment(
            forceMoment.forceX(),
            forceMoment.forceY(),
            forceMoment.forceZ(),
            forceMoment.momentX(),
            forceMoment.momentY(),
            forceMoment.momentZ(),
            forceMoment.centerOfPressureX(),
            forceMoment.centerOfPressureY(),
            forceMoment.centerOfPressureZ(),
            forceMoment.referenceX(),
            forceMoment.referenceY(),
            forceMoment.referenceZ()
        );
    }
}
