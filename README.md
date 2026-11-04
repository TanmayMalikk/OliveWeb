# Project Overview
Olive Branch is an Android application supporting Android 9 (API 28) and newer.
its purpose is to help victims plan and escape from Domestic Violence.
This is a student Work in Progress for Grant Macewan University.
Educational purposes only.

---------------------------------------------
# Installation
Currently, there is no way to download the .apk file directly to your android device.
Instead, Clone this GitHub repository "https://github.com/macewanCS/F26_D1.git" and open the repository in Android Studio.
Then run the Gradle build/sync, to compile the application
then you can run the application on an emulated or physical device.
---------------------------------------------
# Testing
## UNIX Based Systems
In the Android Studio terminal, run the standard suite (Android JVM/build/lint/architecture checks, web tests/build/browser accessibility checks, Cloud Functions tests/audit, and Firebase rules tests):
```bash
./test-all.sh
```

To also run isolated Android UI tests on a disposable emulator:

```bash
./test-all.sh --with-device-tests
```

Requires Python 3, Node/npm, the Android SDK/JDK, and Chrome for Playwright. Device tests erase the emulator's Olive data and use test PIN `246810`; physical phones are rejected. See [Testing](docs/TESTING.md) for configuration, skip options, and reports.

if you only want to perform unit tests.
run command
```bash
./gradlew testDebugUnitTest check
./gradlew check
```

## Windows Systems
in the Android Studio terminal
While having an emulator running the Olive app.
run the following command
```bash
./test-all.bat -WithDeviceTests
```

If you only want to perform unit tests.
run command
```bash
./test-all.bat 
```
--------------------------------

# Features

This inventory describes the implementation on the `readme-update` branch, reviewed against the [team's Jira site](https://cmpt395f26d1.atlassian.net) on November 3, 2026. Olive Branch remains a student project in development. A capability being present in the source does not mean it has completed acceptance testing or is deployed. Items still tracked as unfinished are listed below.

## Cover apps and discreet entry

Users can choose between four Android cover apps. The selected cover controls the visible interface and launcher identity.

| Cover | Features present in the Android implementation |
| --- | --- |
| **Olive** (default) | Food-delivery-style menu, dish details and ingredient choices, search, cart, delivery-address entry, checkout screens, and account settings. The menu is a demo catalog; a real delivery-service integration is not included. |
| **Daily Digest** | News feed and article reader, with cover settings and private-entry controls. Fetching new articles requires connectivity. |
| **Fitness Tracker** | Fitness profile, food and meal logging, calorie and nutrient totals, workout tracking, history charts, and phone/watch fitness summaries. Sensor data depends on hardware and permissions. |
| **Game** | A simple interactive score-based game, nickname field, and cover settings. The Android game is a basic cover; expanded gameplay remains backlog work. |

- Configurable trigger phrases provide an entry route from cover inputs. Hidden-icon and gesture entry controls are also present; the available controls depend on the cover.
- First-time entry leads to sign-in or account creation; enrolled accounts continue through the configured private-entry check.
- Users can select an app PIN or Android device-lock authentication, with an app-PIN alternative where enrolled.
- Quick Exit returns from the private workspace to the cover. Backgrounding and inactivity also participate in the private-session lock flow.

## Accounts, onboarding, and user roles

- Role-specific registration, sign-in, onboarding, profiles, and settings.
- Profile information includes language, location, contact details, and optional information used for safety planning.
- Settings support password changes, entry-security configuration, trigger changes, and cover selection.
- Firebase-backed accounts support shared services when configured; local development accounts provide a separate testing path.

| Role | Main capabilities |
| --- | --- |
| **User** | Private dashboard, safety plans, safe contacts, notes, documents, camera, messages, support resources, activity history, and location sharing. |
| **Safe Contact** | A connection-focused dashboard, messaging, private notes, and access to safety plans, locations, and other items explicitly shared with the account. |
| **Staff** | Organization dashboard, assigned-user support, support conversations, safety-plan consultations, availability controls, organization settings, and map views. |
| **Administrator** | Organization overview and account counts, managed staff/admin account creation, permitted account deletion, support messages, organization settings, safety-plan and map views. |

## Safety planning and support

- Editable safety-plan checklists and notes, including primary and backup planning sections.
- Local plan persistence, queued synchronization, and field-level conflict resolution between local and server changes.
- Explicit sharing of a safety plan with selected accepted contacts, with access checks and revocation handling.
- Staff-help requests and consultation workflows connect users with assigned support staff. The implementation includes staff availability and consultation management.
- A safe-contact directory records relationship details and whether a contact offers a safe place to stay. Entries can be linked to accepted chat friends.
- A bundled Canadian hotline and support-resource directory provides region/location selection and resource contact actions. Broader location-based hotline/EMS work remains in the backlog.

## Messaging and trusted connections

- Account discovery, friend requests, accepted connections, and friend-list management.
- Private conversations with client-side message encryption and recipient key handling.
- Conversation archiving and private notes associated with individual contacts.
- Support conversations for user/staff assistance and organization support workflows.
- Sharing flows for private entries, documents, safety plans, and live location through selected connections. Media/document sharing is still tracked as in progress in Jira.
- Cover-appropriate local notifications use neutral wording rather than exposing private message bodies or sender names. Server-side notification delivery remains backlog work.

## Private notes, documents, and camera

- Searchable private-note boards with full-screen editing, pinning, trash, restoration, and an edit history.
- Document selection, upload, listing, download, viewing, deletion, and sharing controls.
- In-app camera capture and attachment selection for document and messaging workflows.
- On-device optical character recognition (OCR), with extracted-text review in the capture/upload flow.
- Client-side media encryption, encrypted metadata handling, and transfer progress/retry infrastructure. Upload recovery and encrypted document retrieval still have verification work open in Jira.
- Private activity history groups account interactions and supports search and date/time filtering.

## Maps, navigation, and location sharing

- Device-location display with permission-aware location access.
- Live-location sharing with selected contacts, including controls to add or remove recipients and stop sharing.
- Map views for locations shared with the signed-in account.
- Route selection and navigation toward a selected location, including route refresh as the device moves.
- Address suggestions and navigation integrations use Geoapify and Mapbox configuration. Network access, configured service keys, and applicable device permissions are required.

## Privacy and access controls

- A private workspace gated separately from the visible cover, with failed-entry handling and session locking.
- Encrypted local vault storage and Android Keystore integration for protected account data and device-lock entry.
- Client-side encryption paths for messages, private entries, and uploaded media, plus server-side ownership and role checks for shared services.
- Screen-capture and recent-app-preview protections are implemented behind the `screen_capture_protection_enabled` resource toggle, which is currently **false**. These protections are disabled in the current configuration; behavior when enabled also depends on the Android version.
- Explicit recipient selection and access checks for shared content. Staff and safe contacts do not automatically receive unrestricted access to a user's private workspace.

Security implementation and verification are ongoing. These controls are implementation details of the educational prototype, not a claim of independently audited protection.

## Languages and accessibility

- The current Android language selector offers **English, French, Spanish, Arabic, and Chinese**, with system-language selection and translated resource files.
- Accessibility support includes screen-reader labels and traversal order, headings, live status announcements, keyboard/focus handling, and responsive screen layouts.
- Accessibility and localization checks exist in both unit and device test suites. Translation coverage and usability should be reviewed for each release; dedicated color-blind filters remain backlog work.

## Wear OS companion

- A separate `wear` module provides a fitness companion screen and watch tiles.
- Supported watch sensors supply step and heart-rate information, with phone/watch data synchronization and nutrition summaries.
- Availability depends on a compatible paired watch, sensor support, and granted permissions. Jira tracks smartwatch integration as Done ([SCRUM-217](https://cmpt395f26d1.atlassian.net/browse/SCRUM-217)).

## Web portal

- A companion React/TypeScript application contains Food, News, Fitness, and Game covers, private account views, and separate staff/administrator workspaces.
- Browser enrollment, personal entry links, private-entry checks, Quick Exit, background locking, and an encrypted browser vault are implemented.
- Firebase configuration connects the portal to shared account, messaging, organization, and safety-plan services. Without it, local registration creates a browser-only account that is separate from Android local accounts.
- Responsive layouts and PWA installation support mobile browsers. Android-specific screenshot protection and Keystore behavior do not carry over to the browser.
- The web service worker does not cache private data or provide a fully offline private workspace.

The web portal remains **In Progress** in Jira ([SCRUM-23](https://cmpt395f26d1.atlassian.net/browse/SCRUM-23)). Source availability does not establish that the planned domains or services are deployed. See [the web README](web/README.md) for platform differences and setup.

## Offline behavior and service dependencies

- Locally saved notes, profile/cover changes, and safety-plan drafts have persistence/synchronization paths. Safety-plan edits can remain queued until the connection returns.
- Offline support varies by feature. Do not assume uncached documents, message delivery, live locations, routes, news, or cloud account operations work without a connection.
- Firebase-backed messaging, sharing, organization actions, and synchronization require the corresponding backend configuration and services.
- Fitness data additionally depends on available sensors; camera, location, and notifications depend on device permissions.
