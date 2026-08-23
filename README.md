Some docs 

## Rotation — `client/rotation/RotationController.kt`

Each axis moves toward its requested angle at up to the caller's rate cap per frame and settles
once within one mouse pixel of it. Deltas are emitted as whole pixels with sub-pixel remainders
carried across frames, so every applied rotation is an integer multiple of the current
sensitivity step (GCD-safe) while still flowing through vanilla's own `MouseHandler` turn path.

| Constant | Default | What it controls |
|---|---|---|
| `DEFAULT_MAX_STEP` | 1800 deg/s | Ceiling on turn speed when the caller doesn't specify one. `snapTo` bypasses it entirely (`Double.MAX_VALUE`). |
| `EPSILON` | 0.01 deg | Floor on the settle tolerance when one mouse pixel is finer than this. |

## Path following — `client/movement/PathFollower.kt`

The head tracks a lookahead carrot ahead along the path; the feet track the path line itself via
strafe keys. Look direction and travel direction are deliberately independent.

| Constant | Default | What it controls |
|---|---|---|
| `NODE_REACH_XZ` | 0.75 | Horizontal distance at which a node counts as reached. |
| `LOOKAHEAD` | 3.0 | Blocks ahead along the path the head aims at. |
| `AIM_HOLD_XZ` | 0.5 | Below this distance the bearing is meaningless; hold view instead. |
| `TURN_RATE_DEG_PER_SEC` | 180.0 | Head turn speed while following the path. |
| `TARGET_AIM_RATE_DEG_PER_SEC` | 540.0 | Head turn speed while acquiring a nearby target. |
| `TARGET_AIM_BLEND_MS` | 400.0 | Window over which the view slides from carrot onto target. |
| `CROSSTRACK_GAIN` | 1.0 | Sideways correction commanded per block of offset from the path line. |
| `CROSSTRACK_DAMPING` | 3.0 | Damping on offset rate-of-change so correction doesn't weave. |
| `MAX_LATERAL` | 1.0 | Caps the crosstrack correction at ~45° off the segment. |
| `STRAFE_ENTER_DEGREES` | 22.5° | Heading error where strafe keys start assisting forward travel. |
| `STRAFE_ONLY_DEGREES` | 67.5° | Heading error where forward input drops and strafe takes over. |
| `STRAFE_HYSTERESIS` | 8.0° | Deadband around the boundaries so keys don't chatter on them. |
| `HEADING_ERROR_SMOOTHING_PER_SEC` | 8.0 | Low-pass bandwidth on heading error fed to the strafe quantiser. |
| `SIDE_FLIP_TICKS` | 3 | Consecutive ticks a side must agree before strafe flips sides. |
| `OFF_PATH_XZ` | 3.0 | Offset beyond which steering home counts as lost, not tracking. |
| `OFF_PATH_TICKS` | 10 | Ticks past `OFF_PATH_XZ` before the run is declared off-path. |
| `STUCK_SPEED_EPS` | 0.01 | Movement speed treated as "not moving" for stuck detection. |
| `STUCK_TICKS` | 8 | Consecutive still ticks before declaring stuck. |

Tuning guide:

- **Corners cut too tight / clips walls?** Lower `LOOKAHEAD`; raise it if the head lags behind corners.
- **Weaves side-to-side along straights?** Lower `CROSSTRACK_GAIN` or raise `CROSSTRACK_DAMPING`.
- **Slow to correct drift back onto the line?** Raise `CROSSTRACK_GAIN` (keep `MAX_LATERAL` sane).
- **Strafe keys stutter near boundaries?** Raise `STRAFE_HYSTERESIS`.
- **Turns look robotic mid-path?** Lower `TURN_RATE_DEG_PER_SEC`; raise if it misses the carrot.
- **Declares failure too eagerly after a knockback?** Raise `OFF_PATH_TICKS` / `OFF_PATH_XZ`.

## Pathfinding — `client/path/AStarPathfinder.kt`

| Constant | Default | What it controls |
|---|---|---|
| `MAX_EXPANSIONS` | 2000 | Node expansions before giving up — hard runtime cap. |
| `MAX_RADIUS` | 64 | Search radius from start, in blocks. |
| `MAX_GAP` | 2.5 | Widest horizontal gap a jump link may span. |
| `JUMP_EPSILON` | 0.05 | Flat cost surcharge applied to every jump link, so walking wins ties. |
| `MAX_DROP_SCAN` | 64 | How far straight down a drop link may fall while searching for landing. |
| `SWEEPS_PER_NODE` | 80 | Cap on candidate cells examined per expansion — bounds worst-case frame time. |
| `GAP_COST_PER_BLOCK` | 0.25 | Extra cost per block of gap, so jumps are avoided unless shorter. |
| `VERTICAL_COST_WEIGHT` | 1.05 | Slight bias against climbing routes. |
| `GOAL_SEARCH_RADIUS` | 2 | Blocks around the goal hint scanned for a standable goal cell. |

Tuning guide:

- **Paths fail on long routes?** Raise `MAX_EXPANSIONS` (costs frame time on the worker thread) or
  `MAX_RADIUS` if the target is simply out of range.
- **Routes detour around trivial gaps?** Lower `GAP_COST_PER_BLOCK`.
- **Pathfinder refuses jumps the player can make?** Raise `MAX_GAP` slightly; keep it under what
  sprint-jumping actually covers (~4 blocks) or paths will demand impossible leaps.

## Targets — `client/target/AutoTargetScanner.kt`

| Constant | Default | What it controls |
|---|---|---|
| `ZOMBIE_MAX_HEALTH` | 190f | Health fingerprint identifying valid zombie targets. |
| `REMOTE_PLAYER_MAX_HEALTH` | 210f | Health fingerprint identifying valid player targets. |

These are identity checks (`maxHealth == ...`), not thresholds — entities match only if their max
health equals the value exactly. Change them only if the server's entity stats change.

## Recompute pacing — `client/path/PathfindingController.kt`

| Constant | Default | What it controls |
|---|---|---|
| `OFF_PATH_COOLDOWN` | 20 ticks | Minimum spacing between off-path recomputes so a search in flight isn't resubmitted every tick. |
| `MAX_AUTO_CANDIDATES` | 10 | Upper bound on auto-mode candidates considered. |

Raise `OFF_PATH_COOLDOWN` if recomputes pile up during lag spikes; lower it only if recovery from
knockbacks feels slow.

## Fighting — `combat/`

Select a target like for pathfinding (middle mouse on the crosshair pick), then `/fight`. The bot
walks at the target holding W and releases inside melee distance, locks the crosshair onto the
target through a humanised aim model (see below), and clicks at a wandering 8–12 CPS delivered
through vanilla's own attack-key path (`KeyMapping.click`, so cooldowns, miss swings and knockback
behave exactly like real presses). Mutually exclusive with pathfinding — whichever starts second
stops the other.

### Approach — `FightBot.kt`

| Constant | Default | What it controls |
|---|---|---|
| `STOP_DISTANCE_XZ` | 1.5 | Horizontal distance where the adjust state releases forward so the bot never walks inside the target. |
| `ENGAGE_DISTANCE_XZ` | 3.0 | Horizontal distance beyond which the target counts as unattackable and walking resumes (latched adjust state). |
| `CLICK_START_MIN_X` / `CLICK_START_MAX_X` | 4.0–5.2 | Distance band from which clicking engages, sampled once per fight like a player who starts mousing before reach. |
| `CLICK_STOP_MARGIN_X` | 0.75 | Extra distance required before clicking pauses again (gate hysteresis). |
| `CLICK_GATE_DRIFT_SIGMA_X` | 0.015 | Per-tick random walk step on the click-start distance, so it is never a constant. |
| `CLICK_GATE_MIN_X` / `CLICK_GATE_MAX_X` | 3.7–5.5 | Bounds on how far the drifting click gate may wander. |

Tuning guide:

- **Walks too deep into the target?** Lower `STOP_DISTANCE_XZ`; raise it if it stops out of reach.
- **Lags behind a retreating target?** Raise `ENGAGE_DISTANCE_XZ` so walking re-engages sooner.
- **Starts clicking suspiciously early/late?** Shift the `CLICK_START_*` band; the drift bounds
  follow it automatically.
- **Gate flickers when the target dashes away?** Raise `CLICK_STOP_MARGIN_X`.

### Aim — `CombatAim.kt`

Not a lock-on: aiming reads as a person. After each acquisition nothing tracks until a sampled
reaction delay elapses. The aim point is a gaussian-weighted offset inside the hitbox that slowly
wanders, hanging a sampled drop below the shooter's own eye line so elevation differences get
absorbed by hitting higher or lower on the body instead of pitching. Corrections run in two
phases — a ballistic flick that eases out onto an apex placed slightly past the target, then a
soft lagged tracking filter once close, re-flicking if the target escapes. Moving targets are only
partially led (the lead fraction wobbles), hand tremor rides on top as sub-degree noise, and while
the view error sits inside a sampled deadzone no corrections are issued at all. Most bounds are
sampled once per acquisition, so no two fights share the same constants.

Reaction, deadzones and attack window:

| Constant | Default | What it controls |
|---|---|---|
| `REACTION_MIN_MS` / `_MAX_` | 120–250 ms | Sampled delay after acquiring a target before any tracking starts. |
| `HOLD_DEADZONE_MIN_DEG` / `_MAX_` | 0.8–1.5° | Sampled idle tolerance band; errors inside it trigger no corrections. |
| `ATTACK_DEADZONE_MIN_DEG` / `_MAX_` | 0.35–0.65° | Sampled tightened band active right after a synthetic click. |
| `ATTACK_WINDOW_MS` | 350 ms | How long after a click the tighter deadzone stays active. |
| `DEADZONE_YAW_SCALE_MIN` / `_MAX_` | ×1.15–1.45 | Sampled widening of the deadzone along yaw — horizontal misses bother humans less. |
| `YAW_BIAS_MIN` / `_MAX_` | ×1.35–1.75 | Sampled speed multiplier of yaw over pitch; horizontal sweeps are faster. |

Flick phase:

| Constant | Default | What it controls |
|---|---|---|
| `FLICK_SPEED_MIN_DEG_PER_S` / `_MAX_` | 240–520 deg/s | Sampled ceiling for the current flick. |
| `FLICK_GAIN_PER_S` | 14.0 | Proportional gain turning remaining angle into the flick cap (ease-out decay). |
| `FLICK_MIN_STEP_DEG_PER_S` | 40.0 | Floor for the proportional cap so distant starts still move briskly. |
| `OVERSHOOT_MIN_FRACTION` / `_MAX_` | 4–10% | Sampled overshoot past the mark, as a fraction of the flick's initial angle. |
| `OVERSHOOT_MAX_DEG` | 6.0° | Hard ceiling on overshoot regardless of flick size. |
| `OVERSHOOT_MIN_DISTANCE_DEG` | 20° | Flicks shorter than this go straight at the mark, no apex. |
| `FITTS_REF_ID` | 4.0 | Reference index of difficulty at which the sampled flick ceiling applies at full speed; larger angles-onto-small-targets pace slower (Fitts' law). |

Tracking phase:

| Constant | Default | What it controls |
|---|---|---|
| `TRACK_ENTER_DEG` | 8° | Angular distance where a flick hands over to tracking. |
| `TRACK_EXIT_DEG` | 18° | Angular distance above which tracking gives up and re-flicks. |
| `TRACK_BANDWIDTH_PER_S` | 12.0 | First-order tracking bandwidth; higher follows more tightly. |
| `TRACK_BANDWIDTH_WANDER` | ±25% | Peak wander of the bandwidth so pursuit tightens and loosens organically. |
| `TRACK_BANDWIDTH_MIN_PER_S` / `_MAX_` | 8–20 /s | Bounds on the wandering bandwidth. |
| `TRACK_MAX_STEP_DEG_PER_S` | 120.0 | Hard cap on tracking-phase rotation speed. |

Prediction (partial target lead):

| Constant | Default | What it controls |
|---|---|---|
| `PREDICT_LEAD_MIN_FRACTION` / `_MAX_` | 0.3–0.7 | Sampled fraction of target motion the aim leads by — never full compensation. |
| `PREDICT_HORIZON_SECONDS` | 0.25 s | Motion horizon the lead fraction applies over. |
| `PREDICT_VELOCITY_SMOOTH_PER_S` | 6.0 | Smoothing rate of the observed target velocity estimate. |
| `PREDICT_LEAD_WOBBLE` | ±15% | Wander of the lead strength so the net bias stays slightly trailing. |

Tremor and aim-point placement:

| Constant | Default | What it controls |
|---|---|---|
| `TREMOR_MIN_DEG` / `_MAX_` | 0.08–0.30° | Sampled per-axis hand-tremor amplitude riding on the tracked aim. |
| `TREMOR_BASE_FREQ_HZ` | 4.0 | Base frequency of tremor waves; components detune into the 3–15 Hz band. |
| `TREMOR_ATTACK_SCALE` | ×0.5 | Tremor scale while attacking — squeezing off a click steadies the hand. |
| `AIM_SIGMA_OF_HALF_EXTENT` | 0.35 | Sigma of the gaussian anchor offset, as a fraction of hitbox half-extent per axis. |
| `AIM_DROP_MIN_BLOCKS` / `_MAX_` | 0.25–0.45 | Sampled drop of the preferred aim height below the shooter's own eyes. |
| `CLAMP_KNEE_MIN_BLOCKS` / `_MAX_` | 0.10–0.22 | Sampled soft-knee size of the vertical clamp — deep overshoots settle near-but-not-at the wall. |
| `CLAMP_BREATH_BLOCKS` | 0.06 | Peak slow wobble of the vertical clamp bounds so it never sits machined-exact. |
| `AIM_EDGE_MARGIN_BLOCKS` | 0.05 | Margin kept from the hitbox edge for any combined aim offset. |
| `DRIFT_AMPLITUDE_BLOCKS` | 0.06 | Peak total wander amplitude of the drifting horizontal offset. |
| `DRIFT_FREQUENCY_HZ` | 0.35 | Base temporal frequency of the drift wander. |

Tuning guide:

- **Flicks look robotic / too fast?** Narrow the `FLICK_SPEED_*` band downward; raise `FITTS_REF_ID`
  only if long-range flicks feel artificially slowed.
- **Sits visibly off-centre in replays?** Shrink `AIM_SIGMA_OF_HALF_EXTENT` and `DRIFT_AMPLITUDE_BLOCKS`;
  raise them if the anchor looks pinned.
- **Jittery micro-corrections near the target?** Widen the `HOLD_DEADZONE_*` band or `DEADZONE_YAW_SCALE_*`.
- **Keeps missing elevated targets?** Adjust `AIM_DROP_*` — the point hangs below your own eye line.
- **Loses fast-strafing targets?** Raise `PREDICT_LEAD_*` fractions and `TRACK_BANDWIDTH_PER_S`.
- **Reaction pause too theatrical or absent?** Move the `REACTION_*` window.
- **Hands shake too much on camera?** Lower `TREMOR_MIN_DEG`/`_MAX_`.

### Clicks — `HumanClicker.kt`

Frame-driven scheduler: the CPS target itself wanders (resampled every few hundred ms), intervals
are Gaussian-jittered around it and trend via AR(1)-style persistence, and the rhythm occasionally
breaks with short fast bursts and reaction-shaped pauses.

| Constant | Default | What it controls |
|---|---|---|
| `CPS_MIN` / `CPS_MAX` | 8–12 | Bounds between which the wandering CPS target is resampled. |
| `JITTER_SIGMA_MS` | 8.0 | Gaussian jitter applied to every interval around the current mean. |
| `INTERVAL_PERSISTENCE` | 0.45 | Fraction of the previous interval's deviation carried forward, so intervals trend instead of firing white noise. |
| `INTERVAL_MIN_MS` / `INTERVAL_MAX_MS` | 45–400 ms | Hard floor/ceiling on any single interval. |
| `RESAMPLE_MIN_S` / `RESAMPLE_MAX_S` | 0.4–0.9 s | How often the CPS target is resampled. |
| `BURST_CHANCE_PER_WINDOW` | 0.15 | Chance per resample that a short fast burst starts. |
| `BURST_SPEEDUP_MIN` / `_MAX_` | ×1.15–1.28 | CPS multiplier during a burst. |
| `BURST_CLICKS_MIN` / `_MAX_` | 2–4 | Burst length bounds, clicks. |
| `PAUSE_CHANCE_PER_CLICK` | 0.02 | Chance that a reaction-shaped pause follows a click. |
| `PAUSE_MIN_S` / `_MAX_` | 0.12–0.35 s | Pause length bounds. |

Tuning guide:

- **Rhythm feels metronomic in replays?** Raise `JITTER_SIGMA_MS`, widen the `CPS_*` band, shorten
  the resample window, or raise `PAUSE_CHANCE_PER_CLICK`.
- **Feels sluggish in fights?** Shift `CPS_MIN`/`CPS_MAX` up together; keep `INTERVAL_MAX_MS`
  comfortably above the mean interval so pauses still fit.
- **Bursts look unnatural?** Lower `BURST_CHANCE_PER_WINDOW` or narrow the speedup band.
- **Server anticheat flags interval patterns?** Raise `INTERVAL_PERSISTENCE` (more trend) and
  `JITTER_SIGMA_MS` (more spread) together rather than jitter alone.

Delivery goes through `AttackController` plus two mixins: `MinecraftMixin` injects at the head of
`handleKeybinds`, and `KeyMappingAccessor` exposes the bound attack key (26.2 removed its public
getter) so synthetic clicks enter through `KeyMapping.click` — the same path as real GLFW presses.
At most `AttackController.MAX_CLICKS_PER_TICK` synthetic clicks are delivered per tick; extras are
dropped to keep the rhythm honest under frame hitches.

## Structural constants

Some constants are not tuning knobs and shouldn't move casually:

- `StandingPositions.PLAYER_WIDTH/HEIGHT`, `STEP_HEIGHT`, `JUMP_HEIGHT` — mirror vanilla player
  collision and movement physics; the pathfinder's world model depends on them being exact.
  `THIN_FLOOR_MAX` (floors thinner than half a block count as their own surface) and `SWEEP_STEP`
  (clearance-sweep resolution) sit close behind them.
- `PathfindingRegion.MIN_X..MAX_Y` — the hard region bounds the whole system operates inside.
- `RotationController.EPSILON`, `PathFollower.TICK_SECONDS` — tied to mouse-pixel granularity and
  the vanilla tick respectively.
- Renderer values (`PathGizmoRenderer`, `ManualNodeRenderer`) — pure visuals: line width, node box
  size, lift above ground, and ARGB colours. Safe to taste.
