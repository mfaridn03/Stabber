Some docs 

## Rotation — `client/rotation/RotationController.kt`

Each axis runs a three-stage pipeline: the requested angle feeds an exponentially smoothed
*reference*, the remaining error commands a *turn speed*, and that speed is approached under an
*acceleration limit*. On top sits an organic *drift* layer so the gaze never moves robotically.

| Constant | Default | What it controls |
|---|---|---|
| `DEFAULT_MAX_STEP` | 1800 deg/s | Ceiling on turn speed when the caller doesn't specify one. `snapTo` bypasses it entirely (`Double.MAX_VALUE`). |
| `EPSILON` | 0.01 deg | Angle below which an axis counts as arrived. |
| `SETTLE_SPEED` | 0.5 deg/s | Residual speed below which a settled axis stops nudging. |
| `REFERENCE_GAIN_PER_SEC` | 30.0 | How fast the smoothed reference chases the requested angle. |
| `STEERING_GAIN_PER_SEC` | 8.0 | Turn speed commanded per degree of remaining error (deg/s per deg). |
| `MAX_ACCEL_DEG_PER_SEC_SQ` | 6000.0 | Acceleration limit that rounds off the start/end of every glance. |
| `DRIFT_YAW_DEG` | 0.45 | Peak yaw wander layered onto requests. |
| `DRIFT_PITCH_DEG` | 0.30 | Peak pitch wander layered onto requests. |
| `DRIFT_SLOW_HZ` | 0.35 Hz | Rate of the lazy sway layer. |
| `DRIFT_FAST_HZ` | 1.30 Hz | Rate of the quicker flick layer. |
| `DRIFT_FAST_WEIGHT` | 0.5 | Strength of the quick flick relative to the slow sway. |

Tuning guide:

- **Feels too laggy while walking?** Raise `REFERENCE_GAIN_PER_SEC` / `STEERING_GAIN_PER_SEC`.
- **Overshoots or rings around the target?** Lower `STEERING_GAIN_PER_SEC`, or raise
  `MAX_ACCEL_DEG_PER_SEC_SQ` if it feels sluggish rather than wobbly.
- **Starts/stops too abruptly (robotic)?** Lower `MAX_ACCEL_DEG_PER_SEC_SQ` for softer ramps;
  raising it makes glances snappier but mechanical.
- **Wobble/jitter near the target or slow final approach?** Raise `EPSILON` (arrive sooner) and/or
  `SETTLE_SPEED` (stop micro-nudging earlier).
- **Looks drunk / wanders too much while aiming?** Lower `DRIFT_YAW_DEG` / `DRIFT_PITCH_DEG`.
- **Drift imperceptible or too obvious in recordings?** Scale both drift amplitudes together; tweak
  `DRIFT_SLOW_HZ` / `DRIFT_FAST_HZ` only if the sway rhythm itself looks wrong.

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
- **Strafe keys stutter near `STRAFE_ENTER_DEGREES`?** Raise `STRAFE_HYSTERESIS`.
- **Turns look robotic mid-path?** Lower `TURN_RATE_DEG_PER_SEC`; raise if it misses the carrot.
- **Declares failure too eagerly after a knockback?** Raise `OFF_PATH_TICKS` / `OFF_PATH_XZ`.

## Pathfinding — `client/path/AStarPathfinder.kt`

| Constant | Default | What it controls |
|---|---|---|
| `MAX_EXPANSIONS` | 2000 | Node expansions before giving up — hard runtime cap. |
| `MAX_RADIUS` | 64 | Search radius from start, in blocks. |
| `MAX_GAP` | 2.5 | Widest horizontal gap a jump/drop link may span. |
| `JUMP_EPSILON` | 0.05 | Height tolerance when validating jump links. |
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
walks at the target holding W and releases inside melee distance, rests the cursor on the
opponent instead of tracking them (gliding back toward their centre only when strafing pushes the
hitbox toward the edge of view), and clicks at a wandering 8–12 CPS delivered through vanilla's own
attack-key path (`KeyMapping.click`, so cooldowns, miss swings and knockback behave exactly like
real presses). Mutually exclusive with pathfinding — whichever starts second stops the other.

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

The cursor's offset from the hitbox centre is measured in *apparent radii* — degrees off centre
divided by `atan(halfDiagonal / distance)` — so one hysteresis band works at every range. Pitch
hugs zero whenever a level ray already passes through the hitbox (which also keeps the full
interaction range); only boxes entirely above or below the horizon pull pitch off zero.

| Constant | Default | What it controls |
|---|---|---|
| `PITCH_LIMIT_DEG` | 30° | Saturation clamp for pitch; targets higher/lower than this stop pulling the view further down/up. |
| `PITCH_DEADBAND_DEG` | 6° | Required pitch corrections within this band of level are treated as exactly level. |
| `PITCH_REACQUIRE_DEG` | 0.6° | A settled pitch is left alone until the error grows past this. |
| `PITCH_ONLY_RATE_DEG_PER_SEC` | 120 | Turn speed cap while correcting pitch alone. |
| `HOVER_ENTER` | 0.55 | Offset below which the cursor counts as resting centred (no rotation issued). |
| `HOVER_EXIT` | 0.80 | Offset past which — near the apparent edge — a glide back to centre fires. |
| `REACQUIRE_RATIO` | 1.5 | Offset beyond which the correction becomes a fast reacquire glance. |
| `RECENTER_MIN_RATE_DEG_PER_SEC` / `_MAX_` | 260–400 | Glide speed rolled once per correction event. |
| `REACQUIRE_RATE_DEG_PER_SEC` | 540 | Speed when the cursor lost the target entirely. |
| `OVERSHOOT_MAX_FRACTION` | 0.15 | Glides may carry past centre by up to this fraction of the apparent radius, rolled per event. |
| `MIN_APPARENT_RADIUS_DEG` | 1.5° | Floor on the apparent radius so distant targets cannot make ratios explode. |
| `MIN_AIM_DISTANCE_XZ` | 1.0 | Below this horizontal distance bearings are meaningless; hold the view. |
| `NUDGE_MIN_INTERVAL_S` / `_MAX_` | 0.6–1.4 s | Cadence of hover nudges outside of bursts. |
| `NUDGE_BURST_CHANCE` | 0.30 | Chance a nudge is followed by a quick second one (flick-and-correct). |
| `NUDGE_BURST_MIN_GAP_S` / `_MAX_` | 0.12–0.26 s | Gap between the two nudges of a burst. |
| `NUDGE_MIN_FRACTION` / `_MAX_` | 0.06–0.16 | Nudge peak as a fraction of the hitbox's apparent radius. |
| `NUDGE_MAX_YAW_DEG` | 2.5° | Absolute yaw safety cap; only bites on small/distant targets. |
| `NUDGE_PITCH_RATIO` | 0.85 | Pitch nudge size relative to the rolled yaw nudge size. |
| `NUDGE_RATE_DEG_PER_SEC` | 14 | Turn speed cap while playing a nudge out. |

Tuning guide:

- **Tracks the target too much (cursor never rests)?** Widen the gap by lowering `HOVER_ENTER`
  and/or raising `HOVER_EXIT`.
- **Eats hits because recentres are too slow?** Raise both `RECENTER_*` rates, or lower `HOVER_EXIT`
  so glides fire earlier.
- **Looks robotic when strafe-crossing?** Raise `ADJUST_OFFSET_MAX_FRACTION` and widen the rate band.
- **Aim sits visibly above/below heads?** Lower `PITCH_DEADBAND_DEG` (or raise it if the pitch
  micro-adjustments bother you).
- **Micro-stutters too visible in recordings?** Lower `NUDGE_MAX_FRACTION` / `NUDGE_MAX_YAW_DEG`
  or stretch the nudge interval band.
- **Hover nudges imperceptible?** Raise `NUDGE_MAX_FRACTION` toward 0.08 or slow
  `NUDGE_RATE_DEG_PER_SEC` so each glide reads longer.

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
- `PathfindingRegion.MIN_X..MAX_Y` — the hard region bounds the whole system operates inside.
- Renderer values (`PathGizmoRenderer`, `ManualNodeRenderer`) — pure visuals: line width, node box
  size, lift above ground, and ARGB colours. Safe to taste.
