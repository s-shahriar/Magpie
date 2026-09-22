// Generates the Kotlin bindings for the Android module.
// Run via: cargo run --bin uniffi-bindgen -- generate --library <.so> --language kotlin --out-dir <dir>
fn main() {
    uniffi::uniffi_bindgen_main()
}
