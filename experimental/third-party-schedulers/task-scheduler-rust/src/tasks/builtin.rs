use crate::models::ArgValue;
use crate::tasks::task;

#[task]
pub fn add(args: Vec<ArgValue>) -> String {
    let sum: i32 = args
        .into_iter()
        .map(|v| if let ArgValue::Int32(n) = v { n } else { 0 })
        .sum();
    sum.to_string()
}

#[task]
pub fn remove(args: Vec<ArgValue>) -> String {
    let nums: Vec<i32> = args
        .into_iter()
        .map(|v| if let ArgValue::Int32(n) = v { n } else { 0 })
        .collect();
    if nums.len() < 2 {
        "Error: Need at least 2 arguments".to_string()
    } else {
        (nums[0] - nums[1..].iter().sum::<i32>()).to_string()
    }
}

#[task]
pub async fn delete(args: Vec<ArgValue>) -> String {
    let nums: Vec<i32> = args
        .into_iter()
        .map(|v| if let ArgValue::Int32(n) = v { n } else { 0 })
        .collect();
    tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
    format!("Deleted {} items", nums.len())
}

#[task]
pub fn process_collection(args: Vec<ArgValue>) -> String {
    if args.len() != 2 {
        return "Error: Expected two arguments".to_string();
    }
    let array = match &args[0] {
        ArgValue::Array(arr) => arr,
        _ => return "Error: First argument is not an array".to_string(),
    };
    let map = match &args[1] {
        ArgValue::Map(m) => m,
        _ => return "Error: Second argument is not a map".to_string(),
    };
    format!("Array: {} items, Map: {} items", array.len(), map.len())
}

#[task]
pub fn echo_bool(args: Vec<ArgValue>) -> String {
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
    bools.join(",")
}

#[task]
pub fn echo_string(args: Vec<ArgValue>) -> String {
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
    strings.join(",")
}

#[task]
pub fn echo_bytes(args: Vec<ArgValue>) -> String {
    let bytes: Vec<String> = args
        .into_iter()
        .map(|v| {
            if let ArgValue::Bytes(b) = v {
                String::from_utf8_lossy(&b).into_owned()
            } else {
                "".to_string()
            }
        })
        .collect();
    bytes.join(",")
}
