use crate::{
    config::MongoDBConfig,
    models::{PageInfo, StoredTaskResult, TaskExecutionHistory, TaskQuery, TaskStatus},
    traits::{StorageError, StorageResult, TaskStorage},
};
use async_trait::async_trait;
use chrono::{DateTime, Utc};
use futures::TryStreamExt;
use mongodb::{
    bson::{self, doc, Bson, Document},
    options::{ClientOptions, FindOptions, IndexOptions},
    Client, Collection, Database, IndexModel,
};
use serde_json::Value;
use std::collections::HashMap;
use std::str::FromStr;
use uuid::Uuid;

/// MongoDB storage implementation
pub struct MongoStorage {
    _client: Client,
    database: Database,
    results_collection: Collection<Document>,
    history_collection: Collection<Document>,
}

impl MongoStorage {
    /// Create a new MongoDB storage instance
    pub async fn new(config: &MongoDBConfig) -> StorageResult<Self> {
        let mut client_options = ClientOptions::parse(&config.url)
            .await
            .map_err(|e| StorageError::ConnectionFailed(format!("Failed to parse URL: {}", e)))?;

        client_options.connect_timeout = Some(std::time::Duration::from_secs(30));

        let client = Client::with_options(client_options)
            .map_err(|e| StorageError::ConnectionFailed(format!("Failed to create client: {}", e)))?;

        let database = client.database(&config.database);
        let results_collection = database.collection::<Document>(&config.collections.results);
        let history_collection = database.collection::<Document>(&config.collections.history);

        let storage = Self {
            _client: client,
            database,
            results_collection,
            history_collection,
        };

        // Create indexes
        storage.create_indexes().await?;

        Ok(storage)
    }

    /// Create necessary indexes
    async fn create_indexes(&self) -> StorageResult<()> {
        // Results collection indexes
        let task_id_index = IndexModel::builder()
            .keys(doc! { "task_id": 1 })
            .options(IndexOptions::builder().unique(true).build())
            .build();

        let status_index = IndexModel::builder()
            .keys(doc! { "status": 1, "created_at": -1 })
            .build();

        let created_at_index = IndexModel::builder()
            .keys(doc! { "created_at": -1 })
            .build();

        self.results_collection
            .create_indexes([task_id_index, status_index, created_at_index])
            .await
            .map_err(|e| StorageError::Database(format!("Failed to create results indexes: {}", e)))?;

        // History collection indexes
        let history_task_id_index = IndexModel::builder()
            .keys(doc! { "task_id": 1, "executed_at": -1 })
            .build();

        let history_executed_at_index = IndexModel::builder()
            .keys(doc! { "executed_at": -1 })
            .build();

        self.history_collection
            .create_indexes([history_task_id_index, history_executed_at_index])
            .await
            .map_err(|e| StorageError::Database(format!("Failed to create history indexes: {}", e)))?;

        Ok(())
    }

    /// Convert TaskResult to BSON document
    fn task_result_to_document(result: &StoredTaskResult) -> Document {
        let mut doc = Document::new();
        
        doc.insert("task_id", result.task_id.to_string());
        doc.insert("method", &result.method);
        doc.insert("status", status_to_string(&result.status));
        
        if let Some(ref res) = result.result {
            doc.insert("result", bson::to_bson(res).unwrap_or(Bson::Null));
        }
        
        if let Some(ref err) = result.error {
            doc.insert("error", err);
        }
        
        doc.insert("created_at", bson::DateTime::from_system_time(result.created_at.into()));
        doc.insert("updated_at", bson::DateTime::from_system_time(result.updated_at.into()));
        
        if let Some(started) = result.started_at {
            doc.insert("started_at", bson::DateTime::from_system_time(started.into()));
        }
        
        if let Some(completed) = result.completed_at {
            doc.insert("completed_at", bson::DateTime::from_system_time(completed.into()));
        }
        
        doc.insert("retry_count", result.retry_count as i32);
        
        if let Some(ref worker) = result.worker_id {
            doc.insert("worker_id", worker);
        }
        
        if let Some(ref queue) = result.queue_name {
            doc.insert("queue_name", queue);
        }
        
        if !result.tags.is_empty() {
            doc.insert("tags", bson::to_bson(&result.tags).unwrap_or(Bson::Null));
        }
        
        if !result.metadata.is_empty() {
            doc.insert("metadata", bson::to_bson(&result.metadata).unwrap_or(Bson::Null));
        }
        
        doc
    }

    /// Convert BSON document to TaskResult
    fn document_to_task_result(doc: Document) -> StorageResult<StoredTaskResult> {
        let task_id = doc
            .get_str("task_id")
            .map_err(|e| StorageError::Serialization(format!("Missing task_id: {}", e)))?;
        
        let task_id = Uuid::from_str(task_id)
            .map_err(|e| StorageError::Serialization(format!("Invalid UUID: {}", e)))?;
        
        let method = doc
            .get_str("method")
            .map_err(|e| StorageError::Serialization(format!("Missing method: {}", e)))?
            .to_string();
        
        let status_str = doc
            .get_str("status")
            .map_err(|e| StorageError::Serialization(format!("Missing status: {}", e)))?;
        
        let status = string_to_status(status_str)?;
        
        let result = doc
            .get("result")
            .and_then(|b| bson::from_bson::<Value>(b.clone()).ok());
        
        let error = doc.get_str("error").ok().map(|s| s.to_string());
        
        let created_at = doc
            .get_datetime("created_at")
            .map_err(|e| StorageError::Serialization(format!("Missing created_at: {}", e)))?
            .to_system_time()
            .into();
        
        let updated_at = doc
            .get_datetime("updated_at")
            .map_err(|e| StorageError::Serialization(format!("Missing updated_at: {}", e)))?
            .to_system_time()
            .into();
        
        let started_at = doc
            .get_datetime("started_at")
            .ok()
            .map(|dt| dt.to_system_time().into());
        
        let completed_at = doc
            .get_datetime("completed_at")
            .ok()
            .map(|dt| dt.to_system_time().into());
        
        let retry_count = doc.get_i32("retry_count").unwrap_or(0) as u32;
        
        let worker_id = doc.get_str("worker_id").ok().map(|s| s.to_string());
        let queue_name = doc.get_str("queue_name").ok().map(|s| s.to_string());
        
        let tags = doc
            .get("tags")
            .and_then(|b| bson::from_bson::<Vec<String>>(b.clone()).ok())
            .unwrap_or_default();
        
        let metadata = doc
            .get("metadata")
            .and_then(|b| bson::from_bson::<HashMap<String, String>>(b.clone()).ok())
            .unwrap_or_default();
        
        let args = doc
            .get("args")
            .and_then(|b| bson::from_bson::<Vec<Value>>(b.clone()).ok())
            .unwrap_or_default();
        
        let duration_ms = doc
            .get_i64("duration_ms")
            .ok()
            .map(|d| d as u64);
        
        let node_name = doc.get_str("node_name").ok().map(|s| s.to_string());
        
        let priority = doc.get_i32("priority").unwrap_or(0);
        
        Ok(StoredTaskResult {
            task_id,
            method,
            args,
            status,
            result,
            error,
            worker_id,
            node_name,
            created_at,
            started_at,
            completed_at,
            duration_ms,
            retry_count,
            metadata,
            priority,
            updated_at,
            queue_name,
            tags,
        })
    }

    /// Convert TaskExecutionHistory to BSON document
    fn history_to_document(history: &TaskExecutionHistory) -> Document {
        let mut doc = Document::new();
        
        doc.insert("task_id", history.task_id.to_string());
        doc.insert("execution_id", history.execution_id.to_string());
        doc.insert("status", status_to_string(&history.status));
        doc.insert("executed_at", bson::DateTime::from_system_time(history.executed_at.into()));
        doc.insert("completed_at", bson::DateTime::from_system_time(history.completed_at.into()));
        doc.insert("duration_ms", history.duration_ms as i64);
        
        if let Some(ref err) = history.error {
            doc.insert("error", err);
        }
        
        if let Some(ref worker) = history.worker_id {
            doc.insert("worker_id", worker);
        }
        
        if let Some(ref node) = history.worker_node {
            doc.insert("worker_node", node);
        }
        
        doc.insert("retry_attempt", history.retry_attempt as i32);
        
        if !history.metrics.is_empty() {
            doc.insert("metrics", bson::to_bson(&history.metrics).unwrap_or(Bson::Null));
        }
        
        doc
    }

    /// Convert BSON document to TaskExecutionHistory
    fn document_to_history(doc: Document) -> StorageResult<TaskExecutionHistory> {
        let task_id = doc
            .get_str("task_id")
            .map_err(|e| StorageError::Serialization(format!("Missing task_id: {}", e)))?;
        
        let task_id = Uuid::from_str(task_id)
            .map_err(|e| StorageError::Serialization(format!("Invalid task UUID: {}", e)))?;
        
        let execution_id = doc
            .get_str("execution_id")
            .map_err(|e| StorageError::Serialization(format!("Missing execution_id: {}", e)))?;
        
        let execution_id = Uuid::from_str(execution_id)
            .map_err(|e| StorageError::Serialization(format!("Invalid execution UUID: {}", e)))?;
        
        let status_str = doc
            .get_str("status")
            .map_err(|e| StorageError::Serialization(format!("Missing status: {}", e)))?;
        
        let status = string_to_status(status_str)?;
        
        let executed_at = doc
            .get_datetime("executed_at")
            .map_err(|e| StorageError::Serialization(format!("Missing executed_at: {}", e)))?
            .to_system_time()
            .into();
        
        let completed_at = doc
            .get_datetime("completed_at")
            .map_err(|e| StorageError::Serialization(format!("Missing completed_at: {}", e)))?
            .to_system_time()
            .into();
        
        let duration_ms = doc.get_i64("duration_ms").unwrap_or(0) as u64;
        let error = doc.get_str("error").ok().map(|s| s.to_string());
        let worker_id = doc.get_str("worker_id").ok().map(|s| s.to_string());
        let worker_node = doc.get_str("worker_node").ok().map(|s| s.to_string());
        let retry_attempt = doc.get_i32("retry_attempt").unwrap_or(0) as u32;
        
        let metrics = doc
            .get("metrics")
            .and_then(|b| bson::from_bson::<HashMap<String, Value>>(b.clone()).ok())
            .unwrap_or_default();
        
        Ok(TaskExecutionHistory {
            task_id,
            execution_id,
            status,
            executed_at,
            completed_at,
            duration_ms,
            error,
            worker_id,
            worker_node,
            retry_attempt,
            metrics,
        })
    }
}

#[async_trait]
impl TaskStorage for MongoStorage {
    async fn store_result(&self, result: StoredTaskResult) -> StorageResult<()> {
        let doc = Self::task_result_to_document(&result);
        let filter = doc! { "task_id": result.task_id.to_string() };
        
        self.results_collection
            .replace_one(filter, doc)
            .upsert(true)
            .await
            .map_err(|e| StorageError::Database(format!("Failed to store result: {}", e)))?;
        
        Ok(())
    }

    async fn get_result(&self, task_id: Uuid) -> StorageResult<StoredTaskResult> {
        let filter = doc! { "task_id": task_id.to_string() };
        
        let doc = self
            .results_collection
            .find_one(filter)
            .await
            .map_err(|e| StorageError::Database(format!("Failed to find result: {}", e)))?
            .ok_or(StorageError::NotFound)?;
        
        Self::document_to_task_result(doc)
    }

    async fn query_results(
        &self,
        query: TaskQuery,
        page_info: PageInfo,
    ) -> StorageResult<(Vec<StoredTaskResult>, u64)> {
        let mut filter = Document::new();
        
        if let Some(status) = query.status {
            filter.insert("status", status_to_string(&status));
        }
        
        if let Some(method) = query.method {
            filter.insert("method", method);
        }
        
        if let Some(worker_id) = query.worker_id {
            filter.insert("worker_id", worker_id);
        }
        
        if let Some(queue_name) = query.queue_name {
            filter.insert("queue_name", queue_name);
        }
        
        if let Some(created_after) = query.created_after {
            filter.insert("created_at", doc! { "$gte": bson::DateTime::from_system_time(created_after.into()) });
        }
        
        if let Some(created_before) = query.created_before {
            filter.insert("created_at", doc! { "$lte": bson::DateTime::from_system_time(created_before.into()) });
        }
        
        if !query.tags.is_empty() {
            filter.insert("tags", doc! { "$in": &query.tags });
        }
        
        // Count total results
        let total = self
            .results_collection
            .count_documents(filter.clone())
            .await
            .map_err(|e| StorageError::Database(format!("Failed to count results: {}", e)))?;
        
        // Query with pagination
        let options = FindOptions::builder()
            .skip(Some((page_info.page as u64 - 1) * page_info.page_size as u64))
            .limit(Some(page_info.page_size as i64))
            .sort(doc! { "created_at": -1 })
            .build();
        
        let mut cursor = self
            .results_collection
            .find(filter)
            .with_options(options)
            .await
            .map_err(|e| StorageError::Database(format!("Failed to query results: {}", e)))?;
        
        let mut results = Vec::new();
        while let Some(doc) = cursor
            .try_next()
            .await
            .map_err(|e| StorageError::Database(format!("Failed to iterate results: {}", e)))?
        {
            results.push(Self::document_to_task_result(doc)?);
        }
        
        Ok((results, total))
    }

    async fn cleanup_results(&self, older_than: DateTime<Utc>) -> StorageResult<u64> {
        let filter = doc! {
            "created_at": { "$lt": bson::DateTime::from_system_time(older_than.into()) }
        };
        
        let result = self
            .results_collection
            .delete_many(filter)
            .await
            .map_err(|e| StorageError::Database(format!("Failed to cleanup results: {}", e)))?;
        
        Ok(result.deleted_count)
    }

    async fn store_history(&self, history: TaskExecutionHistory) -> StorageResult<()> {
        let doc = Self::history_to_document(&history);
        
        self.history_collection
            .insert_one(doc)
            .await
            .map_err(|e| StorageError::Database(format!("Failed to store history: {}", e)))?;
        
        Ok(())
    }

    async fn get_history(
        &self,
        task_id: Uuid,
        limit: Option<u32>,
    ) -> StorageResult<Vec<TaskExecutionHistory>> {
        let filter = doc! { "task_id": task_id.to_string() };
        let options = FindOptions::builder()
            .sort(doc! { "executed_at": -1 })
            .limit(limit.map(|l| l as i64))
            .build();
        
        let mut cursor = self
            .history_collection
            .find(filter)
            .with_options(options)
            .await
            .map_err(|e| StorageError::Database(format!("Failed to query history: {}", e)))?;
        
        let mut history = Vec::new();
        while let Some(doc) = cursor
            .try_next()
            .await
            .map_err(|e| StorageError::Database(format!("Failed to iterate history: {}", e)))?
        {
            history.push(Self::document_to_history(doc)?);
        }
        
        Ok(history)
    }

    async fn get_statistics(
        &self,
        start_time: DateTime<Utc>,
        end_time: DateTime<Utc>,
        group_by: Option<String>,
    ) -> StorageResult<HashMap<String, Value>> {
        let pipeline = match group_by.as_deref() {
            Some("status") => {
                vec![
                    doc! {
                        "$match": {
                            "created_at": {
                                "$gte": bson::DateTime::from_system_time(start_time.into()),
                                "$lte": bson::DateTime::from_system_time(end_time.into())
                            }
                        }
                    },
                    doc! {
                        "$group": {
                            "_id": "$status",
                            "count": { "$sum": 1 },
                            "avg_duration": { "$avg": "$duration_ms" }
                        }
                    },
                ]
            }
            Some("method") => {
                vec![
                    doc! {
                        "$match": {
                            "created_at": {
                                "$gte": bson::DateTime::from_system_time(start_time.into()),
                                "$lte": bson::DateTime::from_system_time(end_time.into())
                            }
                        }
                    },
                    doc! {
                        "$group": {
                            "_id": "$method",
                            "count": { "$sum": 1 },
                            "success_count": {
                                "$sum": {
                                    "$cond": [{ "$eq": ["$status", "succeeded"] }, 1, 0]
                                }
                            },
                            "failed_count": {
                                "$sum": {
                                    "$cond": [{ "$eq": ["$status", "failed"] }, 1, 0]
                                }
                            }
                        }
                    },
                ]
            }
            _ => {
                vec![
                    doc! {
                        "$match": {
                            "created_at": {
                                "$gte": bson::DateTime::from_system_time(start_time.into()),
                                "$lte": bson::DateTime::from_system_time(end_time.into())
                            }
                        }
                    },
                    doc! {
                        "$group": {
                            "_id": null,
                            "total_tasks": { "$sum": 1 },
                            "succeeded": {
                                "$sum": {
                                    "$cond": [{ "$eq": ["$status", "succeeded"] }, 1, 0]
                                }
                            },
                            "failed": {
                                "$sum": {
                                    "$cond": [{ "$eq": ["$status", "failed"] }, 1, 0]
                                }
                            },
                            "avg_duration_ms": { "$avg": "$duration_ms" },
                            "total_retries": { "$sum": "$retry_count" }
                        }
                    },
                ]
            }
        };
        
        let mut cursor = self
            .results_collection
            .aggregate(pipeline)
            .await
            .map_err(|e| StorageError::Database(format!("Failed to aggregate statistics: {}", e)))?;
        
        let mut stats = HashMap::new();
        while let Some(doc) = cursor
            .try_next()
            .await
            .map_err(|e| StorageError::Database(format!("Failed to iterate statistics: {}", e)))?
        {
            let json_value = bson::from_document::<Value>(doc)
                .map_err(|e| StorageError::Serialization(format!("Failed to convert stats: {}", e)))?;
            
            if let Some(obj) = json_value.as_object() {
                for (key, value) in obj {
                    stats.insert(key.clone(), value.clone());
                }
            }
        }
        
        Ok(stats)
    }

    async fn health_check(&self) -> StorageResult<()> {
        self.database
            .run_command(doc! { "ping": 1 })
            .await
            .map_err(|e| StorageError::Database(format!("Health check failed: {}", e)))?;
        
        Ok(())
    }
}

// Helper functions
fn status_to_string(status: &TaskStatus) -> &'static str {
    match status {
        TaskStatus::Pending => "pending",
        TaskStatus::Queued => "queued",
        TaskStatus::Running => "running",
        TaskStatus::Succeeded => "succeeded",
        TaskStatus::Failed => "failed",
        TaskStatus::Cancelled => "cancelled",
    }
}

fn string_to_status(s: &str) -> StorageResult<TaskStatus> {
    match s {
        "pending" => Ok(TaskStatus::Pending),
        "queued" => Ok(TaskStatus::Queued),
        "running" => Ok(TaskStatus::Running),
        "succeeded" => Ok(TaskStatus::Succeeded),
        "failed" => Ok(TaskStatus::Failed),
        "cancelled" => Ok(TaskStatus::Cancelled),
        _ => Err(StorageError::Serialization(format!("Unknown status: {}", s))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_status_conversion() {
        assert_eq!(status_to_string(&TaskStatus::Pending), "pending");
        assert_eq!(status_to_string(&TaskStatus::Queued), "queued");
        assert_eq!(status_to_string(&TaskStatus::Running), "running");
        assert_eq!(status_to_string(&TaskStatus::Succeeded), "succeeded");
        assert_eq!(status_to_string(&TaskStatus::Failed), "failed");
        assert_eq!(status_to_string(&TaskStatus::Cancelled), "cancelled");

        assert!(matches!(string_to_status("pending"), Ok(TaskStatus::Pending)));
        assert!(matches!(string_to_status("queued"), Ok(TaskStatus::Queued)));
        assert!(matches!(string_to_status("running"), Ok(TaskStatus::Running)));
        assert!(matches!(string_to_status("succeeded"), Ok(TaskStatus::Succeeded)));
        assert!(matches!(string_to_status("failed"), Ok(TaskStatus::Failed)));
        assert!(matches!(string_to_status("cancelled"), Ok(TaskStatus::Cancelled)));
        assert!(string_to_status("unknown").is_err());
    }
}