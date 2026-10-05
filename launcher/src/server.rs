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
pub const CONFIG_VERSION: &str = "1";

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
        server.command(&format!("account set gmlevel {user} 3"))?;
        let t0 = Instant::now();
        while t0.elapsed() < Duration::from_secs(10) {
            let n = db.query(
                Some("realmd"),
                &format!("SELECT COUNT(*) FROM account WHERE username=UPPER('{user}')"),
            )?;
            if n == "1" {
                return Ok(());
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
    let r = session(inst, &s, &client, &mut server);
    ui::say("Stopping the server and the database...");
    server.stop();
    db.stop()?;
    ui::ok("stopped");
    r
}

fn session(inst: &Install, s: &Settings, client: &Path, server: &mut Server) -> anyhow::Result<()> {
    server.wait_ready()?;
    ui::ok("server running");
    if !minecraft::installed(inst) {
        ui::warn(
            "The Minecraft side isn't installed yet - run setup again once the Minecraft launcher is set up.",
        );
    }
    ui::say("");
    ui::say(
        "Now start Minecraft: in the Minecraft launcher pick the \"classiccraft\" profile, press Play and",
    );
    ui::say(
        "load your world (first time: Create New World > World Type: Superflat > Customize > Presets >",
    );
    ui::say("\"The Void\"). WoW opens next; log in with your account and enter the world.");
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
        let mut wow = start_client(inst, s, client)?;
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

fn start_client(inst: &Install, s: &Settings, client: &Path) -> anyhow::Result<Child> {
    let log = std::fs::File::create(inst.run_dir().join("classiccraft.log"))?;
    let exe = inst.client_exe();
    Command::new(&exe)
        .current_dir(exe.parent().expect("in client/"))
        .env("WOW_DATA", client.join("Data"))
        .env("WOW_HOST", format!("127.0.0.1:{}", s.realm_port))
        .stdin(Stdio::null())
        .stdout(log.try_clone()?)
        .stderr(log)
        .spawn()
        .context("starting the WoW client")
}
