//! Title, channel, date, chapters and cover art for a finished MP4.
//!
//! The app merges with Media3, which writes none of these. Its index (`moov`)
//! is the last box of the file, so tagging is cheap: the app reads that one
//! box, this module returns it with a `udta` box added, and the app writes it
//! back over itself — the file only grows at the end, and the gigabyte of
//! media in front is never touched.
//!
//! Tags are iTunes-style (`meta`/`ilst`), which Android's media scanner, VLC,
//! mpv and music players read. Chapters are Nero-style (`chpl`), which VLC,
//! mpv and anything built on ffmpeg read.

use crate::model::Chapter;

#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct Mp4Tags {
    pub title: Option<String>,
    /// The channel, written as the artist.
    pub artist: Option<String>,
    /// "2024-09-03".
    pub date: Option<String>,
    pub chapters: Vec<Chapter>,
    /// A JPEG to show as cover art; worth it for audio files.
    pub cover_jpeg: Option<Vec<u8>>,
}

/// `moov` with the tags added, or None when it is not a `moov` box or there is
/// nothing to add. A `udta` the writer left keeps what else it holds; tags or
/// chapters already in it are replaced, since the file is one Magpie just wrote.
pub fn tag_moov(moov: &[u8], tags: &Mp4Tags) -> Option<Vec<u8>> {
    if moov.len() < 8 || &moov[4..8] != b"moov" {
        return None;
    }
    let size = u32::from_be_bytes(moov[..4].try_into().ok()?) as usize;
    if size != moov.len() {
        return None;
    }
    let mut udta = Vec::new();
    if let Some(chpl) = chpl(&tags.chapters) {
        udta.extend(chpl);
    }
    if let Some(meta) = meta(tags) {
        udta.extend(meta);
    }
    if udta.is_empty() {
        return None;
    }
    // Rebuilt from its children, with any existing `udta` folded into ours.
    let mut body = Vec::with_capacity(moov.len() + udta.len());
    let mut kept = Vec::new();
    for (kind, child) in child_boxes(&moov[8..])? {
        if &kind == b"udta" {
            for (k, grandchild) in child_boxes(&child[8..])? {
                if &k != b"meta" && &k != b"chpl" {
                    kept.extend_from_slice(grandchild);
                }
            }
        } else {
            body.extend_from_slice(child);
        }
    }
    kept.extend(udta);
    body.extend(boxed(b"udta", &kept));
    Some(boxed(b"moov", &body))
}

/// Each box directly inside `body`, whole; None if the sizes do not add up.
fn child_boxes(body: &[u8]) -> Option<Vec<([u8; 4], &[u8])>> {
    let mut out = Vec::new();
    let mut at = 0;
    while at < body.len() {
        let size = u32::from_be_bytes(body.get(at..at + 4)?.try_into().ok()?) as usize;
        if size < 8 || at + size > body.len() {
            return None;
        }
        out.push((body[at + 4..at + 8].try_into().ok()?, &body[at..at + size]));
        at += size;
    }
    Some(out)
}

/// The types of the boxes directly inside `body`.
#[cfg(test)]
fn children(body: &[u8]) -> Vec<[u8; 4]> {
    let mut out = Vec::new();
    let mut at = 0;
    while at + 8 <= body.len() {
        let size = u32::from_be_bytes(body[at..at + 4].try_into().unwrap()) as usize;
        out.push(body[at + 4..at + 8].try_into().unwrap());
        if size < 8 {
            break;
        }
        at += size;
    }
    out
}

fn boxed(kind: &[u8; 4], body: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(body.len() + 8);
    out.extend_from_slice(&((body.len() + 8) as u32).to_be_bytes());
    out.extend_from_slice(kind);
    out.extend_from_slice(body);
    out
}

/// Nero chapters: version 1, then up to 255 marks in 100 ns units.
fn chpl(chapters: &[Chapter]) -> Option<Vec<u8>> {
    if chapters.is_empty() {
        return None;
    }
    let mut body = vec![1, 0, 0, 0, 0, 0, 0, 0];
    let list = &chapters[..chapters.len().min(255)];
    body.push(list.len() as u8);
    for c in list {
        body.extend_from_slice(&(c.start_ms * 10_000).to_be_bytes());
        let title = truncate(&c.title, 255);
        body.push(title.len() as u8);
        body.extend_from_slice(title.as_bytes());
    }
    Some(boxed(b"chpl", &body))
}

/// iTunes metadata: `meta` → `hdlr` (mdir) + `ilst` of `data` atoms.
fn meta(tags: &Mp4Tags) -> Option<Vec<u8>> {
    let mut ilst = Vec::new();
    let text = |kind: &[u8; 4], value: &Option<String>| -> Vec<u8> {
        match value.as_deref().map(str::trim).filter(|v| !v.is_empty()) {
            Some(v) => boxed(kind, &data(1, v.as_bytes())),
            None => Vec::new(),
        }
    };
    ilst.extend(text(b"\xa9nam", &tags.title));
    ilst.extend(text(b"\xa9ART", &tags.artist));
    ilst.extend(text(b"\xa9day", &tags.date));
    if let Some(jpeg) = tags.cover_jpeg.as_ref().filter(|j| !j.is_empty()) {
        ilst.extend(boxed(b"covr", &data(13, jpeg)));
    }
    if ilst.is_empty() {
        return None;
    }
    let mut hdlr = vec![0u8; 8]; // version/flags, pre_defined
    hdlr.extend_from_slice(b"mdir");
    hdlr.extend_from_slice(b"appl");
    hdlr.extend_from_slice(&[0u8; 8]);
    hdlr.push(0); // empty name
    let mut body = vec![0u8; 4]; // `meta` is a full box
    body.extend(boxed(b"hdlr", &hdlr));
    body.extend(boxed(b"ilst", &ilst));
    Some(boxed(b"meta", &body))
}

/// A `data` atom: type (1 UTF-8, 13 JPEG), a zero locale, then the value.
fn data(kind: u32, value: &[u8]) -> Vec<u8> {
    let mut body = kind.to_be_bytes().to_vec();
    body.extend_from_slice(&[0u8; 4]);
    body.extend_from_slice(value);
    boxed(b"data", &body)
}

/// At most `max` bytes, cut on a character boundary.
fn truncate(s: &str, max: usize) -> &str {
    if s.len() <= max {
        return s;
    }
    let mut end = max;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    &s[..end]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn moov(body: &[u8]) -> Vec<u8> {
        boxed(b"moov", body)
    }

    #[test]
    fn appends_udta_and_fixes_the_size() {
        let mvhd = boxed(b"mvhd", &[0u8; 100]);
        let tags = Mp4Tags {
            title: Some("Talk".into()),
            artist: Some("Channel".into()),
            date: Some("2024-09-03".into()),
            chapters: vec![
                Chapter { start_ms: 0, title: "Intro".into() },
                Chapter { start_ms: 26_000, title: "Next".into() },
            ],
            cover_jpeg: None,
        };
        let out = tag_moov(&moov(&mvhd), &tags).unwrap();
        assert_eq!(u32::from_be_bytes(out[..4].try_into().unwrap()) as usize, out.len());
        assert_eq!(children(&out[8..]), [*b"mvhd", *b"udta"]);
        let udta = &out[8 + mvhd.len() + 8..];
        assert_eq!(children(udta), [*b"chpl", *b"meta"]);
        // chpl: version 1, 2 marks, the second at 26 s in 100 ns units.
        assert_eq!(&udta[8..16], &[1, 0, 0, 0, 0, 0, 0, 0]);
        assert_eq!(udta[16], 2);
        let second = 17 + 8 + 1 + 5;
        assert_eq!(&udta[second..second + 8], &260_000_000u64.to_be_bytes());
        let s = String::from_utf8_lossy(&out);
        assert!(s.contains("Talk") && s.contains("Channel") && s.contains("2024-09-03") && s.contains("mdir"));
    }

    #[test]
    fn leaves_alone_what_it_cannot_tag() {
        let mvhd = boxed(b"mvhd", &[0u8; 100]);
        let tags = Mp4Tags { title: Some("x".into()), ..Default::default() };
        assert!(tag_moov(&boxed(b"free", &mvhd), &tags).is_none());

        assert!(tag_moov(&moov(&mvhd), &Mp4Tags::default()).is_none());
    }

    #[test]
    fn joins_a_udta_the_writer_left_replacing_its_tags() {
        let mvhd = boxed(b"mvhd", &[0u8; 100]);
        let enc = boxed(b"\xa9too", b"Lavf");
        let old = boxed(b"meta", &[0; 4]);
        let src = moov(&[mvhd.clone(), boxed(b"udta", &[enc, old].concat())].concat());
        let tags = Mp4Tags { title: Some("x".into()), ..Default::default() };
        let out = tag_moov(&src, &tags).unwrap();
        assert_eq!(children(&out[8..]), [*b"mvhd", *b"udta"]);
        let udta = &out[8 + mvhd.len() + 8..];
        assert_eq!(children(udta), [*b"\xa9too", *b"meta"]);
    }

    #[test]
    fn long_titles_cut_on_a_character() {
        assert_eq!(truncate("ab€", 3), "ab");
        assert_eq!(truncate("abc", 255), "abc");
    }
}
