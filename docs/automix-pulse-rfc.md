# RFC: AutoMix "Pulse" engine

Status: accepted, in implementation. Replaces the pair-planner automix (kept one release
behind *Settings → Automix engine → Legacy*, removed in the release after).

Source of the algorithm: [VertexSelection/automix-engine](https://github.com/VertexSelection/automix-engine)
`cue_analysis.py` and `stem_transition.py`. That engine renders a transition offline from
Demucs stems and Rubber Band time maps. Square-Yuma mixes live on a phone, so every stage is
mapped onto real-time pieces the app already has: the C++ analyzer, the two INT8 ONNX models,
Bungee, and the DJ filter.

## 1. Goals and constraints

- Same musical decisions as automix-engine: 32-pulse locked overlap, vocal-safe exit, drum/bass
  cue refinement, per-beat tempo map with a 6.48 s release, vocal reveal, outgoing fill,
  loudness smoothing, grid QA.
- No stem separation, no offline render, no full-file decode. Analysis touches 65 s at each end
  of a track (60 s window + 5 s preroll), local or streamed, and is cached for good.
- No `playbackParameters` changes while mixing: each one flushes Media3's processor chain, which
  was the stutter at the end of the old mix. Tempo lives inside the stretcher.
- Battery: one track analysed at a time; the ONNX pass is skipped under Battery Saver or
  thermal status ≥ MODERATE.

## 2. Function map

| automix-engine | Square-Yuma | Notes |
|---|---|---|
| `onset_strength`, autocorrelation tempo, `beat_track` (cue_analysis) | `cpp/analyzer/tempo_analysis.cpp` (existing) | Whole-mix grid, as before. |
| `regular_pulse_interval` | `tempo_analysis.cpp` → JSON `pulseInterval`, `pulseConfidence` | Autocorrelation of the low-band + percussive onset flux, 55–135 BPM, parabolic peak refine, strongest peak within ±8 % of the prior with ac ≥ 0.82·max, else the global maximum. |
| `drum_beat_grid` | JSON `beats` | Fixed-period grid at `pulseInterval`, phase from the tracker, extrapolated over the window. |
| `estimate_drum_pulse` (subdivision) | JSON `pulseSubdivision` | Mode of inter-onset intervals vs the pulse: 1, 2 or 4. Used by QA only. |
| drum attacks (`peak_pick`) | JSON `drumOnsets` | Peaks of the percussive flux (`<250 Hz` + `3–5 kHz`; the analyzer runs at 11 025 Hz), below the 35th percentile dropped. |
| `rhythm_db` (drums+bass stems) | JSON `rhythmDb` (50 ms frames) | 10·log10 of band energy `<250 Hz` + `3–5 kHz`. Stand-in for the drums+bass stems. |
| vocal stem RMS (all vocal logic) | `PulseFeatures.vocalDb` (50 ms) | Inside ONNX windows: mix RMS dB + 20·log10(open-unmix mask). Elsewhere: JSON `vocalDb`, a 300–3400 Hz band proxy from the analyzer. |
| `short_term_rms_db`, LUFS | JSON `rmsDb` (50 ms) | Mix RMS. Loudness matching keeps the existing −16 LUFS normalisation. |
| CUE-DETR candidates, `choose_cue` | `pulse/CuePlanner.kt` | Candidates are `mixOutCandidates`/`mixInCandidates` + phrase boundaries, snapped to `beats`. Outgoing: latest in [0.55·dur, dur−4 s]. Incoming: earliest ≤ min(0.35·dur, 75 s). |
| `outgoing_rhythm_timing` | `CuePlanner.outgoing` | Vocal ref = P75 of per-beat vocal dB over the 32 beats before the cue, quiet = max(−42, ref−18). End = first quiet beat within +32 beats, +`POST_VOCAL_HANDOFF_PULSES` (4); start = end − 32. `cuePulse` = beat of the last sustained vocal frame, clipped to [16, 28]; 16 with no vocal. |
| `refine_incoming_cue` | `CuePlanner.incoming` | Score = after + 0.65·min(rise, 12) over 8 beats of `rhythmDb`; strong if after ≥ P90 − 10; taken if it gains ≥ 3 dB or the original cue is weak. |
| `build_tempo_map` | `pulse/TempoMap.kt` | 33 incoming beats pinned onto the 33 outgoing beats; release of 16 segments × 0.405 s (6.48 s), rate = matched + (1 − matched)·smoothstep, slope = mean of neighbouring rates, then 1:1. |
| `build_outgoing_fill_tempo_map` | `TempoMap.outgoingFill` | Outgoing follows the incoming release rates ÷ tempo-match rate, so both stay on one pulse. |
| `detect_vocal_handoff` | `pulse/VocalPlanner.outgoingFade` | Outgoing vocal fades only after the last sustained active frame, over 0.5 bar. |
| `detect_next_vocal_line_start` | `VocalPlanner.nextLineStart` | 50 ms frames from release end − 3 s to + 30 s, quiet = max(−42, P75 − 15); gap ≥ 0.04 s then ≥ 0.75 s active (one dropout allowed); 25 ms high-res pass when the wait exceeds 1.5 s. |
| `plan_incoming_vocal_reveal` | `VocalPlanner.twoStage` | lead = min(wait, clip(0.55 + 0.65·wait, 0.55, 1.60)); line gain 0.32–0.52; tail = clip(0.45 + 0.25·wait, 0.45, 0.80). |
| `plan_cue_synchronous_vocal_entry` | `VocalPlanner.cueSync` | Onset within ±0.40 s of the cue, ≤ 1 active frame in the 0.25 s before, rise ≥ 10 dB, outgoing vocal quiet, cue ≥ 45 % of the transition, rate 0.82–1.18. Fill off in this mode. |
| `build_instrumental_bus`, `suppress_local_vocal_projection` | `DjFilterAudioProcessor` M/S vocal duck | Mid channel 300–3400 Hz attenuated, Side and the low/high bands kept. |
| stem gains on the bars axis | `pulse/MixAutomation.kt` | Same bar ranges (`cue_bar = cuePulse/4`, bed/rhythm ends, tail start). Stems map onto bands: bass → low, drums → low+high, other → mid, vocals → M/S duck. 320 Hz bed / 170 Hz drums high-pass reveal on the incoming; 3.6 kHz "dark" low-pass on the outgoing. |
| `OUTGOING_FILL*` | `MixAutomation.fill` | Level = clip(rmsIn/rmsOut · 10^(−6/20), 0.28, 0.65), floor applied to every outgoing gain during the lock, then the fill runs after handoff until the outgoing vocal fade ends. |
| `smooth_loudness_control` | `pulse/LoudnessControl.kt` | Predicted from `rmsDb` + the gain plan: correction = clip(target/predicted, −3 dB, +5 dB), smoothed (σ ≈ 0.2 s), tapered over 0.5 s at both ends; applied through deck volume. |
| `drum_grid_qa`, `beat_alignment_qa` | `pulse/GridQa.kt` | Per side: `drumOnsets` in the transition vs the subdivided grid, pass if p90 \|phase\| ≤ 0.32·pulse. Pair: \|median relative phase\| ≤ 0.12·pulse after the tempo map; local rates within [0.82, 1.25] and monotonic. **Fail ⇒ reject** the beat-match (automix-engine only reports) and fall back to FILTER_SWEEP (grid confidence ≥ 0.2) or EQUAL_POWER. |
| JSON report | `pulse/MixReport.kt` → `filesDir/automix/reports/` | Inputs, chosen cues, tempo map, vocal mode, fill, every QA metric and the accept/reject reason. Last 50 kept; included in *Save a report*. |
| `pair_identity`, `PAIR_ANALYSIS_CACHE_VERSION` | `smart/PairCache.kt` → `filesDir/automix/pairs/` | id = sha256(trackIdA \| trackIdB \| analysis version \| pair version)[:16]. |
| `STEM_WINDOW_SECONDS` = 60, `STEM_WINDOW_PREROLL_SECONDS` = 5 | `smart/AudioDecoder.kt` | Head 0–65 s, tail dur−65 s → end, for every source. |
| `vocal_echo` | not ported | Defined but never called upstream. |

## 3. Analyzer JSON additions (per window; times relative to the window start)

```json
{
  "pulseInterval": 0.5,          "pulseConfidence": 0.8,   "pulseSubdivision": 2,
  "beats":      [0.12, 0.62, ...],
  "drumOnsets": [0.11, 0.37, ...],
  "rmsDb":      {"t0": 0.0, "hop": 0.05, "v": [-23.1, ...]},
  "rhythmDb":   {"t0": 0.0, "hop": 0.05, "v": [-30.2, ...]},
  "vocalDb":    {"t0": 0.0, "hop": 0.05, "v": [-41.0, ...]}
}
```

dB values are 10·log10(mean square + 1e-10) on the analyzer's normalised mono signal. The fake
mid/high curves are removed. Existing fields are unchanged. The tail window's times are shifted
by `tailOffset` when parsed, as before.

## 4. Data structures

- `pulse/Envelope` — segments of (t0, hop, values); `at(t)` is NaN where uncovered.
- `pulse/PulseFeatures` — wraps `MixFeatures` (key, cues, phrases, downbeats) and adds `beats`,
  `pulseSec`, `pulseSubdivision`, `pulseConfidence`, `drumOnsets`, `rmsDb`, `rhythmDb`,
  `vocalDb` (+ `vocalFromModel`), joined across head and tail.
- `pulse/PulsePlan` — what the engine plays:
  `outBeats[33]`, `inBeats[33]` (track ms), `cuePulse`, `inTempo`/`outFillTempo` (`TempoMap`
  points source ms → target ms), `releaseEndTargetMs`, `outVocalFadeBars`, `inVocal`
  (`mode` CUE_SYNC | TWO_STAGE | NONE, `fadeStartMs`, `lineStartMs`, `lineGain`, `tailMs`, on
  the incoming track clock), `fill` (`level`, `endMs` on the outgoing clock), `loudness`
  (correction points on the bars axis), `beatmatched`, `fallbackStyle`, `qa`, `pairId`.

`AutomixPlan` stays as it is for the legacy engine (its loop fields are removed with Loop Mix).

## 5. Real-time DSP

Per deck, Media3 chain: `Vocal(karaoke) → Bungee → EQ → DjFilter`.

- **Bungee** carries a `TempoMap` keyed on the deck's source frame (position from
  `onFlush.positionOffsetUs`). Each block runs at `rate(sourceFrame) × userSpeed`. The chain's
  `getMediaDuration` integrates actual frames in/out, so the deck clock, seek bar and lyrics stay
  true. Returning to 1.0 still flushes the stretcher's held latency from history.
- **DjFilter** becomes a virtual stem bus: LR4 crossovers at 250 Hz and 5 kHz give low/mid/high
  bands with their own gains; a Mid/Side stage ducks the Mid channel's 300–3400 Hz for
  instrumental-first; the high-pass (320 Hz bed / 170 Hz drums) and the 3.6 kHz low-pass stay.
  Parameters ramp linearly across each block (no zipper).

## 6. Engine state machine (YumaDualDeckPlayer, Pulse branch)

```
IDLE ─(both analysed, plan ready)→ PREPARED ─(out reaches outBeats[0])→ LOCK
LOCK: 32 pulses. Progress = outgoing position mapped onto bars via outBeats (not wall clock).
      Incoming plays inTempo; band gains/filters from MixAutomation; drift measured every
      500 ms and corrected by shifting the schedule, never via playbackParameters.
LOCK ─(outBeats[32])→ HANDOFF: forwarding player attaches the incoming deck (UI, session,
      queue follow it).
TAIL: incoming in tempo release (6.48 s) + vocal reveal; outgoing continues as fill on
      outFillTempo until the outgoing vocal fade / fill end, then stops.
TAIL → IDLE.  Seek, skip, queue edit, sleep timer, pause beyond a stall → cancel, both decks
      reset (tail included).
```

Beat-match rejected by QA ⇒ the same machine runs with constant rate 1.0 and the fallback
style's curves (`MixStyles`), length 16 beats (FILTER_SWEEP) or the user's crossfade (EQUAL_POWER).

## 7. Caching and battery

- Track analysis: `filesDir/track_analysis/<md5(trackId)>.json`, `CACHE_VERSION = 5`. A
  DSP-only result (model skipped by the guard) is stored with `modelSkipped = true` and upgraded
  later instead of re-analysed every play.
- One analysis at a time (`Semaphore(1)`), current track first, then the next one only.
- `ModelPass`: skipped under Battery Saver or thermal ≥ MODERATE; 2 inference threads, one mutex.

## 8. Testing

Pure-Kotlin unit tests (no device): 32-pulse alignment and pinning, mid-lyric exit wait,
cue-sync vs two-stage selection, release shape and length, QA rejection fallback with its
reason in the report, `TransitionRules` compatibility, M/S duck response, tempo-map media
duration math, battery guard skipping the model pass.
