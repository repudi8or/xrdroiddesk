---
type: community
cohesion: 0.19
members: 14
---

# Camera Device Tests

**Cohesion:** 0.19 - loosely connected
**Members:** 14 nodes

## Members
- [[.`findDevice ignores devices with correct PID but wrong VID`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/XRealGlassesCameraTest.kt
- [[.`findDevice ignores devices with correct VID but wrong PID`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/XRealGlassesCameraTest.kt
- [[.`findDevice returns device matching XReal VID and PID`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/XRealGlassesCameraTest.kt
- [[.`findDevice returns null when device list is empty`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/XRealGlassesCameraTest.kt
- [[.`findDevice returns null when no device matches VID and PID`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/XRealGlassesCameraTest.kt
- [[.mockUsbDevice()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/XRealGlassesCameraTest.kt
- [[.onDestroy()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/GrantUsbPermissionActivity.kt
- [[.setUp()_1]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/XRealGlassesCameraTest.kt
- [[Activity]] - code
- [[GrantUsbPermissionActivity]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/GrantUsbPermissionActivity.kt
- [[GrantUsbPermissionActivity.kt]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/GrantUsbPermissionActivity.kt
- [[UsbManager]] - code
- [[XRealGlassesCameraTest]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/XRealGlassesCameraTest.kt
- [[XRealGlassesCameraTest.kt]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/XRealGlassesCameraTest.kt

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/Camera_Device_Tests
SORT file.name ASC
```

## Connections to other communities
- 3 edges to [[_COMMUNITY_USB Setup & Dialog Automation]]
- 2 edges to [[_COMMUNITY_UVC Camera & TCP Pilot]]
- 1 edge to [[_COMMUNITY_Android UI Components]]
- 1 edge to [[_COMMUNITY_HID Frame Building & UVC Enable]]

## Top bridge nodes
- [[.mockUsbDevice()]] - degree 7, connects to 2 communities
- [[XRealGlassesCameraTest]] - degree 10, connects to 1 community
- [[GrantUsbPermissionActivity]] - degree 5, connects to 1 community
- [[UsbManager]] - degree 3, connects to 1 community
- [[.setUp()_1]] - degree 2, connects to 1 community
