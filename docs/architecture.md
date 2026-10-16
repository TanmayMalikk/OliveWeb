# Code organization

Handwritten source files should stay below 400 lines where practical, with a hard
limit of 500 lines. Split by responsibility rather than hiding code in dense lines.
Generated output, dependencies, translation resources and lockfiles are data or
build artifacts and are not part of this source-code limit.

## Android session

`SessionViewModel` is the Android lifecycle adapter and the API used by screens.
It exposes read-only observable state, except for the existing checkout flag.
Implementation details live in `ui/session`:

| Component | Responsibility |
| --- | --- |
| `SessionState` | Observable presentation state |
| `SessionRuntime` | Dependencies, account identity, jobs and cancellation epoch |
| `SessionAuthentication` | Startup restoration, sign-in, onboarding and sign-out |
| `SessionAccess` | Cover triggers and PIN verification |
| `SessionNavigation` | Navigation history, background locking and quick exit |
| `SessionOperations` | Busy state, operation cancellation, timeouts and failures |
| `OrganizationAccounts` | Staff/admin provisioning and profile workflows |
| `SessionMessaging` | Messaging connection and live subscriptions |
| `FriendActions` | Directory searches and friend requests |
| `SessionConversations` | Conversation selection, messages and read markers |
| `SessionPrivacy` | Capture detection and inactivity locking |
| `SessionCoverState` | Saved food/news state, addresses and checkout records |
| `SessionHistory` | Activity refresh and history synchronization |
| `SessionNotes` | Note editing, pinning, trash and restoration |
| `SessionSettings` | Profile, password, trigger, PIN and contact settings |

Controllers share one lifecycle scope and cancellation epoch. They do not create
independent background scopes. Quick Exit still cancels in-flight operations, and
PIN verification still checks the active session before opening private screens.

The refactor preserves the ViewModel's public API, storage names, serialized data,
account IDs and navigation/test tags. Local and Firebase account repositories now
have separate files. `DeviceStore` handles account/PIN storage and delegates
activity history and private entries to `DeviceActivityStore` using the same vault.

## Android screens

Onboarding is divided into account entry, profile setup, organization setup,
shared controls, progress geometry and olive rendering. Validation is independent
of rendering in `ProfileSetupValidation`.

Private screens are divided into home, profile, settings, contacts, messages,
chat, notes and activity history. Food cover files separate discovery, dishes,
cart, account and shared presentation. News separates the cover shell, feed
snapshot/actions, feed components, story cards and article reader. Calculator
files separate the shell, display/history, scientific key dispatch, graph editor,
graph drawing, solvers and analysis tools.

## Backend

`functions/index.js` keeps the callable export names stable. Implementations live
in `functions/src`: Firebase initialization, account policy, storage helpers,
admin accounts, organization accounts and account deletion. Importing policy
does not initialize Firebase or perform network requests.

## Verification

- `gradlew.bat checkSourceSize` verifies source sizes across Android, backend,
  scripts and Gradle code. Android's `check` task also runs it.
- `gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest -PoliveLocalTest=true -PoliveBuildDir=build-refactor`
  builds and tests against local fictional accounts.
- Run Android UI tests on an emulator with the local test APKs.
- Run `node --test` from `functions` for backend policy and endpoint-contract tests.

Database security rules, authentication policy and deployed endpoint names are
unchanged. Real Firebase workflows still require a configured integration test
environment; local tests cannot prove live service behavior.
