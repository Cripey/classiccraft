//! Windows only: Microsoft's Visual C++ runtimes the server needs - 2015-2022 (mangosd, the
//! extractors, MariaDB) and 2008 (VMaNGOS's bundled libmySQL.dll). A fresh Windows may lack them;
//! they're installed with Microsoft's own installers, which ask for permission (UAC) once.
//! The launcher itself is built with a static runtime, so it runs before they exist.

use std::path::PathBuf;
use std::process::Command;

use crate::install::Install;
use crate::{net, ui};

const VC2015: &str = "https://aka.ms/vs/17/release/vc_redist.x64.exe";
const VC2008: &str = "https://download.microsoft.com/download/5/D/8/5D8C65CB-C849-4025-8E95-C3966CAFD8AE/vcredist_x64.exe";

fn windir() -> PathBuf {
    std::env::var_os("SystemRoot")
        .or_else(|| std::env::var_os("windir"))
        .map(PathBuf::from)
        .unwrap_or_else(|| r"C:\Windows".into())
}

fn has_2015() -> bool {
    let sys = windir().join("System32");
    ["vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll"]
        .iter()
        .all(|f| sys.join(f).is_file())
}

fn has_2008() -> bool {
    std::fs::read_dir(windir().join("WinSxS"))
        .map(|rd| {
            rd.filter_map(|e| e.ok()).any(|e| {
                e.file_name()
                    .to_string_lossy()
                    .to_ascii_lowercase()
                    .starts_with("amd64_microsoft.vc90.crt_")
                    && e.path().join("msvcr90.dll").is_file()
            })
        })
        .unwrap_or(false)
}

pub fn ensure(inst: &Install) -> anyhow::Result<()> {
    let missing: Vec<(&str, &str, &str)> = [
        (
            has_2015(),
            "Visual C++ 2015-2022",
            VC2015,
            "/install /quiet /norestart",
        ),
        (has_2008(), "Visual C++ 2008", VC2008, "/q"),
    ]
    .into_iter()
    .filter(|(has, ..)| !has)
    .map(|(_, name, url, args)| (name, url, args))
    .collect();
    if missing.is_empty() {
        return Ok(());
    }
    ui::say("The server needs Microsoft's Visual C++ runtimes, which aren't installed yet:");
    for (name, ..) in &missing {
        ui::say(&format!("  - {name} (x64)"));
    }
    ui::say("They come from Microsoft's own installers; Windows will ask for permission.");
    if !ui::yes_no("Install them now?", true)? {
        anyhow::bail!("the Visual C++ runtimes are needed - run setup again to install them");
    }
    for (name, url, args) in missing {
        let file = inst
            .downloads()
            .join(format!("{}.exe", name.replace(' ', "_")));
        if !file.is_file() {
            net::download(url, &file, name)?;
        }
        // Start-Process -Verb RunAs: CreateProcess can't raise the UAC prompt the installer needs.
        let script = format!(
            "$p = Start-Process -FilePath '{}' -ArgumentList '{args}' -Verb RunAs -Wait -PassThru; exit $p.ExitCode",
            file.display()
        );
        let status = Command::new("powershell")
            .args(["-NoProfile", "-Command", &script])
            .status()?;
        // 3010: installed, restart later; 1638: a newer version is already there.
        match status.code() {
            Some(0 | 3010 | 1638) => ui::ok(name),
            other => anyhow::bail!(
                "installing {name} failed (exit {other:?}); install it from {url} and run setup again"
            ),
        }
    }
    Ok(())
}
