JAVA_HOME  := /Applications/Android Studio.app/Contents/jbr/Contents/Home
ANDROID_SDK := $(HOME)/Library/Android/sdk
# Override with: make install DEVICE=192.168.1.230:35717  (or set ANDROID_SERIAL env var)
ADB        := $(ANDROID_SDK)/platform-tools/adb $(if $(DEVICE),-s $(DEVICE),)
EMULATOR   := $(ANDROID_SDK)/emulator/emulator
AVD        := xrdroiddesk_desktop_api34
APP_ID     := com.repudi8or.xrdroiddesk
ACTIVITY   := $(APP_ID)/.MainActivity
CG_PKG     := com.xreal.glassescontrol.store
FRIDA_SCRIPT := frida/hook_cg_hid.js
APK        := app/build/outputs/apk/debug/app-debug.apk
MODEL_URL  := https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task
MODEL_DIR  := app/src/main/assets
MODEL_FILE := $(MODEL_DIR)/hand_landmarker.task

export JAVA_HOME

.DEFAULT_GOAL := help

# ── Setup ────────────────────────────────────────────────────────────────────

.PHONY: download-model
download-model:  ## Download MediaPipe hand_landmarker.task into assets/
	@mkdir -p $(MODEL_DIR)
	@if [ -f "$(MODEL_FILE)" ]; then \
	  echo "Model already present: $(MODEL_FILE)"; \
	else \
	  echo "Downloading hand_landmarker.task..."; \
	  curl -L -o "$(MODEL_FILE)" "$(MODEL_URL)"; \
	  echo "Saved to $(MODEL_FILE)"; \
	fi

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

LOG_FILE := /data/local/tmp/xr_log.txt
LOG_TAGS  := '*:S' XRealGlassesCamera:D GestureA11yService:I GlassesUvcEnabler:I Requirements:I MainActivity:I ActivityManager:I H264FrameDecoder:W HandLandmarker:I GestureRecognizer:I GestureDispatcher:I DesktopController:I

.PHONY: logcat
logcat:  ## Stream filtered logcat for this app
	$(ADB) logcat --pid=$$($(ADB) shell pidof -s $(APP_ID)) -v time

.PHONY: log-start
log-start:  ## Start on-device filtered log capture (survives ADB disconnect); clears previous log
	$(ADB) shell "kill \$$(ps -A | grep logcat | grep ' 1 ' | awk '{print \$$2}') 2>/dev/null; logcat -c; rm -f $(LOG_FILE); nohup logcat -v time $(LOG_TAGS) > $(LOG_FILE) 2>&1 &"
	@sleep 1
	@$(ADB) shell "ps -A | grep logcat | grep ' 1 ' | awk '{print \"  logcat running: pid=\"\$$2}'"

.PHONY: log-pull
log-pull:  ## Pull the on-device log file to stdout
	$(ADB) shell "cat $(LOG_FILE) 2>/dev/null || echo '(log file not found)'"

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

.PHONY: usb-permission-status
usb-permission-status:  ## Show USB device permissions and default app for XReal glasses
	@echo "=== Full USB permissions_manager section ==="
	@$(ADB) shell dumpsys usb | grep -A 20 "permissions_manager"
	@echo ""
	@echo "=== Apps registered for XReal device (VID=13080/PID=1078) ==="
	@$(ADB) shell dumpsys usb | grep -B 5 "vendor_id=13080" | grep "package_name" || echo "  (none)"
	@echo ""
	@echo "=== All USB-registered apps (wildcard / any vendor) ==="
	@$(ADB) shell dumpsys usb | grep -B 3 "vendor_id=-1" | grep "package_name" || echo "  (none)"
	@echo ""
	@echo "NOTE: UIDs shown in device_permissions without grant/deny value."
	@echo "  If hasPermission() returns false, the UID entry is likely stored as DENIED."
	@echo "  Fix: unplug glasses → replug → pick xrdroiddesk from the USB app chooser."
	@echo "  Do NOT tap 'Request USB Permission' in the app before seeing the chooser."

# ── Frida RE ─────────────────────────────────────────────────────────────────

FRIDA_SERVER_DIR := /data/local/tmp

.PHONY: frida-server-push
frida-server-push:  ## Push frida-server to device (download from releases.frida.re first)
	@if [ -z "$(FRIDA_SERVER)" ]; then \
	  echo "Usage: make frida-server-push FRIDA_SERVER=path/to/frida-server-arm64"; \
	  exit 1; \
	fi
	$(ADB) push $(FRIDA_SERVER) $(FRIDA_SERVER_DIR)/frida-server
	$(ADB) shell chmod +x $(FRIDA_SERVER_DIR)/frida-server
	@echo "frida-server pushed — start with: make frida-server-start"

.PHONY: frida-server-start
frida-server-start:  ## Start frida-server on device (background, requires root or dev mode)
	$(ADB) shell "nohup $(FRIDA_SERVER_DIR)/frida-server &"
	@echo "frida-server started"

.PHONY: frida-server-stop
frida-server-stop:  ## Kill frida-server on device
	$(ADB) shell "pkill frida-server || true"

.PHONY: frida-hook-cg
frida-hook-cg:  ## Hook CG USB transfers — attach to running CG process
	@echo "Ensure glasses are NOT plugged in yet. Press Ctrl-C to stop."
	@echo "Workflow: run this → plug glasses → watch msgId log"
	$(ADB) shell am start -n $(CG_PKG)/.ui.main.MainActivity
	@sleep 1
	frida -U -n $(CG_PKG) -l $(FRIDA_SCRIPT) --no-pause

.PHONY: frida-hook-cg-spawn
frida-hook-cg-spawn:  ## Spawn CG fresh under Frida then wait for USB attach
	@echo "Spawning CG under Frida — then plug glasses in"
	frida -U -f $(CG_PKG) -l $(FRIDA_SCRIPT) --no-pause

# ── Help ─────────────────────────────────────────────────────────────────────

.PHONY: help
help:  ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*##' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*## "}; {printf "  \033[36m%-24s\033[0m %s\n", $$1, $$2}'
