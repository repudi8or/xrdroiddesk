---
source_file: "app/src/main/kotlin/com/repudi8or/xrdroiddesk/camera/LandmarkToHandData.kt"
type: "code"
community: "MediaPipe Landmark Conversion"
location: "L16"
tags:
  - graphify/code
  - graphify/EXTRACTED
  - community/MediaPipe_Landmark_Conversion
---

# landmarkToHandData()

## Connections
- [[.`pointer pose uses image X and Y of index MCP`()]] - `calls` [INFERRED]
- [[.`thumb and index at same world position gives pinchStrength 1`()]] - `calls` [INFERRED]
- [[.`thumb and index far apart gives pinchStrength 0`()]] - `calls` [INFERRED]
- [[.`untracked input yields untracked HandData`()]] - `calls` [INFERRED]
- [[Boolean]] - `references` [EXTRACTED]
- [[Float]] - `references` [EXTRACTED]
- [[HandData]] - `references` [EXTRACTED]
- [[LandmarkToHandData.kt]] - `contains` [EXTRACTED]
- [[List]] - `references` [EXTRACTED]
- [[Pose]] - `calls` [INFERRED]
- [[Triple]] - `references` [EXTRACTED]
- [[toHandData()]] - `calls` [INFERRED]

#graphify/code #graphify/EXTRACTED #community/MediaPipe_Landmark_Conversion
