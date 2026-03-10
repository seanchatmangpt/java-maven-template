//! Guard System - Template-level quality guards for Java Maven projects
//!
//! Simplified H-Guards implementation for the java-maven-template.
//! Detects 3 forbidden patterns: H_TODO, H_MOCK, H_STUB

use clap::{Parser, Subcommand};
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use walkdir::WalkDir;

/// Guard patterns to detect
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum GuardPattern {
    /// Deferred work markers (TODO, FIXME, etc.)
    HTodo,
    /// Mock/stub/fake implementations
    HMock,
    /// Empty/placeholder returns
    HStub,
}

impl GuardPattern {
    fn as_str(&self) -> &'static str {
        match self {
            GuardPattern::HTodo => "H_TODO",
            GuardPattern::HMock => "H_MOCK",
            GuardPattern::HStub => "H_STUB",
        }
    }

    fn fix_guidance(&self) -> &'static str {
        match self {
            GuardPattern::HTodo => "Implement real logic or throw UnsupportedOperationException",
            GuardPattern::HMock => "Delete mock/stub/fake or implement real service",
            GuardPattern::HStub => "Replace empty return with real implementation or throw UnsupportedOperationException",
        }
    }
}

/// A single guard violation
#[derive(Debug, Serialize, Deserialize)]
struct Violation {
    pattern: String,
    severity: String,
    file: String,
    line: usize,
    content: String,
    fix_guidance: String,
}

/// Guard validation receipt
#[derive(Debug, Serialize, Deserialize)]
struct GuardReceipt {
    phase: String,
    timestamp: String,
    files_scanned: usize,
    violations: Vec<Violation>,
    status: String,
    summary: Summary,
}

#[derive(Debug, Serialize, Deserialize)]
struct Summary {
    h_todo_count: usize,
    h_mock_count: usize,
    h_stub_count: usize,
    total_violations: usize,
}

impl GuardReceipt {
    fn new(files_scanned: usize) -> Self {
        let timestamp = chrono_lite_timestamp();
        Self {
            phase: "guards".to_string(),
            timestamp,
            files_scanned,
            violations: Vec::new(),
            status: "GREEN".to_string(),
            summary: Summary {
                h_todo_count: 0,
                h_mock_count: 0,
                h_stub_count: 0,
                total_violations: 0,
            },
        }
    }

    fn add_violation(&mut self, pattern: GuardPattern, file: &str, line: usize, content: &str) {
        let pattern_str = pattern.as_str();
        self.violations.push(Violation {
            pattern: pattern_str.to_string(),
            severity: "FAIL".to_string(),
            file: file.to_string(),
            line,
            content: content.trim().to_string(),
            fix_guidance: pattern.fix_guidance().to_string(),
        });

        match pattern {
            GuardPattern::HTodo => self.summary.h_todo_count += 1,
            GuardPattern::HMock => self.summary.h_mock_count += 1,
            GuardPattern::HStub => self.summary.h_stub_count += 1,
        }
        self.summary.total_violations += 1;
        self.status = "RED".to_string();
    }
}

/// Simple ISO-8601 timestamp without chrono dependency
fn chrono_lite_timestamp() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let duration = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let secs = duration.as_secs();
    // Convert to datetime (simplified - just format as ISO-ish)
    let days = secs / 86400;
    let years = 1970 + days / 365;
    let remaining_days = days % 365;
    let month = remaining_days / 30 + 1;
    let day = remaining_days % 30 + 1;
    let hours = (secs % 86400) / 3600;
    let mins = (secs % 3600) / 60;
    let secs = secs % 60;
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}Z",
        years, month, day, hours, mins, secs
    )
}

/// Guard detector with regex patterns
struct GuardDetector {
    todo_pattern: Regex,
    mock_pattern: Regex,
    mock_class_pattern: Regex,
    stub_pattern: Regex,
}

impl GuardDetector {
    fn new() -> Self {
        Self {
            // H_TODO: Deferred work markers in comments
            todo_pattern: Regex::new(
                r"//\s*(TODO|FIXME|XXX|HACK|LATER|FUTURE|@incomplete|@unimplemented|placeholder|not\s+implemented\s+yet)"
            ).expect("Invalid TODO regex"),

            // H_MOCK: Mock method/variable names
            mock_pattern: Regex::new(
                r"(mock|stub|fake|demo)[A-Z][a-zA-Z]*\s*[=(]"
            ).expect("Invalid MOCK regex"),

            // H_MOCK_CLASS: Mock class declarations
            mock_class_pattern: Regex::new(
                r"(class|interface)\s+(Mock|Stub|Fake|Demo)[A-Za-z]*\s+(implements|extends|\{)"
            ).expect("Invalid MOCK_CLASS regex"),

            // H_STUB: Empty string/null returns with stub comments
            stub_pattern: Regex::new(
                r#"return\s+""\s*;|return\s+null\s*;.*//.*(stub|placeholder)|return\s+0\s*;.*//.*stub"#
            ).expect("Invalid STUB regex"),
        }
    }

    fn check_file(&self, path: &Path, receipt: &mut GuardReceipt) {
        let content = match std::fs::read_to_string(path) {
            Ok(c) => c,
            Err(_) => return,
        };

        let file_str = path.to_string_lossy();

        for (line_num, line) in content.lines().enumerate() {
            let line_num = line_num + 1; // 1-indexed

            // Check H_TODO
            if self.todo_pattern.is_match(line) {
                receipt.add_violation(
                    GuardPattern::HTodo,
                    &file_str,
                    line_num,
                    line,
                );
            }

            // Check H_MOCK (method names)
            if self.mock_pattern.is_match(line) {
                receipt.add_violation(
                    GuardPattern::HMock,
                    &file_str,
                    line_num,
                    line,
                );
            }

            // Check H_MOCK_CLASS
            if self.mock_class_pattern.is_match(line) {
                receipt.add_violation(
                    GuardPattern::HMock,
                    &file_str,
                    line_num,
                    line,
                );
            }

            // Check H_STUB
            if self.stub_pattern.is_match(line) {
                receipt.add_violation(
                    GuardPattern::HStub,
                    &file_str,
                    line_num,
                    line,
                );
            }
        }
    }
}

#[derive(Parser)]
#[command(name = "dx-guard")]
#[command(about = "Template-level quality guards for Java Maven projects")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Run guard validation on files or directories
    Validate {
        /// Paths to scan (files or directories)
        #[arg(required = true)]
        paths: Vec<PathBuf>,

        /// Output receipt file path
        #[arg(long, short = 'o')]
        output: Option<PathBuf>,

        /// Output format (json, text)
        #[arg(long, default_value = "text")]
        format: String,

        /// Exclude patterns (glob)
        #[arg(long = "exclude")]
        excludes: Vec<String>,
    },

    /// Validate a single file (hook mode - reads from stdin for file path)
    Hook {
        /// File path to validate
        #[arg(long, short = 'f')]
        file: PathBuf,
    },

    /// Show guard pattern documentation
    Info,
}

fn main() {
    let cli = Cli::parse();

    match cli.command {
        Commands::Validate { paths, output, format, excludes } => {
            let detector = GuardDetector::new();
            let mut receipt = GuardReceipt::new(0);
            let mut files_scanned = 0;

            for path in &paths {
                if path.is_file() {
                    if should_check_file(path, &excludes) {
                        files_scanned += 1;
                        detector.check_file(path, &mut receipt);
                    }
                } else if path.is_dir() {
                    for entry in WalkDir::new(path)
                        .into_iter()
                        .filter_map(|e| e.ok())
                    {
                        let p = entry.path();
                        if p.is_file() && p.extension().map_or(false, |e| e == "java") {
                            if should_check_file(p, &excludes) {
                                files_scanned += 1;
                                detector.check_file(p, &mut receipt);
                            }
                        }
                    }
                }
            }

            receipt.files_scanned = files_scanned;

            match format.as_str() {
                "json" => {
                    let json = serde_json::to_string_pretty(&receipt).unwrap();
                    if let Some(out) = output {
                        std::fs::write(&out, &json).expect("Failed to write output");
                        println!("Receipt written to: {}", out.display());
                    } else {
                        println!("{}", json);
                    }
                }
                _ => {
                    // Text output
                    if receipt.status == "GREEN" {
                        println!("[PASS] No violations in {} files", files_scanned);
                    } else {
                        eprintln!("[FAIL] {} violations in {} files",
                            receipt.summary.total_violations, files_scanned);
                        for v in &receipt.violations {
                            eprintln!("  {} {}:{}: {}",
                                v.pattern, v.file, v.line, v.content);
                        }
                    }
                }
            }

            // Exit code
            std::process::exit(if receipt.status == "GREEN" { 0 } else { 2 });
        }

        Commands::Hook { file } => {
            if !file.is_file() || file.extension().map_or(true, |e| e != "java") {
                std::process::exit(0);
            }

            let detector = GuardDetector::new();
            let mut receipt = GuardReceipt::new(1);
            detector.check_file(&file, &mut receipt);

            if receipt.status == "RED" {
                eprintln!("");
                eprintln!("╔═══════════════════════════════════════════════════════════════════╗");
                eprintln!("║  🚨 QUALITY GUARD VIOLATION DETECTED                              ║");
                eprintln!("╚═══════════════════════════════════════════════════════════════════╝");
                eprintln!("");
                eprintln!("File: {}", file.display());
                eprintln!("");

                for v in &receipt.violations {
                    eprintln!("❌ {} at line {}:", v.pattern, v.line);
                    eprintln!("   {}", v.content);
                    eprintln!("   Fix: {}", v.fix_guidance);
                    eprintln!("");
                }

                std::process::exit(2);
            }

            std::process::exit(0);
        }

        Commands::Info => {
            println!("Guard System - Template-level Quality Guards");
            println!("");
            println!("Patterns detected:");
            println!("  H_TODO  - Deferred work markers (TODO, FIXME, XXX, HACK, etc.)");
            println!("  H_MOCK  - Mock/stub/fake implementations");
            println!("  H_STUB  - Empty/placeholder returns");
            println!("");
            println!("Exit codes:");
            println!("  0 - GREEN (no violations)");
            println!("  2 - RED (violations found)");
            println!("");
            println!("Usage:");
            println!("  dx-guard validate src/ --format json -o receipt.json");
            println!("  dx-guard hook --file src/Main.java");
        }
    }
}

/// Check if file should be validated (apply exclusions)
fn should_check_file(path: &Path, excludes: &[String]) -> bool {
    let path_str = path.to_string_lossy();

    // Default exclusions for test directories
    let default_excludes = [
        "/test/",
        "/tests/",
        "Test.java",
        "IT.java",  // Integration tests
    ];

    for exclude in default_excludes.iter() {
        if path_str.contains(exclude) {
            return false;
        }
    }

    // Custom exclusions
    for pattern in excludes {
        if path_str.contains(pattern) {
            return false;
        }
    }

    true
}
