#!/usr/bin/env python3
"""
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
"""

import os
import sys
import re
import json
import time
import argparse
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET

RES_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../app/src/main/res"))
DEFAULT_STRINGS_PATH = os.path.join(RES_DIR, "values", "strings.xml")

QUALIFIER_TO_LANG = {
    "values-ar": "ar",
    "values-es": "es",
    "values-fr": "fr",
    "values-zh": "zh-CN",
    "values-zh-rCN": "zh-CN",
    "values-zh-rTW": "zh-TW",
    "values-de": "de",
    "values-it": "it",
    "values-ja": "ja",
    "values-ko": "ko",
    "values-pt": "pt",
    "values-ru": "ru",
    "values-hi": "hi",
    "values-en-rCA": "en",
    "values-fr-rCA": "fr",
}

# Runs the parse strings xml steps.
def parse_strings_xml(file_path):
    """Parses a strings.xml file into a dictionary of key -> text."""
    if not os.path.exists(file_path):
        return {}
    try:
        tree = ET.parse(file_path)
        root = tree.getroot()
        strings = {}
        for child in root.findall("string"):
            name = child.get("name")
            if name:
                strings[name] = child.text or ""
        return strings
    except Exception as e:
        print(f"Error parsing {file_path}: {e}", file=sys.stderr)
        return {}

# Runs the protect placeholders steps.
def protect_placeholders(text):
    """Masks formatting placeholders (%1$s, %1$d, \\n, HTML tags) prior to translation."""
    placeholders = []
    
    # Runs the mask match steps.
    def mask_match(m):
        placeholders.append(m.group(0))
        return f"__PARAM_{len(placeholders) - 1}__"

    pattern = re.compile(r"%[0-9]+\$[a-zA-Z]|%[a-zA-Z]|\\n|\n|<[^>]+>")
    masked_text = pattern.sub(mask_match, text)
    return masked_text, placeholders

# Runs the restore placeholders steps.
def restore_placeholders(text, placeholders):
    """Restores masked placeholders after translation."""
    result = text
    for i, ph in enumerate(placeholders):
        token = f"__PARAM_{i}__"
        result = re.sub(rf"__\s*PARAM\s*_\s*{i}\s*__", ph, result)
        result = result.replace(token, ph)
    return result

# Runs the translate batch gtx steps.
def translate_batch_gtx(masked_texts, target_lang, max_retries=3):
    """Translates a batch of texts in a single HTTP request."""
    delimiter = "\n\n"
    combined = delimiter.join(masked_texts)
    url = f"https://translate.googleapis.com/translate_a/single?client=at&sl=en&tl={target_lang}&dt=t&q=" + urllib.parse.quote(combined)
    req = urllib.request.Request(url, headers={
        "User-Agent": "AndroidTranslate/5.3.0.c.136437311 (Linux; U; Android 10; Pixel 4)",
        "Accept-Language": "en-US,en;q=0.9"
    })
    
    for attempt in range(max_retries):
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                res_text = "".join([part[0] for part in data[0] if part[0]])
                parts = [s.strip() for s in res_text.split("\n\n") if s.strip()]
                return parts, None
        except urllib.error.HTTPError as e:
            if e.code == 400:
                return None, f"Language '{target_lang}' is not supported by Google Translate API."
            if e.code == 429:
                wait_time = (attempt + 1) * 5
                print(f"    [Rate Limited 429] Retrying batch in {wait_time}s...", file=sys.stderr)
                time.sleep(wait_time)
            else:
                return None, f"HTTP Error {e.code}: {e.reason}"
        except Exception as e:
            return None, f"Request Error: {e}"
            
    return None, "Rate limit retries exhausted."

# Runs the escape xml steps.
def escape_xml(val):
    """Escapes special characters for Android strings.xml."""
    val = val.replace("&", "&amp;")
    val = val.replace("<", "&lt;")
    val = val.replace(">", "&gt;")
    val = val.replace('"', '\\"')
    val = val.replace("'", "\\'")
    return val

# Runs the save strings xml steps.
def save_strings_xml(file_path, strings_dict):
    """Saves dictionary into strings.xml with clean Android formatting."""
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    for key, val in strings_dict.items():
        escaped_val = escape_xml(val)
        lines.append(f'    <string name="{key}">{escaped_val}</string>')
    lines.append('</resources>\n')
    
    os.makedirs(os.path.dirname(file_path), exist_ok=True)
    with open(file_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

# Runs the get target lang steps.
def get_target_lang(folder_name):
    if not folder_name.startswith("values-"):
        folder_name = f"values-{folder_name}"
    return QUALIFIER_TO_LANG.get(folder_name, folder_name.replace("values-", ""))

# Runs the process locale steps.
def process_locale(folder_name, default_strings, delay=2.0, batch_size=20, dry_run=False, overwrite=False, verbose=False):
    lang_code = get_target_lang(folder_name)
    target_dir = os.path.join(RES_DIR, folder_name if folder_name.startswith("values-") else f"values-{folder_name}")
    xml_path = os.path.join(target_dir, "strings.xml")
    
    print(f"\nProcessing [{folder_name}] (Target Language Code: '{lang_code}')...")
    
    existing_strings = parse_strings_xml(xml_path)
    updated_strings = dict(existing_strings)
    
    missing_keys = [k for k in default_strings if k not in existing_strings or overwrite]
    
    if not missing_keys:
        print(f"  ✓ [{folder_name}] is already complete! ({len(existing_strings)} strings)")
        return

    print(f"  Found {len(missing_keys)} missing strings to translate...")
    
    # Process missing keys in batches of batch_size
    translated_count = 0
    total_missing = len(missing_keys)
    
    for i in range(0, total_missing, batch_size):
        chunk_keys = missing_keys[i : i + batch_size]
        chunk_originals = [default_strings[k] for k in chunk_keys]
        
        # Mask placeholders for chunk
        masked_chunks = []
        placeholders_list = []
        for text in chunk_originals:
            masked, phs = protect_placeholders(text)
            masked_chunks.append(masked)
            placeholders_list.append(phs)
            
        translated_batch, error_msg = translate_batch_gtx(masked_chunks, lang_code)
        
        if error_msg:
            print(f"  ⚠️ [Warning] Skipping [{folder_name}]: {error_msg}")
            break
            
        if translated_batch and len(translated_batch) == len(chunk_keys):
            for k, raw_trans, phs in zip(chunk_keys, translated_batch, placeholders_list):
                final_trans = restore_placeholders(raw_trans, phs)
                updated_strings[k] = final_trans
                translated_count += 1
                if verbose:
                    print(f"    {k}: '{default_strings[k]}' -> '{final_trans}'")
            print(f"    Batch progress: {min(i + batch_size, total_missing)}/{total_missing} strings translated.")
            if not dry_run:
                save_strings_xml(xml_path, updated_strings)
        else:
            print(f"    [Batch Mismatch] Retrying chunk item-by-item...")
            for k, orig, masked, phs in zip(chunk_keys, chunk_originals, masked_chunks, placeholders_list):
                single_res, err = translate_batch_gtx([masked], lang_code)
                if single_res and len(single_res) == 1:
                    final_trans = restore_placeholders(single_res[0], phs)
                    updated_strings[k] = final_trans
                    translated_count += 1
                time.sleep(0.5)
            if not dry_run:
                save_strings_xml(xml_path, updated_strings)

        # Sleep between batch cycles to avoid triggering rate limits
        if i + batch_size < total_missing and delay > 0:
            time.sleep(delay)

    print(f"  Completed {translated_count}/{total_missing} translations for [{folder_name}].")
    
    if not dry_run and translated_count > 0:
        save_strings_xml(xml_path, updated_strings)
        print(f"  Saved {len(updated_strings)} total strings to '{xml_path}'.")
    elif dry_run:
        print(f"  [Dry Run] No changes written to '{xml_path}'.")

# Runs the main steps.
def main():
    parser = argparse.ArgumentParser(description="Auto-translate missing Android strings.xml entries in batches.")
    parser.add_argument("--target-locale", "-l", help="Target specific locale (e.g., 'es', 'ar', 'zh', 'fr')")
    parser.add_argument("--delay", type=float, default=2.0, help="Pause in seconds between batch requests (default: 2.0s)")
    parser.add_argument("--batch-size", type=int, default=20, help="Number of strings per HTTP request (default: 20)")
    parser.add_argument("--dry-run", action="store_true", help="Preview translations without modifying XML files")
    parser.add_argument("--overwrite", action="store_true", help="Overwrite existing translations")
    parser.add_argument("--verbose", "-v", action="store_true", help="Print detailed logs during translation")
    
    args = parser.parse_args()
    
    if not os.path.exists(DEFAULT_STRINGS_PATH):
        print(f"Error: Default strings file not found at '{DEFAULT_STRINGS_PATH}'", file=sys.stderr)
        sys.exit(1)
        
    default_strings = parse_strings_xml(DEFAULT_STRINGS_PATH)
    print(f"Loaded {len(default_strings)} base strings from 'values/strings.xml'.")
    
    if args.target_locale:
        process_locale(args.target_locale, default_strings, delay=args.delay, batch_size=args.batch_size, dry_run=args.dry_run, overwrite=args.overwrite, verbose=args.verbose)
    else:
        for entry in sorted(os.listdir(RES_DIR)):
            if entry.startswith("values-") and os.path.isdir(os.path.join(RES_DIR, entry)):
                process_locale(entry, default_strings, delay=args.delay, batch_size=args.batch_size, dry_run=args.dry_run, overwrite=args.overwrite, verbose=args.verbose)

if __name__ == "__main__":
    main()
