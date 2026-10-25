//! Windows: Java cannot start from a folder whose path has characters outside the ANSI code page
//! (JDK-8205991, open), e.g. `C:\Users\Łukasz\AppData\Local\CPI Workbench` on a German Windows.
//! The app then reaches its resources through a directory junction under `%ProgramData%`, which is
//! an ASCII path; Java follows the junction without resolving it back.
//!
//! Every user may create folders in `%ProgramData%`, and subfolders inherit write access for all
//! users. The junction therefore lives in a per-user folder with a protected ACL (this user, SYSTEM,
//! Administrators), and a folder that another user created first is refused: its owner could
//! re-point the junction to their own `java.exe`.

use crate::engine::plain_path;
use std::os::windows::ffi::OsStrExt;
use std::os::windows::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::ptr::null_mut;
use windows_sys::Win32::Foundation::{CloseHandle, GetLastError, LocalFree, ERROR_ALREADY_EXISTS, ERROR_SUCCESS, HANDLE};
use windows_sys::Win32::Security::Authorization::{
    ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW, GetNamedSecurityInfoW,
    SDDL_REVISION_1, SE_FILE_OBJECT,
};
use windows_sys::Win32::Security::{
    GetTokenInformation, TokenUser, OWNER_SECURITY_INFORMATION, PSECURITY_DESCRIPTOR, PSID,
    SECURITY_ATTRIBUTES, TOKEN_QUERY, TOKEN_USER,
};
use windows_sys::Win32::Storage::FileSystem::{CreateDirectoryW, FILE_ATTRIBUTE_REPARSE_POINT};
use windows_sys::Win32::System::Threading::{GetCurrentProcess, OpenProcessToken};

const ADMINISTRATORS: &str = "S-1-5-32-544";

pub struct Alias {
    /// Junction that points to the resource folder.
    pub resources: PathBuf,
    /// The private per-user folder that holds it (also usable for caches).
    pub dir: PathBuf,
}

/// Resource and cache folders for the Java engines: unchanged when ASCII, else the alias. If no
/// alias can be made, the engine start fails later with a hint (`engine::path_hint`).
pub fn for_java(resources: Option<PathBuf>, cache: Option<PathBuf>) -> (Option<PathBuf>, Option<PathBuf>) {
    match resources {
        Some(dir) if !dir.to_string_lossy().is_ascii() => match ascii_alias(&dir) {
            Ok(alias) => (Some(alias.resources), Some(alias.dir.join("cds"))),
            Err(e) => {
                eprintln!("No ASCII path for the Java engines: {e}");
                (Some(dir), cache)
            }
        },
        other => (other, cache),
    }
}

/// Creates (or reuses) an ASCII junction to `resources`.
pub fn ascii_alias(resources: &Path) -> Result<Alias, String> {
    let base = std::env::var_os("ProgramData").map(PathBuf::from)
        .filter(|p| p.is_absolute() && p.to_string_lossy().is_ascii())
        .ok_or("%ProgramData% is not set or not an ASCII path")?;
    let user = current_user_sid()?;
    let dir = base.join(format!("CPI Workbench {user}"));
    private_dir(&dir, &user)?;
    let link = dir.join(format!("{:016x}", fnv1a(&resources.to_string_lossy().to_lowercase())));
    let current = junction::get_target(&link).ok().map(plain_path);
    if !current.is_some_and(|t| t.to_string_lossy().eq_ignore_ascii_case(&resources.to_string_lossy())) {
        let _ = std::fs::remove_dir(&link); // stale junction; remove_dir removes the link, not the target
        junction::create(resources, &link).map_err(|e| format!("cannot create junction {}: {e}", link.display()))?;
    }
    Ok(Alias { resources: link, dir })
}

/// Creates `dir` with a protected ACL, or checks that an existing one is a plain folder owned by
/// this user (or by Administrators, when the app ran elevated).
fn private_dir(dir: &Path, user: &str) -> Result<(), String> {
    let sddl = wide(format!("D:P(A;OICI;FA;;;{user})(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)").as_ref());
    let path = wide(dir.as_os_str());
    unsafe {
        let mut sd: PSECURITY_DESCRIPTOR = null_mut();
        if ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.as_ptr(), SDDL_REVISION_1, &mut sd, null_mut()) == 0 {
            return Err(format!("security descriptor: error {}", GetLastError()));
        }
        let sa = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: sd,
            bInheritHandle: 0,
        };
        let created = CreateDirectoryW(path.as_ptr(), &sa) != 0;
        let error = GetLastError();
        LocalFree(sd);
        if !created && error != ERROR_ALREADY_EXISTS {
            return Err(format!("cannot create {}: error {error}", dir.display()));
        }
    }
    let meta = std::fs::symlink_metadata(dir).map_err(|e| e.to_string())?;
    if !meta.is_dir() || meta.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
        return Err(format!("{} is not a plain folder", dir.display()));
    }
    let owner = owner_sid(&path)?;
    if owner != user && owner != ADMINISTRATORS {
        return Err(format!("{} belongs to another user ({owner})", dir.display()));
    }
    Ok(())
}

fn owner_sid(path: &[u16]) -> Result<String, String> {
    unsafe {
        let mut owner: PSID = null_mut();
        let mut sd: PSECURITY_DESCRIPTOR = null_mut();
        let rc = GetNamedSecurityInfoW(path.as_ptr(), SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION,
            &mut owner, null_mut(), null_mut(), null_mut(), &mut sd);
        if rc != ERROR_SUCCESS {
            return Err(format!("cannot read the folder owner: error {rc}"));
        }
        let sid = sid_string(owner);
        LocalFree(sd);
        sid
    }
}

fn current_user_sid() -> Result<String, String> {
    unsafe {
        let mut token: HANDLE = null_mut();
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) == 0 {
            return Err(format!("cannot open the process token: error {}", GetLastError()));
        }
        let mut len = 0u32;
        GetTokenInformation(token, TokenUser, null_mut(), 0, &mut len);
        let mut buf = vec![0u64; (len as usize).div_ceil(8)]; // u64 for TOKEN_USER's alignment
        let ok = GetTokenInformation(token, TokenUser, buf.as_mut_ptr().cast(), len, &mut len) != 0;
        CloseHandle(token);
        if !ok {
            return Err(format!("cannot read the user SID: error {}", GetLastError()));
        }
        sid_string((*buf.as_ptr().cast::<TOKEN_USER>()).User.Sid)
    }
}

unsafe fn sid_string(sid: PSID) -> Result<String, String> {
    let mut s: *mut u16 = null_mut();
    if ConvertSidToStringSidW(sid, &mut s) == 0 {
        return Err(format!("cannot format a SID: error {}", GetLastError()));
    }
    let len = (0..).take_while(|&i| *s.add(i) != 0).count();
    let text = String::from_utf16_lossy(std::slice::from_raw_parts(s, len));
    LocalFree(s.cast());
    Ok(text)
}

fn wide(s: &std::ffi::OsStr) -> Vec<u16> {
    s.encode_wide().chain(std::iter::once(0)).collect()
}

/// Stable across builds (unlike `DefaultHasher`), so an install keeps its junction name.
fn fnv1a(s: &str) -> u64 {
    s.bytes().fold(0xcbf2_9ce4_8422_2325, |h, b| (h ^ b as u64).wrapping_mul(0x0100_0000_01b3))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn junction_reaches_a_non_ascii_folder() {
        let target = std::env::temp_dir().join(format!("cpiwb Łódź 测试 {}", std::process::id()));
        std::fs::create_dir_all(&target).unwrap();
        std::fs::write(target.join("probe.txt"), "ok").unwrap();
        let alias = ascii_alias(&target).unwrap();
        assert!(alias.resources.to_string_lossy().is_ascii());
        assert_eq!(std::fs::read_to_string(alias.resources.join("probe.txt")).unwrap(), "ok");
        let again = ascii_alias(&target).unwrap(); // reused, not recreated
        assert_eq!(again.resources, alias.resources);
        std::fs::remove_dir(&alias.resources).unwrap();
        std::fs::remove_dir_all(&target).unwrap();
    }
}
