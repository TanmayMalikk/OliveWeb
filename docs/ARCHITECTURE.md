# Source layout

The project uses feature-first folders. Put new code with the feature that owns it instead of adding files to a general source root.

## Android UI by user type

`app/src/main/java/com/olive/app/` contains the Android application.

- `ui/users/user/dashboard` contains the user's dashboard, activity history, and support resources.
- `ui/users/user/account` contains profile, settings, and app-entry security screens.
- `ui/users/user/notes`, `documents`, and `location` contain those named features.
- `ui/users/safecontact` contains its single role interface and supporting cards, without another feature folder.
- `ui/users/staff` contains the staff dashboard and safety-plan consultations.
- `ui/users/admin` contains the administrator dashboard and account management.
- `ui/users/shared/organization` contains setup and settings screens used by both organization roles.
- `ui/users/shared` contains features used by multiple signed-in roles: `auth`, `camera`, `messaging`, `onboarding`, `safetyplan`, and `navigation`.
- `ui/covers` contains the fitness tracker, game, news, food, and shared cover UI.
- `ui/session` owns authenticated workflow orchestration; it is application infrastructure rather than a user interface feature.
- `ui/shared` contains presentation primitives used across roles.
- `ocr`, `security`, `notification`, and top-level `location` contain platform integrations that are shared across UI features.
- `OliveActivity.kt` is Android's launcher activity. Android creates it when the user opens Olive; it hosts the Compose application, applies privacy protection, and connects platform lifecycle events to the session.
- `app/src/androidTest/java/com/olive/app` mirrors the production features; reusable emulator rules and drivers live in `support`.

`data/account`, `cover`, `location`, `media`, `messaging`, `news`, and `safety` contain models, repositories, persistence, and remote data access for each feature.

Compose screens receive state and actions from controllers or view models. They must not create repositories or import Firebase directly.

### Finding the code behind a screen action

Start at the screen named for the feature. Each major feature exposes one clearly named property on `SessionViewModel`. That property keeps the state and actions used by the screen together. Its action calls a workflow in `ui/session`, and the workflow calls a repository in `data`.

| User action | Screen to open first | Workflow to follow | Data owner |
| --- | --- | --- | --- |
| Sign in or create an account | `ui/users/shared/auth` and `onboarding` | `ui/session/SessionAccountSignIn.kt` and `AccountSessionController.kt` | `data/account` |
| Find a friend or send a message | `ui/users/shared/messaging` | `ui/session/SessionFriendActions.kt` or `SessionConversations.kt` | `data/messaging` |
| Request staff or admin help | `ui/users/user/dashboard` or `ui/users/staff` | `ui/session/StaffSupportController.kt` | `data/messaging/SupportRepository.kt` |
| Edit a safety plan | `ui/users/shared/safetyplan` | `ui/session/SessionSafetyPlans.kt` | `data/safety` |
| Upload or open a document | `ui/users/user/documents` | `SafetyDocumentController.kt` and `EncryptedMediaTransferController.kt` in that folder | `data/media` and `data/safety` |
| Show or share a location | `ui/users/user/location` (also used by the safe-contact dashboard) | `UserLocationController.kt` | `data/location` |

The three most common paths are:

```text
Credentials screen -> session.account.authenticate()
  -> SessionAccountSignIn -> AccountRepository

Conversation screen -> session.messages.sendMessage()
  -> SessionConversations -> SecureChatRepository

Staff-help screen -> session.staffSupport.requestHelp()
  -> StaffSupportController -> SupportRepository
```

`SessionAccountEntry.kt` keeps entry state and actions together. `SessionMessages.kt` does the same for friends and chats. `SessionCoverNotesActivityActions.kt` covers covers, notes and history, while `SessionAccountManagementActions.kt` covers settings and admin accounts. `SessionViewModel.kt` holds the remaining shared screen state and clears screen controllers when the session ends. `SessionRuntimeCore.kt` creates workflows and holds shared account state; feature behavior belongs in the named workflow file.

Kotlin packages mirror the feature folders. For example, `ui/users/user/notes` declares `com.olive.app.ui.users.user.notes`. The old flat `com.olive.app.ui` package is intentionally empty, so unrelated screens cannot behave like one large global module. `ui.shared` is the small cross-feature presentation API, while `users.shared.navigation` owns root navigation and `SessionViewModel`.

Use classes for mutable state, long-running work, and workflows. `SessionViewModel` coordinates the session controllers, while feature controllers own document, location, and other feature-specific state. A stateless Compose screen can remain a top-level function in its feature package; wrapping it in an object only hides a global behind a singleton. Make helpers `private` by default and use `internal` only for entry points shared with another file or feature. The app module exports no public UI declarations.

Keep closely related composables, helpers, and dialogs in one feature file. Split a file when it approaches 500 lines or contains an independently testable engine or a separate responsibility. Do not create a new file solely for one small helper or one companion composable.

Keep names short and literal. Use the feature shown to the user, such as `dashboard`, `notes`, `account`, and `safetyplan`. Give a source file enough context to identify its contents from its name, such as `UserProfileScreen.kt`. Avoid vague containers such as `workspace`, `shell`, `helpers`, or `misc`. A leaf feature folder must contain at least two source files. If a feature needs only one file, place it beside a closely related feature instead of wrapping it in another directory.

Write Kotlin, TypeScript, and JavaScript for a reader who is still learning: use one statement per line, spaces around operators, braces for control flow, and clear names. Split long conditions and multi-step expressions into named steps. Comments should explain why a rule or workaround exists; the code itself should show what happens.

Every handwritten source file starts with a short comment naming its feature, who uses it, and what the file does. Add simple comments to important code sections when behavior, timing, access, or security rules are not clear from the code. Do not comment every function or variable just to repeat its name.

## Web UI by user type

`web/src/` contains only `main.tsx`, the application entry point, and `styles.css`, the stylesheet entry point.

- `users/user/contacts_and_notes` contains the account owner's contacts and notes.
- `users/safe_contact` is reserved for UI that only safe contacts can access. Current safe-contact workflows reuse the shared safe-app, messaging, and safety-plan features.
- `users/staff` and `users/admin` contain separate organization portals for their distinct responsibilities.
- `users/shared/safe_app` contains the signed-in home, profile, settings, activity, messaging, and safety-plan screens shared across roles.
- `public` contains the public download and landing pages plus the cover apps.
- `components` contains shared presentation components.
- `domain` contains shared types, validation rules, and domain transformations.
- `services` contains browser persistence, encryption, Firebase, messaging, and other external integrations.
- `session` contains the React session provider, context, and security lifecycle.
- `sites` contains hostname and route mapping for the public, download, and administration sites.
- `styles` contains styles split by page or feature.
- `data` contains static JSON catalogs.

## Enforcement

Run `./gradlew checkArchitecture` from the repository root. The architecture check rejects Kotlin files placed directly in the Android `ui` or `data` roots, packages that do not match their feature folders, use of the old flat UI package, public UI declarations, invalid role folders, one-file leaf folders, unexpected files in the web source root, repository construction inside feature screens, and Firebase imports inside those screens. Keeping files reasonably sized is a review guideline rather than a build-breaking check.
