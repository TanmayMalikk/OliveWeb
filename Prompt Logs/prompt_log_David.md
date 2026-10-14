# AI Prompt Interaction Log - D.E.A.L.S. (Olive Branch)
**Sprint:** Sprint 1 Review & Phase 1 Planning
**Team Member:** David
**Goal:** Track AI collaboration, prompt efficiency, and architectural problem-solving.

### 1. Objective: Define Real-World Safety Mitigation in Design Document
* **Prompt 1:** "improve this and give me a title for it 'While not a technical aspect, A difficulty to face in making D.E.A.L.S is that the risk of homicide and physical harm do spike...'"
* **Result:** AI provided a refined section (3.6.1 User Safety and Real-World Threat Mitigation) but needed to clarify specific technical constraints to match the architecture. 
* **Time Taken:** ~5 minutes.
* **Efficiency Note:** Providing the raw text outline in the initial prompt allowed for an immediate, polished output that matched the required academic/professional tone.

### 2. Objective: Establish Client/Server Hardware Specifications
* **Prompt 1:** "what would go in teh hardware specifications"
* **Result:** Successfully generated Section 3.3 (Hardware Specifications), identifying specific Android sensors required (GPS, Mic, Camera) and acknowledging the zero-maintenance serverless infrastructure via Google Cloud and Cloudflare R2.
* **Time Taken:** ~5 minutes.

### 3. Objective: Debug Gradle Sync and False-Positive IDE Errors (PR Verification)
* **Prompt 1:** "teammates wants me to verify the changes I made and verify that they were correctly implemented so we can merge"
* **Prompt 2 (Refinement):** "pulled onto a separate device and got this [screenshot of @DocumentId errors in Models.kt]"
* **Prompt 3 (Refinement):** "trying to run the test cases so I can confirm it's just a visual bug, but... [screenshot of npm install failing]"
* **Result:** Identified that Android Studio's Kotlin K2 analyzer was throwing false-positive `MISSING_DEPENDENCY_SUPERCLASS` errors due to a missing local JDK path (`$env:JAVA_HOME`) on the new device, rather than actual code failures.
* **Time Taken:** ~25 minutes.
* **Efficiency Note:** Using screenshots of the IDE and terminal errors drastically reduced debugging time. Discovered that Node.js (`npm`) is not required for Kotlin compilation, redirecting efforts to the Gradle UI wrapper.

### 4. Objective: Update NoSQL Database Schema Documentation
* **Prompt 1:** "update with current user class 'data class User...'"
* **Prompt 2 (Refinement):** "this is updated code 'data class User... I want you to update this sections 'Collection: users..."
* **Result:** Successfully mapped Kotlin data classes (`User`, `Contact`, `Address`, `FileMetadata`) to standard NoSQL Markdown tables, mapping out exact data types, nullable constraints, and embedded objects.
* **Time Taken:** ~10 minutes.

### 5. Objective: Architect Secure Group Chat for Staff Handovers
* **Prompt 1:** "we are thinking of implementing a group chat, where the trusted contact, admin/support staff and the user can all connect... "
* **Prompt 2 (Challenge):** "why do I need cse for the group chat"
* **Prompt 3 (Refinement):** "Ignoring what the team agreed upon, what are the options(best) for implementing this group chat without compromising the user and their security"
* **Prompt 4 (Final Decision):** "could we have a blend of 3 and 4"
* **Result:** Designed a Hybrid Encryption model. Developed a single symmetric AES "Room Key" for performance, which is encrypted three times (User Public Key, Contact Public Key, KMS Master Key) to allow seamless support staff rotations without compromising zero-knowledge privacy.
* **Time Taken:** ~30 minutes.
* **Efficiency Note:** Challenging the AI on *why* CSE was needed, led to exploring multiple architectural pathways (WebRTC, Ephemeral TTL). Blending options resulted in a custom, enterprise-grade KMS escrow solution.

### 6. Objective: Integrate Media Compression with Hybrid Encryption
* **Prompt 1:** "the team has the goal of compression large files on device to save on firebase free limit, how does this integrate/change this addition?"
* **Result:** Established a strict on-device execution rule: **Order of Operations (Compress -> Encrypt -> Upload)**. If encrypted first, the ciphertext entropy makes compression mathematically impossible. 
* **Time Taken:** ~5 minutes.

### 7. Objective: Sprint Planning & Task Prioritization
* **Prompt 1:** "[List of Jira tasks including offline functionality, group chat, R2 compression, safety plan] I also got assigned my next tasks, I want you to suggest the optimal order to tackle these for peak effectiveness and productivity"
* **Result:** AI re-ordered the Jira priorities to prevent monolithic file sizes (enforcing the 250-line limit) and avoid rewriting network logic. 
* **Time Taken:** ~10 minutes.
* **Efficiency Note:** Providing the full scope of upcoming tasks allowed the AI to identify that offline functionality (WorkManager/Room) must be built *before* the Safety Plan UI to avoid major refactoring later.

---

**Sprint:** Sprint 2 Dev

### 8. Objective: Evaluate Android Keystore Threat Model and Background Notification Architecture
* **Prompt 1:** "Our PM was discussing some issues with Android Keystore, something about it not being secure? What is that about? Additionally, we were considering pivoting from Android WorkManager, at least just for notifications, as we found we were frequently waiting for WorkManager to read through all users' data to process if there were new messages/notifications (took a good amount of time and we need speed). However, FCM seemed not to have this issue, and it also seemed to offer us more reads and writes per user (i.e., more scalable). We determined the issue with the delayed notifications also (or entirely, I am currently unsure) stemmed from Android delaying background activity, as when we opened the app, the message notifications came in instantly."
* **Result:** Addressed the PM's security concerns by clarifying that Android Keystore's vulnerability in this context is physical coercion, which the team's app-specific PIN logic already mitigates. Confirmed the team's diagnosis regarding WorkManager: Android's "Doze Mode" intentionally delays WorkManager jobs. Recommended a hybrid architecture using FCM Data Messages as an invisible "doorbell" to bypass Doze mode and instantly wake WorkManager to process the encrypted payloads.
* **Time Taken:** ~10 minutes.

### 9. Objective: Verify Real-Time Messaging Integrity with FCM Pivot
* **Prompt 1 (Refinement):** "Currently, if both users in a chat are actively in the chat, they get messages instantly. Would implementing this architecture change that?"
* **Result:** Confirmed the architecture pivot establishes a non-disruptive dual-state system. The app will maintain its fast Firestore WebSocket connection while in the foreground, and only rely on the FCM-to-WorkManager pipeline when the app is backgrounded or the device is locked.
* **Time Taken:** ~10 minutes.
* **Efficiency Note:** Clarifying OS-level battery constraints validated the team's testing and prevented an unnecessary full teardown of the real-time Firestore listeners.

### 10. Objective: Define Encryption Target and Key Storage Architecture for Incident Notes
* **Prompt 2:** "What kind of encryption is this running in DeviceStore.kt?"
* **Prompt 3:** "Let's start drafting the EncryptionUtils.kt."
* **Prompt 4:** "Give me the pros and cons of both key storages, and this file would go in the security directory as a class file yes?"
* **Result:** Clarified that notes serve as the victim's private evidence logs. Drafted `EncryptionUtils.kt` as a Singleton object using AES-256 GCM. Evaluated key storage strategies and selected Envelope Encryption (Unique AES Key per note). This ensures granular sharing: a victim can safely share a single incident note with support staff without exposing their entire database history.
* **Time Taken:** ~15 minutes.

### 11. Objective: Integrate Cryptographic Functions into the Notes UI Pipeline
* **Prompt 1:** "What would we need to integrate these functions into the notes UI?"
* **Result:** Defined a `NotesRepository.kt` boundary to separate heavy cryptographic processing from the UI, enforcing the 250-line file limit. Outlined an offline-first pipeline: the UI passes plaintext to the repository, which generates an AES key, encrypts the text, wraps the key via `KeyManager`, and saves a new `CloudNote` model locally to `DeviceStore`. WorkManager then picks up the `pendingSync` flag to handle the network upload in the background.
* **Time Taken:** ~10 minutes.

### 12. Objective: Determine Scope of Client-Side Encryption for Messaging
* **Prompt 1:** "I don't think we need to implement CSE on direct texts to a safe contact. I think we only need the CSE through encryptionutils.kt when the user is writing a private note or incident log, or in a group chat with a safe contact/trusted individual and support staff with the goal of implementing a safety plan for them or better understanding their circumstance (as I think these would be the most important parts that the abuser must not get a hold of). Would it be more efficient to just implement encryption for all kinds of chats or would my specified scenarios work best?"
* **Result:** Decided to unify the encryption architecture across all messaging, treating a 1-on-1 direct message simply as a "Group Chat with two people." This prevents architectural bloat (eliminating the need for parallel secure/insecure messaging systems) and removes metadata breadcrumbs, guaranteeing an abuser cannot see when a victim is discussing basic exit strategies with a safe contact.
* **Time Taken:** ~10 minutes.

### 13. Objective: Envelope Encryption Key Generation & Public Key Syncing
* **Core Prompt:** "Handle key generation."
* **Resolution:** Migrated secure message routing to `SecureChatRepository.kt` and implemented `initializeConversationKeys` and `loadRoomKey` to handle 3-way asymmetric AES key wrapping. Updated the `DirectoryUser` model in `MessagingRepository.kt` to automatically upload and fetch RSA Public Keys during account registration and user searches. Established concrete testing protocols (Firestore Console verification, Two-Device verification, and local unit tests) to guarantee cryptographic integrity.
* **Prompts Required to Resolve:** 7 prompts (Iterated through repository redeclaration conflicts, missing cryptography imports, scope/bracket syntax errors, and final testing validation).
* **Time Taken:** ~40 minutes.

### 14. Objective: Connect Key Generation to UI Flow
* **Core Prompt:** "Let's wire up the initializeConversationKeys call to the UI flow."
* **Resolution:** Added `decodePublicKey` to `KeyManager` to parse external X.509 Base64 strings. Updated `startConversation` in `SessionViewModel` to automatically detect new chat rooms, query the `DirectoryUser` for the contact's RSA key, and initialize the AES Room Key before launching the chat. The Client-Side Encryption pipeline is now fully integrated with the UI.
* **Prompts Required to Resolve:** 1.
* **Time Taken:** ~5 minutes.