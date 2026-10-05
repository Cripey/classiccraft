//! Create and fill the server's databases (tools/db-setup.sh's steps). Safe to rerun:
//! databases and the server's user if missing, VMaNGOS's world dump into empty databases,
//! `sql/migrations` (each records itself and runs once), classiccraft's own rows and the realm.

use std::path::PathBuf;

use anyhow::Context;

use crate::db::Db;
use crate::install::{Install, Settings};
use crate::{net, ui};

const DBS: [(&str, &str, &str); 4] = [
    // database, dump file, migration suffix
    ("mangos", "mangos", "world"),
    ("characters", "characters", "characters"),
    ("realmd", "logon", "logon"),
    ("logs", "logs", "logs"),
];

pub fn run(inst: &Install, s: &Settings, db: &Db) -> anyhow::Result<()> {
    let mut sql = String::new();
    for (d, _, _) in DBS {
        sql += &format!("CREATE DATABASE IF NOT EXISTS `{d}` CHARACTER SET utf8mb4;");
    }
    for h in ["localhost", "127.0.0.1"] {
        sql += &format!(
            "CREATE USER IF NOT EXISTS '{}'@'{h}' IDENTIFIED BY '{}';",
            s.db_user, s.db_pass
        );
        for (d, _, _) in DBS {
            sql += &format!("GRANT ALL PRIVILEGES ON `{d}`.* TO '{}'@'{h}';", s.db_user);
        }
    }
    sql += "FLUSH PRIVILEGES;";
    db.query(None, &sql)?;

    let dump = inst.data().join("db").join("mysql-dump");
    if !dump.join("mangos.sql").is_file() {
        download_dump(inst)?;
    }

    // An import cut short leaves "db-importing-<db>"; the next run drops that half-filled database
    // and imports it again.
    for (d, file, _) in DBS {
        let busy = format!("db-importing-{d}");
        if inst.done(&busy) {
            ui::say(&format!(
                "Redoing the import of {d} (the last one didn't finish)..."
            ));
            db.query(
                None,
                &format!(
                    "DROP DATABASE IF EXISTS `{d}`; CREATE DATABASE `{d}` CHARACTER SET utf8mb4;"
                ),
            )?;
        }
        let tables = db.query(
            None,
            &format!("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{d}'"),
        )?;
        if tables == "0" {
            let path = dump.join(format!("{file}.sql"));
            let mb = std::fs::metadata(&path).map(|m| m.len() >> 20).unwrap_or(0);
            ui::say(&format!("Importing {d} ({mb} MB)..."));
            inst.mark(&busy, "")?;
            db.source(d, &[path])?;
            std::fs::remove_file(inst.data().join("setup").join(&busy))?;
        }
    }

    for (d, _, suffix) in DBS {
        let mut files: Vec<PathBuf> = std::fs::read_dir(inst.sql_dir().join("migrations"))?
            .filter_map(|e| e.ok().map(|e| e.path()))
            .filter(|p| {
                p.file_name()
                    .and_then(|n| n.to_str())
                    .is_some_and(|n| n.ends_with(&format!("_{suffix}.sql")))
            })
            .collect();
        if files.is_empty() {
            continue;
        }
        files.sort();
        let count = || {
            db.query(Some(d), "SELECT COUNT(*) FROM migrations")
                .ok()
                .and_then(|n| n.parse::<u32>().ok())
                .unwrap_or(0)
        };
        let before = count();
        db.source(d, &files)?;
        let after = count();
        if after > before {
            ui::say(&format!("{d}: applied {} migrations", after - before));
        }
    }

    for f in std::fs::read_dir(inst.sql_dir().join("custom"))? {
        let p = f?.path();
        if p.file_name()
            .and_then(|n| n.to_str())
            .is_some_and(|n| n.starts_with("classiccraft_") && n.ends_with(".sql"))
        {
            db.source("mangos", &[p])?;
        }
    }
    db.query(
        Some("realmd"),
        &format!(
            "INSERT INTO realmlist (id, name, address, localAddress, port, icon, realmflags, timezone, \
             allowedSecurityLevel, population, gamebuild_min, gamebuild_max, realmbuilds) \
             VALUES (1, 'classiccraft', '127.0.0.1', '127.0.0.1', {p}, 1, 0, 1, 0, 0, 5875, 5875, '5875') \
             ON DUPLICATE KEY UPDATE port = {p};",
            p = s.world_port
        ),
    )?;
    Ok(())
}

/// VMaNGOS's database release `db_latest` (~30 MB zip) into data/db.
fn download_dump(inst: &Install) -> anyhow::Result<()> {
    ui::say("Downloading the VMaNGOS database (~30 MB)...");
    let rel = net::get_json("https://api.github.com/repos/vmangos/core/releases/tags/db_latest")?;
    let asset = rel["assets"]
        .as_array()
        .into_iter()
        .flatten()
        .find(|a| {
            a["name"]
                .as_str()
                .is_some_and(|n| n.starts_with("db-") && !n.contains("sqlite"))
        })
        .context("could not find the database download on github.com/vmangos/core")?;
    let name = asset["name"].as_str().unwrap_or("db.zip");
    let url = asset["browser_download_url"]
        .as_str()
        .context("no download url")?;
    let zip = inst.data().join("db").join(name);
    net::download(url, &zip, "database")?;
    net::unzip(&zip, &inst.data().join("db"))?;
    anyhow::ensure!(
        inst.data()
            .join("db")
            .join("mysql-dump")
            .join("mangos.sql")
            .is_file(),
        "the database download has no mysql-dump/mangos.sql"
    );
    Ok(())
}
