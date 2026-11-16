//! Reference-based echo canceller: removes the phone's own playback from the microphone signal.
//!
//! Android hands us the exact audio the device is playing (AudioPlaybackCapture). We
//!  1. find the bulk delay between that reference and its echo in the mic by cross-correlating
//!     2 ms energy envelopes (re-estimated every second, so stream slips self-heal), and
//!  2. run an NLMS adaptive FIR over a 24 ms window after that delay and subtract its output.
//! The step size shrinks when the residual is much louder than the predicted echo (someone is
//! talking), so the user's voice is not cancelled or smeared.

const MAX_DELAY_MS: usize = 600;
const TAP_MS: usize = 24;
const ENV_LEN: usize = 1024; // 2 ms blocks -> ~2 s of history
const MAX_LAG: usize = MAX_DELAY_MS / 2; // in envelope blocks
const WINDOW: usize = 650; // blocks correlated per estimate
const EST_PERIOD: usize = 250; // blocks between estimates (0.5 s)

pub struct Aec {
    taps: usize,
    n: usize,          // delay-line length
    hist: Vec<f32>,    // mirrored delay line (2n) so any window is contiguous
    pos: usize,
    w: Vec<f32>,
    delay: usize,
    locked: bool,
    px: f32,           // energy of the current reference window
    py: f32,
    pe: f32,
    pm: f32,
    blk: usize,
    acc_r: f32,
    acc_m: f32,
    acc_n: usize,
    env_r: Vec<f32>,
    env_m: Vec<f32>,
    env_pos: usize,
    env_count: usize,
    since_est: usize,
}

impl Aec {
    pub fn new(sample_rate: u32) -> Self {
        let sr = sample_rate as usize;
        let taps = (sr * TAP_MS / 1000).max(64);
        let n = sr * MAX_DELAY_MS / 1000 + taps + 8;
        Self {
            taps,
            n,
            hist: vec![0.0; 2 * n],
            pos: 0,
            w: vec![0.0; taps],
            delay: 0,
            locked: false,
            px: 0.0,
            py: 0.0,
            pe: 0.0,
            pm: 0.0,
            blk: (sr / 500).max(1),
            acc_r: 0.0,
            acc_m: 0.0,
            acc_n: 0,
            env_r: vec![0.0; ENV_LEN],
            env_m: vec![0.0; ENV_LEN],
            env_pos: 0,
            env_count: 0,
            since_est: 0,
        }
    }

    pub fn delay_samples(&self) -> Option<usize> {
        if self.locked {
            Some(self.delay)
        } else {
            None
        }
    }

    /// One sample of microphone + one sample of device playback (both -1..1) -> cleaned mic sample.
    #[inline]
    pub fn process(&mut self, mic: f32, reference: f32) -> f32 {
        // push reference into the mirrored delay line
        self.hist[self.pos] = reference;
        self.hist[self.pos + self.n] = reference;
        self.pos += 1;
        if self.pos == self.n {
            self.pos = 0;
        }

        // window = reference samples for times (t-delay-taps+1 ..= t-delay), ascending in time
        let end = self.pos + self.n - self.delay; // exclusive index in mirrored buffer
        let start = end - self.taps;
        let x = &self.hist[start..end];

        let mut y = 0.0f32;
        for (wi, xi) in self.w.iter().zip(x) {
            y += wi * xi;
        }
        let e = mic - y;

        // running window energy: add newest, drop the one that just left
        let newest = x[self.taps - 1];
        let oldest = self.hist[start - 1 + if start == 0 { 2 * self.n } else { 0 }];
        self.px += newest * newest - oldest * oldest;
        if self.px < 0.0 {
            self.px = 0.0;
        }

        self.py += 0.002 * (y * y - self.py);
        // fast attack / slow release: a voice onset must slow adaptation immediately
        let e2 = e * e;
        self.pe += (if e2 > self.pe { 0.05 } else { 0.002 }) * (e2 - self.pe);

        if self.px > 1e-4 * self.taps as f32 * 0.01 {
            // double-talk aware step: confident (echo-dominated) -> fast, voice present -> slow
            let coh = self.py / (self.py + self.pe + 1e-9);
            // bootstrap term: only when the residual is not louder than the playback itself
            let f = ((self.px / self.taps as f32) / (self.pe + 1e-9)).min(1.0);
            let burst = e2 > 8.0 * self.py + 1e-7; // residual far above predicted echo = near-end speech
            let mu = if burst { 0.0 } else { 0.5 * coh * coh } + 0.02 * f * f;
            let g = mu * e / (self.px + 1e-3);
            for (wi, xi) in self.w.iter_mut().zip(x) {
                *wi += g * xi;
            }
        }

        // envelopes for delay tracking
        self.acc_r += reference.abs();
        self.acc_m += mic.abs();
        self.acc_n += 1;
        if self.acc_n == self.blk {
            self.env_r[self.env_pos] = self.acc_r;
            self.env_m[self.env_pos] = self.acc_m;
            self.env_pos = (self.env_pos + 1) % ENV_LEN;
            self.env_count += 1;
            self.acc_r = 0.0;
            self.acc_m = 0.0;
            self.acc_n = 0;
            self.since_est += 1;
            if self.since_est >= EST_PERIOD && self.env_count >= WINDOW + MAX_LAG {
                self.since_est = 0;
                self.estimate_delay();
            }
        }
        // never let a diverged filter make things louder than the raw microphone
        let m2 = mic * mic;
        self.pm += (if m2 > self.pm { 0.05 } else { 0.002 }) * (m2 - self.pm);
        if self.pe > self.pm * 4.0 + 1e-4 {
            for wi in self.w.iter_mut() {
                *wi = 0.0;
            }
            self.pe = self.pm;
            self.py = 0.0;
            return mic;
        }
        e
    }

    fn estimate_delay(&mut self) {
        let at = |v: &Vec<f32>, back: usize, p: usize| v[(p + ENV_LEN * 2 - 1 - back) % ENV_LEN];
        let p = self.env_pos;
        let mean = |v: &Vec<f32>, off: usize| (0..WINDOW).map(|i| at(v, i + off, p)).sum::<f32>() / WINDOW as f32;
        let mm = mean(&self.env_m, 0);
        let var_m: f32 = (0..WINDOW).map(|i| (at(&self.env_m, i, p) - mm).powi(2)).sum();
        if var_m <= 0.0 {
            return;
        }
        let mut best = (0usize, 0.0f32);
        let cur_lag = (self.delay + self.taps / 4) / self.blk;
        let mut cur_c = 0.0f32;
        for lag in 0..MAX_LAG {
            let mr = mean(&self.env_r, lag);
            let mut num = 0.0f32;
            let mut var_r = 0.0f32;
            for i in 0..WINDOW {
                let r = at(&self.env_r, i + lag, p) - mr;
                num += r * (at(&self.env_m, i, p) - mm);
                var_r += r * r;
            }
            // reference must actually carry signal
            if var_r < 1e-6 * WINDOW as f32 * (self.blk as f32).powi(2) {
                continue;
            }
            let c = num / (var_r * var_m).sqrt();
            if lag.abs_diff(cur_lag) <= 2 && c > cur_c {
                cur_c = c;
            }
            if c > best.1 {
                best = (lag, c);
            }
        }
        if best.1 < 0.35 {
            return;
        }
        // Once locked, only move for clearly better evidence (speech can correlate by chance).
        if self.locked && !(best.1 > 0.5 && best.1 > cur_c * 1.5 + 0.1) {
            return;
        }
        let margin = self.taps / 4;
        let target = (best.0 * self.blk).saturating_sub(margin).min(self.n - self.taps - 2);
        if !self.locked || target.abs_diff(self.delay) > margin / 2 + self.blk {
            self.delay = target;
            self.locked = true;
            for wi in self.w.iter_mut() {
                *wi = 0.0;
            }
            // re-seed window energy for the new alignment
            let end = self.pos + self.n - self.delay;
            self.px = self.hist[end - self.taps..end].iter().map(|v| v * v).sum();
            self.py = 0.0;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Lcg(u32);
    impl Lcg {
        fn next(&mut self) -> f32 {
            self.0 = self.0.wrapping_mul(1664525).wrapping_add(1013904223);
            (self.0 >> 8) as f32 / 8_388_608.0 - 1.0
        }
    }

    /// "Music": noise shaped by a slow, irregular amplitude envelope.
    fn music(n: usize) -> Vec<f32> {
        let mut g = Lcg(7);
        let mut lp = 0.0f32;
        (0..n)
            .map(|i| {
                lp += 0.35 * (g.next() - lp);
                let t = i as f32 / 16000.0;
                let env = 0.25 + 0.2 * (t * 5.3).sin() + 0.15 * (t * 1.7 + 1.0).sin() + 0.1 * (t * 11.9).sin().abs();
                lp * env.max(0.02)
            })
            .collect()
    }

    fn rms(v: &[f32]) -> f32 {
        (v.iter().map(|x| x * x).sum::<f32>() / v.len() as f32).sqrt()
    }

    fn echo_of(r: &[f32], delay: usize) -> Vec<f32> {
        (0..r.len())
            .map(|i| {
                let g = |k: usize| if i >= delay + k { r[i - delay - k] } else { 0.0 };
                0.6 * g(0) + 0.3 * g(5) - 0.2 * g(17) + 0.1 * g(60)
            })
            .collect()
    }

    #[test]
    fn finds_delay_and_cancels_playback() {
        let sr = 16000;
        let n = sr * 8;
        let r = music(n);
        let delay = 1900; // ~119 ms
        let echo = echo_of(&r, delay);
        let mut aec = Aec::new(sr as u32);
        let out: Vec<f32> = (0..n).map(|i| aec.process(echo[i], r[i])).collect();
        let d = aec.delay_samples().expect("delay not locked");
        assert!(d <= delay && delay + 60 < d + aec.taps, "delay {d} does not cover echo at {delay}");
        let erle = rms(&echo[sr * 6..]) / rms(&out[sr * 6..]);
        assert!(erle > 8.0, "echo only reduced x{erle}"); // > 18 dB
    }

    #[test]
    fn keeps_the_voice_during_double_talk() {
        let sr = 16000;
        let n = sr * 10;
        let r = music(n);
        let echo = echo_of(&r, 1500);
        // voice: broadband, syllable-like bursts (independent of the playback), only in the last 4 s
        let mut g = Lcg(99);
        let mut lp = 0.0f32;
        let voice: Vec<f32> = (0..n)
            .map(|i| {
                lp += 0.2 * (g.next() - lp);
                if i < sr * 6 {
                    return 0.0;
                }
                let t = i as f32 / sr as f32;
                let gate = if (t * 3.0).fract() < 0.6 { 1.0 } else { 0.0 };
                gate * 0.9 * lp
            })
            .collect();
        let mut aec = Aec::new(sr as u32);
        let out: Vec<f32> = (0..n).map(|i| aec.process(echo[i] + voice[i], r[i])).collect();
        let seg = sr * 7..n;
        let err: Vec<f32> = seg.clone().map(|i| out[i] - voice[i]).collect();
        let residual = rms(&err) / rms(&voice[seg.clone()]);
        assert!(residual < 0.35, "voice distorted / echo left: {residual}");
        let before = rms(&echo[seg.clone()]) / rms(&voice[seg]);
        assert!(residual < before * 0.5, "no improvement: {residual} vs {before}");
    }

    #[test]
    fn silent_reference_passes_mic_untouched() {
        let mut aec = Aec::new(48000);
        let mut g = Lcg(3);
        for _ in 0..48000 {
            let m = g.next() * 0.3;
            assert_eq!(aec.process(m, 0.0), m);
        }
    }
}
