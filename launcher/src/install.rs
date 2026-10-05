//! Where things are: the bundle's folders, `data/` for everything made here, and the settings.

use std::path::{Path, PathBuf};

use anyhow::Context;
use serde::{Deserialize, Serialize};

pub const WINDOWS: bool = cfg!(windows);
pub const EXE: &str = if WINDOWS { ".exe" } else { "" };

/// The player's choices and what setup made, `data/settings.json`. Ports avoid the stock WoW server
/// ports (3306/3724/8085), so classiccraft runs next to another private server.
#[derive(Serialize, Deserialize, Clone)]
#[serde(default)]
pub struct Settings {
    pub wow_client: Option<PathBuf>,
    pub account: Option<String>,
    /// The account's password, kept so WoW logs in by itself (the server only listens on this PC).
    pub password: Option<String>,
    /// The save Play starts: its WoW character's name (= its Minecraft world's folder).
    pub save: Option<String>,
    pub db_port: u16,
    pub realm_port: u16,
    pub world_port: u16,
    pub db_user: String,
    pub db_pass: String,
    /// The private MariaDB's root password where it has no socket login (Windows).
    pub db_root_pass: String,
    /// The Minecraft launcher's folder, when not the usual one.
    pub minecraft_dir: Option<PathBuf>,
    /// The Minecraft launcher program, when Play can't find it by itself.
    pub minecraft_launcher: Option<PathBuf>,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            wow_client: None,
            account: None,
            password: None,
            save: None,
            db_port: 3307,
            realm_port: 3725,
            world_port: 8086,
            db_user: "mangos".into(),
            db_pass: "mangos".into(),
            db_root_pass: String::new(),
            minecraft_dir: None,
            minecraft_launcher: None,
        }
    }
}

pub struct Install {
    pub root: PathBuf,
}

impl Install {
    /// The bundle the launcher sits in (`CLASSICCRAFT_ROOT` overrides it, for testing).
    pub fn locate() -> anyhow::Result<Self> {
        let root = match std::env::var_os("CLASSICCRAFT_ROOT") {
            Some(r) => PathBuf::from(r),
            None => std::env::current_exe()?
                .parent()
                .context("launcher has no folder")?
                .to_path_buf(),
        };
        let root = root.canonicalize().unwrap_or(root);
        let inst = Self {
            root: strip_unc(root),
        };
        anyhow::ensure!(
            inst.server_bin().is_dir() && inst.client_exe().is_file(),
            "{} is not a classiccraft bundle (no server/bin or client/). Start the launcher from the unpacked download.",
            inst.root.display()
        );
        Ok(inst)
    }

    pub fn client_exe(&self) -> PathBuf {
        self.root.join("client").join(format!("classiccraft{EXE}"))
    }
    pub fn weapon_exe(&self) -> PathBuf {
        self.root.join("client").join(format!("cc_weapon{EXE}"))
    }
    pub fn server_bin(&self) -> PathBuf {
        self.root.join("server").join("bin")
    }
    /// The extractors: `bin/Extractors` on Linux, `bin` itself on Windows.
    pub fn extractor(&self, name: &str) -> PathBuf {
        let sub = self.server_bin().join("Extractors");
        let dir = if sub.is_dir() { sub } else { self.server_bin() };
        dir.join(format!("{name}{EXE}"))
    }
    pub fn conf_dist(&self, name: &str) -> PathBuf {
        self.root
            .join("server")
            .join("etc")
            .join(format!("{name}.conf.dist"))
    }
    pub fn sql_dir(&self) -> PathBuf {
        self.root.join("sql")
    }
    pub fn mod_dir(&self) -> PathBuf {
        self.root.join("mod")
    }
    pub fn data(&self) -> PathBuf {
        self.root.join("data")
    }
    /// Extracted server data (dbc, maps, vmaps, mmaps).
    pub fn server_data(&self) -> PathBuf {
        self.data().join("server")
    }
    /// Live server configs, logs and process files.
    pub fn etc(&self) -> PathBuf {
        self.data().join("etc")
    }
    pub fn run_dir(&self) -> PathBuf {
        self.data().join("run")
    }
    pub fn downloads(&self) -> PathBuf {
        self.data().join("downloads")
    }

    pub fn settings(&self) -> Settings {
        std::fs::read_to_string(self.data().join("settings.json"))
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default()
    }
    pub fn save_settings(&self, s: &Settings) -> anyhow::Result<()> {
        std::fs::create_dir_all(self.data())?;
        std::fs::write(
            self.data().join("settings.json"),
            serde_json::to_string_pretty(s)?,
        )?;
        Ok(())
    }

    /// Setup's finished steps, `data/setup/<step>`.
    pub fn done(&self, step: &str) -> bool {
        self.data().join("setup").join(step).exists()
    }
    pub fn mark(&self, step: &str, content: &str) -> anyhow::Result<()> {
        let dir = self.data().join("setup");
        std::fs::create_dir_all(&dir)?;
        std::fs::write(dir.join(step), content)?;
        Ok(())
    }
    pub fn mark_content(&self, step: &str) -> Option<String> {
        std::fs::read_to_string(self.data().join("setup").join(step)).ok()
    }

    /// "v0.1.0 (commit ...)" from the bundle's VERSIONS.txt, or "(development build)".
    pub fn version_label(&self) -> String {
        let tag = std::fs::read_to_string(self.root.join("RELEASE.txt")).unwrap_or_default();
        let tag = tag.trim();
        if tag.is_empty() {
            "(unreleased build)".into()
        } else {
            tag.into()
        }
    }
}

/// A path as the server's config files want it: forward slashes on Windows too.
pub fn conf_path(p: &Path) -> String {
    p.display().to_string().replace('\\', "/")
}

/// Windows' canonicalize gives `\\?\C:\...`, which some programs (and the configs) don't take.
fn strip_unc(p: PathBuf) -> PathBuf {
    let s = p.display().to_string();
    match s.strip_prefix(r"\\?\") {
        Some(rest) if !rest.starts_with("UNC") => PathBuf::from(rest),
        _ => p,
    }
}
