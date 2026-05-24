JAVA_HOME  := /Applications/Android Studio.app/Contents/jbr/Contents/Home
ANDROID_SDK := $(HOME)/Library/Android/sdk
ADB        := $(ANDROID_SDK)/platform-tools/adb
EMULATOR   := $(ANDROID_SDK)/emulator/emulator
AVD        := xrdroiddesk_desktop_api34
APP_ID     := com.repudi8or.xrdroiddesk
ACTIVITY   := $(APP_ID)/.MainActivity
APK        := app/build/outputs/apk/debug/app-debug.apk

export JAVA_HOME

.DEFAULT_GOAL := help

# ── Build ────────────────────────────────────────────────────────────────────

.PHONY: build
build:  ## Assemble debug APK
	./gradlew :app:assembleDebug

.PHONY: clean
clean:  ## Delete all build outputs
	./gradlew clean

# ── Quality ──────────────────────────────────────────────────────────────────

.PHONY: test
test:  ## Run JVM unit tests
	./gradlew :app:testDebugUnitTest

.PHONY: lint
lint:  ## Run ktlint check
	./gradlew :app:ktlintCheck

.PHONY: fmt
fmt:  ## Auto-fix ktlint violations
	./gradlew :app:ktlintFormat

.PHONY: check
check: test lint  ## Run tests + lint (CI gate)

# ── Device / Emulator ────────────────────────────────────────────────────────

.PHONY: install
install: build  ## Install debug APK on connected device/emulator
	$(ADB) install -r $(APK)

.PHONY: run
run: install  ## Install and launch MainActivity
	$(ADB) shell am start -n $(ACTIVITY)

.PHONY: logcat
logcat:  ## Stream filtered logcat for this app
	$(ADB) logcat --pid=$$($(ADB) shell pidof -s $(APP_ID)) -v time

.PHONY: devices
devices:  ## List connected ADB devices
	$(ADB) devices -l

# ── Emulator ─────────────────────────────────────────────────────────────────

.PHONY: emulator
emulator:  ## Start the desktop AVD (background)
	$(EMULATOR) -avd $(AVD) -no-snapshot-load &
	@echo "Emulator starting — wait ~30s then run: make desktop-mode"

.PHONY: desktop-mode
desktop-mode:  ## Enable freeform/desktop windowing on the active device
	$(ADB) shell settings put global enable_freeform_support 1
	$(ADB) shell settings put global force_resizable_activities 1
	$(ADB) shell settings put global force_desktop_mode_on_external_displays 1
	@echo "Settings applied. Rebooting device..."
	$(ADB) reboot

.PHONY: emulator-stop
emulator-stop:  ## Kill the running emulator
	$(ADB) emu kill

# ── Physical device (ADB over WiFi) ──────────────────────────────────────────

.PHONY: adb-wifi-enable
adb-wifi-enable:  ## Switch physical device to TCP/IP mode (run once while plugged in)
	$(ADB) tcpip 5555
	@echo "Unplug USB then run:  make adb-wifi-connect DEVICE_IP=<ip>"
	@echo "Find IP: Settings > About phone > Status > IP address"

.PHONY: adb-wifi-connect
adb-wifi-connect:  ## Connect to device over WiFi  (DEVICE_IP=x.x.x.x)
ifndef DEVICE_IP
	$(error DEVICE_IP is not set — usage: make adb-wifi-connect DEVICE_IP=192.168.x.x)
endif
	$(ADB) connect $(DEVICE_IP):5555

.PHONY: adb-wifi-disconnect
adb-wifi-disconnect:  ## Disconnect WiFi ADB
	$(ADB) disconnect

# ── Accessibility service ─────────────────────────────────────────────────────

.PHONY: accessibility-check
accessibility-check:  ## Check if GestureAccessibilityService is enabled on device
	@$(ADB) shell settings get secure enabled_accessibility_services \
	  | grep --color=always xrdroiddesk \
	  || echo "Service NOT enabled — go to Settings > Accessibility > xrdroiddesk"

# ── Help ─────────────────────────────────────────────────────────────────────

.PHONY: help
help:  ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*##' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*## "}; {printf "  \033[36m%-24s\033[0m %s\n", $$1, $$2}'
