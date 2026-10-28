# Developer documentation template

A small, static documentation site with Markdown content, a left-hand page navigation, and an automatically generated table of contents on the right.

## Preview locally

The site fetches Markdown files, so serve the project over HTTP rather than opening `index.html` directly:

```bash
python3 -m http.server 8000
```

Then open [http://localhost:8000](http://localhost:8000).

## Add a page

1. Create a Markdown file in `docs/`.
2. Add its title, section, URL slug, and file path to the `pages` array in `app.js`.
3. Use `##` and `###` headings to generate the page's right-hand table of contents.
4. Add diagrams in fenced blocks tagged `mermaid`; they are rendered when the page loads:

   ````markdown
   ```mermaid
   flowchart TD
     A[Start] --> B[Next step]
   ```
   ````

The Markdown and Mermaid renderers are loaded from jsDelivr, and the fonts are loaded from Google Fonts. Replace those external references in `index.html` and `styles.css` if your hosting environment needs to be fully offline.
