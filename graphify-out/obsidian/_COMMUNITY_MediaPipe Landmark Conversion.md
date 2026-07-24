---
type: community
cohesion: 0.22
members: 16
---

# MediaPipe Landmark Conversion

**Cohesion:** 0.22 - loosely connected
**Members:** 16 nodes

## Members
- [[.`pinchStrength decreases as fingers separate`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/LandmarkToHandDataTest.kt
- [[.`pointer pose uses image X and Y of index MCP`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/LandmarkToHandDataTest.kt
- [[.`thumb and index at same world position gives pinchStrength 1`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/LandmarkToHandDataTest.kt
- [[.`thumb and index far apart gives pinchStrength 0`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/LandmarkToHandDataTest.kt
- [[.`untracked input yields untracked HandData`()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/LandmarkToHandDataTest.kt
- [[.landmarks()]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/LandmarkToHandDataTest.kt
- [[LandmarkIndex]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/camera/LandmarkToHandData.kt
- [[LandmarkToHandData.kt]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/camera/LandmarkToHandData.kt
- [[LandmarkToHandDataTest]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/LandmarkToHandDataTest.kt
- [[LandmarkToHandDataTest.kt]] - code - app/src/test/kotlin/com/repudi8or/xrdroiddesk/camera/LandmarkToHandDataTest.kt
- [[List]] - code
- [[Pose]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/gesture/Pose.kt
- [[Pose.kt]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/gesture/Pose.kt
- [[Triple]] - code
- [[landmarkToHandData()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/camera/LandmarkToHandData.kt
- [[toHandData()]] - code - app/src/main/kotlin/com/repudi8or/xrdroiddesk/camera/HandLandmarkerHelper.kt

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/MediaPipe_Landmark_Conversion
SORT file.name ASC
```

## Connections to other communities
- 5 edges to [[_COMMUNITY_Gesture Recognition & Config]]
- 1 edge to [[_COMMUNITY_HID Frame Building & UVC Enable]]
- 1 edge to [[_COMMUNITY_UVC Camera & TCP Pilot]]
- 1 edge to [[_COMMUNITY_Hand Landmarker Helper]]
- 1 edge to [[_COMMUNITY_Hand Tracking Pipeline]]
- 1 edge to [[_COMMUNITY_USB Setup & Dialog Automation]]
- 1 edge to [[_COMMUNITY_Desktop Input Injection]]

## Top bridge nodes
- [[.landmarks()]] - degree 10, connects to 3 communities
- [[landmarkToHandData()]] - degree 12, connects to 2 communities
- [[toHandData()]] - degree 4, connects to 2 communities
- [[List]] - degree 4, connects to 2 communities
- [[Pose]] - degree 3, connects to 1 community
