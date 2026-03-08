//! Pattern API Extractor — feeds Java pattern APIs to ggen as template variables.
//!
//! # Purpose
//!
//! This Rust tool is the **primary pipeline** for ggen's pattern-generator capability:
//!
//! ```text
//! Java source → [pattern-api-extractor] → ggen vars → [ggen sync] → generated patterns
//! ```
//!
//! It reads Java source files, extracts:
//! - Sealed type hierarchies (name, variants, component fields)
//! - Record definitions (component fields, compact constructor rules)
//! - Public method signatures (return type, name, parameters)
//! - Module exports (from module-info.java)
//!
//! And outputs structured data as JSON or TOML, suitable as ggen template variables.
//!
//! # Usage
//!
//! ```bash
//! # Extract sealed type from a Java file → JSON
//! pattern-api-extractor extract sealed src/main/java/org/acme/Result.java
//!
//! # Extract all public methods from a file → JSON
//! pattern-api-extractor extract api src/main/java/org/acme/dogfood/core/GathererPatterns.java
//!
//! # Convert extracted JSON to ggen TOML vars for a specific template
//! pattern-api-extractor to-ggen-vars extracted.json --template core/record
//!
//! # Walk entire source tree, extract all patterns, write .ggen_vars/
//! pattern-api-extractor generate-all src/main/java
//! ```

use clap::{Parser, Subcommand, ValueEnum};
use colored::Colorize;
use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use walkdir::WalkDir;

// =============================================================================
// CLI definition
// =============================================================================

#[derive(Parser, Debug)]
#[command(
    name = "pattern-api-extractor",
    version = "0.1.0",
    about = "Extracts Java pattern APIs and feeds them to ggen as template variables",
    long_about = "Reads Java source files, extracts sealed hierarchies/records/APIs, \
                  and outputs ggen-compatible TOML variable files.\n\n\
                  This is the primary pipeline: Java source → ggen vars → generated patterns."
)]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand, Debug)]
enum Commands {
    /// Extract pattern APIs from a Java source file
    Extract {
        /// What to extract from the file
        #[arg(value_enum)]
        kind: ExtractKind,
        /// Path to the Java source file
        file: PathBuf,
        /// Output format (default: json)
        #[arg(short, long, default_value = "json")]
        format: OutputFormat,
    },
    /// Convert extracted JSON to ggen TOML template variables
    ToGgenVars {
        /// Path to the extracted JSON file (or - for stdin)
        input: PathBuf,
        /// ggen template to target (e.g., core/record, concurrency/virtual-thread)
        #[arg(short, long)]
        template: String,
        /// Output file (default: stdout)
        #[arg(short, long)]
        output: Option<PathBuf>,
    },
    /// Walk a source directory, extract all patterns, write .ggen_vars/
    GenerateAll {
        /// Root source directory to walk (e.g., src/main/java)
        source_dir: PathBuf,
        /// Output directory for .toml var files (default: .ggen_vars)
        #[arg(short, long, default_value = ".ggen_vars")]
        output_dir: PathBuf,
        /// Only process files matching this pattern (e.g., "dogfood")
        #[arg(short, long)]
        filter: Option<String>,
    },
    /// Show extraction summary for a Java source directory
    Inspect {
        /// Directory to inspect
        dir: PathBuf,
    },
}

#[derive(ValueEnum, Debug, Clone)]
enum ExtractKind {
    /// Extract sealed type hierarchy (name, permits, variant records)
    Sealed,
    /// Extract record component fields
    Record,
    /// Extract public method signatures
    Api,
    /// Extract all pattern information
    All,
}

#[derive(ValueEnum, Debug, Clone)]
enum OutputFormat {
    Json,
    Toml,
    Pretty,
}

// =============================================================================
// Data model — maps to ggen template variables
// =============================================================================

#[derive(Debug, Serialize, Deserialize)]
struct PatternApi {
    /// Java package (e.g., "org.acme.dogfood.concurrency")
    package: String,
    /// Class name
    name: String,
    /// Type of pattern detected
    pattern_type: PatternType,
    /// Sealed type hierarchy (if sealed interface/class)
    sealed_hierarchy: Option<SealedHierarchy>,
    /// Record components (if record)
    record_components: Vec<RecordComponent>,
    /// Public method signatures
    public_methods: Vec<MethodSignature>,
    /// Javadoc comment (first paragraph)
    javadoc: Option<String>,
    /// Template source file
    source_file: String,
}

#[derive(Debug, Serialize, Deserialize, PartialEq)]
enum PatternType {
    SealedInterface,
    SealedClass,
    Record,
    FinalUtility,
    Actor,
    Unknown,
}

#[derive(Debug, Serialize, Deserialize)]
struct SealedHierarchy {
    /// The sealed type name
    type_name: String,
    /// Permitted subtypes
    variants: Vec<SealedVariant>,
}

#[derive(Debug, Serialize, Deserialize)]
struct SealedVariant {
    name: String,
    is_record: bool,
    components: Vec<RecordComponent>,
}

#[derive(Debug, Serialize, Deserialize)]
struct RecordComponent {
    field_type: String,
    name: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct MethodSignature {
    modifiers: Vec<String>,
    return_type: String,
    name: String,
    parameters: Vec<RecordComponent>,
    throws: Vec<String>,
}

// =============================================================================
// Extraction logic
// =============================================================================

struct JavaExtractor {
    // Compiled regexes for Java pattern detection
    package_re: Regex,
    sealed_interface_re: Regex,
    sealed_class_re: Regex,
    record_re: Regex,
    permits_re: Regex,
    public_method_re: Regex,
    javadoc_re: Regex,
    inner_record_re: Regex,
}

impl JavaExtractor {
    fn new() -> Self {
        Self {
            package_re: Regex::new(r"^package\s+([\w.]+)\s*;").unwrap(),
            sealed_interface_re: Regex::new(
                r"public\s+sealed\s+interface\s+(\w+)(?:<[^>]+>)?\s+permits\s+([\w,\s.]+)\s*\{"
            ).unwrap(),
            sealed_class_re: Regex::new(
                r"public\s+sealed\s+(?:abstract\s+)?class\s+(\w+)(?:<[^>]+>)?\s+permits\s+([\w,\s.]+)"
            ).unwrap(),
            record_re: Regex::new(
                r"(?:public\s+)?record\s+(\w+)\s*\(([^)]*)\)\s+(?:implements\s+[\w<>, .]+\s*)?\{"
            ).unwrap(),
            permits_re: Regex::new(r"permits\s+([\w,\s.]+?)\s*\{").unwrap(),
            public_method_re: Regex::new(
                r"(?:public|protected)\s+(?:static\s+)?(?:final\s+)?(?:<[^>]+>\s+)?(\S+)\s+(\w+)\s*\(([^)]*)\)(?:\s+throws\s+([\w,\s]+))?\s*\{"
            ).unwrap(),
            javadoc_re: Regex::new(r"/\*\*\s*(.*?)\s*\*/").unwrap(),
            inner_record_re: Regex::new(
                r"\s+record\s+(\w+)\s*\(([^)]*)\)\s+implements\s+([\w<>, ]+)"
            ).unwrap(),
        }
    }

    fn extract(&self, source: &str, file_path: &str) -> PatternApi {
        let package = self.extract_package(source);
        let name = self.extract_class_name(source, file_path);
        let javadoc = self.extract_first_javadoc(source);
        let sealed_hierarchy = self.extract_sealed_hierarchy(source);
        let record_components = self.extract_record_components(source);
        let public_methods = self.extract_public_methods(source);

        let pattern_type = self.detect_pattern_type(source, &sealed_hierarchy, &record_components);

        PatternApi {
            package,
            name,
            pattern_type,
            sealed_hierarchy,
            record_components,
            public_methods,
            javadoc,
            source_file: file_path.to_string(),
        }
    }

    fn extract_package(&self, source: &str) -> String {
        for line in source.lines() {
            if let Some(cap) = self.package_re.captures(line) {
                return cap[1].to_string();
            }
        }
        String::new()
    }

    fn extract_class_name(&self, source: &str, file_path: &str) -> String {
        // Try to get from file name first
        Path::new(file_path)
            .file_stem()
            .and_then(|s| s.to_str())
            .unwrap_or("Unknown")
            .to_string()
    }

    fn extract_first_javadoc(&self, source: &str) -> Option<String> {
        // Find first /** ... */ block and extract first sentence
        let start = source.find("/**")?;
        let end = source[start..].find("*/")?;
        let doc = &source[start..start + end + 2];
        let cleaned: String = doc
            .lines()
            .map(|l| l.trim().trim_start_matches('*').trim())
            .filter(|l| !l.is_empty() && !l.starts_with('@') && !l.starts_with("/**") && !l.starts_with("*/"))
            .take(3)
            .collect::<Vec<_>>()
            .join(" ");
        if cleaned.is_empty() { None } else { Some(cleaned) }
    }

    fn extract_sealed_hierarchy(&self, source: &str) -> Option<SealedHierarchy> {
        // Try sealed interface
        if let Some(cap) = self.sealed_interface_re.captures(source)
            .or_else(|| self.sealed_class_re.captures(source))
        {
            let type_name = cap[1].to_string();
            let permits_str = &cap[2];
            let permits: Vec<String> = permits_str
                .split(',')
                .map(|s| {
                    let clean = s.trim();
                    // Strip outer type prefix if it matches type_name
                    if clean.contains('.') {
                        clean.split('.').last().unwrap_or(clean).to_string()
                    } else {
                        clean.to_string()
                    }
                })
                .filter(|s| !s.is_empty())
                .collect();

            let variants = permits
                .iter()
                .map(|variant_name| {
                    let components = self.extract_inner_record_components(source, variant_name);
                    SealedVariant {
                        name: variant_name.clone(),
                        is_record: self.is_inner_record(source, variant_name),
                        components,
                    }
                })
                .collect();

            return Some(SealedHierarchy { type_name, variants });
        }
        None
    }

    fn is_inner_record(&self, source: &str, type_name: &str) -> bool {
        let pattern = format!(r"record\s+{}\s*\(", regex::escape(type_name));
        Regex::new(&pattern).map(|re| re.is_match(source)).unwrap_or(false)
    }

    fn extract_inner_record_components(&self, source: &str, type_name: &str) -> Vec<RecordComponent> {
        let pattern = format!(r"record\s+{}\s*\(([^)]*)\)", regex::escape(type_name));
        if let Ok(re) = Regex::new(&pattern) {
            if let Some(cap) = re.captures(source) {
                return self.parse_param_list(&cap[1]);
            }
        }
        vec![]
    }

    fn extract_record_components(&self, source: &str) -> Vec<RecordComponent> {
        // Find the top-level record declaration (first match)
        if let Some(cap) = self.record_re.captures(source) {
            return self.parse_param_list(&cap[2]);
        }
        vec![]
    }

    fn extract_public_methods(&self, source: &str) -> Vec<MethodSignature> {
        let mut methods = vec![];
        for cap in self.public_method_re.captures_iter(source) {
            let modifiers = vec!["public".to_string()];
            let return_type = cap[1].to_string();
            let name = cap[2].to_string();
            let params = self.parse_param_list(&cap[3]);
            let throws: Vec<String> = cap
                .get(4)
                .map(|t| t.as_str().split(',').map(|s| s.trim().to_string()).collect())
                .unwrap_or_default();

            // Skip constructors and common noise
            if !["void", "class", "interface", "record", "if", "for", "while", "switch"]
                .contains(&return_type.as_str())
            {
                methods.push(MethodSignature {
                    modifiers,
                    return_type,
                    name,
                    parameters: params,
                    throws,
                });
            }
        }
        methods
    }

    fn parse_param_list(&self, params_str: &str) -> Vec<RecordComponent> {
        params_str
            .split(',')
            .filter_map(|p| {
                let p = p.trim();
                if p.is_empty() { return None; }
                // Handle "Type name" or "Type<T> name"
                let parts: Vec<&str> = p.rsplitn(2, ' ').collect();
                if parts.len() == 2 {
                    Some(RecordComponent {
                        name: parts[0].trim().to_string(),
                        field_type: parts[1].trim().to_string(),
                    })
                } else {
                    None
                }
            })
            .collect()
    }

    fn detect_pattern_type(
        &self,
        source: &str,
        sealed: &Option<SealedHierarchy>,
        record_comps: &[RecordComponent],
    ) -> PatternType {
        if source.contains("public sealed interface") { return PatternType::SealedInterface; }
        if source.contains("public sealed") && source.contains("class") { return PatternType::SealedClass; }
        if source.contains("public record ") || !record_comps.is_empty() { return PatternType::Record; }
        if source.contains("public final class") && source.contains("private") && source.contains("() {}") {
            return PatternType::FinalUtility;
        }
        PatternType::Unknown
    }
}

// =============================================================================
// ggen variable generation
// =============================================================================

fn to_ggen_vars(api: &PatternApi, template: &str) -> toml::Value {
    let mut vars = toml::map::Map::new();
    vars.insert("name".into(), toml::Value::String(api.name.clone()));
    vars.insert("package".into(), toml::Value::String(api.package.clone()));
    vars.insert("output_dir".into(), toml::Value::String("src/main/java".into()));

    if let Some(doc) = &api.javadoc {
        vars.insert("javadoc".into(), toml::Value::String(doc.clone()));
    }

    // Add sealed hierarchy as variants array
    if let Some(hierarchy) = &api.sealed_hierarchy {
        let variants: Vec<toml::Value> = hierarchy.variants.iter().map(|v| {
            let mut variant_map = toml::map::Map::new();
            variant_map.insert("name".into(), toml::Value::String(v.name.clone()));
            let components: Vec<toml::Value> = v.components.iter().map(|c| {
                let mut comp_map = toml::map::Map::new();
                comp_map.insert("type".into(), toml::Value::String(c.field_type.clone()));
                comp_map.insert("name".into(), toml::Value::String(c.name.clone()));
                toml::Value::Table(comp_map)
            }).collect();
            variant_map.insert("components".into(), toml::Value::Array(components));
            toml::Value::Table(variant_map)
        }).collect();
        vars.insert("variants".into(), toml::Value::Array(variants));
    }

    // Add common methods from public API
    let methods: Vec<toml::Value> = api.public_methods.iter().take(10).map(|m| {
        let mut method_map = toml::map::Map::new();
        method_map.insert("return_type".into(), toml::Value::String(m.return_type.clone()));
        method_map.insert("name".into(), toml::Value::String(m.name.clone()));
        let params: Vec<toml::Value> = m.parameters.iter().map(|p| {
            let mut pm = toml::map::Map::new();
            pm.insert("type".into(), toml::Value::String(p.field_type.clone()));
            pm.insert("name".into(), toml::Value::String(p.name.clone()));
            toml::Value::Table(pm)
        }).collect();
        method_map.insert("params".into(), toml::Value::Array(params));
        toml::Value::Table(method_map)
    }).collect();
    vars.insert("common_methods".into(), toml::Value::Array(methods));

    // Add template-specific metadata
    let mut meta = toml::map::Map::new();
    meta.insert("template".into(), toml::Value::String(template.into()));
    meta.insert("source_file".into(), toml::Value::String(api.source_file.clone()));
    meta.insert("pattern_type".into(), toml::Value::String(format!("{:?}", api.pattern_type)));
    vars.insert("_meta".into(), toml::Value::Table(meta));

    toml::Value::Table(vars)
}

// =============================================================================
// Command implementations
// =============================================================================

fn cmd_extract(kind: &ExtractKind, file: &Path, format: &OutputFormat) {
    let source = match fs::read_to_string(file) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("{} {}: {}", "Error reading".red(), file.display(), e);
            std::process::exit(1);
        }
    };

    let extractor = JavaExtractor::new();
    let api = extractor.extract(&source, &file.display().to_string());

    let filter_api = match kind {
        ExtractKind::Sealed => {
            let mut filtered = api;
            filtered.record_components.clear();
            filtered.public_methods.clear();
            filtered
        }
        ExtractKind::Record => {
            let mut filtered = api;
            filtered.sealed_hierarchy = None;
            filtered.public_methods.clear();
            filtered
        }
        ExtractKind::Api => {
            let mut filtered = api;
            filtered.sealed_hierarchy = None;
            filtered.record_components.clear();
            filtered
        }
        ExtractKind::All => api,
    };

    match format {
        OutputFormat::Json => println!("{}", serde_json::to_string_pretty(&filter_api).unwrap()),
        OutputFormat::Toml => {
            let vars = to_ggen_vars(&filter_api, "auto-detect");
            println!("{}", toml::to_string_pretty(&vars).unwrap());
        }
        OutputFormat::Pretty => {
            println!("{}", "Pattern API Extraction".blue().bold());
            println!("File:    {}", file.display());
            println!("Package: {}", filter_api.package);
            println!("Name:    {}", filter_api.name);
            println!("Type:    {:?}", filter_api.pattern_type);
            if let Some(h) = &filter_api.sealed_hierarchy {
                println!("\n{}", "Sealed Hierarchy:".yellow());
                println!("  {} permits:", h.type_name);
                for v in &h.variants {
                    println!("    {} {} ({})", if v.is_record { "record" } else { "class" }, v.name,
                        v.components.iter().map(|c| format!("{} {}", c.field_type, c.name)).collect::<Vec<_>>().join(", "));
                }
            }
            if !filter_api.public_methods.is_empty() {
                println!("\n{}", "Public Methods:".yellow());
                for m in &filter_api.public_methods {
                    let params = m.parameters.iter()
                        .map(|p| format!("{} {}", p.field_type, p.name))
                        .collect::<Vec<_>>().join(", ");
                    println!("  {} {}({})", m.return_type, m.name, params);
                }
            }
        }
    }
}

fn cmd_to_ggen_vars(input: &Path, template: &str, output: &Option<PathBuf>) {
    let json_str = match fs::read_to_string(input) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("{} {}: {}", "Error reading".red(), input.display(), e);
            std::process::exit(1);
        }
    };

    let api: PatternApi = match serde_json::from_str(&json_str) {
        Ok(a) => a,
        Err(e) => {
            eprintln!("{}: {}", "Failed to parse JSON".red(), e);
            std::process::exit(1);
        }
    };

    let vars = to_ggen_vars(&api, template);
    let toml_str = toml::to_string_pretty(&vars).unwrap();

    match output {
        Some(path) => {
            if let Some(parent) = path.parent() {
                fs::create_dir_all(parent).ok();
            }
            fs::write(path, &toml_str).expect("Failed to write output file");
            eprintln!("{} {}", "Written:".green(), path.display());
        }
        None => print!("{}", toml_str),
    }
}

fn cmd_generate_all(source_dir: &Path, output_dir: &Path, filter: &Option<String>) {
    println!("{}", "Pattern API Extractor — Generate All".blue().bold());
    println!("Source: {}", source_dir.display());
    println!("Output: {}", output_dir.display());
    println!();

    fs::create_dir_all(output_dir).expect("Failed to create output directory");
    let extractor = JavaExtractor::new();
    let mut processed = 0;
    let mut skipped = 0;

    for entry in WalkDir::new(source_dir)
        .into_iter()
        .filter_map(|e| e.ok())
        .filter(|e| e.path().extension().map(|x| x == "java").unwrap_or(false))
    {
        let path = entry.path();

        // Apply filter
        if let Some(f) = filter {
            if !path.to_string_lossy().contains(f.as_str()) {
                skipped += 1;
                continue;
            }
        }

        let source = match fs::read_to_string(path) {
            Ok(s) => s,
            Err(_) => { skipped += 1; continue; }
        };

        let api = extractor.extract(&source, &path.display().to_string());

        // Skip module-info
        if api.name == "module-info" { continue; }

        // Detect template category from path
        let template = detect_template(&path.to_string_lossy());

        // Convert to ggen vars
        let vars = to_ggen_vars(&api, &template);
        let toml_str = toml::to_string_pretty(&vars).unwrap();

        // Write to output directory
        let out_file = output_dir.join(format!("{}.toml", api.name));
        fs::write(&out_file, &toml_str).expect("Failed to write var file");

        println!("  {} {} → {} [{}]",
            "extracted".green(),
            path.file_name().unwrap_or_default().to_string_lossy(),
            out_file.display(),
            template);
        processed += 1;
    }

    println!();
    println!("Summary: {} files extracted, {} skipped", processed, skipped);
    println!();
    println!("{}", "Next steps:".yellow().bold());
    println!("  For each .toml in {}/:", output_dir.display());
    println!("  bin/jgen generate -t <template> -n <Name> -p <package>");
    println!();
    println!("Or use bin/feed-patterns to run the full pipeline automatically.");
}

fn detect_template(path: &str) -> String {
    if path.contains("/concurrency/") {
        if path.contains("ScopedValue") { "concurrency/scoped-value".to_string() }
        else if path.contains("StructuredTask") { "concurrency/structured-task-scope".to_string() }
        else { "concurrency/virtual-thread".to_string() }
    } else if path.contains("/core/") {
        if path.contains("Gatherer") { "core/gatherer".to_string() }
        else if path.contains("Pattern") { "core/pattern-matching-switch".to_string() }
        else if path.contains("Stream") { "core/stream-pipeline".to_string() }
        else { "core/record".to_string() }
    } else if path.contains("/api/") {
        if path.contains("JavaTime") { "api/java-time".to_string() }
        else if path.contains("String") { "api/string-methods".to_string() }
        else if path.contains("Nio") { "api/nio2-files".to_string() }
        else { "api/http-client".to_string() }
    } else if path.contains("/errorhandling/") || path.contains("/error") {
        "error-handling/result-railway".to_string()
    } else if path.contains("/security/") {
        "security/input-validation".to_string()
    } else if path.contains("/patterns/") {
        "patterns/strategy-functional".to_string()
    } else {
        "core/record".to_string()
    }
}

fn cmd_inspect(dir: &Path) {
    println!("{}", "Pattern API Inspector".blue().bold());
    println!("Directory: {}", dir.display());
    println!();

    let extractor = JavaExtractor::new();
    let mut by_type: HashMap<String, Vec<String>> = HashMap::new();

    for entry in WalkDir::new(dir)
        .into_iter()
        .filter_map(|e| e.ok())
        .filter(|e| e.path().extension().map(|x| x == "java").unwrap_or(false))
    {
        let path = entry.path();
        if let Ok(source) = fs::read_to_string(path) {
            let api = extractor.extract(&source, &path.display().to_string());
            if api.name != "module-info" {
                let type_name = format!("{:?}", api.pattern_type);
                by_type.entry(type_name).or_default().push(api.name);
            }
        }
    }

    for (pattern_type, names) in &by_type {
        println!("{}:", pattern_type.yellow());
        for name in names {
            println!("  • {}", name);
        }
        println!();
    }

    println!("Total: {} pattern classes detected", by_type.values().map(|v| v.len()).sum::<usize>());
}

// =============================================================================
// Main entry point
// =============================================================================

fn main() {
    let cli = Cli::parse();

    match &cli.command {
        Commands::Extract { kind, file, format } => cmd_extract(kind, file, format),
        Commands::ToGgenVars { input, template, output } => cmd_to_ggen_vars(input, template, output),
        Commands::GenerateAll { source_dir, output_dir, filter } => cmd_generate_all(source_dir, output_dir, filter),
        Commands::Inspect { dir } => cmd_inspect(dir),
    }
}
