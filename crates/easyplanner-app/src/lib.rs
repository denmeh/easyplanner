uniffi::setup_scaffolding!();

pub use easyplanner::*;


#[uniffi::export]
pub fn add(left: u64, right: u64) -> u64 {
    left + right
}

