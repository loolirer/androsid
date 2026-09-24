# androsid Maturity Matrix

This document defines what maturity means for different aspects of androsid. It is not a scoring system: each dimension is evaluated independently, and the goal is to make "what would improve this?" a concrete, answerable question.

Some of these concerns are already covered by an existing standard for the ROS 2 ecosystem — [REP-2004: Package Quality Categories](https://www.ros.org/reps/rep-2004.html), and this matrix adopts that standard wherever it applies, rather than redefining criteria that already exist. Dimensions specific to a mobile sensor-streaming app (the Android side, the communication protocol, on-device reliability) are defined here instead, since REP-2004 does not cover them.

## Maturity levels

| Level | Meaning |
|---|---|
| **0 — Not implemented** | There is no established practice or capability yet. |
| **1 — Basic** | The capability works for the expected/happy path, but relies mostly on manual processes or implicit knowledge. |
| **2 — Defined** | The behaviour is clearly defined, documented and objectively verifiable. |
| **3 — Mature** | The behaviour is automated or continuously verified, and regressions/failures can be detected reliably. |

Level 3 does not mean "perfect" or "production-grade" — it means the practice is mature enough to be maintained and verified with minimal manual effort.

---

## 1. Sensor & ROS 2 Integration

Covers the core purpose of androsid: making Android sensor data available through ROS 2 in a consistent and predictable way.

| Level | Criteria |
|---|---|
| **0** | There is no defined or functional path from Android sensor data to ROS 2. |
| **1** | The main sensor data can be collected and published through ROS 2 in the expected scenario. |
| **2** | Topics, message types, units, timestamps, coordinate frames and relevant QoS behaviour are explicitly defined and consistently handled. |
| **3** | The Android → ROS 2 interfaces are automatically verified through integration/regression tests and changes to these interfaces can be detected reliably. |

## 2. Communication & Error Handling

Covers communication between the Android application and the local ROS 2 environment, and how the system behaves when things go wrong (dropped connections, malformed data, unavailable sensors, timeouts, reconnects, resource cleanup).

| Level | Criteria |
|---|---|
| **0** | Communication failures and runtime errors are not handled in a defined way. |
| **1** | Common errors are handled enough for the normal use case, but recovery is mostly manual. |
| **2** | Connection failures, malformed/invalid data, timeouts, unavailable sensors, disconnects and resource cleanup have defined behaviour. |
| **3** | Error and recovery paths are automatically tested, and the system can reliably recover from expected failures without manual intervention. |

## 3. Reliability & Performance

Whether androsid can operate reliably on a mobile device for realistic periods of time (CPU/memory/battery usage, streaming stability, latency, behaviour under reconnects).

| Level | Criteria |
|---|---|
| **0** | There is no defined expectation for runtime reliability or resource usage. |
| **1** | The system works reliably during short, normal usage sessions. |
| **2** | Runtime behaviour, resource usage and relevant performance expectations are understood and documented. |
| **3** | Long-running and performance-sensitive behaviour is automatically tested or continuously monitored, and regressions can be detected. |

## 4. Security

*Partially maps to REP-2004 [Security, 7.i]: a declared Vulnerability Disclosure Policy. androsid adds mobile-specific concerns REP-2004 doesn't address.*

| Level | Criteria |
|---|---|
| **0** | Security considerations are not explicitly addressed. |
| **1** | Required Android permissions, network exposure and security assumptions are identified and understood. |
| **2** | Security-sensitive behaviour is documented and common risks are explicitly mitigated. Includes a Vulnerability Disclosure Policy, per REP-2004 [7.i]. |
| **3** | Security checks are automated where possible, sensitive dependencies/configurations are monitored, and security regressions can be detected continuously. |

Relevant aspects: Android permissions, network interfaces and exposed ports, validation of data received over the socket, dependency vulnerabilities, protection of sensitive sensor data, secure configuration of the local ROS 2 environment.

## 5. Testing & Code Quality

*Maps to REP-2004 [Testing, 4] and [Change Control Process, 2.iv] (CI). Deferred to a lighter bar for now —> see phase note above and #35.*

| Level | Criteria |
|---|---|
| **0** | There is no established testing strategy or consistent code-quality standard. |
| **1** | Important behaviour can be manually verified and basic coding conventions are followed manually. |
| **2** | Critical logic has automated tests, and linters/formatters are configured for the relevant languages with their configuration versioned in the repository. |
| **3** | Tests, linting and formatting are automatically executed in CI and prevent known regressions or quality issues from being merged, per REP-2004's Testing and Change Control Process requirements ([4], [2.iv]). Tracked in #35. |

Testing should prioritize behaviour critical to the system: sensor data processing, data conversion, message serialization/parsing, communication, error handling, ROS 2 interfaces.

## 6. Architecture & Reproducibility

*Partially maps to REP-2004 [Version Policy, 1] and [Documentation, 3.v] (quality declaration).*

How understandable the system is and how easily another developer can reproduce its environment (Android app, local ROS 2 environment, communication interfaces, build/runtime dependencies).

| Level | Criteria |
|---|---|
| **0** | Important architectural decisions and environment requirements are mostly implicit. |
| **1** | The main components can be understood and the project can be built/run following the existing instructions. |
| **2** | Component responsibilities, interfaces, dependencies and environment requirements are clearly documented and reproducible. |
| **3** | The architecture and build environment are continuously validated through automated checks or clean builds, making regressions difficult to introduce unnoticed. |

## 7. Documentation & Developer Experience

*Maps to REP-2004 [Documentation, 3.i–3.iv]: feature documentation, license, copyright.*

| Level | Criteria |
|---|---|
| **0** | A new contributor cannot reliably understand how to build or run the project. |
| **1** | The main setup and usage flow is documented. |
| **2** | Setup, architecture, development workflow, interfaces and common troubleshooting scenarios are documented. |
| **3** | Documentation is maintained alongside the implementation and important parts of the documented workflow are automatically validated where practical. |

---

## How the matrix should be used

Each dimension is evaluated independently, this is a roadmap, not a single score:

```text
Sensor & ROS 2 Integration           → Level N
Communication & Error Handling       → Level N
Reliability & Performance            → Level N
Security                             → Level N
Testing & Code Quality               → Level N
Architecture & Reproducibility       → Level N
Documentation & Developer Experience → Level N
```

Current state is tracked on the project's GitHub Project board, with one card per dimension. Each level-up is a concrete issue, e.g.:

> **Communication & Error Handling: Level 1 → Level 2**
> Define and document the expected behaviour for connection loss, invalid messages, unavailable sensors and timeouts.

## Principles

- Criteria should be **objective whenever possible** and describe something **verifiable**, not a subjective judgement.
- Level 3 means **automated or continuously verified**, not "perfect".
- Where REP-2004 already defines a criterion, this matrix defers to it instead of duplicating it.
- The matrix focuses on androsid's specific needs, not generic software engineering practice for its own sake.
- Testing prioritizes critical system behaviour, especially the Android/ROS 2 boundary and error-handling paths.
- The matrix is a **roadmap and contribution guide**, evaluating the project as a whole rather than per module — most of androsid's real complexity lives at the boundary between the Android app and the ROS 2 bridge, and splitting per module would fragment exactly the cross-cutting concerns (protocol changes, error handling across the socket) this matrix is meant to surface. It can evolve as the project evolves.