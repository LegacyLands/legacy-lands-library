use task_plugins::{Plugin, PluginInfo, TaskContext, TaskResult};
use async_trait::async_trait;
use serde_json::{json, Value};

/// Example custom plugin that provides data transformation tasks
pub struct DataTransformPlugin;

#[async_trait]
impl Plugin for DataTransformPlugin {
    fn info(&self) -> PluginInfo {
        PluginInfo {
            name: "data-transform".to_string(),
            version: "0.1.0".to_string(),
            description: "Data transformation tasks for the task scheduler".to_string(),
            author: "Example Corp".to_string(),
            methods: vec![
                "json_to_csv".to_string(),
                "csv_to_json".to_string(),
                "filter_data".to_string(),
                "aggregate_data".to_string(),
            ],
        }
    }
    
    async fn execute(&self, method: &str, args: Vec<Value>, ctx: &TaskContext) -> TaskResult {
        match method {
            "json_to_csv" => self.json_to_csv(args, ctx).await,
            "csv_to_json" => self.csv_to_json(args, ctx).await,
            "filter_data" => self.filter_data(args, ctx).await,
            "aggregate_data" => self.aggregate_data(args, ctx).await,
            _ => TaskResult::Error(format!("Unknown method: {}", method)),
        }
    }
}

impl DataTransformPlugin {
    async fn json_to_csv(&self, args: Vec<Value>, _ctx: &TaskContext) -> TaskResult {
        if args.is_empty() {
            return TaskResult::Error("Missing JSON data argument".to_string());
        }
        
        // Simple JSON to CSV conversion
        let json_data = &args[0];
        if let Some(array) = json_data.as_array() {
            let mut csv = String::new();
            
            // Extract headers from first object
            if let Some(first_obj) = array.first().and_then(|v| v.as_object()) {
                let headers: Vec<&str> = first_obj.keys().map(|k| k.as_str()).collect();
                csv.push_str(&headers.join(","));
                csv.push('\n');
                
                // Convert each object to CSV row
                for obj in array {
                    if let Some(obj_map) = obj.as_object() {
                        let values: Vec<String> = headers.iter()
                            .map(|h| obj_map.get(*h)
                                .map(|v| v.to_string())
                                .unwrap_or_else(|| "".to_string()))
                            .collect();
                        csv.push_str(&values.join(","));
                        csv.push('\n');
                    }
                }
            }
            
            TaskResult::Success(json!(csv))
        } else {
            TaskResult::Error("Input must be a JSON array".to_string())
        }
    }
    
    async fn csv_to_json(&self, args: Vec<Value>, _ctx: &TaskContext) -> TaskResult {
        if args.is_empty() {
            return TaskResult::Error("Missing CSV data argument".to_string());
        }
        
        let csv_data = args[0].as_str().unwrap_or("");
        let lines: Vec<&str> = csv_data.lines().collect();
        
        if lines.is_empty() {
            return TaskResult::Success(json!([]));
        }
        
        // Parse headers
        let headers: Vec<&str> = lines[0].split(',').collect();
        let mut result = Vec::new();
        
        // Parse data rows
        for line in lines.iter().skip(1) {
            let values: Vec<&str> = line.split(',').collect();
            let mut obj = serde_json::Map::new();
            
            for (i, header) in headers.iter().enumerate() {
                if let Some(value) = values.get(i) {
                    obj.insert(header.to_string(), json!(value));
                }
            }
            
            result.push(json!(obj));
        }
        
        TaskResult::Success(json!(result))
    }
    
    async fn filter_data(&self, args: Vec<Value>, _ctx: &TaskContext) -> TaskResult {
        if args.len() < 2 {
            return TaskResult::Error("Missing data or filter condition".to_string());
        }
        
        let data = &args[0];
        let filter_field = args[1].as_str().unwrap_or("");
        let filter_value = args.get(2).cloned().unwrap_or(json!(null));
        
        if let Some(array) = data.as_array() {
            let filtered: Vec<Value> = array.iter()
                .filter(|item| {
                    if let Some(obj) = item.as_object() {
                        if let Some(field_value) = obj.get(filter_field) {
                            field_value == &filter_value
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                })
                .cloned()
                .collect();
            
            TaskResult::Success(json!(filtered))
        } else {
            TaskResult::Error("Input must be a JSON array".to_string())
        }
    }
    
    async fn aggregate_data(&self, args: Vec<Value>, _ctx: &TaskContext) -> TaskResult {
        if args.len() < 2 {
            return TaskResult::Error("Missing data or aggregation field".to_string());
        }
        
        let data = &args[0];
        let agg_field = args[1].as_str().unwrap_or("");
        let operation = args.get(2).and_then(|v| v.as_str()).unwrap_or("sum");
        
        if let Some(array) = data.as_array() {
            let values: Vec<f64> = array.iter()
                .filter_map(|item| {
                    item.as_object()
                        .and_then(|obj| obj.get(agg_field))
                        .and_then(|v| v.as_f64())
                })
                .collect();
            
            if values.is_empty() {
                return TaskResult::Success(json!(0));
            }
            
            let result = match operation {
                "sum" => values.iter().sum::<f64>(),
                "avg" | "average" => values.iter().sum::<f64>() / values.len() as f64,
                "min" => values.iter().fold(f64::INFINITY, |a, &b| a.min(b)),
                "max" => values.iter().fold(f64::NEG_INFINITY, |a, &b| a.max(b)),
                "count" => values.len() as f64,
                _ => return TaskResult::Error(format!("Unknown operation: {}", operation)),
            };
            
            TaskResult::Success(json!(result))
        } else {
            TaskResult::Error("Input must be a JSON array".to_string())
        }
    }
}

// Export the plugin
#[no_mangle]
pub extern "C" fn create_plugin() -> Box<dyn Plugin> {
    Box::new(DataTransformPlugin)
}