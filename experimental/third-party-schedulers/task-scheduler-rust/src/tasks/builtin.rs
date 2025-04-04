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

#[task]
pub fn process_nested_list(args: Vec<ArgValue>) -> String {
    if let Some(ArgValue::Array(outer_list)) = args.get(0) {
        let mut description = format!("Received nested list with {} inner lists: ", outer_list.len());
        for (i, inner_item) in outer_list.iter().enumerate() {
            if let ArgValue::Array(inner_list) = inner_item {
                 description.push_str(&format!("[List {} ({} items): ", i, inner_list.len()));
                 let strings: Vec<String> = inner_list.iter().filter_map(|val| {
                     if let ArgValue::String(s) = val { Some(s.clone()) } else { None }
                 }).collect();
                 description.push_str(&strings.join(", "));
                 description.push_str("] ");
            } else {
                 description.push_str(&format!("[Item {} is not a list] ", i));
            }
        }
        description
    } else {
        "Error: Expected a nested list argument.".to_string()
    }
}

#[task]
pub fn process_complex_map(args: Vec<ArgValue>) -> String {
    if let Some(ArgValue::Map(map)) = args.get(0) {
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
             let tag_strings: Vec<String> = tags.iter().filter_map(|tag| {
                 if let ArgValue::String(s) = tag { Some(s.clone()) } else { None }
             }).collect();
             description.push_str(&format!("Tags=[{}]", tag_strings.join(", ")));
        }

        if description.ends_with(", ") {
            description.pop();
            description.pop();
        }
        description
    } else {
        "Error: Expected a map argument.".to_string()
    }
}

#[task]
pub fn process_person_map(args: Vec<ArgValue>) -> String {
     if let Some(ArgValue::Map(map)) = args.get(0) {
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
             let score_strings: Vec<String> = scores.iter().filter_map(|score| {
                 match score {
                    ArgValue::Float(f) => Some(f.to_string()),
                    ArgValue::Double(d) => Some(d.to_string()),
                    _ => None
                 }
             }).collect();
             description.push_str(&format!("Scores=[{}]", score_strings.join(", ")));
         }
         // Trim trailing comma and space
         if description.ends_with(", ") {
             description.pop();
             description.pop();
         }
         description
     } else {
         "Error: Expected a person map argument.".to_string()
     }
}

#[task]
pub fn ping(args: Vec<ArgValue>) -> String {
    if let Some(ArgValue::String(s)) = args.get(0) {
        format!("pong: {}", s)
    } else {
        "pong".to_string()
    }
}

#[task]
pub fn sum_list(args: Vec<ArgValue>) -> String {
    if let Some(ArgValue::Array(list)) = args.get(0) {
        let sum: i64 = list.iter().filter_map(|val| {
            match val {
                ArgValue::Int32(i) => Some(*i as i64),
                ArgValue::Int64(l) => Some(*l),
                // Add other numeric types if needed
                _ => None
            }
        }).sum();
        sum.to_string()
    } else {
        "Error: Expected a list argument.".to_string()
    }
}

#[task]
pub async fn fibonacci(args: Vec<ArgValue>) -> String {
    if let Some(ArgValue::Int32(n)) = args.get(0) {
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
