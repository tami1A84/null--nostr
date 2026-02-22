# Makefile — nurunuru-ffi binding generation & cross-compilation targets
#
# Usage:
#   make swift          — generate Swift bindings (macOS host required)
#   make kotlin         — generate Kotlin/Android bindings
#   make ios-device     — compile static lib for iOS device (aarch64)
#   make ios-sim        — compile static lib for iOS Simulator (arm64 + x86_64)
#   make xcframework    — build XCFramework (requires ios-device + ios-sim first)
#   make android-arm64  — compile .so for Android ARM64
#   make android-x86_64 — compile .so for Android x86_64 (emulator)
#   make android-all    — compile all Android ABI targets
#   make clean          — remove all generated output

BINDGEN_DIR := $(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))
CRATE_DIR := $(shell dirname $(BINDGEN_DIR))
IOS_DIR := $(CRATE_DIR)/ios

.PHONY: swift kotlin ios-device ios-sim xcframework android-arm64 android-x86_64 android-all clean

# ─── Swift / iOS ──────────────────────────────────────────────────

swift:
	@bash $(BINDGEN_DIR)/gen_swift.sh

ios-device:
	@echo "==> Building for iOS device (aarch64-apple-ios)..."
	cd $(CRATE_DIR) && cargo build --release --target aarch64-apple-ios

ios-sim-arm64:
	@echo "==> Building for iOS Simulator ARM64 (aarch64-apple-ios-sim)..."
	cd $(CRATE_DIR) && cargo build --release --target aarch64-apple-ios-sim

ios-sim-x86:
	@echo "==> Building for iOS Simulator x86_64 (x86_64-apple-ios)..."
	cd $(CRATE_DIR) && cargo build --release --target x86_64-apple-ios

ios-sim: ios-sim-arm64 ios-sim-x86
	@echo "==> Creating fat library for iOS Simulator..."
	mkdir -p $(CRATE_DIR)/target/ios-sim-fat/release
	lipo -create \
		$(CRATE_DIR)/target/aarch64-apple-ios-sim/release/libnurunuru_ffi.a \
		$(CRATE_DIR)/target/x86_64-apple-ios/release/libnurunuru_ffi.a \
		-output $(CRATE_DIR)/target/ios-sim-fat/release/libnurunuru_ffi.a
	@echo "==> Fat simulator library ready."

xcframework: ios-device ios-sim swift
	@echo "==> Creating XCFramework..."
	rm -rf $(IOS_DIR)/NuruNuruFFI.xcframework
	xcodebuild -create-xcframework \
		-library $(CRATE_DIR)/target/aarch64-apple-ios/release/libnurunuru_ffi.a \
		-headers $(BINDGEN_DIR)/swift-out \
		-library $(CRATE_DIR)/target/ios-sim-fat/release/libnurunuru_ffi.a \
		-headers $(BINDGEN_DIR)/swift-out \
		-output $(IOS_DIR)/NuruNuruFFI.xcframework
	@echo "==> XCFramework written to ios/NuruNuruFFI.xcframework"
	@echo "==> Copy the generated Swift file alongside your project and add the XCFramework."

# ─── Kotlin / Android ─────────────────────────────────────────────

kotlin:
	@bash $(BINDGEN_DIR)/gen_kotlin.sh

android-arm64:
	@echo "==> Building for Android ARM64 (aarch64-linux-android)..."
	cd $(CRATE_DIR) && cargo ndk -t aarch64-linux-android build --release
	mkdir -p $(CRATE_DIR)/android/libs/arm64-v8a
	cp $(CRATE_DIR)/target/aarch64-linux-android/release/libnurunuru_ffi.so \
	   $(CRATE_DIR)/android/libs/arm64-v8a/

android-x86_64:
	@echo "==> Building for Android x86_64 (x86_64-linux-android)..."
	cd $(CRATE_DIR) && cargo ndk -t x86_64-linux-android build --release
	mkdir -p $(CRATE_DIR)/android/libs/x86_64
	cp $(CRATE_DIR)/target/x86_64-linux-android/release/libnurunuru_ffi.so \
	   $(CRATE_DIR)/android/libs/x86_64/

android-all: android-arm64 android-x86_64
	@echo "==> All Android targets built. .so files are in android/libs/."

# ─── Clean ────────────────────────────────────────────────────────

clean:
	rm -rf $(BINDGEN_DIR)/swift-out $(BINDGEN_DIR)/kotlin-out
	rm -rf $(IOS_DIR)/NuruNuruFFI.xcframework
	@echo "Cleaned generated binding output. Run 'cargo clean' separately to remove compiled artifacts."
