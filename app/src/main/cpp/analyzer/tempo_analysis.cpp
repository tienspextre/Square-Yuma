/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard) via BitChord.
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into YumaPlayer -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

// Offline tempo and beat-grid estimation, entirely DSP (autocorrelation plus a
// phase-locked tracking loop) -- no ML model. All spectra, envelopes, scores,
// and event vectors are call-owned allocations; this file has no
// synchronization or real-time guarantees and must run on a worker thread.
//
// This is the confidence a transition policy without a trained beat-tracking
// model has to work with. It is deliberately treated as less trustworthy than
// a model's grid would be -- see MIN_BEATMATCH_CONFIDENCE in
// TransitionPolicy.kt -- but is real evidence, not a guess: BuildStructure and
// the mix-in/out scoring in audio_analysis.cpp both read the resulting
// downbeats to place transitions on the bar.

#include "audio_analysis.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <numeric>
#include <vector>

namespace yuma::smart {
namespace {

constexpr double kPi = 3.14159265358979323846;

// How much audio the onset envelope covers. The envelope feeds beat
// *tracking* as well as tempo estimation, so it has to reach the end of the
// track: a transition's mix-out anchor sits in the outro, and a grid that
// stops three minutes short of it is extrapolation, not measurement. The cap
// is a memory guard for pathological input, not a tuning constant -- twenty
// minutes at 86 frames per second is under a megabyte of envelope.
constexpr double kMaxEnvelopeSeconds = 1200.0;

// How much of the envelope the autocorrelation tempo search reads. Bounded
// separately because Correlation() is O(n) per lag across ~110 lags, so
// letting it see a whole track would cost seconds; three minutes is ample to
// establish which metrical level a track is on, and the phase-locked grid
// below handles everything local from there.
constexpr double kMaxTempoSearchSeconds = 180.0;

// Upper edge of the band used to find downbeats. Kick drums live here and
// snares essentially do not, which is the entire point -- see the downbeat
// scoring below.
constexpr double kLowBandHz = 150.0;

double Clamp(double value, double minimum, double maximum) {
  return std::max(minimum, std::min(maximum, value));
}

// Unnormalized in-place radix-2 FFT. The fixed 512-sample caller satisfies the
// non-empty power-of-two precondition, so no generic padding is performed here.
void Fft(std::vector<std::complex<double>>& values) {
  const size_t size = values.size();
  for (size_t index = 1, swapped = 0; index < size; ++index) {
    size_t bit = size >> 1;
    for (; swapped & bit; bit >>= 1) swapped ^= bit;
    swapped ^= bit;
    if (index < swapped) std::swap(values[index], values[swapped]);
  }

  for (size_t length = 2; length <= size; length <<= 1) {
    const std::complex<double> root = std::polar(1.0, -2.0 * kPi / length);
    for (size_t start = 0; start < size; start += length) {
      std::complex<double> weight(1, 0);
      for (size_t offset = 0; offset < length / 2; ++offset) {
        const auto even = values[start + offset];
        const auto odd = values[start + offset + length / 2] * weight;
        values[start + offset] = even + odd;
        values[start + offset + length / 2] = even - odd;
        weight *= root;
      }
    }
  }
}

struct OnsetEnvelopes {
  // Positive spectral flux across the whole spectrum: what "a note started"
  // looks like without regard to which instrument played it.
  std::vector<double> full;
  // The same measure restricted to the bass band. Kept separately because
  // deciding *which* beat is beat one is a different question from deciding
  // where the beats are, and the two want different evidence.
  std::vector<double> low;
  // Percussive flux: <250 Hz (kick) plus 3-5 kHz (snare/hat attack).
  std::vector<double> perc;
};

// Normalizes an onset envelope in place: subtract a local mean to suppress
// steady-state energy, then peak-normalize and sqrt-expand what remains so
// the quieter onsets still participate in correlation and phase scoring.
void NormalizeEnvelope(std::vector<double>& envelope, double frames_per_second) {
  if (envelope.empty()) return;
  const size_t radius = std::max<size_t>(2, static_cast<size_t>(frames_per_second * 0.35));
  std::vector<double> prefix(envelope.size() + 1, 0);
  for (size_t index = 0; index < envelope.size(); ++index) {
    prefix[index + 1] = prefix[index] + envelope[index];
  }
  for (size_t index = 0; index < envelope.size(); ++index) {
    const size_t left = index > radius ? index - radius : 0;
    const size_t right = std::min(envelope.size(), index + radius + 1);
    const double local_mean = (prefix[right] - prefix[left]) / std::max<size_t>(1, right - left);
    envelope[index] = std::max(0.0, envelope[index] - local_mean * 1.08);
  }

  const double peak = *std::max_element(envelope.begin(), envelope.end());
  if (peak > 0) {
    for (double& value : envelope) value = std::sqrt(value / peak);
  }
}

// Converts the track into full-band and bass-band spectral-flux onset
// envelopes. Each Hann-windowed spectrum contributes only positive
// log-magnitude changes; subtracting 1.08 times a roughly +/-350 ms local mean
// suppresses steady-state energy, then peak normalization plus sqrt expands
// quieter remaining onsets.
OnsetEnvelopes OnsetEnvelope(
  const std::vector<float>& samples,
  double sample_rate,
  size_t frame_size,
  size_t hop_size
) {
  OnsetEnvelopes result;
  const size_t maximum_samples = std::min(
    samples.size(),
    static_cast<size_t>(sample_rate * kMaxEnvelopeSeconds)
  );
  if (maximum_samples < frame_size) return result;

  const size_t frame_count = 1 + (maximum_samples - frame_size) / hop_size;
  // Bins from DC up to kLowBandHz. At the normal 11,025 Hz rate with a
  // 512-sample frame each bin spans 21.5 Hz, so this is bins 1 through 7.
  const size_t low_band_bins = std::min<size_t>(
    frame_size / 2,
    std::max<size_t>(2, static_cast<size_t>(kLowBandHz * frame_size / sample_rate))
  );
  result.full.assign(frame_count, 0);
  result.low.assign(frame_count, 0);
  result.perc.assign(frame_count, 0);
  const double bin_hz = sample_rate / frame_size;
  const size_t perc_low_bins = std::max<size_t>(2, static_cast<size_t>(250.0 / bin_hz));
  const size_t perc_hi_start = static_cast<size_t>(3000.0 / bin_hz);
  const size_t perc_hi_end = std::min<size_t>(frame_size / 2, static_cast<size_t>(5000.0 / bin_hz));
  std::vector<double> previous(frame_size / 2, 0);
  std::vector<std::complex<double>> spectrum(frame_size);
  // The Hann shape depends on the fixed FFT size, not on the frame. Reuse it
  // instead of recomputing its cosine values for every structural frame.
  std::vector<double> window(frame_size);
  for (size_t index = 0; index < frame_size; ++index) {
    window[index] = 0.5 - 0.5 * std::cos(2.0 * kPi * index / (frame_size - 1));
  }

  for (size_t frame = 0; frame < frame_count; ++frame) {
    const size_t start = frame * hop_size;
    for (size_t index = 0; index < frame_size; ++index) {
      spectrum[index] = std::complex<double>(samples[start + index] * window[index], 0);
    }
    Fft(spectrum);

    double flux = 0;
    double low_flux = 0;
    double perc_flux = 0;
    for (size_t bin = 1; bin < frame_size / 2; ++bin) {
      const double magnitude = std::log1p(std::abs(spectrum[bin]));
      const double rise = std::max(0.0, magnitude - previous[bin]);
      flux += rise;
      if (bin < low_band_bins) low_flux += rise;
      if (bin < perc_low_bins || (bin >= perc_hi_start && bin < perc_hi_end)) perc_flux += rise;
      previous[bin] = magnitude;
    }
    result.full[frame] = flux;
    result.low[frame] = low_flux;
    result.perc[frame] = perc_flux;
  }

  const double frames_per_second = sample_rate / hop_size;
  NormalizeEnvelope(result.full, frames_per_second);
  NormalizeEnvelope(result.low, frames_per_second);
  NormalizeEnvelope(result.perc, frames_per_second);
  return result;
}

// Energy-normalized autocorrelation: sum(x[n]x[n-lag]) divided by the geometric
// mean of both lagged energies. The epsilon keeps silent input finite.
double Correlation(const std::vector<double>& values, int lag, size_t limit) {
  const size_t length = std::min(limit, values.size());
  if (lag <= 0 || static_cast<size_t>(lag) >= length) return 0;
  double cross = 0;
  double left_energy = 0;
  double right_energy = 0;
  for (size_t index = lag; index < length; ++index) {
    const double left = values[index];
    const double right = values[index - lag];
    cross += left * right;
    left_energy += left * left;
    right_energy += right * right;
  }
  return cross / std::sqrt(std::max(1e-12, left_energy * right_energy));
}

// Linear interpolation lets sub-frame lag refinement participate in phase
// scoring without resampling the complete onset envelope.
double SampleEnvelope(const std::vector<double>& values, double position) {
  if (position < 0 || position >= values.size() - 1) return 0;
  const size_t left = static_cast<size_t>(position);
  const double fraction = position - left;
  return values[left] * (1.0 - fraction) + values[left + 1] * fraction;
}

// Log-Gaussian preference for tempi near 120 BPM, used only to choose between
// metrical levels of the *same* reading -- never to move a tempo off its
// measured lag. Width 0.7 octaves is inside the range the perceptual-tempo
// literature reports and, measured here, is what separates a 140 BPM track from
// its half-time reading without disturbing anything already near 120.
double MetricalPrior(double bpm) {
  if (!(bpm > 0)) return 0;
  const double octaves = std::log2(bpm / 120.0) / 0.7;
  return std::exp(-0.5 * octaves * octaves);
}

// "Pulse" fields (docs/automix-pulse-rfc.md section 2): a regular 55-135 BPM
// pulse from the autocorrelation of the low-band + percussive onset flux, a
// fixed-period beat grid over the whole window, and the drum attacks.
// `prior_bpm` is the tracker's tempo (0 if none).
void AnalyzePulse(
  const OnsetEnvelopes& envelopes,
  double sample_rate,
  double duration,
  double prior_bpm,
  TempoResult& result
) {
  const size_t frames = envelopes.perc.size();
  const double fps = sample_rate / 128.0;
  const double centre = 512.0 / (2.0 * sample_rate);
  const size_t limit = std::min(frames, static_cast<size_t>(fps * kMaxTempoSearchSeconds));
  if (limit < 64) return;

  std::vector<double> flux(limit);
  double mean = 0;
  for (size_t i = 0; i < limit; ++i) mean += (flux[i] = envelopes.low[i] + envelopes.perc[i]);
  mean /= limit;
  double energy = 0;
  for (double& v : flux) { v -= mean; energy += v * v; }
  const double ac0 = energy / limit;
  if (ac0 < 1e-9) return;

  const int min_lag = std::max(2, static_cast<int>(std::floor(fps * 60.0 / 135.0)));
  const int max_lag = std::min<int>(static_cast<int>(limit) / 2, static_cast<int>(std::ceil(fps * 60.0 / 55.0)));
  if (max_lag <= min_lag + 2) return;
  // ac[l] is the mean lagged product, so long and short lags compare fairly.
  std::vector<double> ac(max_lag + 2, 0);
  double ac_max = 0;
  for (int lag = min_lag - 1; lag <= max_lag + 1; ++lag) {
    if (lag < 1 || lag >= static_cast<int>(limit)) continue;
    double sum = 0;
    for (size_t i = lag; i < limit; ++i) sum += flux[i] * flux[i - lag];
    ac[lag] = sum / (limit - lag);
    if (lag >= min_lag && lag <= max_lag) ac_max = std::max(ac_max, ac[lag]);
  }
  if (ac_max <= 0) return;

  // Prior folded by octaves into the pulse range.
  double prior_lag = 0;
  if (prior_bpm > 0) {
    double bpm = prior_bpm;
    while (bpm > 135.0) bpm /= 2;
    while (bpm < 55.0) bpm *= 2;
    prior_lag = fps * 60.0 / bpm;
  }
  double best_lag = 0, best_value = -1;       // strongest near the prior
  double global_lag = 0, global_value = -1;   // global maximum
  for (int lag = min_lag; lag <= max_lag; ++lag) {
    if (!(ac[lag] >= ac[lag - 1] && ac[lag] > ac[lag + 1])) continue;
    const double denominator = ac[lag - 1] - 2.0 * ac[lag] + ac[lag + 1];
    double refined = lag;
    double value = ac[lag];
    if (std::abs(denominator) > 1e-12) {
      const double offset = Clamp(0.5 * (ac[lag - 1] - ac[lag + 1]) / denominator, -0.5, 0.5);
      refined += offset;
      value = ac[lag] - 0.25 * (ac[lag - 1] - ac[lag + 1]) * offset;
    }
    if (value > global_value) { global_value = value; global_lag = refined; }
    if (value >= 0.82 * ac_max && prior_lag > 0 &&
        std::abs(refined - prior_lag) <= 0.08 * prior_lag && value > best_value) {
      best_value = value;
      best_lag = refined;
    }
  }
  const double lag = best_lag > 0 ? best_lag : global_lag;
  const double peak = best_lag > 0 ? best_value : global_value;
  if (lag <= 0) return;
  const double period = lag / fps;
  result.pulse_interval = period;
  result.pulse_confidence = Clamp(peak / ac0, 0.0, 1.0);

  // Phase: comb over the combined flux (the whole window is read, so the
  // refined lag keeps the comb in step). 0.25-frame steps, linear interpolation.
  const double step = 0.25;
  double best_phase = 0, best_score = -1;
  for (double phase = 0; phase < lag; phase += step) {
    double score = 0;
    for (double position = phase; position < limit - 1; position += lag) {
      score += SampleEnvelope(flux, position);
    }
    if (score > best_score) { best_score = score; best_phase = phase; }
  }
  const double first = std::fmod(best_phase / fps + centre, period);
  for (double t = first; t <= duration + 1e-9; t += period) result.pulse_beats.push_back(t);

  // Drum onsets: local maxima of the percussive flux, strongest first with a
  // 70 ms exclusion, then the weakest 35% (by peak strength) dropped.
  const auto& perc = envelopes.perc;
  std::vector<size_t> peaks;
  for (size_t i = 1; i + 1 < perc.size(); ++i) {
    if (perc[i] > 0.05 && perc[i] >= perc[i - 1] && perc[i] > perc[i + 1]) peaks.push_back(i);
  }
  std::sort(peaks.begin(), peaks.end(), [&](size_t a, size_t b) { return perc[a] > perc[b]; });
  const double min_gap = 0.07 * fps;
  std::vector<size_t> kept;
  for (const size_t i : peaks) {
    bool free = true;
    for (const size_t k : kept) {
      if (std::abs(static_cast<double>(i) - static_cast<double>(k)) < min_gap) { free = false; break; }
    }
    if (free) kept.push_back(i);
  }
  if (!kept.empty()) {
    std::vector<double> strengths;
    for (const size_t i : kept) strengths.push_back(perc[i]);
    std::sort(strengths.begin(), strengths.end());
    const double cutoff = strengths[static_cast<size_t>(0.35 * (strengths.size() - 1))];
    std::vector<size_t> strong;
    for (const size_t i : kept) if (perc[i] >= cutoff) strong.push_back(i);
    std::sort(strong.begin(), strong.end());
    for (const size_t i : strong) result.drum_onsets.push_back(i / fps + centre);
  }

  // Subdivision: mode of inter-onset intervals relative to the pulse.
  int counts[5] = {0, 0, 0, 0, 0};
  for (size_t i = 1; i < result.drum_onsets.size(); ++i) {
    const double ratio = (result.drum_onsets[i] - result.drum_onsets[i - 1]) / period;
    if (ratio >= 0.7 && ratio < 1.4) ++counts[1];
    else if (ratio >= 0.35 && ratio < 0.7) ++counts[2];
    else if (ratio >= 0.17 && ratio < 0.35) ++counts[4];
  }
  result.pulse_subdivision = 1;
  if (counts[2] > counts[result.pulse_subdivision]) result.pulse_subdivision = 2;
  if (counts[4] > counts[result.pulse_subdivision]) result.pulse_subdivision = 4;
}

}  // namespace

// Searches 70-200 BPM. A candidate combines normalized correlation at its lag,
// 0.42 times the double-lag correlation, and a small Gaussian prior around 118
// BPM; the winner is then re-examined against its own half and double lag so a
// pattern that repeats every two beats cannot pass itself off as the tempo.
// Quadratic interpolation refines the winning lag by at most half a frame, and
// phase maximizes onset strength. The grid is then tracked forward with a
// phase-locked loop rather than extrapolated, and the four-beat downbeat offset
// is chosen on bass-band onset strength. Confidence blends tempo strength,
// phase strength, and separation from non-neighboring candidates.
TempoResult AnalyzeTempo(
  const std::vector<float>& samples,
  double sample_rate,
  double duration,
  double audible_start
) {
  TempoResult result;
  // At the normal 11,025 Hz analysis rate this is a 46 ms Hann window with an
  // 11.6 ms hop. Other accepted sample rates retain the same sample counts.
  constexpr size_t frame_size = 512;
  constexpr size_t hop_size = 128;
  const auto envelopes = OnsetEnvelope(samples, sample_rate, frame_size, hop_size);
  const auto& envelope = envelopes.full;
  // Short or silent-enough inputs fail closed to the default zero tempo.
  if (envelope.size() < 64) return result;

  const double frames_per_second = sample_rate / hop_size;
  // The tempo search reads a bounded prefix; the tracking below reads all of it.
  const size_t search_limit = std::min(
    envelope.size(),
    static_cast<size_t>(frames_per_second * kMaxTempoSearchSeconds)
  );
  const int minimum_lag = std::max(2, static_cast<int>(std::floor(frames_per_second * 60.0 / 200.0)));
  const int maximum_lag = static_cast<int>(std::ceil(frames_per_second * 60.0 / 70.0));
  std::vector<double> scores(maximum_lag + 1, 0);
  int best_lag = minimum_lag;
  for (int lag = minimum_lag; lag <= maximum_lag; ++lag) {
    const double bpm = frames_per_second * 60.0 / lag;
    const double tempo_prior = std::exp(-std::pow((bpm - 118.0) / 75.0, 2.0));
    scores[lag] = Correlation(envelope, lag, search_limit) +
      0.42 * Correlation(envelope, lag * 2, search_limit) +
      0.08 * tempo_prior;
    if (scores[lag] > scores[best_lag]) best_lag = lag;
  }

  // Resolve the metrical level. The 0.42 double-lag term above stabilizes the
  // search against picking double time, but it does so by rewarding whichever
  // lag has a strong correlation one octave up -- and in most produced music
  // the drum pattern repeats every two beats, so the half-tempo lag inherits
  // that reward and wins. Measured on synthetic backbeat material, 140, 150 and
  // 174 BPM all came back at almost exactly half.
  //
  // So the winner is re-examined against its own half and double lag using the
  // *raw* correlation, with no double-lag term to bias the comparison, scaled
  // by a perceptual tempo prior. This can only move the reading by an octave;
  // it never overrides which lag the search actually found.
  {
    double best_metrical = -1;
    int metrical_lag = best_lag;
    for (const double ratio : {0.5, 1.0, 2.0}) {
      const int candidate = static_cast<int>(std::lround(best_lag * ratio));
      if (candidate < minimum_lag || candidate > maximum_lag) continue;
      const double bpm = frames_per_second * 60.0 / candidate;
      const double score =
        Correlation(envelope, candidate, search_limit) * MetricalPrior(bpm);
      if (score > best_metrical) {
        best_metrical = score;
        metrical_lag = candidate;
      }
    }
    best_lag = metrical_lag;
  }

  double refined_lag = best_lag;
  if (best_lag > minimum_lag && best_lag < maximum_lag) {
    // Refined against raw correlation rather than `scores`, because the octave
    // decision above may have moved the winner to a lag whose combined score
    // was never the local maximum the parabola assumes.
    const double left = Correlation(envelope, best_lag - 1, search_limit);
    const double center = Correlation(envelope, best_lag, search_limit);
    const double right = Correlation(envelope, best_lag + 1, search_limit);
    const double denominator = left - 2.0 * center + right;
    if (std::abs(denominator) > 1e-9) {
      refined_lag += Clamp(0.5 * (left - right) / denominator, -0.5, 0.5);
    }
  }

  // Which offset within the beat carries the onsets, scored by laying a comb of
  // period `lag` over the envelope and summing what it lands on.
  //
  // `limit` matters more than it looks. The comb only measures phase if it stays
  // in step with the music across the window it reads, and the lag it is given
  // is quantized -- 0.15% off is enough to walk a comb half a beat out of phase
  // in three minutes, which flattens the score into noise and makes the argmax
  // arbitrary. So the first estimate reads a short window, where a 0.15% error
  // is a few milliseconds, and it is taken again over everything once the
  // tracking loop below has locked the interval to within 0.001%.
  const auto estimate_phase = [&](double lag, size_t limit) {
    const int phase_count = std::max(1, static_cast<int>(std::round(lag)));
    const double end = static_cast<double>(std::min(limit, envelope.size()));
    double best_score = -1;
    int best = 0;
    for (int phase = 0; phase < phase_count; ++phase) {
      double score = 0;
      int count = 0;
      for (double position = phase; position < end; position += lag) {
        score += SampleEnvelope(envelope, position);
        ++count;
      }
      score /= std::max(1, count);
      if (score > best_score) {
        best_score = score;
        best = phase;
      }
    }
    return std::pair<int, double>{best, best_score};
  };

  // Thirty seconds: long enough for a hundred-odd beats to vote, short enough
  // that a quantized lag cannot smear them.
  constexpr double kPhaseSearchSeconds = 30.0;
  auto [best_phase, best_phase_score] = estimate_phase(
    refined_lag,
    static_cast<size_t>(frames_per_second * kPhaseSearchSeconds)
  );

  const auto anchor_first_beat = [&](int phase, double lag) {
    const double interval_seconds = lag / frames_per_second;
    double first = phase / frames_per_second;
    while (first + interval_seconds < audible_start - 0.15) first += interval_seconds;
    while (first > audible_start + interval_seconds) first -= interval_seconds;
    result.first_beat = std::max(0.0, first);
  };
  anchor_first_beat(best_phase, refined_lag);

  // Track the grid forward instead of extrapolating it.
  //
  // A rigid grid is only as good as the tempo estimate that generated it, and
  // that estimate is quantized: at 11,025 Hz with a 128-sample hop, one frame of
  // autocorrelation lag is 3.17 BPM at 128 BPM, so sub-frame interpolation is
  // doing all the precision work and lands within roughly 0.15%. Harmless over a
  // few bars; across the four minutes to a track's mix-out anchor it is 90 to
  // 300 ms of accumulated phase error, measured on a *perfect* click track --
  // up to 40% of a beat at 128 BPM, and both sides of a transition err
  // independently. That is the single largest reason beat-matched blends do not
  // line up.
  //
  // This is a phase-locked loop over the onset envelope: each beat is predicted
  // from the running grid, the envelope is searched within a fraction of a beat
  // for the onset that actually occurred, and the error feeds back as a fast
  // phase correction plus a slow interval correction. The interval term is what
  // removes the accumulating component -- a persistent error means the estimate
  // is wrong, not that one beat moved. Both terms are bounded: phase to a
  // quarter beat and interval to 3%, so the loop can follow a drifting
  // performance but can never walk onto the offbeat.
  //
  // Run twice. The interval term needs on the order of 1/kIntervalGain beats to
  // pull a 0.15% tempo error out of the loop, which is about two minutes of
  // music -- so a single forward pass is still 160 ms out at the half-minute
  // mark, and the incoming track's mix-in point lives exactly there. The first
  // pass therefore only learns the interval; the second lays the grid down
  // already locked to it, from the first beat.
  const double envelope_end = static_cast<double>(envelope.size()) - 1;
  const double last_frame = duration * frames_per_second;

  struct TrackedGrid {
    std::vector<double> beats;
    std::vector<double> intervals;
  };

  const auto track = [&](double start_lag) {
    TrackedGrid grid;
    // A quarter beat: wide enough for real tempo drift, narrow enough that the
    // adjacent beat is never a candidate.
    const double search_radius = start_lag * 0.25;
    constexpr double kPhaseGain = 0.20;
    constexpr double kIntervalGain = 0.01;
    const double max_interval_drift = start_lag * 0.03;

    double position = result.first_beat * frames_per_second;
    double interval = start_lag;

    while (position <= last_frame + 1e-6) {
      grid.beats.push_back(std::max(0.0, position));
      const double predicted = position + interval;
      if (predicted > last_frame + 1e-6) break;

      if (predicted + search_radius < envelope_end) {
        // Find the strongest onset within the window, at whole-frame resolution
        // refined by a parabola so the correction is not itself quantized.
        double best_value = -1;
        double best_offset = 0;
        const int low = static_cast<int>(std::floor(predicted - search_radius));
        const int high = static_cast<int>(std::ceil(predicted + search_radius));
        for (int frame = std::max(0, low);
             frame <= high && frame < static_cast<int>(envelope.size()); ++frame) {
          if (envelope[frame] > best_value) {
            best_value = envelope[frame];
            best_offset = frame;
          }
        }
        // Only a real onset may steer the loop. On a passage with no percussion
        // the envelope is flat noise, and following it would be worse than
        // coasting on the interval the loop has already learned.
        if (best_value > 0.15) {
          const auto index = static_cast<size_t>(best_offset);
          if (index > 0 && index + 1 < envelope.size()) {
            const double left = envelope[index - 1];
            const double center = envelope[index];
            const double right = envelope[index + 1];
            const double denominator = left - 2.0 * center + right;
            if (std::abs(denominator) > 1e-9) {
              best_offset += Clamp(0.5 * (left - right) / denominator, -0.5, 0.5);
            }
          }
          const double error = best_offset - predicted;
          position = predicted + kPhaseGain * error;
          interval = Clamp(
            interval + kIntervalGain * error,
            start_lag - max_interval_drift,
            start_lag + max_interval_drift
          );
          grid.intervals.push_back(interval);
          continue;
        }
      }
      position = predicted;
    }
    return grid;
  };

  // The locked interval is a better tempo estimate than the lag the search
  // started from: it was fitted against every beat in the track rather than
  // against a quantized autocorrelation peak. The stretch ratio a transition
  // uses comes from this number, so it is worth the median.
  const auto learning = track(refined_lag);
  auto grid = learning;
  if (learning.intervals.size() >= 8) {
    auto sorted = learning.intervals;
    std::nth_element(sorted.begin(), sorted.begin() + sorted.size() / 2, sorted.end());
    const double locked = sorted[sorted.size() / 2];
    if (locked > 0) refined_lag = locked;
    // Re-take the phase against the locked interval before laying the final
    // grid. Skipping this leaves the second pass inheriting the first pass's
    // starting phase, and a locked interval has no way to slide off a bad one:
    // measured, two of six tempi sat a half-beat out for the whole track,
    // because the search radius around a wrong phase never contains the right
    // one. The comb can now read the entire envelope without smearing.
    const auto relocked = estimate_phase(refined_lag, envelope.size());
    best_phase = relocked.first;
    best_phase_score = relocked.second;
    anchor_first_beat(best_phase, refined_lag);
    grid = track(refined_lag);
  }
  result.beats.reserve(grid.beats.size());
  for (const double frame : grid.beats) result.beats.push_back(frame / frames_per_second);
  result.bpm = frames_per_second * 60.0 / refined_lag;
  result.beat_interval = 60.0 / result.bpm;

  // Which of the four beats is beat one.
  //
  // This used to take the offset with the highest mean *full-band* onset
  // strength, which finds the loudest recurring hit -- and in produced music
  // that is the snare, on two and four. Measured on synthetic backbeat
  // material it chose a backbeat in nine runs out of nine, which puts every
  // mix a half-bar out of phase even when the beats themselves line up.
  //
  // Bass-band onset strength answers the question the full band cannot: kick
  // drums are down there and snares are not. The full band still contributes,
  // because on four-on-the-floor material every offset has the same kick and
  // the decision falls back to whatever else marks the bar.
  int downbeat_offset = 0;
  double downbeat_score = -1;
  for (int offset = 0; offset < 4; ++offset) {
    double low_score = 0;
    double full_score = 0;
    int count = 0;
    for (size_t beat = offset; beat < result.beats.size() && beat < 256; beat += 4) {
      const double position_frames = result.beats[beat] * frames_per_second;
      low_score += SampleEnvelope(envelopes.low, position_frames);
      full_score += SampleEnvelope(envelope, position_frames);
      ++count;
    }
    const double score = (low_score + 0.4 * full_score) / std::max(1, count);
    if (score > downbeat_score) {
      downbeat_score = score;
      downbeat_offset = offset;
    }
  }
  for (size_t beat = downbeat_offset; beat < result.beats.size(); beat += 4) {
    result.downbeats.push_back(result.beats[beat]);
  }

  // Everything above works in envelope-frame space, where frame f is indexed by
  // its first sample. The flux it carries belongs to the whole 46 ms window,
  // though, so an onset detected in frame f actually happened around the
  // window's centre -- and reporting the frame start puts every beat time
  // half a window early. Measured against a click track of known phase the bias
  // was a constant 27 ms, which is a flam on its own.
  //
  // It cancels between two tracks aligned to each other, so it was never what
  // made blends drift, but everything that maps a beat onto real audio -- where
  // to cue the incoming deck, where the drop lands -- is straighter without it.
  const double frame_centre_seconds = frame_size / (2.0 * sample_rate);
  result.first_beat += frame_centre_seconds;
  for (double& beat : result.beats) beat += frame_centre_seconds;
  for (double& beat : result.downbeats) beat += frame_centre_seconds;

  double runner_up = 0;
  for (int lag = minimum_lag; lag <= maximum_lag; ++lag) {
    if (std::abs(lag - best_lag) > 2) runner_up = std::max(runner_up, scores[lag]);
  }
  const double separation = (scores[best_lag] - runner_up) / std::max(0.05, scores[best_lag]);
  result.confidence = Clamp(
    0.35 * scores[best_lag] + 0.35 * best_phase_score + 0.3 * std::max(0.0, separation),
    0.0,
    1.0
  );
  const bool tempo_ok = std::isfinite(result.bpm) && result.bpm >= 60 && result.bpm <= 220;
  AnalyzePulse(envelopes, sample_rate, duration, tempo_ok ? result.bpm : 0.0, result);
  if (!tempo_ok) {
    TempoResult failed;  // pulse fields survive a failed tempo reading
    failed.pulse_interval = result.pulse_interval;
    failed.pulse_confidence = result.pulse_confidence;
    failed.pulse_subdivision = result.pulse_subdivision;
    failed.pulse_beats = std::move(result.pulse_beats);
    failed.drum_onsets = std::move(result.drum_onsets);
    return failed;
  }
  return result;
}

}  // namespace yuma::smart
