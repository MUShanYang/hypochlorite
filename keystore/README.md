# Nightly signing

Fixed keystore for debug / Nightly APKs so each CI build can upgrade over the previous install.

This is **not** a Play Store upload key. Password is committed on purpose so CI and local `assembleDebug` share one signature.
