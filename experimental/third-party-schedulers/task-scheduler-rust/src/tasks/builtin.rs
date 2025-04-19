use crate::error::{Result, TaskError};
use crate::info_log;
use crate::models::ArgValue;
use task_macro::{async_task, sync_task};

#[sync_task]
pub fn add(args: Vec<ArgValue>) -> Result<String> {
    let sum: Result<i32> = args
        .into_iter()
        .map(|v| match v {
            ArgValue::Int32(n) => Ok(n),
            _ => Err(TaskError::InvalidArguments(
                "Expected Int32 for add".to_string(),
            )),
        })
        .sum();
    Ok(sum?.to_string())
}

#[sync_task]
pub fn remove(args: Vec<ArgValue>) -> Result<String> {
    info_log!("Executing builtin::remove with args: {:?}", args);
    let nums: Result<Vec<i32>> = args
        .into_iter()
        .map(|v| match v {
            ArgValue::Int32(n) => Ok(n),
            _ => Err(TaskError::InvalidArguments(
                "Invalid argument type for remove, expected Int32".to_string(),
            )),
        })
        .collect();
    let nums = match nums {
        Ok(n) => n,
        Err(e) => {
            info_log!("builtin::remove returning Err (conversion): {}", e);
            return Err(e);
        }
    };

    if nums.len() < 2 {
        let err = TaskError::InvalidArguments("Need at least 2 arguments for remove".to_string());
        info_log!("builtin::remove returning Err (arg count): {}", err);
        Err(err)
    } else {
        let result = (nums[0] - nums[1..].iter().sum::<i32>()).to_string();
        info_log!("builtin::remove returning Ok: {}", result);
        Ok(result)
    }
}

#[async_task]
pub async fn delete(args: Vec<ArgValue>) -> String {
    let nums: Vec<i32> = args
        .into_iter()
        .map(|v| if let ArgValue::Int32(n) = v { n } else { 0 })
        .collect();
    tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
    format!("Deleted {} items", nums.len())
}

#[sync_task]
pub fn process_collection(args: Vec<ArgValue>) -> Result<String> {
    if args.len() != 2 {
        return Err(TaskError::InvalidArguments(
            "Expected two arguments".to_string(),
        ));
    }
    let array = match &args[0] {
        ArgValue::Array(arr) => arr,
        _ => {
            return Err(TaskError::InvalidArguments(
                "First argument is not an array".to_string(),
            ))
        }
    };
    let map = match &args[1] {
        ArgValue::Map(m) => m,
        _ => {
            return Err(TaskError::InvalidArguments(
                "Second argument is not a map".to_string(),
            ))
        }
    };
    Ok(format!(
        "Array: {} items, Map: {} items",
        array.len(),
        map.len()
    ))
}

#[sync_task]
pub fn echo_bool(args: Vec<ArgValue>) -> Result<String> {
    let bools: Vec<String> = args
        .into_iter()
        .map(|v| {
            if let ArgValue::Bool(b) = v {
                b.to_string()
            } else {
                "".to_string()
            }
        })
        .collect();
    Ok(bools.join(","))
}

#[sync_task]
pub fn echo_string(args: Vec<ArgValue>) -> Result<String> {
    let strings: Vec<String> = args
        .into_iter()
        .map(|v| {
            if let ArgValue::String(s) = v {
                s
            } else {
                "".to_string()
            }
        })
        .collect();
    Ok(strings.join(","))
}

#[sync_task]
pub fn echo_bytes(args: Vec<ArgValue>) -> Result<String> {
    let bytes_res: Result<Vec<String>> = args
        .into_iter()
        .map(|v| {
            if let ArgValue::Bytes(b) = v {
                String::from_utf8(b)
                    .map_err(|e| TaskError::InvalidArguments(format!("Invalid UTF-8 bytes: {}", e)))
            } else {
                Ok("".to_string())
            }
        })
        .collect();
    Ok(bytes_res?.join(","))
}

#[sync_task]
pub fn process_nested_list(args: Vec<ArgValue>) -> Result<String> {
    if let Some(ArgValue::Array(outer_list)) = args.first() {
        let mut description = format!(
            "Received nested list with {} inner lists: ",
            outer_list.len()
        );
        for (i, inner_item) in outer_list.iter().enumerate() {
            if let ArgValue::Array(inner_list) = inner_item {
                description.push_str(&format!("[List {} ({} items): ", i, inner_list.len()));
                let strings: Vec<String> = inner_list
                    .iter()
                    .filter_map(|val| {
                        if let ArgValue::String(s) = val {
                            Some(s.clone())
                        } else {
                            None
                        }
                    })
                    .collect();
                description.push_str(&strings.join(", "));
                description.push_str("] ");
            } else {
                description.push_str(&format!("[Item {} is not a list] ", i));
            }
        }
        Ok(description)
    } else {
        Err(TaskError::InvalidArguments(
            "Expected a nested list argument.".to_string(),
        ))
    }
}

#[sync_task]
pub fn process_complex_map(args: Vec<ArgValue>) -> Result<String> {
    if let Some(ArgValue::Map(map)) = args.first() {
        let mut description = "Received complex map: ".to_string();
        if let Some(ArgValue::Int64(id)) = map.get("id") {
            description.push_str(&format!("ID={}, ", id));
        }
        if let Some(ArgValue::Bool(active)) = map.get("active") {
            description.push_str(&format!("Active={}, ", active));
        }
        if let Some(ArgValue::Map(metadata)) = map.get("metadata") {
            description.push_str("Metadata={ ");
            if let Some(ArgValue::String(source)) = metadata.get("source") {
                description.push_str(&format!("source={}, ", source));
            }
            if let Some(ArgValue::String(ts)) = metadata.get("timestamp") {
                description.push_str(&format!("timestamp={}", ts));
            }
            description.push_str(" }, ");
        }
        if let Some(ArgValue::Array(tags)) = map.get("tags") {
            let tag_strings: Vec<String> = tags
                .iter()
                .filter_map(|tag| {
                    if let ArgValue::String(s) = tag {
                        Some(s.clone())
                    } else {
                        None
                    }
                })
                .collect();
            description.push_str(&format!("Tags=[{}]", tag_strings.join(", ")));
        }

        if description.ends_with(", ") {
            description.pop();
            description.pop();
        }
        Ok(description)
    } else {
        Err(TaskError::InvalidArguments(
            "Expected a map argument.".to_string(),
        ))
    }
}

#[sync_task]
pub fn process_person_map(args: Vec<ArgValue>) -> Result<String> {
    if let Some(ArgValue::Map(map)) = args.first() {
        let mut description = "Processing person: ".to_string();
        if let Some(ArgValue::String(name)) = map.get("name") {
            description.push_str(&format!("Name={}, ", name));
        }
        if let Some(ArgValue::Int32(age)) = map.get("age") {
            description.push_str(&format!("Age={}, ", age));
        }
        if let Some(ArgValue::Bool(is_student)) = map.get("isStudent") {
            description.push_str(&format!("IsStudent={}, ", is_student));
        }
        if let Some(ArgValue::Array(scores)) = map.get("scores") {
            let score_strings: Vec<String> = scores
                .iter()
                .filter_map(|score| match score {
                    ArgValue::Float(f) => Some(f.to_string()),
                    ArgValue::Double(d) => Some(d.to_string()),
                    _ => None,
                })
                .collect();
            description.push_str(&format!("Scores=[{}]", score_strings.join(", ")));
        }
        if description.ends_with(", ") {
            description.pop();
            description.pop();
        }
        Ok(description)
    } else {
        Err(TaskError::InvalidArguments(
            "Expected a person map argument.".to_string(),
        ))
    }
}

#[sync_task]
pub fn ping(args: Vec<ArgValue>) -> Result<String> {
    if let Some(ArgValue::String(s)) = args.first() {
        Ok(format!("pong: {}", s))
    } else {
        Ok("pong".to_string())
    }
}

#[sync_task]
pub fn sum_list(args: Vec<ArgValue>) -> Result<String> {
    if let Some(ArgValue::Array(list)) = args.first() {
        let sum: i64 = list
            .iter()
            .filter_map(|val| match val {
                ArgValue::Int32(i) => Some(*i as i64),
                ArgValue::Int64(l) => Some(*l),
                _ => None,
            })
            .sum();
        Ok(sum.to_string())
    } else {
        Err(TaskError::InvalidArguments(
            "Expected a list argument.".to_string(),
        ))
    }
}

#[async_task]
pub async fn fibonacci(args: Vec<ArgValue>) -> String {
    if let Some(ArgValue::Int32(n)) = args.first() {
        if *n < 0 {
            return "Error: Input must be non-negative".to_string();
        }
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
        fn fib(n: i32) -> u64 {
            if n <= 1 {
                n as u64
            } else {
                fib(n - 1) + fib(n - 2)
            }
        }
        fib(*n).to_string()
    } else {
        "Error: Expected an integer argument".to_string()
    }
}
