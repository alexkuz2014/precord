//! Stationary-noise reducer (hiss, fans, hum, room tone): STFT spectral subtraction with a
//! minimum-statistics noise floor, over-subtraction, a spectral floor and time smoothing of the gains
//! (keeps "musical noise" low). 512-point frames, 50 % overlap, sqrt-Hann analysis/synthesis.
//! Adds a fixed latency of one frame; audio before the first output is silence.

const N: usize = 512;
const HOP: usize = N / 2;
const BINS: usize = N / 2 + 1;

fn fft(re: &mut [f32], im: &mut [f32], inverse: bool) {
    let n = re.len();
    let mut j = 0;
    for i in 1..n {
        let mut bit = n >> 1;
        while j & bit != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j |= bit;
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
    }
    let mut len = 2;
    while len <= n {
        let ang = 2.0 * std::f32::consts::PI / len as f32 * if inverse { 1.0 } else { -1.0 };
        let (wr, wi) = (ang.cos(), ang.sin());
        for start in (0..n).step_by(len) {
            let (mut cr, mut ci) = (1.0f32, 0.0f32);
            for k in 0..len / 2 {
                let (a, b) = (start + k, start + k + len / 2);
                let tr = re[b] * cr - im[b] * ci;
                let ti = re[b] * ci + im[b] * cr;
                re[b] = re[a] - tr;
                im[b] = im[a] - ti;
                re[a] += tr;
                im[a] += ti;
                let ncr = cr * wr - ci * wi;
                ci = cr * wi + ci * wr;
                cr = ncr;
            }
        }
        len <<= 1;
    }
    if inverse {
        for v in re.iter_mut() {
            *v /= n as f32;
        }
        for v in im.iter_mut() {
            *v /= n as f32;
        }
    }
}

pub struct Denoiser {
    win: Vec<f32>,
    inbuf: Vec<f32>,   // last N input samples (ring, oldest at `pos`)
    pos: usize,
    count: usize,      // samples since the last frame
    outbuf: Vec<f32>,  // overlap-add accumulator (ring of N)
    out_pos: usize,
    noise: Vec<f32>,   // power estimate per bin
    gain_prev: Vec<f32>,
    frames: usize,
    re: Vec<f32>,
    im: Vec<f32>,
    pending: Vec<f32>, // finished HOP-sample blocks waiting to be handed out
    pend_pos: usize,
}

impl Default for Denoiser {
    fn default() -> Self {
        Self::new()
    }
}

impl Denoiser {
    pub fn new() -> Self {
        let win = (0..N).map(|i| ((std::f32::consts::PI * (i as f32 + 0.5)) / N as f32).sin()).collect();
        Self {
            win,
            inbuf: vec![0.0; N],
            pos: 0,
            count: 0,
            outbuf: vec![0.0; N],
            out_pos: 0,
            noise: vec![1e-6; BINS],
            gain_prev: vec![1.0; BINS],
            frames: 0,
            re: vec![0.0; N],
            im: vec![0.0; N],
            pending: vec![0.0; HOP],
            pend_pos: HOP,
        }
    }

    /// One sample in, one (delayed) sample out.
    #[inline]
    pub fn process(&mut self, x: f32) -> f32 {
        self.inbuf[self.pos] = x;
        self.pos = (self.pos + 1) % N;
        self.count += 1;
        if self.count == HOP {
            self.count = 0;
            self.frame();
        }
        if self.pend_pos < HOP {
            let y = self.pending[self.pend_pos];
            self.pend_pos += 1;
            y
        } else {
            0.0
        }
    }

    fn frame(&mut self) {
        for i in 0..N {
            self.re[i] = self.inbuf[(self.pos + i) % N] * self.win[i];
            self.im[i] = 0.0;
        }
        fft(&mut self.re, &mut self.im, false);
        self.frames += 1;
        let warm = self.frames <= 6; // learn the floor from the first ~0.2 s
        for k in 0..BINS {
            let p = self.re[k] * self.re[k] + self.im[k] * self.im[k] + 1e-12;
            let nz = &mut self.noise[k];
            if warm {
                *nz = if self.frames == 1 { p } else { *nz * 0.6 + p * 0.4 };
            } else if p < *nz * 4.0 {
                *nz += 0.05 * (p - *nz); // frames that look like the floor refine the mean noise power
            } else {
                *nz *= 1.002; // louder content: creep up slowly so a changed room tone is still adopted
            }
            let snr_floor = (1.0 - 1.6 * *nz / p).max(0.12); // over-subtraction 1.6x, floor ~ -18 dB
            let g = 0.55 * self.gain_prev[k] + 0.45 * snr_floor; // smooth over time
            self.gain_prev[k] = g;
            let (gr, gi) = (self.re[k] * g, self.im[k] * g);
            self.re[k] = gr;
            self.im[k] = gi;
            if k > 0 && k < N / 2 {
                self.re[N - k] = gr;
                self.im[N - k] = -gi;
            }
        }
        fft(&mut self.re, &mut self.im, true);
        for i in 0..N {
            self.outbuf[(self.out_pos + i) % N] += self.re[i] * self.win[i];
        }
        for i in 0..HOP {
            self.pending[i] = self.outbuf[(self.out_pos + i) % N];
            self.outbuf[(self.out_pos + i) % N] = 0.0;
        }
        self.out_pos = (self.out_pos + HOP) % N;
        self.pend_pos = 0;
    }

    /// Samples of delay introduced by the reducer.
    pub const LATENCY: usize = N - 1;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rms(v: &[f32]) -> f32 {
        (v.iter().map(|x| x * x).sum::<f32>() / v.len() as f32).sqrt()
    }

    #[test]
    fn reduces_stationary_noise_and_keeps_tone() {
        let sr = 16000.0;
        let mut seed = 1u32;
        let mut rnd = move || {
            seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
            (seed >> 8) as f32 / 8_388_608.0 - 1.0
        };
        let n = 16000 * 4;
        let tone: Vec<f32> = (0..n).map(|i| if i > 16000 { 0.2 * (i as f32 * 2.0 * std::f32::consts::PI * 700.0 / sr).sin() } else { 0.0 }).collect();
        let noise: Vec<f32> = (0..n).map(|_| 0.03 * rnd()).collect();
        let mut d = Denoiser::new();
        let out: Vec<f32> = (0..n).map(|i| d.process(tone[i] + noise[i])).collect();
        let lat = Denoiser::LATENCY;
        // noise-only stretch (before the tone starts) after warm-up
        let quiet = 8000..15000;
        let before = rms(&noise[quiet.clone()]);
        let after = rms(&out[quiet.start + lat..quiet.end + lat]);
        assert!(after < before * 0.4, "noise only reduced {before} -> {after}");
        // tone stretch: output tracks the tone
        let seg = 40000..60000;
        let err: Vec<f32> = seg.clone().map(|i| out[i + lat] - tone[i]).collect();
        let before_err = rms(&noise[seg.clone()]);
        assert!(rms(&err) < before_err * 0.8, "tone stretch not cleaner: {} vs {}", rms(&err), before_err);
        let gain = rms(&out[seg.start + lat..seg.end + lat]) / rms(&tone[seg]);
        assert!((0.85..1.15).contains(&gain), "tone level changed x{gain}");
    }

    #[test]
    fn fft_roundtrip() {
        let mut re: Vec<f32> = (0..N).map(|i| (i as f32 * 0.1).sin()).collect();
        let orig = re.clone();
        let mut im = vec![0.0; N];
        fft(&mut re, &mut im, false);
        fft(&mut re, &mut im, true);
        assert!(re.iter().zip(&orig).all(|(a, b)| (a - b).abs() < 1e-4));
    }
}
