//! Minimal RIFF/WAVE (PCM16) reader/writer: header, waveform peaks, lossless trim.

use std::fs::File;
use std::io::{self, BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use std::path::Path;

fn bad(msg: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, msg.to_string())
}

/// Writes a canonical 44-byte PCM16 header. `data_bytes` is the size of the data chunk.
pub fn write_header<W: Write>(w: &mut W, sample_rate: u32, channels: u16, data_bytes: u32) -> io::Result<()> {
    let block_align = channels * 2;
    w.write_all(b"RIFF")?;
    w.write_all(&(36u32.saturating_add(data_bytes)).to_le_bytes())?;
    w.write_all(b"WAVEfmt ")?;
    w.write_all(&16u32.to_le_bytes())?;
    w.write_all(&1u16.to_le_bytes())?;
    w.write_all(&channels.to_le_bytes())?;
    w.write_all(&sample_rate.to_le_bytes())?;
    w.write_all(&(sample_rate * block_align as u32).to_le_bytes())?;
    w.write_all(&block_align.to_le_bytes())?;
    w.write_all(&16u16.to_le_bytes())?;
    w.write_all(b"data")?;
    w.write_all(&data_bytes.to_le_bytes())?;
    Ok(())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WavInfo {
    pub sample_rate: u32,
    pub channels: u16,
    pub frames: u64,
    pub data_offset: u64,
}

/// Walks the RIFF chunks and returns format info. Leaves the reader at an unspecified position.
pub fn read_info<R: Read + Seek>(r: &mut R) -> io::Result<WavInfo> {
    let total = r.seek(SeekFrom::End(0))?;
    r.seek(SeekFrom::Start(0))?;
    let mut hdr = [0u8; 12];
    r.read_exact(&mut hdr)?;
    if &hdr[0..4] != b"RIFF" || &hdr[8..12] != b"WAVE" {
        return Err(bad("not a RIFF/WAVE file"));
    }
    let mut fmt: Option<(u16, u32, u16)> = None; // channels, rate, bits
    loop {
        let mut ch = [0u8; 8];
        r.read_exact(&mut ch).map_err(|_| bad("no data chunk"))?;
        let id = [ch[0], ch[1], ch[2], ch[3]];
        let size = u32::from_le_bytes([ch[4], ch[5], ch[6], ch[7]]) as u64;
        let body = r.stream_position()?;
        match &id {
            b"fmt " => {
                let mut f = [0u8; 16];
                r.read_exact(&mut f)?;
                let tag = u16::from_le_bytes([f[0], f[1]]);
                let channels = u16::from_le_bytes([f[2], f[3]]);
                let rate = u32::from_le_bytes([f[4], f[5], f[6], f[7]]);
                let bits = u16::from_le_bytes([f[14], f[15]]);
                if (tag != 1 && tag != 0xFFFE) || bits != 16 || channels == 0 {
                    return Err(bad("only 16-bit PCM WAV is supported"));
                }
                fmt = Some((channels, rate, bits));
            }
            b"data" => {
                let (channels, sample_rate, _) = fmt.ok_or_else(|| bad("data before fmt"))?;
                // Tolerate streaming headers (size 0 / 0xFFFFFFFF) by trusting the file length.
                let avail = total.saturating_sub(body);
                let bytes = if size == 0 || size > avail { avail } else { size };
                return Ok(WavInfo {
                    sample_rate,
                    channels,
                    frames: bytes / (2 * channels as u64),
                    data_offset: body,
                });
            }
            _ => {}
        }
        r.seek(SeekFrom::Start(body + size + (size & 1)))?;
    }
}

pub fn info_of(path: &Path) -> io::Result<WavInfo> {
    read_info(&mut BufReader::new(File::open(path)?))
}

/// Peak amplitude (0..1) per bucket across `buckets` equal slices of the file.
pub fn peaks(path: &Path, buckets: usize) -> io::Result<Vec<f32>> {
    let mut r = BufReader::with_capacity(1 << 16, File::open(path)?);
    let info = read_info(&mut r)?;
    r.seek(SeekFrom::Start(info.data_offset))?;
    let buckets = buckets.max(1);
    let mut out = vec![0f32; buckets];
    if info.frames == 0 {
        return Ok(out);
    }
    let ch = info.channels as usize;
    let mut frame = vec![0u8; 2 * ch];
    for f in 0..info.frames {
        r.read_exact(&mut frame)?;
        let mut m = 0i32;
        for c in 0..ch {
            m = m.max((i16::from_le_bytes([frame[2 * c], frame[2 * c + 1]]) as i32).abs());
        }
        let b = ((f as u128 * buckets as u128) / info.frames as u128) as usize;
        let v = m as f32 / 32768.0;
        if v > out[b] {
            out[b] = v;
        }
    }
    Ok(out)
}

/// Lossless trim of `[start_ms, end_ms)` into a new WAV. Returns frames written.
pub fn trim(src: &Path, dst: &Path, start_ms: u64, end_ms: u64) -> io::Result<u64> {
    let mut r = BufReader::with_capacity(1 << 16, File::open(src)?);
    let info = read_info(&mut r)?;
    let start = (start_ms * info.sample_rate as u64 / 1000).min(info.frames);
    let end = (end_ms * info.sample_rate as u64 / 1000).min(info.frames);
    if end <= start {
        return Err(bad("empty trim range"));
    }
    let frames = end - start;
    let fb = 2 * info.channels as u64;
    r.seek(SeekFrom::Start(info.data_offset + start * fb))?;
    let result = (|| -> io::Result<()> {
        let mut w = BufWriter::with_capacity(1 << 16, File::create(dst)?);
        write_header(&mut w, info.sample_rate, info.channels, (frames * fb) as u32)?;
        let mut left = frames * fb;
        let mut buf = vec![0u8; 1 << 16];
        while left > 0 {
            let n = left.min(buf.len() as u64) as usize;
            r.read_exact(&mut buf[..n])?;
            w.write_all(&buf[..n])?;
            left -= n as u64;
        }
        w.flush()
    })();
    if let Err(e) = result {
        let _ = std::fs::remove_file(dst);
        return Err(e);
    }
    Ok(frames)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("precord_test_{}_{}", std::process::id(), name))
    }

    fn make_wav(path: &Path, rate: u32, samples: &[i16]) {
        let mut w = BufWriter::new(File::create(path).unwrap());
        write_header(&mut w, rate, 1, (samples.len() * 2) as u32).unwrap();
        for s in samples {
            w.write_all(&s.to_le_bytes()).unwrap();
        }
        w.flush().unwrap();
    }

    #[test]
    fn header_roundtrip() {
        let p = tmp("hdr.wav");
        make_wav(&p, 16000, &vec![0i16; 1600]);
        let i = info_of(&p).unwrap();
        assert_eq!((i.sample_rate, i.channels, i.frames, i.data_offset), (16000, 1, 1600, 44));
        std::fs::remove_file(p).ok();
    }

    #[test]
    fn peaks_and_trim() {
        let p = tmp("pk.wav");
        let q = tmp("pk_trim.wav");
        // 1 s @ 1 kHz: first half quiet (1000), second half loud (16384)
        let mut s = vec![1000i16; 500];
        s.extend(vec![16384i16; 500]);
        make_wav(&p, 1000, &s);
        let pk = peaks(&p, 2).unwrap();
        assert!((pk[0] - 1000.0 / 32768.0).abs() < 1e-6);
        assert!((pk[1] - 0.5).abs() < 1e-6);
        let n = trim(&p, &q, 500, 900).unwrap();
        assert_eq!(n, 400);
        let i = info_of(&q).unwrap();
        assert_eq!(i.frames, 400);
        let pk = peaks(&q, 1).unwrap();
        assert!((pk[0] - 0.5).abs() < 1e-6);
        assert!(trim(&p, &q, 900, 100).is_err());
        std::fs::remove_file(p).ok();
        std::fs::remove_file(q).ok();
    }

    #[test]
    fn rejects_garbage() {
        let p = tmp("garbage.wav");
        std::fs::write(&p, b"definitely not a wav file").unwrap();
        assert!(info_of(&p).is_err());
        std::fs::remove_file(p).ok();
    }
}
