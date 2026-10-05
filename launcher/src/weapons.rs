//! WoW weapon models (tools/wow-weapons.sh's steps): for each weapon in the mod's
//! `mcwow/wow_weapons.json`, the client's `cc_weapon` builds the model from the player's own WoW
//! install into the local resource pack the mod loads. Nothing of WoW's art is shipped.

use std::io::Read;
use std::path::PathBuf;
use std::process::Command;

use anyhow::Context;

use crate::db::Db;
use crate::install::Install;
use crate::ui;

/// The per-user data folder (benilla's link.rs / the mod's McwowLinks.dataDir; keep in step).
fn data_dir() -> Option<PathBuf> {
    if let Some(d) = std::env::var_os("CLASSICCRAFT_DATA_DIR").filter(|d| !d.is_empty()) {
        return Some(PathBuf::from(d));
    }
    if cfg!(windows) {
        return std::env::var_os("APPDATA").map(|d| PathBuf::from(d).join("classiccraft"));
    }
    if let Some(d) = std::env::var_os("XDG_DATA_HOME").filter(|d| !d.is_empty()) {
        return Some(PathBuf::from(d).join("classiccraft"));
    }
    std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/share/classiccraft"))
}

/// Build the weapons not built yet (all of them with `force`). Needs the database running.
pub fn build(inst: &Install, force: bool) -> anyhow::Result<()> {
    let s = inst.settings();
    let client = s
        .wow_client
        .clone()
        .context("no WoW client set - run setup")?;
    let pack = data_dir().context("no data folder")?.join("resourcepack");
    let jar = std::fs::read_dir(inst.mod_dir())?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .find(|p| p.extension().is_some_and(|x| x == "jar"))
        .context("no mod jar in mod/")?;
    let mut list = String::new();
    zip::ZipArchive::new(std::fs::File::open(&jar)?)?
        .by_name("mcwow/wow_weapons.json")?
        .read_to_string(&mut list)?;
    let list: serde_json::Value = serde_json::from_str(&list)?;

    let db = Db::new(inst, &s);
    let was_running = db.running();
    db.start()?;
    let r = (|| -> anyhow::Result<()> {
        for w in list["weapons"].as_array().into_iter().flatten() {
            let key = w["key"].as_str().context("weapon without key")?;
            let model = pack
                .join("assets/mcwow/models/item/wow")
                .join(format!("{key}.json"));
            if model.is_file() && !force {
                continue;
            }
            let item = w["model_item"]
                .as_i64()
                .context("weapon without model_item")?;
            let display = db.query(
                Some("mangos"),
                &format!("SELECT display_id FROM item_template WHERE entry = {item} ORDER BY patch DESC LIMIT 1"),
            )?;
            if display.is_empty() {
                ui::warn(&format!("{key}: WoW item {item} not found"));
                continue;
            }
            let voxels = w["voxels"].as_i64().unwrap_or(40).to_string();
            let out = Command::new(inst.weapon_exe())
                .args([display.as_str(), key, voxels.as_str()])
                .env("OUT", &pack)
                .env("WOW_DATA", client.join("Data"))
                .output()?;
            if out.status.success() {
                ui::say(&format!("  weapon model {key}"));
            } else {
                ui::warn(&format!(
                    "{key}: {}",
                    String::from_utf8_lossy(&out.stderr).trim()
                ));
            }
        }
        Ok(())
    })();
    if !was_running {
        db.stop()?;
    }
    r
}
