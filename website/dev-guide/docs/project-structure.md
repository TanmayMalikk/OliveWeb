# Project structure

## Top-level folders

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/olive/app/ui/users/` | Android screens grouped by account role and feature |
| `app/src/main/java/com/olive/app/ui/session/` | Android session state and feature controllers |
| `app/src/main/java/com/olive/app/data/` | Android repositories, workers, and persisted models |
| `app/src/test/`, `app/src/androidTest/` | Android unit and device tests |
| `wear/` | Wear OS companion app |
| `web/src/` | Browser app, including role-specific screens and shared services |
| `functions/src/`, `firebase/` | Backend functions and Firebase authorization rules and tests |
| `ppocr-sdk/` | On-device document OCR library |
| `docs/` | Testing and distribution documentation |

Android screens render session state and send actions to feature controllers through the view model.
The session owns the mutable state for navigation, accounts, activity, messaging, and dashboards.
Navigation, the messaging list, and the admin dashboard collect read-only state flows. Repositories
own Firebase calls and validate data before it reaches the UI.

The source tree includes three client applications, their shared Firebase backend, and an OCR library.
Each target has its own build and tests. Run `checkArchitecture` from the repository root to verify
Android package layout, source size, and UI dependency boundaries.


### Naming conventions
Use PascalCase in all .kt filenames
ex.
```
SessionViewModel.kt
CameraManager.kt
```

use snake_case for all .xml files
ex.
```
safe_contact.xml
ui_text_1.xml
```

## Add a documentation page

1. Create a Markdown file in `docs/`, such as `docs/deployment.md`.
2. Add the page to the `pages` list in `app.js`, including its section, title, URL slug, and file path.
3. Refresh the site and use the new link in the left navigation.

Headings in each Markdown page automatically populate the **On this page** links. Use `##` for main sections and `###` for nested sections.
