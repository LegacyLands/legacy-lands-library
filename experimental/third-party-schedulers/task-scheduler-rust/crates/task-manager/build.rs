fn main() {
    tonic_build::configure()
        .build_server(true)
        .build_client(false)
        .compile_protos(
            &["../task-common/proto/task_scheduler.proto"],
            &["../task-common/proto"],
        )
        .unwrap_or_else(|e| panic!("Failed to compile protos: {}", e));
}
