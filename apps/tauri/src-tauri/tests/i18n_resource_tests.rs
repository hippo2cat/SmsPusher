use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeSet,
    fs,
    path::{Path, PathBuf},
};

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(|path| path.parent())
        .and_then(|path| path.parent())
        .expect("repo root")
        .to_path_buf()
}

fn json_keys(relative: &str) -> BTreeSet<String> {
    read_json(relative)
        .as_object()
        .expect("locale source must be a JSON object")
        .keys()
        .cloned()
        .collect()
}

fn read_json(relative: &str) -> Value {
    let path = repo_root().join(relative);
    let data = fs::read_to_string(&path)
        .unwrap_or_else(|error| panic!("failed to read {}: {error}", path.display()));
    serde_json::from_str(&data)
        .unwrap_or_else(|error| panic!("failed to parse {}: {error}", path.display()))
}

#[test]
fn source_locale_keys_match_exactly() {
    assert_eq!(
        json_keys("locales/source/en-US.json"),
        json_keys("locales/source/zh-CN.json")
    );
}

#[test]
fn generated_i18n_resources_exist_for_all_runtime_targets() {
    let root = repo_root();
    for relative in [
        "apps/android/src/main/res/values/strings.xml",
        "apps/android/src/main/res/values-zh-rCN/strings.xml",
        "apps/tauri/src/i18n/generated/en-US.json",
        "apps/tauri/src/i18n/generated/zh-CN.json",
        "apps/tauri/src-tauri/locales/en-US/desktop.ftl",
        "apps/tauri/src-tauri/locales/zh-CN/desktop.ftl",
        "locales/generated/manifest.json",
    ] {
        assert!(root.join(relative).exists(), "{relative} should exist");
    }
}

#[test]
fn generated_android_string_names_match_between_locales() {
    let root = repo_root();
    let en = fs::read_to_string(root.join("apps/android/src/main/res/values/strings.xml")).unwrap();
    let zh = fs::read_to_string(root.join("apps/android/src/main/res/values-zh-rCN/strings.xml"))
        .unwrap();
    assert_eq!(unique_string_names(&en), unique_string_names(&zh));
}

#[test]
fn generated_react_resources_are_flat_i18next_json() {
    let en = read_json("apps/tauri/src/i18n/generated/en-US.json");
    let en = en.as_object().expect("React locale must be a JSON object");

    assert_eq!(
        en.get("tray.networkInterface"),
        Some(&Value::String("Network interface".to_owned()))
    );
    assert_eq!(
        en.get("tray.networkInterface.auto"),
        Some(&Value::String("Auto select".to_owned()))
    );
    assert_eq!(
        en.get("history.copied"),
        Some(&Value::String("{{label}} copied".to_owned()))
    );
    assert_eq!(
        en.get("history.count"),
        Some(&Value::String("{{count}} messages".to_owned()))
    );

    for (key, value) in en {
        assert!(value.is_string(), "{key} should be a flat string value");
    }
}

#[test]
fn chinese_locale_uses_localized_display_name_without_renaming_english_brand() {
    let en = read_json("locales/source/en-US.json");
    let zh = read_json("locales/source/zh-CN.json");

    assert_eq!(en.get("app.name"), Some(&Value::String("SmsPusher".to_owned())));
    assert_eq!(zh.get("app.name"), Some(&Value::String("信推推".to_owned())));

    let zh_values = zh
        .as_object()
        .expect("Chinese source locale must be a JSON object");
    for (key, value) in zh_values {
        let value = value
            .as_str()
            .unwrap_or_else(|| panic!("{key} should be a string"));
        assert!(
            !value.contains("SmsPusher"),
            "{key} should use the localized display name in Chinese UI text"
        );
    }
}

#[test]
fn generated_manifest_hashes_match_actual_files() {
    let root = repo_root();
    let manifest = read_json("locales/generated/manifest.json");
    let generated = manifest
        .get("generated")
        .and_then(Value::as_object)
        .expect("manifest generated section must be an object");
    let actual_files = generated.keys().cloned().collect::<BTreeSet<_>>();
    let expected_files = [
        "apps/android/src/main/res/values/strings.xml",
        "apps/android/src/main/res/values-zh-rCN/strings.xml",
        "apps/tauri/src/i18n/generated/en-US.json",
        "apps/tauri/src/i18n/generated/zh-CN.json",
        "apps/tauri/src-tauri/locales/en-US/desktop.ftl",
        "apps/tauri/src-tauri/locales/zh-CN/desktop.ftl",
    ]
    .into_iter()
    .map(str::to_owned)
    .collect::<BTreeSet<_>>();

    assert_eq!(
        actual_files, expected_files,
        "manifest generated file set should match runtime generated outputs exactly"
    );

    for (relative, metadata) in generated {
        let expected = metadata
            .get("sha256")
            .and_then(Value::as_str)
            .expect("generated file metadata must include sha256");
        let actual = sha256_file(&root.join(relative));
        assert_eq!(actual, expected, "{relative} hash should match manifest");
    }
}

#[test]
fn generated_android_string_names_do_not_contain_duplicates() {
    let root = repo_root();
    for relative in [
        "apps/android/src/main/res/values/strings.xml",
        "apps/android/src/main/res/values-zh-rCN/strings.xml",
    ] {
        let xml = fs::read_to_string(root.join(relative)).unwrap();
        let names = string_names(&xml);
        let unique = names.iter().collect::<BTreeSet<_>>();
        assert_eq!(
            names.len(),
            unique.len(),
            "{relative} should not contain duplicate string names"
        );
    }
}

#[test]
fn generated_fluent_messages_have_valid_ids_and_placeholders() {
    let root = repo_root();
    for relative in [
        "apps/tauri/src-tauri/locales/en-US/desktop.ftl",
        "apps/tauri/src-tauri/locales/zh-CN/desktop.ftl",
    ] {
        let ftl = fs::read_to_string(root.join(relative)).unwrap();
        validate_generated_fluent(relative, &ftl);
    }
}

#[test]
fn generated_fluent_validator_rejects_malformed_generated_syntax() {
    for invalid in [
        "Bad.Id = value",
        "bad-id value",
        "bad-id = value {code}",
        "bad-id = value { $code",
        "bad-id = value { $$code }",
        "bad-id = value { $9code }",
        "bad-id = value { $code } and }",
    ] {
        assert!(
            validate_fluent_line("test.ftl", 1, invalid).is_err(),
            "{invalid} should be rejected"
        );
    }
}

fn string_names(xml: &str) -> Vec<String> {
    xml.lines()
        .filter_map(|line| line.split("name=\"").nth(1))
        .filter_map(|tail| tail.split('"').next())
        .map(str::to_owned)
        .collect()
}

fn unique_string_names(xml: &str) -> BTreeSet<String> {
    string_names(xml).into_iter().collect()
}

fn sha256_file(path: &Path) -> String {
    let data =
        fs::read(path).unwrap_or_else(|error| panic!("failed to read {}: {error}", path.display()));
    format!("{:x}", Sha256::digest(data))
}

fn validate_generated_fluent(relative: &str, ftl: &str) {
    for (index, line) in ftl.lines().enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        validate_fluent_line(relative, index + 1, line)
            .unwrap_or_else(|error| panic!("{relative}:{}: {error}", index + 1));
    }
}

fn validate_fluent_line(relative: &str, line_number: usize, line: &str) -> Result<(), String> {
    if line.contains("$$") {
        return Err("line contains raw $$".to_owned());
    }

    let (id, value) = line.split_once(" = ").ok_or_else(|| {
        format!("{relative}:{line_number} must use single-line 'message-id = value' shape")
    })?;
    if !is_valid_fluent_id(id) {
        return Err(format!("invalid Fluent message id {id}"));
    }

    validate_fluent_placeables(value)
}

fn is_valid_fluent_id(id: &str) -> bool {
    !id.is_empty()
        && id.split('-').all(|part| {
            !part.is_empty()
                && part
                    .chars()
                    .all(|char| char.is_ascii_lowercase() || char.is_ascii_digit())
        })
}

fn validate_fluent_placeables(value: &str) -> Result<(), String> {
    let chars = value.chars().collect::<Vec<_>>();
    let mut index = 0;

    while index < chars.len() {
        match chars[index] {
            '{' => {
                let close = chars[index + 1..]
                    .iter()
                    .position(|char| *char == '}')
                    .map(|offset| index + 1 + offset)
                    .ok_or_else(|| "unbalanced opening brace".to_owned())?;
                if chars[index + 1..close].contains(&'{') {
                    return Err("nested or unbalanced opening brace".to_owned());
                }
                validate_fluent_placeable(&chars[index + 1..close])?;
                index = close + 1;
            }
            '}' => return Err("unbalanced closing brace".to_owned()),
            _ => index += 1,
        }
    }

    Ok(())
}

fn validate_fluent_placeable(expression: &[char]) -> Result<(), String> {
    if expression.len() < 4 || expression[0] != ' ' || expression[expression.len() - 1] != ' ' {
        return Err("brace expression must use spaced Fluent placeable syntax".to_owned());
    }

    if expression[1] == '$' {
        let name = &expression[2..expression.len() - 1];
        return if is_valid_variable_name(name) {
            Ok(())
        } else {
            Err("invalid Fluent variable name".to_owned())
        };
    }

    if expression[1] == '"' && expression[expression.len() - 2] == '"' {
        let literal = &expression[2..expression.len() - 2];
        return if literal.iter().all(|char| *char == ' ') {
            Ok(())
        } else {
            Err("generated Fluent string literal placeables may only preserve spaces".to_owned())
        };
    }

    Err("brace expression must use '{ $name }' or generated space literal syntax".to_owned())
}

fn is_valid_variable_name(name: &[char]) -> bool {
    matches!(name.first(), Some(first) if first.is_ascii_alphabetic() || *first == '_')
        && name
            .iter()
            .all(|char| char.is_ascii_alphanumeric() || *char == '_')
}
