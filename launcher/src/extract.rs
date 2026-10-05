//! Server data from the player's WoW client into data/server (tools/extract.sh's steps): dbc and
//! maps, vmaps, mmaps. Reads the client only.
//!
//! Each step is remembered (`data/setup/extract-<step>`), so a rerun skips finished ones, and a step
//! being (re)done starts from an empty output folder: VMapExtractor refuses a non-empty Buildings
//! ("polluted"). The extractors get no input: without `--silent` they wait for Enter (on Windows
//! the launcher's console would feed them, and setup sat there forever, 2026-10-05).

use std::path::Path;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use crate::install::Install;
use crate::ui;

pub fn run(inst: &Install, client: &Path) -> anyhow::Result<()> {
    let out = inst.server_data();
    std::fs::create_dir_all(&out)?;
    let threads = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
        .to_string();
    let c = client.display().to_string();
    let o = out.display().to_string();
    let data = client.join("Data").display().to_string();
    let mm = inst.extractor("MoveMapGenerator");
    let mm_dir = mm.parent().expect("in a folder");
    let offmesh = mm_dir.join("offmesh.txt").display().to_string();
    let config = mm_dir.join("config.json").display().to_string();

    step(
        inst,
        "maps",
        "MapExtractor: dbc and maps (a few minutes)",
        &[&out.join("maps"), &out.join("dbc")],
        || {
            tool(
                inst,
                "MapExtractor",
                &["-i", &c, "-o", &o, "--silent"],
                &out,
            )?;
            // mangosd looks for DBCs per client build: <DataDir>/5875/dbc
            let dbc = out.join("5875").join("dbc");
            std::fs::create_dir_all(out.join("5875"))?;
            if dbc.exists() {
                std::fs::remove_dir_all(&dbc)?;
            }
            std::fs::rename(out.join("dbc"), &dbc)?;
            Ok(())
        },
    )?;
    step(
        inst,
        "buildings",
        "VMapExtractor: buildings (a few minutes)",
        &[&out.join("Buildings")],
        || {
            tool(
                inst,
                "VMapExtractor",
                &["-l", "-d", &data, "--silent"],
                &out,
            )
        },
    )?;
    step(
        inst,
        "vmaps",
        "VMapAssembler",
        &[&out.join("vmaps")],
        || {
            std::fs::create_dir_all(out.join("vmaps"))?;
            tool(
                inst,
                "VMapAssembler",
                &["Buildings", "vmaps", "--silent"],
                &out,
            )
        },
    )?;
    step(
        inst,
        "mmaps",
        "MoveMapGenerator: paths for creatures (the long one, 10-30 minutes)",
        &[&out.join("mmaps")],
        || {
            std::fs::create_dir_all(out.join("mmaps"))?;
            tool(
                inst,
                "MoveMapGenerator",
                &[
                    "--threads",
                    &threads,
                    "--silent",
                    "--offMeshInput",
                    &offmesh,
                    "--configInputPath",
                    &config,
                ],
                &out,
            )
        },
    )?;
    Ok(())
}

/// One remembered step: skipped when done, else its outputs are cleared and it runs.
fn step(
    inst: &Install,
    key: &str,
    what: &str,
    outputs: &[&Path],
    f: impl FnOnce() -> anyhow::Result<()>,
) -> anyhow::Result<()> {
    let mark = format!("extract-{key}");
    if inst.done(&mark) {
        ui::say(&format!("  {what}: done before"));
        return Ok(());
    }
    ui::say(&format!("  {what}"));
    for o in outputs {
        if o.exists() {
            std::fs::remove_dir_all(o)?;
        }
    }
    f()?;
    inst.mark(&mark, "")
}

/// Run an extractor (no input, output to its log), saying every minute that it's still going.
fn tool(inst: &Install, name: &str, args: &[&str], cwd: &Path) -> anyhow::Result<()> {
    let exe = inst.extractor(name);
    let log = inst.run_dir().join(format!("extract-{name}.log"));
    std::fs::create_dir_all(inst.run_dir())?;
    let out = std::fs::File::create(&log)?;
    let mut child = Command::new(&exe)
        .args(args)
        .current_dir(cwd)
        .stdin(Stdio::null())
        .stdout(out.try_clone()?)
        .stderr(out)
        .spawn()
        .map_err(|e| anyhow::anyhow!("{}: {e}", exe.display()))?;
    let t0 = Instant::now();
    let mut said = 0;
    let status = loop {
        if let Some(st) = child.try_wait()? {
            break st;
        }
        std::thread::sleep(Duration::from_millis(500));
        let min = t0.elapsed().as_secs() / 60;
        if min > said {
            said = min;
            ui::say(&format!("    still working... {min} min"));
        }
    };
    anyhow::ensure!(
        status.success(),
        "{name} failed ({status}), see {}",
        log.display()
    );
    Ok(())
}
