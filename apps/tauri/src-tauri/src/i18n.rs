use fluent_bundle::{FluentArgs, FluentBundle, FluentResource};
use serde::{Deserialize, Serialize};
use unic_langid::LanguageIdentifier;

pub const SUPPORTED_LOCALES: [&str; 2] = ["en-US", "zh-CN"];
const EN_US_FTL: &str = include_str!("../locales/en-US/desktop.ftl");
const ZH_CN_FTL: &str = include_str!("../locales/zh-CN/desktop.ftl");

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum LanguagePreference {
    Auto,
    #[serde(rename = "zh-CN")]
    ZhCn,
    #[serde(rename = "en-US")]
    EnUs,
}

impl Default for LanguagePreference {
    fn default() -> Self {
        Self::Auto
    }
}

pub fn resolve_locale<I, S>(preference: LanguagePreference, system_locales: I) -> &'static str
where
    I: IntoIterator<Item = S>,
    S: AsRef<str>,
{
    match preference {
        LanguagePreference::ZhCn => "zh-CN",
        LanguagePreference::EnUs => "en-US",
        LanguagePreference::Auto => resolve_system_locale(system_locales),
    }
}

fn resolve_system_locale<I, S>(system_locales: I) -> &'static str
where
    I: IntoIterator<Item = S>,
    S: AsRef<str>,
{
    for locale in system_locales {
        let normalized = locale.as_ref().replace('_', "-");
        if normalized == "zh"
            || normalized.starts_with("zh-CN")
            || normalized.starts_with("zh-Hans")
        {
            return "zh-CN";
        }
    }
    "en-US"
}

pub fn current_system_locale_candidates() -> Vec<String> {
    sys_locale::get_locale().into_iter().collect()
}

pub struct DesktopI18n {
    locale: &'static str,
    bundle: FluentBundle<FluentResource>,
}

impl DesktopI18n {
    pub fn resolve<I, S>(preference: LanguagePreference, system_locales: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: AsRef<str>,
    {
        Self::for_locale(resolve_locale(preference, system_locales))
    }

    pub fn for_locale(locale: &str) -> Self {
        let locale = if locale == "zh-CN" { "zh-CN" } else { "en-US" };
        let ftl = if locale == "zh-CN" {
            ZH_CN_FTL
        } else {
            EN_US_FTL
        };
        let langid: LanguageIdentifier = locale.parse().expect("valid locale id");
        let resource = FluentResource::try_new(ftl.to_owned()).expect("valid fluent resource");
        let mut bundle = FluentBundle::new(vec![langid]);
        bundle.add_resource(resource).expect("resource added once");
        Self { locale, bundle }
    }

    pub fn locale(&self) -> &'static str {
        self.locale
    }

    pub fn text(&self, key: &str, args: &[(&str, &str)]) -> String {
        let fluent_key = fluent_message_id(key);
        let Some(message) = self.bundle.get_message(&fluent_key) else {
            return if self.locale == "en-US" {
                key.to_owned()
            } else {
                DesktopI18n::for_locale("en-US").text(key, args)
            };
        };
        let Some(pattern) = message.value() else {
            return key.to_owned();
        };
        let mut fluent_args = FluentArgs::new();
        for (name, value) in args {
            fluent_args.set(*name, *value);
        }
        let mut errors = Vec::new();
        self.bundle
            .format_pattern(pattern, Some(&fluent_args), &mut errors)
            .to_string()
    }
}

pub fn fluent_message_id(key: &str) -> String {
    let mut message_id = String::new();
    let mut previous_was_lower_or_digit = false;
    let mut previous_was_separator = true;

    for character in key.chars() {
        if character.is_ascii_alphanumeric() {
            if character.is_ascii_uppercase() && previous_was_lower_or_digit {
                message_id.push('-');
            }
            message_id.push(character.to_ascii_lowercase());
            previous_was_lower_or_digit =
                character.is_ascii_lowercase() || character.is_ascii_digit();
            previous_was_separator = false;
        } else if !previous_was_separator {
            message_id.push('-');
            previous_was_lower_or_digit = false;
            previous_was_separator = true;
        }
    }

    if message_id.ends_with('-') {
        message_id.pop();
    }
    message_id
}
