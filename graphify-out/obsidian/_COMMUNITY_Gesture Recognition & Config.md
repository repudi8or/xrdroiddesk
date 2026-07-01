---
type: community
cohesion: 0.13
members: 29
---

# Gesture Recognition & Config

**Cohesion:** 0.13 - loosely connected
**Members:** 29 nodes

## Members
- [[.`Pinch takes priority over simultaneous swipe`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[.`custom pinch threshold is respected`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[.`custom swipe threshold is respected`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[.`resets swipe state when hand becomes untracked`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[.`resumes swipe detection after re-track`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[.`returns Pinch when pinchStrength meets threshold`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[.`returns SwipeLeft when pointer moves left beyond threshold`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[.`returns SwipeRight when pointer moves right beyond threshold`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[.`returns null on first frame — no previous position to diff`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[.`returns null when hand is not tracked`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[.`returns null when pinchStrength is below threshold`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[.`returns null when pointer movement is below swipe threshold`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[.detectSwipe()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizer.kt
- [[.onHandData()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/camera/HandTrackingPipeline.kt
- [[.recognize()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizer.kt
- [[.setUp()_3]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[.tracked()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[.triggerClick()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/controller/GestureActionDispatcher.kt
- [[.triggerDebugClick()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/service/GestureAccessibilityService.kt
- [[.untracked()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[Float]] - code
- [[GestureConfig]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureConfig.kt
- [[GestureConfig.kt]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureConfig.kt
- [[GestureRecognizer]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizer.kt
- [[GestureRecognizer.kt]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizer.kt
- [[GestureRecognizerTest]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[GestureRecognizerTest.kt]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/gesture/GestureRecognizerTest.kt
- [[HandData]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/gesture/HandData.kt
- [[HandData.kt]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/gesture/HandData.kt

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/Gesture_Recognition__Config
SORT file.name ASC
```

## Connections to other communities
- 5 edges to [[_COMMUNITY_MediaPipe Landmark Conversion]]
- 4 edges to [[_COMMUNITY_Hand Tracking Pipeline]]
- 3 edges to [[_COMMUNITY_Gesture Action Dispatcher]]
- 2 edges to [[_COMMUNITY_Desktop Input Injection]]

## Top bridge nodes
- [[Float]] - degree 9, connects to 2 communities
- [[.tracked()]] - degree 15, connects to 1 community
- [[GestureRecognizer]] - degree 10, connects to 1 community
- [[HandData]] - degree 7, connects to 1 community
- [[.detectSwipe()]] - degree 4, connects to 1 community
