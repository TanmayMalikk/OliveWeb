# Project structure

Document the folders developers should know about. A short description is usually more useful than listing every file.

## Top-level folders

| Path                                    | Purpose                          |
| --------------------------------------- | -------------------------------- |
| `app/kotlin_java/olive.app/`            | Application source code          |
| `app/kotlin+java/com.olive.app (test)/` | Automated tests                  |
| `docs/`                                 | Developer and user documentation |
| `public/`                               | Static files served directly     |


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
