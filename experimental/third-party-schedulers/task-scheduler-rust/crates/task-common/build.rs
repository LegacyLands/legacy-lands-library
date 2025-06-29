fn main() {
    tonic_build::configure()
        .build_server(false)
        .build_client(true)
        .compile_protos(
            &["proto/task_scheduler.proto", "proto/task_crd.proto", "proto/auth.proto"],
            &["proto"],
        )
        .unwrap_or_else(|e| panic!("Failed to compile protos: {}", e));
}
