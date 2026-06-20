# A4MC Airfoil Visuals Example Resource Pack

This resource pack demonstrates client-side airfoil visuals and generated vanilla block models for A4MC Create: Aeronautics compat.

Copy the `a4mc-airfoil-visuals` folder into your Minecraft `resourcepacks` directory, enable it, then reload resources.

## Vanilla block models

Generated Minecraft block models live under:

```text
assets/aerodynamics4mc_compat_create_aeronautics/models/block/airfoil_wing/generated/
```

The A4MC compat block now exposes a vanilla blockstate property:

```text
variant=flat_plate
variant=naca_0012
variant=naca_2412
variant=naca_4412
variant=custom
```

Official preset airfoil wing blocks therefore select their model through normal Minecraft blockstate variants. Resource packs can override any generated model, for example:

```text
assets/aerodynamics4mc_compat_create_aeronautics/models/block/airfoil_wing/generated/naca_2412.json
```

The compatibility fallback model is:

```text
assets/aerodynamics4mc_compat_create_aeronautics/models/block/airfoil_wing.json
```

It currently points item/default rendering to:

```text
aerodynamics4mc_compat_create_aeronautics:block/airfoil_wing/generated/naca_0012
```

Available generated models:

```text
aerodynamics4mc_compat_create_aeronautics:block/airfoil_wing/generated/flat_plate
aerodynamics4mc_compat_create_aeronautics:block/airfoil_wing/generated/naca_0012
aerodynamics4mc_compat_create_aeronautics:block/airfoil_wing/generated/naca_2412
aerodynamics4mc_compat_create_aeronautics:block/airfoil_wing/generated/naca_4412
aerodynamics4mc_compat_create_aeronautics:block/airfoil_wing/generated/custom
```

Regenerate these model files with:

```text
node tools/generate-airfoil-block-models.mjs
```

Vanilla block models still cannot read arbitrary block entity data. Official presets are exposed through blockstate. Statically loaded custom airfoils currently render with `variant=custom` unless a future compatibility layer assigns additional predeclared variants.

## A4MC visual data

A4MC visual files live under:

```text
assets/<namespace>/aerodynamics4mc/airfoil_visuals/<path>.json
```

That path maps to airfoil id `<namespace>:<path>`. For example:

```text
assets/aerodynamics4mc/aerodynamics4mc/airfoil_visuals/naca_2412.json
```

controls the client-side visual for:

```text
aerodynamics4mc:naca_2412
```

These files are still used by A4MC client tooling such as GUI preview/model generation. Server-side airfoil loading and physics still use the normal A4MC airfoil library.

Runtime import is intentionally not part of the player GUI. Custom airfoil JSON should be placed under `world/aerodynamics4mc/airfoils` and then loaded on the next server/client restart.
