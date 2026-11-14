//! The project's files for an agent that has no file access of its own (Claude Desktop): read a file,
//! save one, import an iFlow. Reads and writes go through the engines' file calls (`ProjectFiles`), the
//! same the app uses, which refuse paths outside the project and symbolic links that lead outside.
//! Saving and importing never replace an existing file or iFlow unless the call says `overwrite`.

use super::tools::{engine_call, file_runtime, require_project, str_arg, Args};
use super::Server;
use serde_json::{json, Value};
use std::path::{Component, Path, PathBuf};

/// Lines read_file returns by default, and a cap on characters so one call stays within a model's context.
const DEFAULT_LINES: usize = 2000;
const MAX_CHARS: usize = 200_000;

/// A project-relative path: not absolute, no `..`, not the project folder itself. The engine checks
/// again (symbolic links included); this check comes first so that nothing outside is even looked at.
fn relative(server: &Server, path: &str) -> Result<PathBuf, String> {
    require_project(server)?;
    let p = Path::new(path);
    // A Windows drive (C:\, C:x) is refused on every OS, so a call behaves the same everywhere.
    let drive = path.as_bytes().get(1) == Some(&b':') && path.as_bytes()[0].is_ascii_alphabetic();
    let bad = path.trim().is_empty() || p.is_absolute() || path.starts_with('/') || path.starts_with('\\') || drive
        || path.split(['/', '\\']).any(|part| part == "..")
        || p.components().any(|c| !matches!(c, Component::Normal(_) | Component::CurDir));
    if bad {
        return Err(format!("'{path}' is not a project-relative path. Give a path inside the project, such as \
            mappings/order.xsl; project_info lists the files."));
    }
    Ok(server.project().unwrap().join(p))
}

pub fn read_file(server: &Server, args: &Args) -> Result<Value, String> {
    let (key, path, content) = match (str_arg(args, "path"), str_arg(args, "runId")) {
        (Some(_), Some(_)) => return Err("Give path or runId, not both.".into()),
        (None, None) => return Err("Give path (a project file) or runId (an earlier run's output body).".into()),
        (None, Some(r)) => ("runId", r, server.runs().message(r)?.body),
        (Some(path), None) => {
            let full = relative(server, path)?;
            if full.is_dir() {
                return Err(format!("{path} is a folder; project_info lists its files."));
            }
            let content = super::tools::read_file(server, path)?;
            if content.contains('\0') {
                return Err(format!("{path} is a binary file, not text."));
            }
            ("path", path, content)
        }
    };
    if let Some(from) = args.get("startChar").and_then(Value::as_u64) {
        return Ok(read_chars(key, path, &content, from as usize));
    }
    let start = args.get("startLine").and_then(Value::as_u64).unwrap_or(1).max(1) as usize;
    let max = args.get("maxLines").and_then(Value::as_u64).map_or(DEFAULT_LINES, |n| n.max(1) as usize);
    let lines: Vec<&str> = content.split_inclusive('\n').collect();
    let total = lines.len();
    if start > total.max(1) {
        return Err(format!("{path} has {total} lines; startLine {start} is past its end."));
    }
    let mut text = String::new();
    let mut end = start - 1;
    let mut cut_line = false;
    for line in lines.iter().skip(start - 1).take(max) {
        if text.len() + line.len() > MAX_CHARS {
            if end < start {
                // One line longer than the cap (minified XML or JSON): its beginning.
                let cut = (0..=MAX_CHARS).rev().find(|&i| line.is_char_boundary(i)).unwrap_or(0);
                text.push_str(&line[..cut]);
                end += 1;
                cut_line = true;
            }
            break;
        }
        text.push_str(line);
        end += 1;
    }
    let mut r = json!({ key: path, "content": text, "totalLines": total });
    if cut_line {
        // The shown text is the cut line's beginning: the next character's offset in the whole content.
        let next: usize = lines.iter().take(end - 1).map(|l| l.chars().count()).sum::<usize>() + text.chars().count();
        r["lineCut"] = json!(format!("Line {end} is longer than {MAX_CHARS} characters; only its beginning is shown. \
            Read on with startChar {next} (character offsets instead of lines). The run tools read the whole file \
            with bodyPath, mappingPath or scriptPath."));
    }
    if start > 1 || end < total {
        r["startLine"] = json!(start);
        r["endLine"] = json!(end);
    }
    if end < total {
        r["truncated"] = json!(format!("Lines {start}-{end} of {total}; read on with startLine {}.", end + 1));
    }
    Ok(r)
}

/// Characters from a 0-based offset, at most MAX_CHARS: for text that is one long line (minified XML or JSON).
fn read_chars(key: &str, path: &str, content: &str, from: usize) -> Value {
    let total = content.chars().count();
    let text: String = content.chars().skip(from).take(MAX_CHARS).collect();
    let end = (from + text.chars().count()).min(total);
    let mut r = json!({ key: path, "content": text, "startChar": from, "endChar": end, "totalChars": total });
    if end < total {
        r["truncated"] = json!(format!("Characters {from}-{end} of {total}; read on with startChar {end}."));
    }
    r
}

pub fn save_file(server: &Server, args: &Args) -> Result<Value, String> {
    let content = match (str_arg(args, "content"), str_arg(args, "contentFrom")) {
        (Some(_), Some(_)) => return Err("Give content or contentFrom, not both.".into()),
        (None, None) => return Err("Give content, or contentFrom (an earlier run's runId).".into()),
        (Some(c), None) => c.to_string(),
        (None, Some(r)) => server.runs().message(r)?.body,
    };
    let path = str_arg(args, "path").unwrap_or_default();
    let full = relative(server, path)?;
    let overwrite = args.get("overwrite").and_then(Value::as_bool).unwrap_or(false);
    let _write = server.write_lock();
    if full.is_dir() {
        return Err(format!("{path} is a folder."));
    }
    let exists = full.exists();
    if exists && !overwrite {
        return Err(format!("{path} exists already. Read it first; to replace it, call save_file again with \
            overwrite: true, or save under another name."));
    }
    engine_call(server, file_runtime(server)?, "file.write", json!({ "path": path, "content": content }))?;
    Ok(json!({ "path": path, "saved": true, "replaced": exists }))
}

/// The folder name under iflows/ that the engine's import gives an iFlow (`IflowImport.importZip`).
fn iflow_folder(zip: &Path, name: Option<&str>) -> String {
    let n = match name.filter(|n| !n.trim().is_empty()) {
        Some(n) => n.to_string(),
        None => {
            let f = zip.file_name().map(|f| f.to_string_lossy().to_string()).unwrap_or_default();
            f.strip_suffix(".zip").map(str::to_string).unwrap_or(f)
        }
    };
    let mut out = String::new();
    let mut gap = false;
    for c in n.chars() {
        if c.is_ascii_alphanumeric() || "._-".contains(c) {
            out.push(c);
            gap = false;
        } else if !gap {
            out.push('_');
            gap = true;
        }
    }
    out
}

pub fn import_iflow(server: &Server, args: &Args) -> Result<Value, String> {
    require_project(server)?;
    let project = server.project().unwrap();
    let zip_arg = str_arg(args, "zip").unwrap_or_default();
    // The export usually lies outside the project (Downloads); a relative path is inside the project.
    let zip = if Path::new(zip_arg).is_absolute() { PathBuf::from(zip_arg) } else { project.join(zip_arg) };
    if !zip.is_file() {
        return Err(format!("No file at {}. Give the path of an iFlow exported from the tenant as a .zip.", zip.display()));
    }
    let name = str_arg(args, "name");
    let folder = iflow_folder(&zip, name);
    if folder.is_empty() || folder == "." || folder == ".." {
        return Err("Give the iFlow a name of letters, digits, '.', '_' or '-'.".into());
    }
    let target = project.join("iflows").join(&folder);
    let _write = server.write_lock();
    if target.exists() && !args.get("overwrite").and_then(Value::as_bool).unwrap_or(false) {
        return Err(format!("iflows/{folder} exists already. Give another name, or overwrite: true to replace its files."));
    }
    let r = engine_call(server, file_runtime(server)?, "iflow.import",
        json!({ "zip": zip.to_string_lossy(), "name": folder }))?;
    let steps: Vec<Value> = r["steps"].as_array().into_iter().flatten().filter(|s| s["iflow"] == folder.as_str()).cloned().collect();
    Ok(json!({ "imported": r["imported"], "files": r["files"], "steps": steps }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_long_line_is_read_on_by_characters() {
        let content = format!("a\n{}é", "x".repeat(MAX_CHARS + 10));
        let first = read_chars("path", "f", &content, 0);
        assert_eq!(first["content"].as_str().unwrap().chars().count(), MAX_CHARS);
        assert!(first["truncated"].as_str().unwrap().contains(&format!("startChar {MAX_CHARS}")));
        let rest = read_chars("path", "f", &content, MAX_CHARS);
        assert_eq!(rest["content"].as_str().unwrap(), "x".repeat(12) + "é", "to the end, with a multibyte character");
        assert!(rest.get("truncated").is_none());
    }

    #[test]
    fn iflow_folder_follows_the_engine() {
        assert_eq!(iflow_folder(Path::new("/d/Order Replication.zip"), None), "Order_Replication");
        assert_eq!(iflow_folder(Path::new("/d/x.zip"), Some("A b//c")), "A_b_c");
        assert_eq!(iflow_folder(Path::new("/d/Flow.v2.zip"), None), "Flow.v2");
    }
}
