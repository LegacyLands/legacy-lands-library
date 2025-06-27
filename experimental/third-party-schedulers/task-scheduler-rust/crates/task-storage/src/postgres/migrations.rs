/// SQL migrations for PostgreSQL
pub const MIGRATIONS: &[(&str, &str)] = &[
    (
        "001_initial_schema",
        include_str!("sql/001_initial_schema.sql"),
    ),
    (
        "002_create_indexes",
        include_str!("sql/002_create_indexes.sql"),
    ),
    (
        "003_add_audit_table",
        include_str!("sql/003_add_audit_table.sql"),
    ),
];

/// Run all migrations
pub async fn run_migrations(pool: &sqlx::PgPool) -> Result<(), sqlx::Error> {
    // Create migrations table if it doesn't exist
    sqlx::query(
        r#"
        CREATE TABLE IF NOT EXISTS _migrations (
            id SERIAL PRIMARY KEY,
            name VARCHAR(255) NOT NULL UNIQUE,
            applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )
        "#,
    )
    .execute(pool)
    .await?;

    // Run each migration
    for (name, sql) in MIGRATIONS {
        let exists: Option<i64> =
            sqlx::query_scalar("SELECT COUNT(*) FROM _migrations WHERE name = $1")
                .bind(name)
                .fetch_one(pool)
                .await?;

        if exists.unwrap_or(0) == 0 {
            tracing::info!("Running migration: {}", name);

            // Execute migration - split by semicolons to handle multiple statements
            // SQLx doesn't support multiple statements in one query, so we need to split them
            let statements: Vec<&str> = sql.split(';')
                .map(|s| s.trim())
                .filter(|s| !s.is_empty())
                .collect();
            
            for statement in statements {
                sqlx::query(statement).execute(pool).await?;
            }

            // Record migration
            sqlx::query("INSERT INTO _migrations (name) VALUES ($1)")
                .bind(name)
                .execute(pool)
                .await?;
        }
    }

    Ok(())
}
