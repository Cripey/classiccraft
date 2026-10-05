//! The Minecraft side, in the official launcher (tools/minecraft-install.sh's steps): Fabric Loader,
//! a "classiccraft" launcher profile with its OWN game folder (<minecraft dir>/classiccraft, so the
//! player's other worlds and mods are untouched), Fabric API and the classiccraft mod.

use std::path::{Path, PathBuf};

use anyhow::Context;

use crate::install::{Install, WINDOWS};
use crate::{net, ui};

pub fn installed(inst: &Install) -> bool {
    inst.done("minecraft")
}

/// The launcher folders that exist (have a profile list), newest first.
fn candidates() -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    if WINDOWS {
        if let Some(a) = std::env::var_os("APPDATA") {
            dirs.push(PathBuf::from(a).join(".minecraft"));
        }
    } else if let Some(h) = std::env::var_os("HOME") {
        let h = PathBuf::from(h);
        dirs.push(h.join(".minecraft"));
        dirs.push(h.join(".var/app/com.mojang.Minecraft/.minecraft"));
    }
    let mut found: Vec<(std::time::SystemTime, PathBuf)> = dirs
        .into_iter()
        .filter_map(|d| {
            let t = profile_files(&d)
                .iter()
                .filter_map(|f| f.metadata().and_then(|m| m.modified()).ok())
                .max()?;
            Some((t, d))
        })
        .collect();
    found.sort_by_key(|f| std::cmp::Reverse(f.0));
    found.into_iter().map(|(_, d)| d).collect()
}

/// The profile lists in a launcher folder: the launcher's own and the Microsoft Store launcher's.
fn profile_files(dir: &Path) -> Vec<PathBuf> {
    [
        "launcher_profiles.json",
        "launcher_profiles_microsoft_store.json",
    ]
    .iter()
    .map(|f| dir.join(f))
    .filter(|f| f.is_file())
    .collect()
}

/// The mod's Minecraft / Fabric versions, `mod/gradle.properties` in the bundle.
fn prop(inst: &Install, key: &str) -> anyhow::Result<String> {
    let text = std::fs::read_to_string(inst.mod_dir().join("gradle.properties"))?;
    text.lines()
        .find_map(|l| {
            l.strip_prefix(&format!("{key}="))
                .map(|v| v.trim().to_string())
        })
        .with_context(|| format!("{key} missing in mod/gradle.properties"))
}

pub fn install(inst: &Install) -> anyhow::Result<()> {
    let s = inst.settings();
    let mc = match s.minecraft_dir.clone() {
        Some(d) => d,
        None => {
            let found = candidates();
            let Some(first) = found.first().cloned() else {
                anyhow::bail!(
                    "no Minecraft launcher found. Install the official Minecraft launcher, start it once and log \
                     in, then run setup again."
                );
            };
            for other in &found[1..] {
                ui::say(&format!(
                    "(Another Minecraft launcher folder exists: {})",
                    other.display()
                ));
            }
            first
        }
    };
    anyhow::ensure!(
        !profile_files(&mc).is_empty(),
        "{} has no launcher_profiles.json",
        mc.display()
    );
    ui::say(&format!("Minecraft folder: {}", mc.display()));

    let mcv = prop(inst, "minecraft_version")?;
    let loader = prop(inst, "loader_version")?;
    let api = prop(inst, "fabric_api_version")?;
    let version_id = format!("fabric-loader-{loader}-{mcv}");
    let vdir = mc.join("versions").join(&version_id);
    if !vdir.join(format!("{version_id}.json")).is_file() {
        ui::say(&format!(
            "Installing Fabric Loader {loader} for Minecraft {mcv}..."
        ));
        let profile = net::get_json(&format!(
            "https://meta.fabricmc.net/v2/versions/loader/{mcv}/{loader}/profile/json"
        ))?;
        std::fs::create_dir_all(&vdir)?;
        std::fs::write(
            vdir.join(format!("{version_id}.json")),
            serde_json::to_string_pretty(&profile)?,
        )?;
        // As Fabric's installer: an empty jar; the launcher fills it from the parent version.
        std::fs::write(vdir.join(format!("{version_id}.jar")), [])?;
    }

    let game = mc.join("classiccraft");
    for file in profile_files(&mc) {
        add_profile(&file, &version_id, &game)?;
    }
    ui::say(&format!(
        "Launcher profile \"classiccraft\" (game folder {})",
        game.display()
    ));

    let mods = game.join("mods");
    std::fs::create_dir_all(&mods)?;
    std::fs::create_dir_all(game.join("config"))?;
    let api_jar = inst.downloads().join(format!("fabric-api-{api}.jar"));
    if !api_jar.is_file() {
        let url = format!(
            "https://api.modrinth.com/v2/project/fabric-api/version?game_versions=%5B%22{mcv}%22%5D&loaders=%5B%22fabric%22%5D"
        );
        let versions = net::get_json(&url)?;
        let file_url = versions
            .as_array()
            .into_iter()
            .flatten()
            .find(|v| v["version_number"] == api.as_str())
            .and_then(|v| v["files"][0]["url"].as_str())
            .with_context(|| format!("Fabric API {api} not found on Modrinth"))?
            .to_string();
        net::download(&file_url, &api_jar, "Fabric API")?;
    }
    let mod_jar = std::fs::read_dir(inst.mod_dir())?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .find(|p| p.extension().is_some_and(|x| x == "jar"))
        .context("no mod jar in mod/")?;
    for e in std::fs::read_dir(&mods)? {
        let p = e?.path();
        let n = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
        if n.starts_with("fabric-api-") || n.starts_with("classiccraft-bridge-") {
            std::fs::remove_file(&p)?;
        }
    }
    for jar in [&api_jar, &mod_jar] {
        std::fs::copy(jar, mods.join(jar.file_name().expect("file")))?;
    }

    // The mod reads the server's character database (instance ids); point it at ours.
    let config = serde_json::json!({
        "jdbcUrl": format!("jdbc:mysql://127.0.0.1:{}/characters", s.db_port),
        "user": s.db_user,
        "password": s.db_pass,
    });
    std::fs::write(
        game.join("config").join("mcwow.json"),
        serde_json::to_string_pretty(&config)?,
    )?;

    if mc.to_string_lossy().contains("com.mojang.Minecraft") {
        ui::warn(
            "Flatpak launcher: Minecraft must see /dev/shm to talk to WoW. Once, in a terminal:",
        );
        ui::say("  flatpak override --user --device=shm com.mojang.Minecraft");
    }
    inst.mark("minecraft", &mc.display().to_string())?;
    Ok(())
}

fn add_profile(file: &Path, version_id: &str, game: &Path) -> anyhow::Result<()> {
    let text = std::fs::read_to_string(file)?;
    let mut data: serde_json::Value =
        serde_json::from_str(&text).with_context(|| format!("{}", file.display()))?;
    std::fs::write(file.with_extension("json.classiccraft-backup"), &text)?;
    let now = now_iso();
    let profiles = data
        .as_object_mut()
        .context("profile list isn't an object")?
        .entry("profiles")
        .or_insert_with(|| serde_json::json!({}));
    let p = profiles
        .as_object_mut()
        .context("profiles isn't an object")?
        .entry("classiccraft")
        .or_insert_with(|| serde_json::json!({ "created": now, "icon": "Grass" }));
    let p = p.as_object_mut().context("bad classiccraft profile")?;
    p.insert("name".into(), "classiccraft".into());
    p.insert("type".into(), "custom".into());
    p.insert("lastVersionId".into(), version_id.into());
    p.insert("gameDir".into(), game.display().to_string().into());
    p.insert("lastUsed".into(), now.into());
    std::fs::write(file, serde_json::to_string_pretty(&data)?)?;
    Ok(())
}

/// Now as the launcher writes times: 2026-10-05T12:00:00.000Z.
fn now_iso() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let (days, rem) = (secs / 86400, secs % 86400);
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    let z = days as i64 + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!(
        "{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.000Z",
        rem / 3600,
        rem % 3600 / 60,
        rem % 60
    )
}
