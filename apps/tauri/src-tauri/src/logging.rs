use std::{
    fs::{self, File},
    io::Read,
    path::Path,
    time::SystemTime,
};
use tracing_appender::non_blocking::WorkerGuard;
use tracing_subscriber::{
    fmt,
    layer::SubscriberExt,
    util::SubscriberInitExt,
    EnvFilter,
};

const LOG_DIR_NAME: &str = "logs";
const LOG_FILE_PREFIX: &str = "smspusher.log";
const MAX_FILE_READ_BYTES: u64 = 256_000;

pub struct LoggingGuard {
    _file_guard: Option<WorkerGuard>,
}

impl LoggingGuard {
    fn disabled() -> Self {
        Self { _file_guard: None }
    }

    fn file(file_guard: WorkerGuard) -> Self {
        Self {
            _file_guard: Some(file_guard),
        }
    }
}

pub fn init_file_logging(data_dir: impl AsRef<Path>) -> LoggingGuard {
    let log_dir = data_dir.as_ref().join(LOG_DIR_NAME);
    if fs::create_dir_all(&log_dir).is_err() {
        init_stderr_logging();
        return LoggingGuard::disabled();
    }

    let file_appender = tracing_appender::rolling::daily(&log_dir, LOG_FILE_PREFIX);
    let (file_writer, file_guard) = tracing_appender::non_blocking(file_appender);
    let subscriber = tracing_subscriber::registry()
        .with(default_env_filter())
        .with(
            fmt::layer()
                .with_writer(file_writer)
                .with_ansi(false)
                .with_thread_names(true)
                .with_target(true),
        )
        .with(
            fmt::layer()
                .with_writer(std::io::stderr)
                .with_ansi(cfg!(debug_assertions))
                .with_thread_names(true)
                .with_target(true),
        );

    if subscriber.try_init().is_err() {
        return LoggingGuard::disabled();
    }
    LoggingGuard::file(file_guard)
}

pub fn recent_log_text(data_dir: impl AsRef<Path>, max_chars: usize) -> String {
    if max_chars == 0 {
        return String::new();
    }
    let log_dir = data_dir.as_ref().join(LOG_DIR_NAME);
    collect_recent_logs(&log_dir, max_chars)
}

fn init_stderr_logging() {
    let subscriber = tracing_subscriber::registry().with(default_env_filter()).with(
        fmt::layer()
            .with_writer(std::io::stderr)
            .with_ansi(cfg!(debug_assertions))
            .with_thread_names(true)
            .with_target(true),
    );
    let _ = subscriber.try_init();
}

fn default_env_filter() -> EnvFilter {
    EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new("info,tao=warn,wry=warn,hyper=warn"))
}

fn collect_recent_logs(log_dir: &Path, max_chars: usize) -> String {
    if !log_dir.is_dir() {
        return String::new();
    }
    let mut files = match fs::read_dir(log_dir) {
        Ok(entries) => entries
            .filter_map(Result::ok)
            .map(|entry| entry.path())
            .filter(|path| path.is_file() && is_sms_pusher_log(path))
            .collect::<Vec<_>>(),
        Err(_) => return String::new(),
    };
    files.sort_by(|left, right| {
        modified_at(left)
            .cmp(&modified_at(right))
            .then_with(|| file_name(left).cmp(&file_name(right)))
    });

    let mut output = String::new();
    for file in files {
        let text = read_log_file(&file);
        if text.trim().is_empty() {
            continue;
        }
        if !output.is_empty() {
            output.push('\n');
        }
        output.push_str(text.trim());
    }
    tail_chars(&output, max_chars)
}

fn is_sms_pusher_log(path: &Path) -> bool {
    let name = file_name(path);
    name.starts_with("smspusher") && name.contains(".log")
}

fn file_name(path: &Path) -> String {
    path.file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_default()
}

fn modified_at(path: &Path) -> SystemTime {
    fs::metadata(path)
        .and_then(|metadata| metadata.modified())
        .unwrap_or(SystemTime::UNIX_EPOCH)
}

fn read_log_file(path: &Path) -> String {
    let mut file = match File::open(path) {
        Ok(file) => file,
        Err(_) => return String::new(),
    };
    let mut bytes = Vec::new();
    let limit = file
        .metadata()
        .map(|metadata| metadata.len().min(MAX_FILE_READ_BYTES))
        .unwrap_or(MAX_FILE_READ_BYTES);
    if file.by_ref().take(limit).read_to_end(&mut bytes).is_err() {
        return String::new();
    }
    String::from_utf8_lossy(&bytes).into_owned()
}

fn tail_chars(value: &str, max_chars: usize) -> String {
    let char_count = value.chars().count();
    if char_count <= max_chars {
        return value.to_owned();
    }
    value.chars().skip(char_count - max_chars).collect()
}
