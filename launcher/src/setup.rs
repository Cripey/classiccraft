//! First-time setup (tools/setup.sh's steps for a bundle). Each finished step is remembered in
//! data/setup, so after an error the player fixes it and runs setup again: it carries on.

use std::path::{Path, PathBuf};

use crate::db::Db;
use crate::install::Install;
use crate::{dbsetup, extract, minecraft, server, ui, weapons, winrt};

pub fn complete(inst: &Install) -> bool {
    ["database", "extract", "account"]
        .iter()
        .all(|s| inst.done(s))
        && inst.mark_content("config").as_deref() == Some(server::CONFIG_VERSION)
}

pub fn run(inst: &Install) -> anyhow::Result<()> {
    let mut s = inst.settings();
    ui::title("classiccraft setup");
    ui::say(
        "You need: your own World of Warcraft 1.12.1 (5875) client, Minecraft Java Edition with the",
    );
    ui::say("official launcher (started and logged in once), and ~15 GB of free disk space.");

    ui::step("WoW client");
    let client = match s.wow_client.clone().filter(|c| valid_client(c)) {
        Some(c) => c,
        None => {
            ui::say(
                "Where is your World of Warcraft 1.12.1 client? (the folder with WoW.exe and Data)",
            );
            loop {
                let a = ui::ask(">")?;
                let p = PathBuf::from(a.trim_matches('"').trim_end_matches(['/', '\\']));
                if valid_client(&p) {
                    break p;
                }
                ui::warn(
                    "No Data/dbc.MPQ and Data/model.MPQ there - that's not a 1.12.1 client folder.",
                );
            }
        }
    };
    s.wow_client = Some(client.clone());
    inst.save_settings(&s)?;
    ui::ok(&format!("{} (only read, never changed)", client.display()));

    if crate::install::WINDOWS {
        ui::step("Visual C++ runtimes");
        winrt::ensure(inst)?;
        ui::ok("Visual C++ runtimes");
    }

    ui::step("Database");
    let mut db = Db::new(inst, &s);
    db.ensure_programs(inst)?;
    db.init(inst, &mut s)?;
    db.start()?;
    let r = (|| -> anyhow::Result<()> {
        if !inst.done("database") {
            dbsetup::run(inst, &s, &db)?;
            inst.mark("database", "")?;
        }
        ui::ok("database");

        if inst.mark_content("config").as_deref() != Some(server::CONFIG_VERSION) {
            server::write_configs(inst, &s)?;
            inst.mark("config", server::CONFIG_VERSION)?;
        }
        ui::ok(&format!("server settings ({})", inst.etc().display()));

        ui::step("Server map data");
        if !inst.done("extract") {
            ui::say("Extracting map data from your client for the server (15-30 minutes)...");
            extract::run(inst, &client)?;
            inst.mark("extract", "")?;
        }
        ui::ok("server map data");

        ui::step("Game account");
        if !inst.done("account") {
            ui::say("Create your game account (to log in to WoW; it stays on this PC).");
            // Unattended setups (tests, scripts) pass them in the environment.
            let preset = std::env::var("CLASSICCRAFT_ACCOUNT")
                .ok()
                .zip(std::env::var("CLASSICCRAFT_PASSWORD").ok());
            let (user, pass) = match preset {
                Some(up) => up,
                None => ask_account()?,
            };
            server::create_account(inst, &db, &user, &pass)?;
            s.account = Some(user);
            s.password = Some(pass);
            inst.save_settings(&s)?;
            inst.mark("account", "")?;
        }
        // Accounts made by older launchers missed their GM level (2026-10-05).
        if let Some(user) = &s.account {
            server::grant_gm(&db, user)?;
        }
        ui::ok(&format!(
            "account {}",
            s.account.as_deref().unwrap_or("(created)")
        ));

        ui::step("Minecraft");
        match minecraft::install(inst) {
            Ok(()) => ui::ok("Minecraft: Fabric, the classiccraft profile and the mod"),
            Err(e) => ui::warn(&format!("Minecraft skipped for now: {e:#}")),
        }

        ui::step("WoW weapon models");
        match weapons::build(inst, false) {
            Ok(()) => ui::ok("weapon models"),
            Err(e) => ui::warn(&format!(
                "weapon models skipped: {e:#} (the game runs without them)"
            )),
        }
        Ok(())
    })();
    db.stop()?;
    r?;
    ui::step("All set");
    ui::say(
        "Pick \"Play\": it starts the server, the Minecraft launcher (press its Play button) and WoW,",
    );
    ui::say("which logs in by itself.");
    Ok(())
}

fn ask_account() -> anyhow::Result<(String, String)> {
    let user = loop {
        let u = ui::ask("Account name:")?;
        if (3..=16).contains(&u.len()) && u.chars().all(|c| c.is_ascii_alphanumeric()) {
            break u;
        }
        ui::warn("3-16 letters or digits.");
    };
    let pass = loop {
        let p = ui::ask_password("Password:")?;
        if !(4..=16).contains(&p.len()) || p.chars().any(char::is_whitespace) {
            ui::warn("4-16 characters, no spaces.");
            continue;
        }
        if ui::ask_password("Again:")? == p {
            break p;
        }
        ui::warn("They don't match.");
    };
    Ok((user, pass))
}

/// A 1.12.1 client: Data/dbc.MPQ and Data/model.MPQ (any letter case).
fn valid_client(dir: &Path) -> bool {
    let has = |name: &str| {
        std::fs::read_dir(dir.join("Data"))
            .map(|rd| {
                rd.filter_map(|e| e.ok())
                    .any(|e| e.file_name().to_string_lossy().eq_ignore_ascii_case(name))
            })
            .unwrap_or(false)
    };
    has("dbc.MPQ") && has("model.MPQ")
}
