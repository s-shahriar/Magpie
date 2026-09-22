//! Desktop front end for the same engine the Android app uses.
//!
//! Replaces the ad-hoc `fbdl` shell script: one code path, so a parser fix made
//! for the phone is a parser fix here too.

use anyhow::{bail, Context, Result};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::Command;

const HELP: &str = "\
magpie — resolve and download media you have access to

USAGE:
    magpie probe <url> [--cookies <file>]
    magpie get   <url> [--cookies <file>] [--quality lowest|highest|<id>] [--out <file>]

OPTIONS:
    --cookies <file>   Netscape cookies.txt (yt-dlp --cookies-from-browser chrome --cookies f)
    --quality <spec>   lowest (default), highest, or a rendition id from `probe`
    --out <file>       output path; default ./<title>.mp4
";

fn main() {
    if let Err(e) = run() {
        eprintln!("error: {e:#}");
        std::process::exit(1);
    }
}

fn run() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.is_empty() || args[0] == "-h" || args[0] == "--help" {
        print!("{HELP}");
        return Ok(());
    }
    let cmd = args[0].clone();
    let url = args.get(1).cloned().unwrap_or_default();
    if url.is_empty() {
        bail!("a URL is required\n\n{HELP}");
    }
    let cookies_path = flag(&args, "--cookies");
    let quality = flag(&args, "--quality").unwrap_or_else(|| "lowest".into());
    let out = flag(&args, "--out");

    let cookie = match &cookies_path {
        Some(p) => cookie_header(Path::new(p), &url)?,
        None => String::new(),
    };

    let info = magpie_core::probe(url.clone(), cookie.clone())
        .map_err(|e| anyhow::anyhow!("{e}"))?;

    match cmd.as_str() {
        "probe" => {
            print_info(&info);
            Ok(())
        }
        "get" => download(&info, &quality, out, &cookie),
        other => bail!("unknown command `{other}`\n\n{HELP}"),
    }
}

fn print_info(info: &magpie_core::MediaInfo) {
    println!("{}", info.title);
    println!(
        "  source={}  id={}  duration={}",
        info.source,
        info.media_id,
        fmt_dur(info.duration_secs)
    );
    println!("\n  {:<28} {:<10} {:>10}  {}", "ID", "QUALITY", "SIZE", "KIND");
    for r in info.video.iter().chain(info.audio.iter()) {
        println!(
            "  {:<28} {:<10} {:>10}{}  {}",
            r.id,
            r.label,
            fmt_size(r.approx_bytes),
            if r.exact_size { " " } else { "~" },
            r.kind
        );
    }
}

fn download(
    info: &magpie_core::MediaInfo,
    quality: &str,
    out: Option<String>,
    cookie: &str,
) -> Result<()> {
    let video = pick(&info.video, quality).context("no matching video rendition")?;
    let name = out.unwrap_or_else(|| format!("{}.mp4", sanitize(&info.title)));
    let target = PathBuf::from(&name);

    println!("video : {} ({})", video.label, fmt_size(video.approx_bytes));

    if info.muxed || info.audio.is_empty() {
        fetch(&video.url, &target, cookie)?;
    } else {
        let audio = &info.audio[0];
        println!("audio : {}", fmt_size(audio.approx_bytes));
        let vt = target.with_extension("v.tmp");
        let at = target.with_extension("a.tmp");
        fetch(&video.url, &vt, cookie)?;
        fetch(&audio.url, &at, cookie)?;
        mux(&vt, &at, &target)?;
        let _ = std::fs::remove_file(&vt);
        let _ = std::fs::remove_file(&at);
    }

    let size = std::fs::metadata(&target)?.len();
    println!("saved : {} ({})", target.display(), fmt_size(Some(size)));
    Ok(())
}

fn fetch(url: &str, to: &Path, cookie: &str) -> Result<()> {
    let client = reqwest::blocking::Client::builder()
        .user_agent(magpie_core::http::UA)
        .timeout(std::time::Duration::from_secs(3600))
        .build()?;
    let mut req = client.get(url).header("accept", "*/*");
    if !cookie.is_empty() {
        req = req.header("cookie", cookie);
    }
    let mut resp = req.send()?.error_for_status()?;
    let total = resp.content_length();
    let mut file = std::fs::File::create(to)?;
    let mut buf = vec![0u8; 256 * 1024];
    let mut done: u64 = 0;
    let mut last = 0u64;
    loop {
        let n = resp.read(&mut buf)?;
        if n == 0 {
            break;
        }
        file.write_all(&buf[..n])?;
        done += n as u64;
        if done - last > 4 << 20 {
            last = done;
            match total {
                Some(t) => print!("\r  {:>6.1}%  {}", done as f64 * 100.0 / t as f64, fmt_size(Some(done))),
                None => print!("\r  {}", fmt_size(Some(done))),
            }
            let _ = std::io::stdout().flush();
        }
    }
    println!("\r  done   {}          ", fmt_size(Some(done)));
    Ok(())
}

/// Remux without re-encoding. ffmpeg is a hard requirement only for split
/// streams; Drive's progressive files skip this entirely.
fn mux(v: &Path, a: &Path, out: &Path) -> Result<()> {
    let st = Command::new("ffmpeg")
        .args(["-v", "error", "-i"])
        .arg(v)
        .arg("-i")
        .arg(a)
        .args(["-c", "copy", "-movflags", "+faststart"])
        .arg(out)
        .arg("-y")
        .status()
        .context("ffmpeg not found — install it to merge split audio/video")?;
    if !st.success() {
        bail!("ffmpeg failed");
    }
    Ok(())
}

fn pick<'a>(list: &'a [magpie_core::Rendition], spec: &str) -> Option<&'a magpie_core::Rendition> {
    match spec {
        "lowest" => list.first(),
        "highest" => list.last(),
        id => list.iter().find(|r| r.id == id || r.label == id),
    }
}

/// Build a `Cookie:` header from a Netscape cookies.txt.
///
/// Matching must follow the real host/path rules, not just "same root domain".
/// Sending every `google.com` cookie produces a ~15 KB header and Drive answers
/// HTTP 400 — verified against the live endpoint.
fn cookie_header(path: &Path, url: &str) -> Result<String> {
    let text = std::fs::read_to_string(path)
        .with_context(|| format!("reading {}", path.display()))?;

    let after_scheme = url.split("://").nth(1).unwrap_or(url);
    let host = after_scheme.split('/').next().unwrap_or("").split(':').next().unwrap_or("");
    let req_path = {
        let p = &after_scheme[host.len().min(after_scheme.len())..];
        let p = p.split(['?', '#']).next().unwrap_or("/");
        if p.is_empty() { "/" } else { p }
    };

    let mut pairs: Vec<(usize, String)> = Vec::new();
    for line in text.lines() {
        if line.starts_with('#') || line.trim().is_empty() {
            continue;
        }
        let f: Vec<&str> = line.split('\t').collect();
        if f.len() < 7 {
            continue;
        }
        let (raw_domain, cookie_path, name, value) = (f[0], f[2], f[5], f[6]);

        let domain = raw_domain.trim_start_matches('.');
        let host_ok = if raw_domain.starts_with('.') {
            host == domain || host.ends_with(&format!(".{domain}"))
        } else {
            host == domain
        };
        if !host_ok {
            continue;
        }
        if !req_path.starts_with(cookie_path) {
            continue;
        }
        // More specific paths win, so keep them for the tie-break below.
        pairs.push((cookie_path.len(), format!("{name}={value}")));
    }
    if pairs.is_empty() {
        bail!("no cookies matching {host}{req_path} in {}", path.display());
    }
    pairs.sort_by(|a, b| b.0.cmp(&a.0));
    Ok(pairs.into_iter().map(|(_, kv)| kv).collect::<Vec<_>>().join("; "))
}

fn flag(args: &[String], name: &str) -> Option<String> {
    args.iter().position(|a| a == name).and_then(|i| args.get(i + 1)).cloned()
}

fn fmt_size(b: Option<u64>) -> String {
    match b {
        None => "?".into(),
        Some(b) => {
            const U: [&str; 4] = ["B", "KB", "MB", "GB"];
            let mut v = b as f64;
            let mut i = 0;
            while v >= 1024.0 && i < 3 {
                v /= 1024.0;
                i += 1;
            }
            format!("{v:.1} {}", U[i])
        }
    }
}

fn fmt_dur(s: u64) -> String {
    format!("{}h{:02}m", s / 3600, (s % 3600) / 60)
}

fn sanitize(s: &str) -> String {
    s.chars()
        .map(|c| if "/\\:*?\"<>|".contains(c) { '-' } else { c })
        .collect::<String>()
        .trim()
        .chars()
        .take(120)
        .collect()
}
