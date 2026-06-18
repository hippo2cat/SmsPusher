use std::{env, path::PathBuf};

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let workspace_root = manifest_dir
        .ancestors()
        .nth(4)
        .expect("workspace root")
        .to_path_buf();
    let boringssl_dir = workspace_root.join("third_party/boringssl");
    println!("cargo:rerun-if-changed={}", boringssl_dir.display());

    let mut config = cmake::Config::new(&boringssl_dir);
    config
        .define("BUILD_SHARED_LIBS", "OFF")
        .define("OPENSSL_NO_ASM", "1")
        .define("CMAKE_POSITION_INDEPENDENT_CODE", "ON");

    let dst = config.build();
    println!("cargo:rustc-link-search=native={}/lib", dst.display());
    println!("cargo:rustc-link-lib=static=crypto");

    if cfg!(target_os = "macos") {
        println!("cargo:rustc-link-lib=c++");
    } else if cfg!(target_os = "windows") {
        println!("cargo:rustc-link-lib=static=stdc++");
    } else {
        println!("cargo:rustc-link-lib=stdc++");
    }
}
