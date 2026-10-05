//! Terminal output and questions. Plain text on Windows (the old console shows ANSI codes raw).

use std::io::{BufRead, Write};

fn paint(code: &str, s: &str) -> String {
    if cfg!(windows) {
        s.to_string()
    } else {
        format!("\x1b[{code}m{s}\x1b[0m")
    }
}

pub fn title(s: &str) {
    println!("{}", paint("1", s));
}
pub fn say(s: &str) {
    println!("{s}");
}
pub fn step(s: &str) {
    println!("\n{}", paint("1", &format!("== {s}")));
}
pub fn ok(s: &str) {
    println!("{} {s}", paint("32", "OK"));
}
pub fn warn(s: &str) {
    println!("{} {s}", paint("33", "!"));
}
pub fn fail(s: &str) {
    eprintln!("{} {s}", paint("31", "ERROR:"));
}

/// One line from the player (trimmed).
pub fn ask(prompt: &str) -> anyhow::Result<String> {
    print!("{prompt} ");
    std::io::stdout().flush()?;
    let mut line = String::new();
    anyhow::ensure!(
        std::io::stdin().lock().read_line(&mut line)? > 0,
        "input closed"
    );
    Ok(line.trim().to_string())
}

pub fn ask_password(prompt: &str) -> anyhow::Result<String> {
    Ok(rpassword::prompt_password(format!("{prompt} "))?)
}

pub fn yes_no(prompt: &str, default_yes: bool) -> anyhow::Result<bool> {
    let a = ask(&format!(
        "{prompt} [{}]",
        if default_yes { "Y/n" } else { "y/N" }
    ))?;
    Ok(match a.chars().next() {
        None => default_yes,
        Some(c) => c == 'y' || c == 'Y',
    })
}

/// A numbered list; returns the index picked.
pub fn choose(prompt: &str, options: &[&str]) -> anyhow::Result<usize> {
    say(prompt);
    for (i, o) in options.iter().enumerate() {
        say(&format!("  {}. {o}", i + 1));
    }
    loop {
        if let Ok(n) = ask(">")?.parse::<usize>()
            && (1..=options.len()).contains(&n)
        {
            return Ok(n - 1);
        }
        warn(&format!("Type a number from 1 to {}.", options.len()));
    }
}

/// A window opened by double-clicking closes with the program; keep the last words readable.
pub fn pause_on_windows() {
    if cfg!(windows) {
        let _ = ask("Press Enter to close.");
    }
}
