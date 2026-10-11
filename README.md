# sbt TaskProgress grows for the life of a command

A long-running command that evaluates tasks over and over (Play's `run` does
this on every reload) gets slower with each evaluation and holds more heap,
until the command ends.

`build.sbt` has 30 one-file subprojects and a `loop` command that runs
`root / Compile / compile` N times inside one command, printing each
evaluation's time, the heap after a GC, and the sizes of the maps on the
command's `TaskProgress`.

```
sbt -batch compile "loop 100"          # one command: grows
sbt -batch compile "loop 100 clear"    # same, clearing anonOwners/calledBy/taskNameCache each time: slower growth
sbt -batch compile "loop 100 clearall" # also closing and clearing TaskProgress.active: time flat, heap still grows
sbt -batch compile "loop 1" "loop 1" … # separate commands: flat
```

Nothing is recompiled after the first `compile`; every evaluation is a no-op
compile. For sbt 2, set `sbt.version=2.0.10` in `project/build.properties` and
run `sbt -batch "compile; loop 100"`.

## Results (macOS, JDK 21.0.11, 30 modules, no-op compile each time)

| Variant | Evaluation 1 | 25 | 50 | 100 | Heap after GC, 1 → 100 |
|---|---|---|---|---|---|
| sbt 1.13.0, one command | 340 ms | 404 | 721 | 1,819 | 125 → 352 MB |
| sbt 1.13.0, one command, three name maps cleared | 344 ms | 334 | 356 | 624 | 135 → 350 MB |
| sbt 1.13.0, one command, three maps + `active` cleared | 274 ms | 180 | 161 | 155 | 125 → 290 MB |
| sbt 1.13.0, separate commands | 276 ms | ~150 | ~150 | ~160 | 126 → 127 MB |
| sbt 2.0.10, one command | 316 ms | 153 | 150 | 142 | 169 → 304 MB |

An earlier 150-evaluation run on 1.13.0 reached 4,044 ms (230 ms at the start).

What grows inside one command (sbt 1.13.0):

- `AbstractTaskProgress.anonOwners`, `calledBy`, `taskNameCache`: never cleared
  (only `timings` is, via `clearTimings`). Same in sbt 2's
  `AbstractTaskExecuteProgress`.
- `TaskProgress.active`: `afterReady` adds on the progress executor,
  `afterCompleted` removes on the calling thread; when the executor runs late,
  the remove misses and the entry (with its scheduled report) stays.
- The progress executor's queue: a histogram diff between evaluations 20 and
  110 showed +367k `LinkedBlockingQueue$Node` / `TaskProgress` lambdas and
  +448k `ScheduledFutureTask`.
- Per-task loggers (~490 `ConsoleAppender` / `LoggerContext` `Log` /
  `PrintWriter` per evaluation), held until the command ends.

In sbt 2.0.10 the same maps grow and heap rises ~1.4 MB per evaluation, but
`active` stays small and evaluation time does not grow over 100 evaluations.
