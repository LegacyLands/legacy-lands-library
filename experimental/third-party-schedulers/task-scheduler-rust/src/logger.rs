use std::fs;
use std::path::Path;
use time::macros::format_description;
use time::OffsetDateTime;
use tracing_appender::non_blocking::WorkerGuard;
use tracing_subscriber::{fmt, layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};

pub fn init_logger() -> (WorkerGuard, WorkerGuard) {
    let now = OffsetDateTime::now_local().unwrap_or_else(|_| OffsetDateTime::now_utc());
    let timestamp = now
        .format(&format_description!(
            "[year]-[month]-[day]-[hour]-[minute]-[second]"
        ))
        .expect("Failed to format timestamp for log file");

    let log_dir = "logs";
    let file_name = format!("{}.log", timestamp);
    let full_path = Path::new(log_dir).join(file_name);

    fs::create_dir_all(log_dir).expect("Failed to create log directory");

    let log_file = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&full_path)
        .expect("Failed to open log file");

    let (non_blocking_file, guard_file) = tracing_appender::non_blocking(log_file);
    let (non_blocking_stdout, guard_stdout) = tracing_appender::non_blocking(std::io::stdout());

    let format = fmt::format()
        .with_target(false)
        .with_level(false)
        .with_thread_ids(false)
        .with_thread_names(false)
        .with_file(false)
        .with_line_number(false)
        .without_time();

    let file_layer = fmt::layer()
        .with_writer(non_blocking_file)
        .with_ansi(false)
        .event_format(format.clone());

    let stdout_layer = fmt::layer()
        .with_writer(non_blocking_stdout)
        .with_ansi(true)
        .event_format(format);

    tracing_subscriber::registry()
        .with(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")))
        .with(stdout_layer)
        .with(file_layer)
        .init();

    (guard_file, guard_stdout)
}

#[macro_export]
macro_rules! log {
    ($level:expr, $($arg:tt)+) => {
        {
            let now = time::OffsetDateTime::now_local().unwrap_or_else(|_| time::OffsetDateTime::now_utc());
            const DATE_FORMAT: &[time::format_description::FormatItem<'_>] = time::macros::format_description!("[year]-[month]-[day]");
            const TIME_FORMAT: &[time::format_description::FormatItem<'_>] = time::macros::format_description!("[hour]:[minute]:[second]");

            let date = now.format(&DATE_FORMAT).unwrap_or("[unknown-date]".to_string());
            let time = now.format(&TIME_FORMAT).unwrap_or("[unknown-time]".to_string());

            let level_str = match $level {
                tracing::Level::ERROR => "[ERROR]",
                tracing::Level::WARN => "[WARN]",
                tracing::Level::INFO => "[INFO]",
                tracing::Level::DEBUG => "[DEBUG]",
                tracing::Level::TRACE => "[TRACE]",
            };

            tracing::event!($level, "[{}] [{}] {} {}", date, time, level_str, format_args!($($arg)+));
        }
    };
}

#[macro_export]
macro_rules! info_log {
    ($($arg:tt)+) => { $crate::log!(tracing::Level::INFO, $($arg)+); };
}

#[macro_export]
macro_rules! warn_log {
    ($($arg:tt)+) => { $crate::log!(tracing::Level::WARN, $($arg)+); };
}

#[macro_export]
macro_rules! error_log {
    ($($arg:tt)+) => { $crate::log!(tracing::Level::ERROR, $($arg)+); };
}

#[macro_export]
macro_rules! debug_log {
    ($($arg:tt)+) => { $crate::log!(tracing::Level::DEBUG, $($arg)+); };
}

#[macro_export]
macro_rules! trace_log {
    ($($arg:tt)+) => { $crate::log!(tracing::Level::TRACE, $($arg)+); };
}
