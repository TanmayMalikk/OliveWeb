//Reminder to update pages manually here for the left sidebar.
const pages = [
  {
    section: "Start here",
    title: "Overview",
    slug: "overview",
    file: "./docs/overview.md",
  },
  {
    section: "Start here",
    title: "Getting started",
    slug: "getting-started",
    file: "./docs/getting-started.md",
  },
  {
    section: "Guides",
    title: "Project structure",
    slug: "project-structure",
    file: "./docs/project-structure.md",
  },
  {
    section: "Guides",
    title: "Configuration",
    slug: "configuration",
    file: "./docs/configuration.md",
  },
  {
    section: "Guides",
    title: "Voip",
    slug: "voip",
    file: "./docs/Voip.md",
  }
];

const article = document.querySelector("#article");
const primaryNav = document.querySelector("#primary-nav");
const tableOfContents = document.querySelector("#table-of-contents");
const breadcrumbs = document.querySelector("#breadcrumbs");
const pagination = document.querySelector("#page-pagination");
const sidebar = document.querySelector("#sidebar");
const menuToggle = document.querySelector(".menu-toggle");
const backdrop = document.querySelector(".sidebar-backdrop");
let mermaidInitialized = false;

function renderNavigation(activeSlug) {
  let currentSection = "";
  primaryNav.innerHTML = pages
    .map((page) => {
      const sectionLabel =
        page.section !== currentSection
          ? `<p class="nav-section">${page.section}</p>`
          : "";
      currentSection = page.section;
      const active = page.slug === activeSlug;

      return `${sectionLabel}<a class="nav-link${active ? " active" : ""}" href="#/${page.slug}"${
        active ? ' aria-current="page"' : ""
      }>${page.title}</a>`;
    })
    .join("");
}

function slugify(text) {
  return text
    .toLowerCase()
    .trim()
    .replace(/[^\w -]/g, "")
    .replace(/\s+/g, "-");
}

function renderTableOfContents(pageSlug) {
  const headings = article.querySelectorAll("h2, h3");
  tableOfContents.innerHTML = "";

  for (const heading of headings) {
    const link = document.createElement("a");
    link.href = `#/${pageSlug}#${heading.id}`;
    link.textContent = heading.textContent;
    link.className = heading.tagName === "H3" ? "toc-link toc-link-nested" : "toc-link";
    tableOfContents.append(link);
  }
}

function renderPagination(pageIndex) {
  const previous = pages[pageIndex - 1];
  const next = pages[pageIndex + 1];

  pagination.innerHTML = `
    ${
      previous
        ? `<a class="pagination-link previous" href="#/${previous.slug}">
            <span class="pagination-direction">← Previous</span>
            <span class="pagination-title">${previous.title}</span>
          </a>`
        : "<span></span>"
    }
    ${
      next
        ? `<a class="pagination-link next" href="#/${next.slug}">
            <span class="pagination-direction">Next →</span>
            <span class="pagination-title">${next.title}</span>
          </a>`
        : "<span></span>"
    }`;
}

function setSidebarOpen(isOpen) {
  document.body.classList.toggle("sidebar-open", isOpen);
  menuToggle.setAttribute("aria-expanded", String(isOpen));
  menuToggle.setAttribute(
    "aria-label",
    isOpen ? "Close documentation navigation" : "Open documentation navigation",
  );
}

async function renderMermaidDiagrams() {
  const codeBlocks = article.querySelectorAll("pre > code.language-mermaid");
  if (!codeBlocks.length) return;
  if (!window.mermaid) {
    throw new Error("The Mermaid renderer did not load. Check your internet connection.");
  }

  if (!mermaidInitialized) {
    window.mermaid.initialize({ startOnLoad: false });
    mermaidInitialized = true;
  }

  const diagrams = Array.from(codeBlocks, (code) => {
    const diagram = document.createElement("div");
    diagram.className = "mermaid";
    diagram.textContent = code.textContent;
    code.parentElement.replaceWith(diagram);
    return diagram;
  });

  await window.mermaid.run({ nodes: diagrams });
}

async function renderPage() {
  const [slug, headingId] = window.location.hash.replace(/^#\/?/, "").split("#");
  const currentSlug = slug || "overview";
  const pageIndex = pages.findIndex((page) => page.slug === currentSlug);
  const page = pages[pageIndex];

  if (!page) {
    window.location.hash = "#/overview";
    return;
  }

  renderNavigation(page.slug);
  breadcrumbs.innerHTML = `<span>Docs</span><span aria-hidden="true">/</span><span>${page.section}</span><span aria-hidden="true">/</span><strong>${page.title}</strong>`;
  document.title = `${page.title} — DevGuide`;
  article.innerHTML = '<p class="loading-message">Loading documentation…</p>';
  tableOfContents.innerHTML = "";

  try {
    const response = await fetch(page.file);
    if (!response.ok) {
      throw new Error(`Could not load ${page.file}: ${response.status} ${response.statusText}`);
    }
    if (!window.marked) {
      throw new Error("The Markdown renderer did not load. Check your internet connection.");
    }

    const markdown = await response.text();
    article.innerHTML = window.marked.parse(markdown);
    await renderMermaidDiagrams();
    for (const heading of article.querySelectorAll("h1, h2, h3, h4")) {
      heading.id = slugify(heading.textContent);
    }
    renderTableOfContents(page.slug);
    renderPagination(pageIndex);
    if (headingId) {
      article.querySelector(`#${CSS.escape(headingId)}`)?.scrollIntoView();
    } else {
      window.scrollTo(0, 0);
    }
  } catch (error) {
    console.error(error);
    article.innerHTML = `
      <div class="error-message">
        <h1>Page unavailable</h1>
        <p>${error.message}</p>
        <p>Run this site through a local web server so the Markdown files can be loaded.</p>
      </div>`;
    pagination.innerHTML = "";
  }

  setSidebarOpen(false);
}

menuToggle.addEventListener("click", () => {
  setSidebarOpen(!document.body.classList.contains("sidebar-open"));
});
backdrop.addEventListener("click", () => setSidebarOpen(false));
sidebar.addEventListener("click", (event) => {
  if (event.target.closest("a")) setSidebarOpen(false);
});
window.addEventListener("hashchange", renderPage);

renderPage();
