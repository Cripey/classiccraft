//! Saves (2026-10-05, user): a save is a WoW character plus its own Minecraft world. The world is the
//! game folder's `saves/<Character>` (the mod's auto-world opens or makes it from `config/mcwow.json`
//! "world"); the character lives in the server's database. The chosen save is `settings.save`.
//! Co-op saves (a friend's server and Minecraft server) come later.

use std::path::PathBuf;
use std::time::Duration;

use crate::db::Db;
use crate::install::{Install, Settings};
use crate::{minecraft, server, ui};

/// One character of the account.
pub struct Save {
    pub name: String,
    pub level: u32,
    /// Last logout, Unix seconds (0 = never played).
    pub last: u64,
}

impl Save {
    pub fn label(&self) -> String {
        let when = match self.last {
            0 => "never played".to_string(),
            t => {
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_secs())
                    .unwrap_or(0);
                match now.saturating_sub(t) / 86_400 {
                    0 => "played today".to_string(),
                    1 => "played yesterday".to_string(),
                    d => format!("played {d} days ago"),
                }
            }
        };
        format!("{} (level {}, {when})", self.name, self.level)
    }
}

/// The account name as setup saved it, only letters and digits (it goes into SQL).
pub fn account(s: &Settings) -> Option<String> {
    s.account
        .clone()
        .filter(|a| !a.is_empty() && a.chars().all(|c| c.is_ascii_alphanumeric()))
}

/// The account's characters, last played first.
pub fn list(db: &Db, account: &str) -> anyhow::Result<Vec<Save>> {
    let out = db.query(
        Some("characters"),
        &format!(
            "SELECT c.name, c.level, c.logout_time FROM characters c JOIN realmd.account a ON a.id = c.account \
             WHERE a.username = UPPER('{account}') ORDER BY c.logout_time DESC, c.guid DESC"
        ),
    )?;
    Ok(out
        .lines()
        .filter_map(|l| {
            let mut f = l.split('\t');
            Some(Save {
                name: f.next()?.to_string(),
                level: f.next()?.parse().ok()?,
                last: f.next()?.parse().ok()?,
            })
        })
        .collect())
}

/// A name as WoW takes it: 2-12 letters, first capital, the rest lower case.
pub fn clean_name(raw: &str) -> Option<String> {
    let letters: String = raw.chars().filter(char::is_ascii_alphabetic).take(12).collect();
    if letters.len() < 2 {
        return None;
    }
    let mut out = letters[..1].to_ascii_uppercase();
    out.push_str(&letters[1..].to_ascii_lowercase());
    Some(out)
}

/// Ask for a new save's name (the Minecraft username, cleaned, is offered); `None` = cancelled.
/// Names WoW reserves are only refused by the server when WoW creates the character.
pub fn ask_new_name(inst: &Install, db: &Db, account: &str) -> anyhow::Result<Option<String>> {
    let taken = list(db, account)?;
    let suggestion = minecraft::username(inst).as_deref().and_then(clean_name);
    ui::say("A new save: a new Blockborn character with its own Minecraft world.");
    loop {
        let prompt = match &suggestion {
            Some(s) => format!("Character name (Enter = {s}, \"cancel\" to go back):"),
            None => "Character name (\"cancel\" to go back):".to_string(),
        };
        let typed = ui::ask(&prompt)?;
        if typed.eq_ignore_ascii_case("cancel") {
            return Ok(None);
        }
        let raw = if typed.is_empty() {
            match &suggestion {
                Some(s) => s.clone(),
                None => continue,
            }
        } else {
            typed
        };
        let Some(name) = clean_name(&raw).filter(|n| n.eq_ignore_ascii_case(&raw)) else {
            ui::warn("2-12 letters, nothing else.");
            continue;
        };
        if taken.iter().any(|s| s.name.eq_ignore_ascii_case(&name)) {
            ui::warn(&format!("You already have {name}."));
            continue;
        }
        let elsewhere = db.query(
            Some("characters"),
            &format!("SELECT COUNT(*) FROM characters WHERE name = '{name}'"),
        )?;
        if elsewhere != "0" {
            ui::warn(&format!("{name} is taken on this server."));
            continue;
        }
        return Ok(Some(name));
    }
}

/// "Choose a save": the account's characters; the pick becomes `settings.save`.
pub fn choose(inst: &Install) -> anyhow::Result<()> {
    let s = inst.settings();
    let Some(account) = account(&s) else {
        anyhow::bail!("no account yet - run setup");
    };
    let db = Db::new(inst, &s);
    db.start()?;
    let saves = list(&db, &account);
    db.stop()?;
    let saves = saves?;
    if saves.is_empty() {
        ui::say("No saves yet - pick \"New save\".");
        return Ok(());
    }
    let mut labels: Vec<String> = saves.iter().map(Save::label).collect();
    labels.push("Back".into());
    let refs: Vec<&str> = labels.iter().map(String::as_str).collect();
    let pick = ui::choose("Which save?", &refs)?;
    if let Some(save) = saves.get(pick) {
        let mut s = inst.settings();
        s.save = Some(save.name.clone());
        inst.save_settings(&s)?;
        ui::ok(&format!("{} is the save Play starts", save.name));
    }
    Ok(())
}

/// "Delete a save": the character (from the server) and its Minecraft world, after the name is
/// typed again. Permanent.
pub fn delete(inst: &Install) -> anyhow::Result<()> {
    let s = inst.settings();
    let Some(account) = account(&s) else {
        anyhow::bail!("no account yet - run setup");
    };
    let db = Db::new(inst, &s);
    db.start()?;
    let r = (|| -> anyhow::Result<()> {
        let saves = list(&db, &account)?;
        if saves.is_empty() {
            ui::say("No saves to delete.");
            return Ok(());
        }
        let mut labels: Vec<String> = saves.iter().map(Save::label).collect();
        labels.push("Back".into());
        let refs: Vec<&str> = labels.iter().map(String::as_str).collect();
        let Some(save) = saves.get(ui::choose("Delete which save?", &refs)?) else {
            return Ok(());
        };
        let world = world_dir(inst, &save.name);
        ui::warn(&format!(
            "This deletes {} for good: the WoW character (level {}) and its Minecraft world{}.",
            save.name,
            save.level,
            world
                .as_ref()
                .map(|w| format!(" ({})", w.display()))
                .unwrap_or_default()
        ));
        if ui::ask(&format!("Type {} to delete it:", save.name))? != save.name {
            ui::say("Not deleted.");
            return Ok(());
        }
        let mut srv = server::Server::start(inst)?;
        let erased = (|| -> anyhow::Result<()> {
            srv.wait_ready()?;
            srv.command(&format!("character erase {}", save.name))?;
            let t0 = std::time::Instant::now();
            while t0.elapsed() < Duration::from_secs(15) {
                let n = db.query(
                    Some("characters"),
                    &format!("SELECT COUNT(*) FROM characters WHERE name = '{}'", save.name),
                )?;
                if n == "0" {
                    return Ok(());
                }
                std::thread::sleep(Duration::from_millis(500));
            }
            anyhow::bail!(
                "the server didn't delete {} - see {}",
                save.name,
                inst.run_dir().join("mangosd.out").display()
            )
        })();
        srv.stop();
        erased?;
        ui::ok(&format!("WoW character {} deleted", save.name));
        if let Some(w) = world {
            std::fs::remove_dir_all(&w)?;
            ui::ok(&format!("Minecraft world {} deleted", w.display()));
        }
        let mut s = inst.settings();
        if s.save.as_deref().is_some_and(|n| n.eq_ignore_ascii_case(&save.name)) {
            s.save = None;
            inst.save_settings(&s)?;
        }
        Ok(())
    })();
    db.stop()?;
    r
}

/// The save's Minecraft world folder, if it exists (names match ignoring case).
fn world_dir(inst: &Install, name: &str) -> Option<PathBuf> {
    let saves = minecraft::game_dir(inst)?.join("saves");
    std::fs::read_dir(saves)
        .ok()?
        .filter_map(|e| e.ok())
        .find(|e| e.file_name().to_string_lossy().eq_ignore_ascii_case(name))
        .map(|e| e.path())
}

#[cfg(test)]
mod tests {
    use super::clean_name;

    #[test]
    fn names_follow_wows_rules() {
        assert_eq!(clean_name("Cripey").as_deref(), Some("Cripey"));
        assert_eq!(clean_name("xX_Steve_99Xx").as_deref(), Some("Xxstevexx"));
        assert_eq!(clean_name("notch").as_deref(), Some("Notch"));
        assert_eq!(clean_name("a_very_long_minecraft_name").as_deref(), Some("Averylongmin"));
        assert_eq!(clean_name("x1"), None);
        assert_eq!(clean_name(""), None);
    }
}
