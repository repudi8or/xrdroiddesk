---
type: community
cohesion: 0.11
members: 28
---

# Hand Tracking Pipeline

**Cohesion:** 0.11 - loosely connected
**Members:** 28 nodes

## Members
- [[.`onHandData does not dispatch when recognizer returns null`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/HandTrackingPipelineTest.kt
- [[.`onHandData feeds HandData into recognizer and dispatches non-null gesture`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/HandTrackingPipelineTest.kt
- [[.`stop closes the camera`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/HandTrackingPipelineTest.kt
- [[.collectAllText()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[.launchOnGlassesDisplay()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[.logFlowState()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[.onAccessibilityEvent()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[.onInterrupt()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[.onServiceConnected()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[.onUnbind()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[.onUsbPermissionDenied()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[.openCamera()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[.setUp()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/HandTrackingPipelineTest.kt
- [[.startHandTracking()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[.startStateLogger()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[.stop()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/camera/HandTrackingPipeline.kt
- [[.stopHandTracking()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[.stopStateLogger()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[.tryConnectCamera()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[AccessibilityEvent]] - code
- [[AccessibilityService]] - code
- [[GestureAccessibilityService]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[GestureAccessibilityService.kt]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[HandTrackingPipeline]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/camera/HandTrackingPipeline.kt
- [[HandTrackingPipeline.kt]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/camera/HandTrackingPipeline.kt
- [[HandTrackingPipelineTest]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/HandTrackingPipelineTest.kt
- [[HandTrackingPipelineTest.kt]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/HandTrackingPipelineTest.kt
- [[Job]] - code

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/Hand_Tracking_Pipeline
SORT file.name ASC
```

## Connections to other communities
- 9 edges to [[_COMMUNITY_USB Setup & Dialog Automation]]
- 6 edges to [[_COMMUNITY_UVC Camera & TCP Pilot]]
- 4 edges to [[_COMMUNITY_Gesture Recognition & Config]]
- 3 edges to [[_COMMUNITY_Gesture Action Dispatcher]]
- 2 edges to [[_COMMUNITY_Hand Landmarker Helper]]
- 1 edge to [[_COMMUNITY_HID Frame Building & UVC Enable]]
- 1 edge to [[_COMMUNITY_Android UI Components]]
- 1 edge to [[_COMMUNITY_MediaPipe Landmark Conversion]]
- 1 edge to [[_COMMUNITY_Desktop Input Injection]]

## Top bridge nodes
- [[GestureAccessibilityService]] - degree 24, connects to 5 communities
- [[HandTrackingPipelineTest]] - degree 9, connects to 3 communities
- [[.startHandTracking()]] - degree 8, connects to 3 communities
- [[.onServiceConnected()]] - degree 7, connects to 3 communities
- [[.collectAllText()]] - degree 6, connects to 3 communities
