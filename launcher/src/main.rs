//! classiccraft-launcher: sets up, runs and updates a classiccraft release bundle (2026-10-05).
//! The bash scripts in `tools/` stay the developer's path; this is the player's, on Linux and Windows.
//!
//! A bundle (`.github/workflows/release.yml`) is the launcher next to `client/`, `server/`, `mod/`,
//! `sql/` and `licenses/`; everything it makes goes into `data/` beside them (`Install`), so an
//! update can replace the bundle's folders without touching the player's world.
//!
//! Usage: `classiccraft-launcher [setup|play|update|minecraft]`; without arguments a menu.

mod db;
mod dbsetup;
mod extract;
mod install;
mod minecraft;
mod net;
mod server;
mod setup;
mod ui;
mod update;
mod weapons;
mod winrt;

use install::Install;

fn main() {
    let result = install::Install::locate().and_then(|inst| run(&inst));
    if let Err(e) = result {
        ui::fail(&format!("{e:#}"));
        ui::pause_on_windows();
        std::process::exit(1);
    }
}

fn run(inst: &Install) -> anyhow::Result<()> {
    let arg = std::env::args().nth(1);
    match arg.as_deref() {
        Some("setup") => setup::run(inst),
        Some("play") => server::play(inst),
        Some("update") => update::run(inst),
        Some("minecraft") => minecraft::install(inst),
        Some("weapons") => weapons::build(inst, std::env::args().any(|a| a == "--force")),
        Some(other) => {
            anyhow::bail!("unknown command {other:?} (setup, play, update, minecraft, weapons)")
        }
        None => menu(inst),
    }
}

fn menu(inst: &Install) -> anyhow::Result<()> {
    ui::title(&format!("classiccraft {}", inst.version_label()));
    if !setup::complete(inst) {
        ui::say(
            "First start: setting everything up. Each finished step is remembered, so if something",
        );
        ui::say(
            "goes wrong, fix it and start the launcher again - it carries on where it stopped.",
        );
        setup::run(inst)?;
    }
    loop {
        println!();
        let pick = ui::choose(
            "What now?",
            &[
                "Play",
                "Update classiccraft",
                "Run setup again (repairs, new Minecraft launcher)",
                "Quit",
            ],
        )?;
        let r = match pick {
            0 => server::play(inst),
            1 => update::run(inst),
            2 => setup::run(inst),
            _ => return Ok(()),
        };
        if let Err(e) = r {
            ui::fail(&format!("{e:#}"));
        }
    }
}
