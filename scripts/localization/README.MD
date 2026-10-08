Android Auto-Translation Script for Olive Branch
------------------------------------------------
Translates missing strings from `values/strings.xml` into target locale resource files
(`values-es/strings.xml`, `values-ar/strings.xml`, `values-tl/strings.xml`, `values-zh/strings.xml`, etc.).

Key Improvements:
- Batching: Translates up to 20 strings per HTTP request to avoid rate limits (429)
- Configurable pause between cycles (default: 2 seconds, configurable via --delay)
- Clear, single-time warning for unsupported languages (e.g., Cree 'cr') without key spam
- Placeholder protection (%1$s, %1$d, \\n, HTML tags)
- Automatic Android XML character escaping (&amp;, \\', \\", &lt;, &gt;)

Usage:
python3 scripts/localization/autotranslate.py
python3 scripts/localization/autotranslate.py --target-locale es
python3 scripts/localization/autotranslate.py --delay 10
python3 scripts/localization/autotranslate.py --dry-run