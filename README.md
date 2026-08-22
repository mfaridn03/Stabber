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
| `SPRINT_YAW_TOLERANCE` | 20 deg | Max heading error while sprinting straight. |
| `SPRINT_MIN_REMAINING` | 3.0 | Blocks left below which sprinting is dropped to regain turn authority. |
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

## Structural constants

Some constants are not tuning knobs and shouldn't move casually:

- `StandingPositions.PLAYER_WIDTH/HEIGHT`, `STEP_HEIGHT`, `JUMP_HEIGHT` — mirror vanilla player
  collision and movement physics; the pathfinder's world model depends on them being exact.
- `PathfindingRegion.MIN_X..MAX_Y` — the hard region bounds the whole system operates inside.
- Renderer values (`PathGizmoRenderer`, `ManualNodeRenderer`) — pure visuals: line width, node box
  size, lift above ground, and ARGB colours. Safe to taste.
