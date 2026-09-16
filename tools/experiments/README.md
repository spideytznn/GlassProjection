# Bounded dual-display experiment

This is a diagnostic for the already inspected **lhasa / HyperOS OS4.0.11.0.XPNCNXM** firmware. It is not part of the APK, does not migrate native tasks, and does not establish that switching the primary physical display is seamless. Physical display identifiers and device-state numbers are deliberately restricted to the tested device configuration.

Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to an SDK containing Android 35 and Build Tools 35.0.0, then run:

```text
python tools/experiments/build_dual_trial.py
```

The DEX is written to `build/diagnostics/dual-display-live/classes.dex`; bundled production helper assets remain untouched.

With this device connected through authorized ADB, the existing app/helpers running, and the phone unlocked and partly open:

```text
adb push build/diagnostics/dual-display-live/classes.dex /data/local/tmp/glass-dual-trial.dex
adb shell chmod 444 /data/local/tmp/glass-dual-trial.dex
adb shell CLASSPATH=/data/local/tmp/glass-dual-trial.dex app_process /system/bin io.github.sixzleo.tabfold.probe.DualDisplayTrial 20 live
```

Modes:

- `pattern`: prefilled grid on the secondary display.
- `cover`: grid, additionally asserting the cover is currently primary.
- `live`: aspect-fit live primary content on the secondary display.
- `fold`: one shared capture and blur pyramid, with separate fold shader outputs on both displays.
- `handoff`: diagnostic grid, then one opposite-primary state request after six seconds. This deliberately measures stock primary remapping and may expose the system's blank interval.
- `handoff-on`: the same remapping test, plus up to 60 direct ON calls to the physical inner panel within a 1.25-second scheduling window. Calls may block. The worker is stopped before cancelling the state request. This is an ON retry experiment, not an OFF interceptor; a tested run still executed OFF and only received subjective feedback of a shorter blank interval.
- `angle-on`: cover-to-inner experiment with no output surfaces. Begin below `max(4, configuredOpenAngle - 12)` degrees. It starts serialized inner-panel ON calls at that angle, with a 1 ms yield after each call; it requests state 5 at the configured opening angle and continues ON for up to 1.5 seconds afterwards. A burst is capped at 6 seconds / 5,000 calls, and retreating four degrees below its start ends the experiment. The initial cover mapping is restored if releasing the installed controller exposes a different base state; keep the small opening until readiness is reported. The entire session remains bounded to 35 seconds and restores the installed helpers. This mode measures power commands without a grid, mirror or transition overlay.

- `angle-live`: the same angle-driven ON experiment, with a normal live secondary mirror. With cover primary, content fills only the upright right half of the inner secondary; the left half is black. After the native swap, the cover secondary displays the right half of the inner primary. Both mappings use proportional center-crop to fill their destination, with no transition snapshot, blur or fade layer. A new capture producer and output geometry are prepared after remapping; the new output is shown only after its first frame. The trial waits for both panels to report committed ON before announcing `DUAL_READY`, then allows up to 12 seconds of observation after the swap within the 35-second overall bound.
- `angle-snapshot`: adds a GPU copy of the current source at the pre-ON angle. Four hidden outputs are allocated in advance and painted from that frozen frame for the old/new display mappings. A transaction changes which pair is visible when the physical primary changes. The screenshot stays above the live outputs until both panels are committed ON and new-source frames have advanced for at least 200 ms, then fades over 160 ms. Readiness has a 3.5-second bound after the request. Screenshots remain in GPU memory and are excluded from mirror capture; they cannot display during physical OFF or an invalid/blank display layer stack. This mode tests coverage of content rebuilding in addition to repeated ON, not interception of OFF.

The duration is bounded to 5–35 seconds. The `handoff*` and `angle-*` modes explicitly swap primary displays; other modes keep the same primary (state 5 or 6). Closing the phone, losing the allowed unlocked scene, or stale telemetry ends trials. Geometry changes end ordinary live trials; `angle-live` rebinds its source and destination. Inner-secondary orientation currently uses the measured upright pose, rotation 3, rather than implementing automatic orientation tracking. Aspect-fit margins in ordinary `live`/`fold` modes are intentional; this is shared content, not independently laid-out launcher desktops. A `TICK` draw count measures loop submissions, not optical presentation rate or latency. Source frames can stop advancing on a static desktop.

The experiment pauses only the verified app-owned helper supervisor, takes both helper locks, and arms a separate 55-second supervisor recovery process before pausing. Cleanup cancels its own state request, removes its output, and resumes the original helpers. Do not overwrite the DEX or overlap trials while either the trial or its recovery process is running. This temporary process isolation is for diagnosis; production requires a single coordinated state owner.

Raw device logs and captures belong under ignored `build/diagnostics/`, not in version control. See [trial results](../../docs/DUAL-DISPLAY-TRIAL.zh-CN.md) for what was actually verified.

`NoRootDisplayProbe` inventories runtime permissions, display records, API signatures and service descriptor responses without requesting a display transition. Its optional `same-on` argument repeats ON on the already committed-ON main panel. It does not grant permissions or modify another process. `PhysicalDisplayAccess` loads the Android 14+ physical-token API in this shell process, following the API-loading approach in [scrcpy's DisplayControl wrapper](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/wrappers/DisplayControl.java). Running this probe does not pause the installed helpers. A separate device path such as `/data/local/tmp/glass-noroot-probe.dex` avoids overwriting a trial's independent recovery executable.
