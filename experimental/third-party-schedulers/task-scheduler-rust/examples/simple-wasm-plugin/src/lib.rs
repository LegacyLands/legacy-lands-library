/// A simple WASM plugin that processes integers
/// This demonstrates a minimal WASM plugin without WASI

/// Process function - doubles the input
#[no_mangle]
pub extern "C" fn process(x: i32) -> i32 {
    x * 2
}

/// Add function - adds two numbers
#[no_mangle]
pub extern "C" fn add(a: i32, b: i32) -> i32 {
    a + b
}

/// Multiply function
#[no_mangle]
pub extern "C" fn multiply(a: i32, b: i32) -> i32 {
    a * b
}

/// Factorial function
#[no_mangle]
pub extern "C" fn factorial(n: i32) -> i32 {
    if n <= 1 {
        1
    } else {
        (2..=n).product()
    }
}

/// Fibonacci function
#[no_mangle]
pub extern "C" fn fibonacci(n: i32) -> i32 {
    if n <= 1 {
        n
    } else {
        let mut a = 0;
        let mut b = 1;
        for _ in 2..=n {
            let temp = a + b;
            a = b;
            b = temp;
        }
        b
    }
}