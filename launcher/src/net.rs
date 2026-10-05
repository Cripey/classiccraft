//! HTTP downloads and small archive helpers.

use std::io::{Read, Write};
use std::path::Path;

use anyhow::Context;

const AGENT: &str = concat!("classiccraft-launcher/", env!("CARGO_PKG_VERSION"));

pub fn get_json(url: &str) -> anyhow::Result<serde_json::Value> {
    let mut resp = ureq::get(url)
        .header("User-Agent", AGENT)
        .header("Accept", "application/json")
        .call()
        .with_context(|| format!("GET {url}"))?;
    Ok(resp.body_mut().read_json()?)
}

/// `url` to `dest` (through `dest.part`, so an interrupted download is never mistaken for a whole one).
pub fn download(url: &str, dest: &Path, what: &str) -> anyhow::Result<()> {
    if let Some(dir) = dest.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let mut resp = ureq::get(url)
        .header("User-Agent", AGENT)
        .call()
        .with_context(|| format!("GET {url}"))?;
    let total: Option<u64> = resp
        .headers()
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse().ok());
    let part = dest.with_extension("part");
    let mut out = std::fs::File::create(&part)?;
    let mut body = resp.body_mut().with_config().limit(u64::MAX).reader();
    let mut buf = vec![0u8; 1 << 16];
    let (mut got, mut shown) = (0u64, 0u64);
    loop {
        let n = body.read(&mut buf)?;
        if n == 0 {
            break;
        }
        out.write_all(&buf[..n])?;
        got += n as u64;
        if got - shown >= 8 << 20 {
            shown = got;
            match total {
                Some(t) => print!("\r  {what}: {} / {} MB  ", got >> 20, t >> 20),
                None => print!("\r  {what}: {} MB  ", got >> 20),
            }
            std::io::stdout().flush().ok();
        }
    }
    println!("\r  {what}: {} MB, done      ", got >> 20);
    out.sync_all()?;
    drop(out);
    std::fs::rename(&part, dest)?;
    Ok(())
}

/// Unpack a zip into `dir`.
pub fn unzip(zip: &Path, dir: &Path) -> anyhow::Result<()> {
    let mut a = zip::ZipArchive::new(std::fs::File::open(zip)?)
        .with_context(|| format!("{}", zip.display()))?;
    a.extract(dir)
        .with_context(|| format!("unpacking {}", zip.display()))?;
    Ok(())
}

/// Unpack a .tar.gz into `dir`.
pub fn untar_gz(tgz: &Path, dir: &Path) -> anyhow::Result<()> {
    let gz = flate2::read::GzDecoder::new(std::fs::File::open(tgz)?);
    tar::Archive::new(gz)
        .unpack(dir)
        .with_context(|| format!("unpacking {}", tgz.display()))?;
    Ok(())
}

/// Lowercase hex SHA-256 of a file.
pub fn sha256_file(p: &Path) -> anyhow::Result<String> {
    use sha2::Digest;
    let mut h = sha2::Sha256::new();
    std::io::copy(&mut std::fs::File::open(p)?, &mut h)?;
    Ok(h.finalize().iter().map(|b| format!("{b:02x}")).collect())
}
