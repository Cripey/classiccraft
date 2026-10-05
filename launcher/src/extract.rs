//! Server data from the player's WoW client into data/server (tools/extract.sh's steps): dbc and
//! maps, vmaps, mmaps. Reads the client only.

use std::path::Path;
use std::process::Command;
use std::time::Instant;

use crate::install::Install;
use crate::ui;

pub fn run(inst: &Install, client: &Path) -> anyhow::Result<()> {
    let out = inst.server_data();
    std::fs::create_dir_all(&out)?;
    let threads = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
        .to_string();
    let t0 = Instant::now();
    let step = |what: &str| {
        ui::say(&format!(
            "  {what} ({} min so far)",
            t0.elapsed().as_secs() / 60
        ))
    };

    step("MapExtractor: dbc and maps");
    let c = client.display().to_string();
    let o = out.display().to_string();
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

    step("VMapExtractor: buildings");
    let data = client.join("Data").display().to_string();
    tool(
        inst,
        "VMapExtractor",
        &["-l", "-d", &data, "--silent"],
        &out,
    )?;

    step("VMapAssembler");
    std::fs::create_dir_all(out.join("vmaps"))?;
    tool(inst, "VMapAssembler", &["Buildings", "vmaps"], &out)?;

    step("MoveMapGenerator: paths for creatures (the long one)");
    std::fs::create_dir_all(out.join("mmaps"))?;
    let dir = inst.extractor("MoveMapGenerator");
    let dir = dir.parent().expect("in a folder");
    let offmesh = dir.join("offmesh.txt").display().to_string();
    let config = dir.join("config.json").display().to_string();
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
    )?;
    step("done");
    Ok(())
}

fn tool(inst: &Install, name: &str, args: &[&str], cwd: &Path) -> anyhow::Result<()> {
    let exe = inst.extractor(name);
    let log = inst.run_dir().join(format!("extract-{name}.log"));
    std::fs::create_dir_all(inst.run_dir())?;
    let status = Command::new(&exe)
        .args(args)
        .current_dir(cwd)
        .stdout(std::fs::File::create(&log)?)
        .stderr(std::fs::File::options().append(true).open(&log)?)
        .status()
        .map_err(|e| anyhow::anyhow!("{}: {e}", exe.display()))?;
    anyhow::ensure!(status.success(), "{name} failed, see {}", log.display());
    Ok(())
}
