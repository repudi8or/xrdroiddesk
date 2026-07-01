---
type: community
cohesion: 0.13
members: 36
---

# USB Setup & Dialog Automation

**Cohesion:** 0.13 - loosely connected
**Members:** 36 nodes

## Members
- [[.armNonUvcNoPerm()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.armNonUvcWithPerm()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.collectAllText()_1]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.findClickableByText()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.findDevice()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/camera/XRealGlassesCamera.kt
- [[.findNegativeButton()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.findNodeByText()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.findPositiveButton()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.handleCgSettingsScreen()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.handleCgUsbDialog()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.handleDisplayModeChooser()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.handleXrdroideskPermDialog()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.hasUvc()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.launchHidEnable()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.onAccessibilityEvent()_1]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.onCameraOpenFailedNonUvc()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.onCameraOpened()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.onCreate()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/GrantUsbPermissionActivity.kt
- [[.onDeviceAttached()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.onDeviceDetached()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.onUsbPermissionDenied()_1]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.release()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/camera/GlassesUvcEnabler.kt
- [[.requestUvcPerm()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.reset()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.scheduleCameraPermissionRequest()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.setUvcPhaseActive()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[.uiLog()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[AccessibilityNodeInfo]] - code
- [[GlassesUvcEnabler]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/camera/GlassesUvcEnabler.kt
- [[GlassesUvcEnabler.kt]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/camera/GlassesUvcEnabler.kt
- [[Intent]] - code
- [[String]] - code
- [[UsbDevice]] - code
- [[UsbSetupAutomator]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[UsbSetupAutomator.kt]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/UsbSetupAutomator.kt
- [[intentDevice()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/GrantUsbPermissionActivity.kt

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/USB_Setup__Dialog_Automation
SORT file.name ASC
```

## Connections to other communities
- 9 edges to [[_COMMUNITY_Hand Tracking Pipeline]]
- 9 edges to [[_COMMUNITY_UVC Camera & TCP Pilot]]
- 7 edges to [[_COMMUNITY_HID Frame Building & UVC Enable]]
- 4 edges to [[_COMMUNITY_Android UI Components]]
- 3 edges to [[_COMMUNITY_Camera Device Tests]]
- 1 edge to [[_COMMUNITY_MediaPipe Landmark Conversion]]

## Top bridge nodes
- [[UsbDevice]] - degree 13, connects to 3 communities
- [[String]] - degree 9, connects to 3 communities
- [[GlassesUvcEnabler]] - degree 13, connects to 2 communities
- [[.collectAllText()_1]] - degree 6, connects to 2 communities
- [[.onCreate()]] - degree 4, connects to 2 communities
