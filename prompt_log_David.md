# AI Prompt Interaction Log - D.E.A.L.S. (Olive Branch)
**Sprint:** Sprint 1 Review & Phase 1 Planning
**Team Member:** David
**Goal:** Track AI collaboration, prompt efficiency, and architectural problem-solving.

---

### 1. Objective: Define Real-World Safety Mitigation in Design Document
* **Prompt 1:** "improve this and give me a title for it 'While not a technical aspect, A difficulty to face in making D.E.A.L.S is that the risk of homicide and physical harm do spike...'"
* **Result:** AI provided a refined section (3.6.1 User Safety and Real-World Threat Mitigation) but needed to clarify specific technical constraints to match the architecture. 
* **Time Taken:** ~5 minutes.
* **Efficiency Note:** Providing the raw text outline in the initial prompt allowed for an immediate, polished output that matched the required academic/professional tone.

### 2. Objective: Establish Client/Server Hardware Specifications
* **Prompt 1:** "what would go in teh hardwarespecifications"
* **Result:** Successfully generated Section 3.3 (Hardware Specifications), identifying specific Android sensors required (GPS, Mic, Camera) and acknowledging the zero-maintenance serverless infrastructure via Google Cloud and Cloudflare R2.
* **Time Taken:** ~5 minutes.

### 3. Objective: Debug Gradle Sync and False-Positive IDE Errors (PR Verification)
* **Prompt 1:** "teammates wants me to verify the changes i made and verify that they where correctly implemented so we can merge"
* **Prompt 2 (Refinement):** "pulled onto a seperate device and got this [screenshot of @DocumentId errors in Models.kt]"
* **Prompt 3 (Refinement):** "trying to run the test cases so i can confirm its just a visual bug, but... [screenshot of npm install failing]"
* **Result:** Identified that Android Studio's Kotlin K2 analyzer was throwing false-positive `MISSING_DEPENDENCY_SUPERCLASS` errors due to a missing local JDK path (`$env:JAVA_HOME`) on the new device, rather than actual code failures.
* **Time Taken:** ~15 minutes.
* **Efficiency Note:** Using screenshots of the IDE and terminal errors drastically reduced debugging time. Discovered that Node.js (`npm`) is not required for Kotlin compilation, redirecting efforts to the Gradle UI wrapper.

### 4. Objective: Update NoSQL Database Schema Documentation
* **Prompt 1:** "update with current user class 'data class User...'"
* **Prompt 2 (Refinement):** "this is updated code 'data class User... i want you to update this sections 'Collection: users..."
* **Result:** Successfully mapped Kotlin data classes (`User`, `Contact`, `Address`, `FileMetadata`) to standard NoSQL Markdown tables, mapping out exact data types, nullable constraints, and embedded objects.
* **Time Taken:** ~10 minutes.

### 5. Objective: Architect Secure Group Chat for Staff Handovers
* **Prompt 1:** "we are thinking of implementing a group chat, where the trusted contact, admin/support staff and the user can all connect... "
* **Prompt 2 (Challenge):** "why do i need cse for the group chat"
* **Prompt 3 (Refinement):** "ingoring what the team agreed upon, what are the options(best) for implemetnting this group chat without compormising the user and their security"
* **Prompt 4 (Final Decision):** "could we have a belnd of 3 and 4"
* **Result:** Designed a Hybrid Encryption model. Developed a single symmetric AES "Room Key" for performance, which is encrypted three times (User Public Key, Contact Public Key, KMS Master Key) to allow seamless support staff rotations without compromising zero-knowledge privacy.
* **Time Taken:** ~20 minutes.
* **Efficiency Note:** Challenging the AI on *why* CSE was needed led to exploring multiple architectural pathways (WebRTC, Ephemeral TTL). Blending options resulted in a custom, enterprise-grade KMS escrow solution.

### 6. Objective: Integrate Media Compression with Hybrid Encryption
* **Prompt 1:** "the team has the goal of compression large files on device to save on firebase free limit, how doe sthis integrate/cahnge this additon?"
* **Result:** Established a strict on-device execution rule: **Order of Operations (Compress -> Encrypt -> Upload)**. If encrypted first, the ciphertext entropy makes compression mathematically impossible. 
* **Time Taken:** ~5 minutes.

### 7. Objective: Sprint Planning & Task Prioritization
* **Prompt 1:** "[List of Jira tasks including offline functionality, group chat, R2 compression, safety plan] i also got assigned my next tasks, i want you to suggest the optimal order to tackle these for peak effectivness and productivity"
* **Result:** AI re-ordered the Jira priorities to prevent monolithic file sizes (enforcing the 250-line limit) and avoid rewriting network logic. 
* **Time Taken:** ~10 minutes.
* **Efficiency Note:** Providing the full scope of upcoming tasks allowed the AI to identify that offline functionality (WorkManager/Room) must be built *before* the Safety Plan UI to avoid major refactoring later.