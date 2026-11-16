//! Precord engine: DSP front-end + ring buffer + snapshot/normalise/WAV export.

use crate::ring::RingBuffer;
use crate::wav;
use std::fs::File;
use std::io::{self, BufWriter, Write};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering};
use std::sync::Mutex;

pub const SCOPE_LEN: usize = 160;
/// Normalisation target: -1 dBFS.
const NORMALIZE_TARGET: f32 = 0.891;
/// Never amplify more than +30 dB when normalising.
const NORMALIZE_MAX_GAIN: f32 = 31.6;
const GATE_FLOOR: f32 = 0.03;
const CHUNK: usize = 1024;
/// AGC aims at this mean-absolute level (~ -19 dBFS RMS for speech) and never adds more than +18 dB.
const AGC_TARGET: f32 = 0.09;
const AGC_MAX_GAIN: f32 = 8.0;
/// Below this smoothed level the AGC stops raising gain, so silence / hiss is not pumped up.
const AGC_FLOOR: f32 = 0.004;

/// Direct-form-II-transposed biquad (RBJ cookbook).
#[derive(Clone, Copy)]
pub struct Biquad {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    z1: f32,
    z2: f32,
}

impl Biquad {
    fn norm(b0: f32, b1: f32, b2: f32, a0: f32, a1: f32, a2: f32) -> Self {
        Self { b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0, z1: 0.0, z2: 0.0 }
    }

    pub fn highpass(sr: f32, f0: f32, q: f32) -> Self {
        let w = 2.0 * std::f32::consts::PI * f0 / sr;
        let (sn, cs) = (w.sin(), w.cos());
        let al = sn / (2.0 * q);
        Self::norm((1.0 + cs) / 2.0, -(1.0 + cs), (1.0 + cs) / 2.0, 1.0 + al, -2.0 * cs, 1.0 - al)
    }

    pub fn peaking(sr: f32, f0: f32, q: f32, gain_db: f32) -> Self {
        let a = 10f32.powf(gain_db / 40.0);
        let w = 2.0 * std::f32::consts::PI * f0 / sr;
        let (sn, cs) = (w.sin(), w.cos());
        let al = sn / (2.0 * q);
        Self::norm(1.0 + al * a, -2.0 * cs, 1.0 - al * a, 1.0 + al / a, -2.0 * cs, 1.0 - al / a)
    }

    #[inline]
    pub fn run(&mut self, x: f32) -> f32 {
        let y = self.b0 * x + self.z1;
        self.z1 = self.b1 * x - self.a1 * y + self.z2;
        self.z2 = self.b2 * x - self.a2 * y;
        y
    }
}

#[derive(Debug, Clone, Copy)]
pub struct Snapshot {
    pub start: u64,
    pub count: u64,
    pub gain: f32,
    pub sample_rate: u32,
}

impl Snapshot {
    pub fn duration_ms(&self) -> u64 {
        self.count * 1000 / self.sample_rate as u64
    }
}

struct WriterState {
    hp: Biquad,
    eq: Biquad,
    agc_env: f32,
    agc_gain: f32,
    env: f32,
    gate_gain: f32,
    acc_sq: f64,
    acc_peak: f32,
    acc_n: u32,
}

pub struct Engine {
    sample_rate: u32,
    ring: RingBuffer,
    gain: AtomicU32,        // f32 bits, linear
    gate_on: AtomicBool,
    highpass_on: AtomicBool,
    voice_on: AtomicBool,
    agc_up: f32,
    agc_down: f32,
    agc_env_up: f32,
    agc_env_down: f32,
    gate_thresh: AtomicU32, // f32 bits, linear
    rms: AtomicU32,
    peak: AtomicU32,
    scope: Vec<AtomicU32>,
    scope_head: AtomicUsize,
    meter_window: u32,
    env_release: f32,
    gate_attack: f32,
    gate_release: f32,
    writer: Mutex<WriterState>, // only ever locked by the single producer thread
}

#[inline]
fn db_to_lin(db: f32) -> f32 {
    10f32.powf(db / 20.0)
}

impl Engine {
    /// `window_ms` of audio can be saved. Returns `None` if memory cannot be allocated.
    pub fn new(sample_rate: u32, window_ms: u64) -> Option<Self> {
        if !(8000..=192_000).contains(&sample_rate) || window_ms == 0 {
            return None;
        }
        let sr = sample_rate as u64;
        let window = (window_ms * sr / 1000) as usize;
        let slack = (sr * 2) as usize; // 2 s head-room for concurrent snapshots
        let ring = RingBuffer::new(window, slack)?;
        let srf = sample_rate as f32;
        Some(Self {
            sample_rate,
            ring,
            gain: AtomicU32::new(1.0f32.to_bits()),
            gate_on: AtomicBool::new(false),
            highpass_on: AtomicBool::new(false),
            voice_on: AtomicBool::new(false),
            agc_up: 1.0 - (-1.0 / (srf * 0.6)).exp(),
            agc_down: 1.0 - (-1.0 / (srf * 0.02)).exp(),
            agc_env_up: 1.0 - (-1.0 / (srf * 0.05)).exp(),
            agc_env_down: 1.0 - (-1.0 / (srf * 0.4)).exp(),
            gate_thresh: AtomicU32::new(db_to_lin(-50.0).to_bits()),
            rms: AtomicU32::new(0),
            peak: AtomicU32::new(0),
            scope: (0..SCOPE_LEN).map(|_| AtomicU32::new(0)).collect(),
            scope_head: AtomicUsize::new(0),
            meter_window: (sample_rate / 50).max(1), // 20 ms
            env_release: (-1.0 / (srf * 0.100)).exp(),
            gate_attack: 1.0 - (-1.0 / (srf * 0.004)).exp(),
            gate_release: 1.0 - (-1.0 / (srf * 0.060)).exp(),
            writer: Mutex::new(WriterState {
                hp: Biquad::highpass(srf, 90.0, 0.707),
                eq: Biquad::peaking(srf, 2800.0, 0.9, 4.0),
                agc_env: 0.0,
                agc_gain: 1.0,
                env: 0.0, gate_gain: 1.0, acc_sq: 0.0, acc_peak: 0.0, acc_n: 0 }),
        })
    }

    pub fn sample_rate(&self) -> u32 {
        self.sample_rate
    }

    pub fn window_samples(&self) -> u64 {
        self.ring.window() as u64
    }

    pub fn buffered_samples(&self) -> u64 {
        self.ring.buffered() as u64
    }

    /// Live DSP parameters (safe to call from any thread).
    pub fn set_dsp(&self, gain_db: f32, gate_enabled: bool, gate_db: f32, highpass: bool, voice: bool) {
        self.highpass_on.store(highpass, Ordering::Relaxed);
        self.voice_on.store(voice, Ordering::Relaxed);
        self.gain.store(db_to_lin(gain_db.clamp(-24.0, 36.0)).to_bits(), Ordering::Relaxed);
        self.gate_thresh.store(db_to_lin(gate_db.clamp(-90.0, -10.0)).to_bits(), Ordering::Relaxed);
        self.gate_on.store(gate_enabled, Ordering::Relaxed);
    }

    /// Audio-thread entry point: DSP -> meters/scope -> ring buffer. Allocation free.
    pub fn process(&self, input: &[i16]) {
        let gain = f32::from_bits(self.gain.load(Ordering::Relaxed));
        let gate_on = self.gate_on.load(Ordering::Relaxed);
        let thresh = f32::from_bits(self.gate_thresh.load(Ordering::Relaxed));
        let hp_on = self.highpass_on.load(Ordering::Relaxed);
        let voice = self.voice_on.load(Ordering::Relaxed);
        let mut st = match self.writer.lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        };
        let mut out = [0i16; CHUNK];
        for block in input.chunks(CHUNK) {
            for (o, &s) in out.iter_mut().zip(block) {
                let mut x = s as f32 * (1.0 / 32768.0);
                if hp_on {
                    x = st.hp.run(x);
                }
                if voice {
                    x = st.eq.run(x);
                }
                let a = x.abs();
                st.env = if a > st.env { a } else { st.env * self.env_release };
                let target = if gate_on && st.env < thresh { GATE_FLOOR } else { 1.0 };
                let coef = if target > st.gate_gain { self.gate_attack } else { self.gate_release };
                st.gate_gain += (target - st.gate_gain) * coef;
                let mut y = x * gain * st.gate_gain;
                if voice {
                    // slow auto-level on the gated signal so removed hiss is never lifted
                    let a = y.abs();
                    let c = if a > st.agc_env { self.agc_env_up } else { self.agc_env_down };
                    st.agc_env += (a - st.agc_env) * c;
                    let want = if st.agc_env < AGC_FLOOR { 1.0 } else { (AGC_TARGET / st.agc_env).clamp(0.7, AGC_MAX_GAIN) };
                    let k = if want < st.agc_gain { self.agc_down } else { self.agc_up };
                    st.agc_gain += (want - st.agc_gain) * k;
                    y *= st.agc_gain;
                }
                if y.abs() > 0.9 {
                    // soft limiter instead of hard clipping
                    y = y.signum() * (0.9 + 0.08 * ((y.abs() - 0.9) / 0.08).tanh());
                }
                *o = (y * 32768.0).round().clamp(-32768.0, 32767.0) as i16;

                let ya = (*o as f32).abs() * (1.0 / 32768.0);
                st.acc_sq += (ya * ya) as f64;
                if ya > st.acc_peak {
                    st.acc_peak = ya;
                }
                st.acc_n += 1;
                if st.acc_n >= self.meter_window {
                    let rms = (st.acc_sq / st.acc_n as f64).sqrt() as f32;
                    self.rms.store(rms.to_bits(), Ordering::Relaxed);
                    self.peak.store(st.acc_peak.to_bits(), Ordering::Relaxed);
                    let h = self.scope_head.load(Ordering::Relaxed);
                    self.scope[h % SCOPE_LEN].store(st.acc_peak.to_bits(), Ordering::Relaxed);
                    self.scope_head.store(h.wrapping_add(1), Ordering::Release);
                    st.acc_sq = 0.0;
                    st.acc_peak = 0.0;
                    st.acc_n = 0;
                }
            }
            self.ring.write(&out[..block.len()]);
        }
    }

    /// Latest 20 ms window `(rms, peak)`, linear 0..1.
    pub fn levels(&self) -> (f32, f32) {
        (f32::from_bits(self.rms.load(Ordering::Relaxed)), f32::from_bits(self.peak.load(Ordering::Relaxed)))
    }

    /// Fills `out` with the newest `out.len()` scope peaks, oldest first.
    pub fn scope(&self, out: &mut [f32]) {
        let head = self.scope_head.load(Ordering::Acquire);
        let n = out.len().min(SCOPE_LEN);
        let pad = out.len() - n;
        for v in out[..pad].iter_mut() {
            *v = 0.0;
        }
        for i in 0..n {
            let idx = head.wrapping_add(SCOPE_LEN - n + i) % SCOPE_LEN;
            out[pad + i] = f32::from_bits(self.scope[idx].load(Ordering::Relaxed));
        }
    }

    /// Decide which samples a save covers and (optionally) the normalisation gain.
    pub fn plan(&self, last_ms: u64, normalize: bool) -> io::Result<Snapshot> {
        let want = (last_ms.saturating_mul(self.sample_rate as u64) / 1000) as usize;
        let (start, count) = self.ring.tail(want);
        if count == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "buffer is empty"));
        }
        let mut gain = 1.0f32;
        if normalize {
            let mut peak = 0i32;
            let mut buf = vec![0i16; 16384];
            let mut pos = start;
            let end = start + count as u64;
            while pos < end {
                let n = ((end - pos) as usize).min(buf.len());
                if !self.ring.read_range(pos, &mut buf[..n]) {
                    return Err(io::Error::new(io::ErrorKind::Other, "buffer overrun while scanning"));
                }
                peak = buf[..n].iter().fold(peak, |m, &s| m.max((s as i32).abs()));
                pos += n as u64;
            }
            if peak > 0 {
                gain = (NORMALIZE_TARGET * 32768.0 / peak as f32).clamp(1.0, NORMALIZE_MAX_GAIN);
            }
        }
        Ok(Snapshot { start, count: count as u64, gain, sample_rate: self.sample_rate })
    }

    /// Copies samples of a planned snapshot, applying its gain. `false` = overwritten.
    pub fn read_pcm(&self, start: u64, out: &mut [i16], gain: f32) -> bool {
        if !self.ring.read_range(start, out) {
            return false;
        }
        if gain != 1.0 {
            for s in out.iter_mut() {
                *s = (*s as f32 * gain).round().clamp(-32768.0, 32767.0) as i16;
            }
        }
        true
    }

    /// Streams the snapshot into a WAV file without duplicating the buffer in RAM.
    pub fn save_wav(&self, path: &Path, last_ms: u64, normalize: bool) -> io::Result<Snapshot> {
        let snap = self.plan(last_ms, normalize)?;
        let result = (|| -> io::Result<()> {
            let mut w = BufWriter::with_capacity(1 << 17, File::create(path)?);
            wav::write_header(&mut w, self.sample_rate, 1, (snap.count * 2) as u32)?;
            let mut buf = vec![0i16; 16384];
            let mut bytes = Vec::with_capacity(buf.len() * 2);
            let mut pos = snap.start;
            let end = snap.start + snap.count;
            while pos < end {
                let n = ((end - pos) as usize).min(buf.len());
                if !self.read_pcm(pos, &mut buf[..n], snap.gain) {
                    return Err(io::Error::new(io::ErrorKind::Other, "buffer overrun while saving"));
                }
                bytes.clear();
                for s in &buf[..n] {
                    bytes.extend_from_slice(&s.to_le_bytes());
                }
                w.write_all(&bytes)?;
                pos += n as u64;
            }
            w.flush()
        })();
        if let Err(e) = result {
            let _ = std::fs::remove_file(path);
            return Err(e);
        }
        Ok(snap)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("precord_eng_{}_{}", std::process::id(), name))
    }

    fn sine(n: usize, amp: f32, rate: f32) -> Vec<i16> {
        (0..n).map(|i| ((i as f32 * 2.0 * std::f32::consts::PI * 440.0 / rate).sin() * amp * 32767.0) as i16).collect()
    }

    #[test]
    fn rejects_bad_params() {
        assert!(Engine::new(100, 1000).is_none());
        assert!(Engine::new(48000, 0).is_none());
    }

    #[test]
    fn buffer_fill_and_window() {
        let e = Engine::new(8000, 1000).unwrap(); // 8000-sample window
        e.process(&sine(4000, 0.5, 8000.0));
        assert_eq!(e.buffered_samples(), 4000);
        e.process(&sine(20000, 0.5, 8000.0));
        assert_eq!(e.buffered_samples(), 8000);
        assert_eq!(e.window_samples(), 8000);
    }

    #[test]
    fn meters_follow_signal() {
        let e = Engine::new(16000, 5000).unwrap();
        e.process(&sine(16000, 0.5, 16000.0));
        let (rms, peak) = e.levels();
        assert!((peak - 0.5).abs() < 0.02, "peak {peak}");
        assert!((rms - 0.5 / 2f32.sqrt()).abs() < 0.03, "rms {rms}");
        let mut sc = [0f32; 32];
        e.scope(&mut sc);
        assert!(sc.iter().all(|&v| v > 0.4));
    }

    #[test]
    fn gain_is_applied() {
        let e = Engine::new(16000, 5000).unwrap();
        e.set_dsp(6.0206, false, -50.0, false, false); // x2
        e.process(&vec![1000i16; 1000]);
        let mut out = vec![0i16; 1000];
        let s = e.plan(1000, false).unwrap();
        assert!(e.read_pcm(s.start, &mut out, 1.0));
        assert!(out.iter().all(|&v| (v - 2000).abs() <= 1));
    }

    #[test]
    fn noise_gate_attenuates_quiet_and_passes_loud() {
        let e = Engine::new(16000, 10_000).unwrap();
        e.set_dsp(0.0, true, -40.0, false, false);
        e.process(&vec![30i16; 16000]); // ~ -60 dBFS hiss
        e.process(&sine(16000, 0.5, 16000.0));
        let mut out = vec![0i16; 32000];
        let s = e.plan(2000, false).unwrap();
        assert!(e.read_pcm(s.start, &mut out, 1.0));
        let tail_hiss = out[8000..16000].iter().map(|v| v.abs()).max().unwrap();
        let loud = out[24000..].iter().map(|v| v.abs() as i32).max().unwrap();
        assert!(tail_hiss <= 3, "gate did not close: {tail_hiss}");
        assert!(loud > 14000, "gate ate the signal: {loud}");
    }

    #[test]
    fn normalization_raises_quiet_audio() {
        let e = Engine::new(16000, 5000).unwrap();
        e.process(&sine(16000, 0.05, 16000.0));
        let s = e.plan(1000, true).unwrap();
        assert!(s.gain > 10.0 && s.gain <= NORMALIZE_MAX_GAIN, "gain {}", s.gain);
        let mut out = vec![0i16; 16000];
        assert!(e.read_pcm(s.start, &mut out, s.gain));
        let peak = out.iter().map(|v| (*v as i32).abs()).max().unwrap() as f32 / 32768.0;
        assert!((peak - 0.891).abs() < 0.02, "peak {peak}");
        // loud audio must not be touched
        let e2 = Engine::new(16000, 5000).unwrap();
        e2.process(&sine(16000, 0.95, 16000.0));
        assert_eq!(e2.plan(1000, true).unwrap().gain, 1.0);
    }

    #[test]
    fn save_last_n_ms_to_wav() {
        let e = Engine::new(8000, 4000).unwrap();
        let data: Vec<i16> = (0..50000).map(|i| (i % 3000) as i16).collect(); // wraps the ring
        e.process(&data);
        let p = tmp("save.wav");
        let snap = e.save_wav(&p, 1000, false).unwrap();
        assert_eq!(snap.count, 8000);
        assert_eq!(snap.duration_ms(), 1000);
        let info = wav::info_of(&p).unwrap();
        assert_eq!((info.sample_rate, info.frames), (8000, 8000));
        let bytes = std::fs::read(&p).unwrap();
        let pcm: Vec<i16> = bytes[44..].chunks(2).map(|c| i16::from_le_bytes([c[0], c[1]])).collect();
        assert_eq!(pcm, data[42000..].to_vec());
        // asking for more than buffered saves the whole window
        let snap = e.save_wav(&p, 60_000, false).unwrap();
        assert_eq!(snap.count, 4000 * 8);
        std::fs::remove_file(p).ok();
    }

    #[test]
    fn empty_buffer_save_fails_cleanly() {
        let e = Engine::new(8000, 4000).unwrap();
        let p = tmp("empty.wav");
        assert!(e.save_wav(&p, 1000, false).is_err());
        assert!(!p.exists());
    }

    fn rms(v: &[i16]) -> f32 {
        (v.iter().map(|&x| (x as f64 / 32768.0).powi(2)).sum::<f64>() / v.len() as f64).sqrt() as f32
    }

    fn tone(n: usize, amp: f32, hz: f32, rate: f32) -> Vec<i16> {
        (0..n).map(|i| ((i as f32 * 2.0 * std::f32::consts::PI * hz / rate).sin() * amp * 32767.0) as i16).collect()
    }

    fn render(e: &Engine, input: &[i16]) -> Vec<i16> {
        e.process(input);
        let mut out = vec![0i16; input.len()];
        let s = e.plan(input.len() as u64 * 1000 / e.sample_rate() as u64, false).unwrap();
        assert!(e.read_pcm(s.start, &mut out, 1.0));
        out
    }

    #[test]
    fn highpass_removes_rumble_keeps_voice_band() {
        let e = Engine::new(16000, 10_000).unwrap();
        e.set_dsp(0.0, false, -50.0, true, false);
        let low = render(&e, &tone(16000, 0.5, 20.0, 16000.0));
        assert!(rms(&low[8000..]) < 0.1 * (0.5 / 2f32.sqrt()), "20 Hz not attenuated: {}", rms(&low[8000..]));
        let e = Engine::new(16000, 10_000).unwrap();
        e.set_dsp(0.0, false, -50.0, true, false);
        let mid = render(&e, &tone(16000, 0.5, 1000.0, 16000.0));
        let r = rms(&mid[8000..]) / (0.5 / 2f32.sqrt());
        assert!((0.9..1.1).contains(&r), "1 kHz changed by {r}");
    }

    #[test]
    fn voice_boost_lifts_quiet_speech_but_not_silence_floor() {
        let e = Engine::new(16000, 20_000).unwrap();
        e.set_dsp(0.0, false, -50.0, false, true);
        let quiet = tone(16000 * 8, 0.01, 800.0, 16000.0); // ~ -43 dBFS peak
        let out = render(&e, &quiet);
        let gain = rms(&out[16000 * 7..]) / rms(&quiet[16000 * 7..]);
        assert!(gain > 3.0 && gain < 10.0, "speech gain {gain}");
        let e = Engine::new(16000, 20_000).unwrap();
        e.set_dsp(0.0, false, -50.0, false, true);
        let hiss = tone(16000 * 6, 0.0008, 800.0, 16000.0); // below AGC floor
        let out = render(&e, &hiss);
        let g = rms(&out[16000 * 5..]) / rms(&hiss[16000 * 5..]);
        assert!(g < 2.0, "floor was amplified x{g}");
    }

    #[test]
    fn limiter_never_overflows() {
        let e = Engine::new(16000, 10_000).unwrap();
        e.set_dsp(24.0, false, -50.0, false, true);
        let out = render(&e, &tone(16000 * 2, 0.9, 500.0, 16000.0));
        assert!(out.iter().all(|&v| (v as i32).abs() <= 32200));
    }
}
