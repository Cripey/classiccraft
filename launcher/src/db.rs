//! The server's private MariaDB in `data/mariadb`, run as the player (no service, no admin rights).
//! Linux: the system's MariaDB programs, root logs in over a socket. Windows: MariaDB's own zip,
//! downloaded once into `data/mariadb-dist`, root logs in with a generated password over TCP.

use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use anyhow::Context;

use crate::install::{Install, Settings, WINDOWS};
use crate::{net, ui};

/// The pinned Windows build (sha256 from downloads.mariadb.org's REST API).
const WIN_VERSION: &str = "11.8.9";
const WIN_SHA256: &str = "830c46727d9278eae212ae3eca44eeb9e71b2a68704e95f344a64fba7b1963f5";

pub struct Db {
    datadir: PathBuf,
    run: PathBuf,
    bin: Option<PathBuf>,
    port: u16,
    root_pass: String,
}

impl Db {
    pub fn new(inst: &Install, s: &Settings) -> Self {
        let bin = if WINDOWS {
            Some(
                inst.data()
                    .join("mariadb-dist")
                    .join(format!("mariadb-{WIN_VERSION}-winx64"))
                    .join("bin"),
            )
        } else {
            None
        };
        Self {
            datadir: inst.data().join("mariadb"),
            run: inst.run_dir(),
            bin,
            port: s.db_port,
            root_pass: s.db_root_pass.clone(),
        }
    }

    /// A MariaDB program: from the downloaded zip on Windows, from the system on Linux.
    fn program(&self, name: &str) -> anyhow::Result<PathBuf> {
        if let Some(bin) = &self.bin {
            return Ok(bin.join(format!("{name}.exe")));
        }
        let mut dirs: Vec<PathBuf> =
            std::env::split_paths(&std::env::var_os("PATH").unwrap_or_default()).collect();
        dirs.extend(
            ["/usr/sbin", "/usr/bin", "/usr/local/sbin", "/usr/local/bin"].map(PathBuf::from),
        );
        dirs.iter()
            .map(|d| d.join(name))
            .find(|p| p.is_file())
            .with_context(|| {
                format!(
                    "MariaDB's `{name}` is missing. Install the MariaDB server and client:\n  \
                 Ubuntu/Debian: sudo apt install mariadb-server-core mariadb-client-core\n  \
                 Arch: sudo pacman -S mariadb"
                )
            })
    }

    /// Windows: download and unpack MariaDB if it isn't there yet. Linux: check the programs exist.
    pub fn ensure_programs(&self, inst: &Install) -> anyhow::Result<()> {
        if let Some(bin) = &self.bin {
            if bin.join("mariadbd.exe").is_file() {
                return Ok(());
            }
            let zip = inst
                .downloads()
                .join(format!("mariadb-{WIN_VERSION}-winx64.zip"));
            if !zip.is_file() {
                ui::say(&format!("Downloading MariaDB {WIN_VERSION} (~100 MB)..."));
                let url = format!(
                    "https://downloads.mariadb.org/rest-api/mariadb/{WIN_VERSION}/mariadb-{WIN_VERSION}-winx64.zip"
                );
                net::download(&url, &zip, "MariaDB")?;
            }
            let sum = net::sha256_file(&zip)?;
            if sum != WIN_SHA256 {
                let _ = std::fs::remove_file(&zip);
                anyhow::bail!(
                    "the MariaDB download is damaged (checksum mismatch) - start setup again"
                );
            }
            net::unzip(&zip, &inst.data().join("mariadb-dist"))?;
            return Ok(());
        }
        for p in ["mariadbd", "mariadb-install-db", "mariadb"] {
            self.program(p)?;
        }
        Ok(())
    }

    fn socket(&self) -> PathBuf {
        // Unix socket paths are limited to ~107 characters, so not under the bundle.
        let dir = std::env::var_os("XDG_RUNTIME_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| "/tmp".into());
        dir.join(format!("classiccraft-mariadb-{}.sock", self.port))
    }

    /// The admin client, logged in as root.
    pub fn client(&self) -> anyhow::Result<Command> {
        let mut c = Command::new(self.program("mariadb")?);
        c.arg("--no-defaults");
        if WINDOWS {
            c.args([
                "-h",
                "127.0.0.1",
                "-P",
                &self.port.to_string(),
                "-u",
                "root",
            ]);
            c.arg(format!("-p{}", self.root_pass));
        } else {
            c.arg(format!("--socket={}", self.socket().display()));
        }
        Ok(c)
    }

    /// Run SQL, returning its output (tab-separated, no headers).
    pub fn query(&self, database: Option<&str>, sql: &str) -> anyhow::Result<String> {
        let mut c = self.client()?;
        c.args(["-N", "-B", "-e", sql]);
        if let Some(d) = database {
            c.arg(d);
        }
        let out = c.stdin(Stdio::null()).output()?;
        anyhow::ensure!(
            out.status.success(),
            "SQL failed: {}",
            String::from_utf8_lossy(&out.stderr).trim()
        );
        Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
    }

    /// Feed a file (or several, in order) to the client.
    pub fn source(&self, database: &str, files: &[PathBuf]) -> anyhow::Result<()> {
        let mut c = self.client()?;
        let mut child = c
            .arg(database)
            .stdin(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()?;
        {
            let mut stdin = child.stdin.take().expect("piped");
            for f in files {
                let mut file =
                    std::fs::File::open(f).with_context(|| format!("{}", f.display()))?;
                std::io::copy(&mut file, &mut stdin)?;
                std::io::Write::write_all(&mut stdin, b"\n")?;
            }
        }
        let out = child.wait_with_output()?;
        anyhow::ensure!(
            out.status.success(),
            "SQL failed: {}",
            String::from_utf8_lossy(&out.stderr).trim()
        );
        Ok(())
    }

    pub fn running(&self) -> bool {
        self.query(None, "SELECT 1").is_ok()
    }

    /// Create the data directory once.
    pub fn init(&mut self, inst: &Install, settings: &mut Settings) -> anyhow::Result<()> {
        if self.datadir.join("mysql").is_dir() {
            return Ok(());
        }
        std::fs::create_dir_all(&self.datadir)?;
        let mut c = Command::new(self.program("mariadb-install-db")?);
        if WINDOWS {
            if settings.db_root_pass.is_empty() {
                settings.db_root_pass = random_password();
                inst.save_settings(settings)?;
            }
            self.root_pass = settings.db_root_pass.clone();
            c.arg(format!("--datadir={}", self.datadir.display()))
                .arg(format!("--port={}", self.port))
                .arg(format!("--password={}", self.root_pass));
        } else {
            // The player's own user becomes a passwordless admin over the socket.
            c.args([
                "--no-defaults",
                "--auth-root-authentication-method=socket",
                "--skip-test-db",
            ])
            .arg(format!("--datadir={}", self.datadir.display()));
        }
        let log = inst.data().join("mariadb-init.log");
        let out = c.output()?;
        std::fs::write(&log, [out.stdout, out.stderr].concat())?;
        if !out.status.success() {
            let _ = std::fs::remove_dir_all(&self.datadir);
            anyhow::bail!("creating the database failed, see {}", log.display());
        }
        Ok(())
    }

    pub fn start(&self) -> anyhow::Result<()> {
        if self.running() {
            return Ok(());
        }
        anyhow::ensure!(
            self.datadir.join("mysql").is_dir(),
            "no database yet - run setup"
        );
        std::fs::create_dir_all(&self.run)?;
        let mut c = Command::new(self.program("mariadbd")?);
        if WINDOWS {
            // --defaults-file must come first; my.ini was written by mariadb-install-db.
            c.arg(format!(
                "--defaults-file={}",
                self.datadir.join("my.ini").display()
            ));
        } else {
            c.arg("--no-defaults")
                .arg(format!("--socket={}", self.socket().display()));
        }
        c.arg(format!("--datadir={}", self.datadir.display()))
            .arg(format!("--port={}", self.port))
            .arg("--bind-address=127.0.0.1")
            .arg(format!(
                "--pid-file={}",
                self.run.join("mariadb.pid").display()
            ))
            .arg(format!(
                "--log-error={}",
                self.run.join("mariadb.err").display()
            ));
        detach(&mut c);
        c.stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()?;
        let t0 = Instant::now();
        while t0.elapsed() < Duration::from_secs(30) {
            if self.running() {
                return Ok(());
            }
            std::thread::sleep(Duration::from_millis(250));
        }
        let err = std::fs::read_to_string(self.run.join("mariadb.err")).unwrap_or_default();
        let tail: Vec<&str> = err.lines().rev().take(12).collect();
        anyhow::bail!(
            "the database didn't start (port {} in use?). End of {}:\n{}",
            self.port,
            self.run.join("mariadb.err").display(),
            tail.into_iter().rev().collect::<Vec<_>>().join("\n")
        )
    }

    pub fn stop(&self) -> anyhow::Result<()> {
        if !self.running() {
            return Ok(());
        }
        let _ = self.query(None, "SHUTDOWN");
        let t0 = Instant::now();
        // Wait for the process itself, not just the port: a quick restart would hit its lock.
        while t0.elapsed() < Duration::from_secs(30) && (self.running() || self.pid_alive()) {
            std::thread::sleep(Duration::from_millis(200));
        }
        Ok(())
    }

    fn pid_alive(&self) -> bool {
        let Ok(pid) = std::fs::read_to_string(self.run.join("mariadb.pid")) else {
            return false;
        };
        let pid = pid.trim();
        if WINDOWS {
            Command::new("tasklist")
                .args(["/FI", &format!("PID eq {pid}"), "/NH"])
                .output()
                .map(|o| String::from_utf8_lossy(&o.stdout).contains(pid))
                .unwrap_or(false)
        } else {
            PathBuf::from(format!("/proc/{pid}")).exists()
        }
    }
}

/// Start `c` outside the launcher's console / process group, so Ctrl+C or closing the window
/// doesn't kill it mid-write.
pub fn detach(c: &mut Command) {
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        c.process_group(0);
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
        c.creation_flags(CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP);
    }
}

fn random_password() -> String {
    use std::hash::{BuildHasher, Hasher};
    let mut out = String::new();
    for i in 0..3u64 {
        let mut h = std::collections::hash_map::RandomState::new().build_hasher();
        h.write_u64(i ^ std::process::id() as u64);
        h.write_u128(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos(),
        );
        out.push_str(&format!("{:016x}", h.finish()));
    }
    out
}
