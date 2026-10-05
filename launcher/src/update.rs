//! Update to the newest published release: download it, swap the bundle's folders (data/ stays),
//! then apply new database migrations, rewrite the server settings if ours changed, reinstall the
//! mod into Minecraft and build any new weapon models.

use std::path::Path;

use anyhow::Context;

use crate::db::Db;
use crate::install::{EXE, Install, WINDOWS};
use crate::{dbsetup, minecraft, net, server, ui, weapons};

const REPO: &str = "Cripey/classiccraft";

pub fn run(inst: &Install) -> anyhow::Result<()> {
    ui::step("Checking for an update");
    let rel = match net::get_json(&format!(
        "https://api.github.com/repos/{REPO}/releases/latest"
    )) {
        Ok(r) => r,
        Err(e) if format!("{e:#}").contains("404") => {
            ui::say("No release has been published yet.");
            return Ok(());
        }
        Err(e) => return Err(e),
    };
    let tag = rel["tag_name"].as_str().context("release without a tag")?;
    let current = inst.version_label();
    if current == tag {
        ui::ok(&format!("classiccraft {tag} is the newest"));
        return Ok(());
    }
    ui::say(&format!("New: {tag} (you have {current})."));
    if !ui::yes_no("Update now? (Close the Minecraft launcher first.)", true)? {
        return Ok(());
    }
    let os = if WINDOWS { "windows" } else { "linux" };
    let name = format!(
        "classiccraft-{os}-x64.{}",
        if WINDOWS { "zip" } else { "tar.gz" }
    );
    let url = rel["assets"]
        .as_array()
        .into_iter()
        .flatten()
        .find(|a| a["name"] == name.as_str())
        .and_then(|a| a["browser_download_url"].as_str())
        .with_context(|| format!("the release has no {name}"))?;

    let work = inst.data().join("update");
    if work.exists() {
        std::fs::remove_dir_all(&work)?;
    }
    let archive = work.join(&name);
    net::download(url, &archive, &name)?;
    if WINDOWS {
        net::unzip(&archive, &work)?;
    } else {
        net::untar_gz(&archive, &work)?;
    }
    let new = work.join(format!("classiccraft-{os}-x64"));
    anyhow::ensure!(
        new.join("server").is_dir(),
        "the download has no classiccraft-{os}-x64/server"
    );
    swap_in(inst, &new)?;
    std::fs::remove_dir_all(&work)?;
    ui::ok(&format!("files updated to {tag}"));

    after_update(inst)?;
    ui::ok(&format!("classiccraft {tag} is ready"));
    ui::say("The launcher itself was updated too: close it and start it again.");
    std::process::exit(0);
}

/// Replace the bundle's folders and files with `new`'s; data/ is never touched.
fn swap_in(inst: &Install, new: &Path) -> anyhow::Result<()> {
    for dir in ["client", "server", "mod", "sql", "licenses"] {
        let (old, fresh) = (inst.root.join(dir), new.join(dir));
        if !fresh.exists() {
            continue;
        }
        if old.exists() {
            std::fs::remove_dir_all(&old)
                .with_context(|| format!("removing the old {dir}/ (still running?)"))?;
        }
        std::fs::rename(&fresh, &old)?;
    }
    for f in std::fs::read_dir(new)? {
        let p = f?.path();
        if !p.is_file() {
            continue;
        }
        let dest = inst.root.join(p.file_name().expect("file"));
        if p.file_name().and_then(|n| n.to_str()) == Some(&format!("classiccraft-launcher{EXE}"))
            && dest.exists()
        {
            // A running program can't be overwritten on Windows, but it can be renamed.
            let old = dest.with_extension("old");
            let _ = std::fs::remove_file(&old);
            std::fs::rename(&dest, &old)?;
        }
        std::fs::rename(&p, &dest)?;
    }
    Ok(())
}

fn after_update(inst: &Install) -> anyhow::Result<()> {
    let s = inst.settings();
    let db = Db::new(inst, &s);
    db.start()?;
    let r = (|| {
        dbsetup::run(inst, &s, &db)?;
        if inst.mark_content("config").as_deref() != Some(server::CONFIG_VERSION) {
            ui::say("Server settings changed: rewriting them (old ones kept as .conf.bak).");
            server::write_configs(inst, &s)?;
            inst.mark("config", server::CONFIG_VERSION)?;
        }
        Ok::<(), anyhow::Error>(())
    })();
    db.stop()?;
    r?;
    if minecraft::installed(inst) {
        minecraft::install(inst)?;
    }
    if let Err(e) = weapons::build(inst, false) {
        ui::warn(&format!("weapon models skipped: {e:#}"));
    }
    Ok(())
}
