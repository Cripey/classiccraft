//! The WoW server (realmd + mangosd) and a play session. The launcher holds mangosd's console, so
//! a session lasts while the launcher runs: lines typed here are server console commands.

use std::io::{BufRead, Write};
use std::path::Path;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use anyhow::Context;

use crate::db::Db;
use crate::install::{EXE, Install, Settings, conf_path};
use crate::{minecraft, ui};

/// Bumped when `write_configs` changes what it writes; setup and update rewrite the configs then.
pub const CONFIG_VERSION: &str = "2";

/// mangosd.conf / realmd.conf in data/etc from the bundle's .dist defaults plus ours (ports, DB
/// login, paths, 2x XP), as tools/server-config.sh. Old files are kept as .conf.bak.
pub fn write_configs(inst: &Install, s: &Settings) -> anyhow::Result<()> {
    let etc = inst.etc();
    let logs = inst.run_dir().join("logs");
    std::fs::create_dir_all(&etc)?;
    std::fs::create_dir_all(&logs)?;
    let db = |name: &str| {
        format!(
            "\"127.0.0.1;{};{};{};{name}\"",
            s.db_port, s.db_user, s.db_pass
        )
    };
    let q = |p: &Path| format!("\"{}\"", conf_path(p));

    let mut m: Vec<(String, String)> = vec![
        ("DataDir".into(), q(&inst.server_data())),
        ("LogsDir".into(), q(&logs)),
        ("LoginDatabase.Info".into(), db("realmd")),
        ("WorldDatabase.Info".into(), db("mangos")),
        ("CharacterDatabase.Info".into(), db("characters")),
        ("LogsDatabase.Info".into(), db("logs")),
        ("WorldServerPort".into(), s.world_port.to_string()),
        ("BindIP".into(), "\"127.0.0.1\"".into()),
    ];
    for r in ["Kill", "Kill.Elite", "Quest", "Explore"] {
        m.push((format!("Rate.XP.{r}"), "2".into()));
    }
    // Players are game masters (GM commands from Minecraft chat), but not immortal (user, 2026-10-05).
    m.push(("GM.CheatGod".into(), "0".into()));
    let r: Vec<(String, String)> = vec![
        ("LoginDatabaseInfo".into(), db("realmd")),
        ("LogsDir".into(), q(&logs)),
        ("RealmServerPort".into(), s.realm_port.to_string()),
        ("BindIP".into(), "\"127.0.0.1\"".into()),
    ];
    for (name, keys) in [("mangosd", m), ("realmd", r)] {
        let dist = std::fs::read_to_string(inst.conf_dist(name))
            .with_context(|| format!("{name}.conf.dist"))?;
        let mut text = String::with_capacity(dist.len());
        let mut seen = vec![false; keys.len()];
        for line in dist.lines() {
            let key = line.split('=').next().unwrap_or("").trim();
            match keys
                .iter()
                .position(|(k, _)| *k == key && !line.trim_start().starts_with('#'))
            {
                Some(i) => {
                    seen[i] = true;
                    text += &format!("{key} = {}\n", keys[i].1);
                }
                None => {
                    text += line;
                    text.push('\n');
                }
            }
        }
        if let Some(i) = seen.iter().position(|s| !s) {
            anyhow::bail!("{} not found in {name}.conf.dist", keys[i].0);
        }
        let out = etc.join(format!("{name}.conf"));
        if out.is_file() {
            std::fs::copy(&out, etc.join(format!("{name}.conf.bak")))?;
        }
        std::fs::write(out, text)?;
    }
    Ok(())
}

pub struct Server {
    realmd: Child,
    mangosd: Child,
    console: ChildStdin,
    out: std::path::PathBuf,
}

impl Server {
    pub fn start(inst: &Install) -> anyhow::Result<Self> {
        let run = inst.run_dir();
        std::fs::create_dir_all(&run)?;
        let bin = inst.server_bin();
        let spawn = |name: &str, stdin: Stdio| -> anyhow::Result<Child> {
            let out = std::fs::File::create(run.join(format!("{name}.out")))?;
            let mut c = Command::new(bin.join(format!("{name}{EXE}")));
            c.arg("-c")
                .arg(inst.etc().join(format!("{name}.conf")))
                .current_dir(&bin);
            c.stdin(stdin).stdout(out.try_clone()?).stderr(out);
            c.spawn().with_context(|| format!("starting {name}"))
        };
        let realmd = spawn("realmd", Stdio::null())?;
        let mut mangosd = spawn("mangosd", Stdio::piped())?;
        let console = mangosd.stdin.take().expect("piped");
        Ok(Self {
            realmd,
            mangosd,
            console,
            out: run.join("mangosd.out"),
        })
    }

    /// Wait for "World initialized" (the first start after setup loads everything: ~1 min).
    pub fn wait_ready(&mut self) -> anyhow::Result<()> {
        let t0 = Instant::now();
        while t0.elapsed() < Duration::from_secs(600) {
            if std::fs::read_to_string(&self.out)
                .unwrap_or_default()
                .contains("World initialized")
            {
                return Ok(());
            }
            if let Some(st) = self.mangosd.try_wait()? {
                anyhow::bail!("the server stopped ({st}), see {}", self.out.display());
            }
            std::thread::sleep(Duration::from_millis(500));
        }
        anyhow::bail!(
            "the server didn't come up in 10 minutes, see {}",
            self.out.display()
        )
    }

    pub fn command(&mut self, line: &str) -> anyhow::Result<()> {
        writeln!(self.console, "{line}")?;
        self.console.flush()?;
        Ok(())
    }

    pub fn stop(mut self) {
        let _ = self.command("server shutdown 1");
        let t0 = Instant::now();
        while t0.elapsed() < Duration::from_secs(30) {
            if matches!(self.mangosd.try_wait(), Ok(Some(_))) {
                break;
            }
            std::thread::sleep(Duration::from_millis(200));
        }
        let _ = self.mangosd.kill();
        let _ = self.realmd.kill();
        let _ = self.mangosd.wait();
        let _ = self.realmd.wait();
    }
}

/// Make the game account (GM level 3: classiccraft uses GM commands from Minecraft chat).
pub fn create_account(inst: &Install, db: &Db, user: &str, pass: &str) -> anyhow::Result<()> {
    let mut server = Server::start(inst)?;
    ui::say("Starting the server to create the account (the first start takes a minute)...");
    let r = (|| -> anyhow::Result<()> {
        server.wait_ready()?;
        server.command(&format!("account create {user} {pass}"))?;
        // The account row is written in the background; the GM level goes in once it's there
        // (a console "account set gmlevel" sent right after the create found no account yet).
        let t0 = Instant::now();
        while t0.elapsed() < Duration::from_secs(10) {
            let n = db.query(
                Some("realmd"),
                &format!("SELECT COUNT(*) FROM account WHERE username=UPPER('{user}')"),
            )?;
            if n == "1" {
                return grant_gm(db, user);
            }
            std::thread::sleep(Duration::from_millis(500));
        }
        anyhow::bail!(
            "creating the account failed, see {}",
            inst.run_dir().join("mangosd.out").display()
        )
    })();
    server.stop();
    r
}

/// GM level 3 on every realm for `user` (classiccraft uses GM commands from Minecraft chat),
/// written straight into `account_access` and checked. Idempotent; the server reads it at login.
pub fn grant_gm(db: &Db, user: &str) -> anyhow::Result<()> {
    db.query(
        Some("realmd"),
        &format!(
            "REPLACE INTO account_access (id, gmlevel, RealmID) \
             SELECT id, 3, -1 FROM account WHERE username=UPPER('{user}')"
        ),
    )?;
    let level = db.query(
        Some("realmd"),
        &format!(
            "SELECT aa.gmlevel FROM account a JOIN account_access aa ON aa.id = a.id \
             WHERE a.username=UPPER('{user}') AND aa.RealmID = -1"
        ),
    )?;
    anyhow::ensure!(level == "3", "giving {user} game master rights failed");
    Ok(())
}

/// A play session: database, server, the WoW client; server console on stdin until the player quits.
pub fn play(inst: &Install) -> anyhow::Result<()> {
    let s = inst.settings();
    let client = s
        .wow_client
        .clone()
        .context("no WoW client set - run setup")?;
    let db = Db::new(inst, &s);
    ui::step("Starting the database and the server");
    db.start()?;
    let mut server = Server::start(inst)?;
    let r = session(inst, &db, &s, &client, &mut server);
    ui::say("Stopping the server and the database...");
    server.stop();
    db.stop()?;
    ui::ok("stopped");
    r
}

fn session(
    inst: &Install,
    db: &Db,
    s: &Settings,
    client: &Path,
    server: &mut Server,
) -> anyhow::Result<()> {
    server.wait_ready()?;
    ui::ok("server running");
    let mut login = login(inst, db, s)?;

    ui::say("");
    match minecraft::launch(inst, s) {
        Ok(Some(true)) => {
            ui::ok("Minecraft launcher started");
            ui::say("In the Minecraft launcher, press Play (the \"classiccraft\" profile is selected). Your");
            ui::say("world opens by itself (the first time a new one is made).");
        }
        Ok(Some(false)) => {
            ui::warn("The Minecraft launcher program wasn't found - start it yourself, pick the");
            ui::say("\"classiccraft\" profile and press Play. (Its path can be set as \"minecraft_launcher\"");
            ui::say(&format!("in {}.)", inst.data().join("settings.json").display()));
        }
        Ok(None) => ui::warn(
            "The Minecraft side isn't installed yet - run setup again once the Minecraft launcher is set up.",
        ),
        Err(e) => ui::warn(&format!("Couldn't start the Minecraft launcher: {e:#}")),
    }
    match &login.character {
        Some(name) => ui::say(&format!("WoW opens next and logs in as {name}.")),
        None => ui::say("WoW opens next and logs in; create your character there."),
    }
    ui::say(
        "In the WoW window, ` (backtick) switches between Minecraft controls and WoW's own UI.",
    );
    ui::say(
        "Lines typed here go to the server console (e.g. \"account set password NAME NEW NEW\").",
    );
    ui::say("Type \"quit\" here to stop everything.");

    let (tx, rx) = mpsc::channel::<String>();
    std::thread::spawn(move || {
        for line in std::io::stdin().lock().lines() {
            let Ok(line) = line else { break };
            if tx.send(line).is_err() {
                break;
            }
        }
    });

    loop {
        login.character = last_character(db, login.account.as_deref());
        let mut wow = start_client(inst, s, client, &login)?;
        ui::ok("WoW started");
        let quit = loop {
            if let Some(st) = wow.try_wait()? {
                if !st.success() {
                    ui::warn(&format!(
                        "WoW exited with {st}; its log: {}",
                        inst.run_dir().join("classiccraft.log").display()
                    ));
                }
                break false;
            }
            match rx.recv_timeout(Duration::from_millis(300)) {
                Ok(line) if line.trim() == "quit" => break true,
                Ok(line) if !line.trim().is_empty() => {
                    server.command(line.trim_start_matches('.'))?
                }
                _ => {}
            }
        };
        if quit {
            let _ = wow.kill();
            return Ok(());
        }
        ui::say("WoW closed. Press Enter to start it again, or type \"quit\" to stop the server.");
        loop {
            match rx.recv() {
                Ok(line) if line.trim() == "quit" => return Ok(()),
                Ok(line) if line.trim().is_empty() => break,
                Ok(line) => server.command(line.trim_start_matches('.'))?,
                Err(_) => return Ok(()),
            }
        }
    }
}

/// Who WoW logs in as: the saved account and password, and the character played last.
struct Login {
    account: Option<String>,
    password: Option<String>,
    character: Option<String>,
}

/// The saved login; an install from before 2026-10-05 has no saved password, so it's asked once.
fn login(inst: &Install, db: &Db, s: &Settings) -> anyhow::Result<Login> {
    let account = s
        .account
        .clone()
        .filter(|a| !a.is_empty() && a.chars().all(|c| c.is_ascii_alphanumeric()));
    let mut password = s.password.clone();
    if let (Some(user), None) = (&account, &password) {
        ui::say(&format!(
            "WoW can log in by itself: type the password of your account {user} once (it's saved in"
        ));
        ui::say(&format!(
            "{}), or just press Enter to log in by hand.",
            inst.data().join("settings.json").display()
        ));
        let p = ui::ask_password("Password:")?;
        if !p.is_empty() {
            let mut saved = inst.settings();
            saved.password = Some(p.clone());
            inst.save_settings(&saved)?;
            password = Some(p);
        }
    }
    let character = last_character(db, account.as_deref());
    Ok(Login {
        account,
        password,
        character,
    })
}

/// The account's character logged out last; none yet = WoW's character screen, to make one.
fn last_character(db: &Db, account: Option<&str>) -> Option<String> {
    let user = account?;
    db.query(
        Some("characters"),
        &format!(
            "SELECT c.name FROM characters c JOIN realmd.account a ON a.id = c.account \
             WHERE a.username = UPPER('{user}') ORDER BY c.logout_time DESC, c.guid DESC LIMIT 1"
        ),
    )
    .ok()
    .filter(|n| !n.is_empty())
}

fn start_client(
    inst: &Install,
    s: &Settings,
    client: &Path,
    login: &Login,
) -> anyhow::Result<Child> {
    let log = std::fs::File::create(inst.run_dir().join("classiccraft.log"))?;
    let exe = inst.client_exe();
    let mut cmd = Command::new(&exe);
    // Auto-login (benilla: both WOW_USER and WOW_PASS), straight into the world as WOW_CHAR.
    if let (Some(user), Some(pass)) = (&login.account, &login.password) {
        cmd.env("WOW_USER", user).env("WOW_PASS", pass);
        if let Some(name) = &login.character {
            cmd.env("WOW_CHAR", name);
        }
    }
    cmd.current_dir(exe.parent().expect("in client/"))
        .env("WOW_DATA", client.join("Data"))
        .env("WOW_HOST", format!("127.0.0.1:{}", s.realm_port))
        .stdin(Stdio::null())
        .stdout(log.try_clone()?)
        .stderr(log)
        .spawn()
        .context("starting the WoW client")
}
