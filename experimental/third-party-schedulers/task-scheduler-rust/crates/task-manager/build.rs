fn main() {
    tonic_build::configure()
        .build_server(true)
        .build_client(false)
        .file_descriptor_set_path(std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("descriptor.bin"))
        .compile_protos(
            &["../task-common/proto/task_scheduler.proto", "../task-common/proto/auth.proto"],
            &["../task-common/proto"],
        )
        .unwrap_or_else(|e| panic!("Failed to compile protos: {}", e));
}
