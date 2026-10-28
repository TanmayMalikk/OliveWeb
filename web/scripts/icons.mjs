import sharp from "sharp";
import { mkdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
const paths = {
  olive:
    '<path d="M65 191C100 136 151 102 199 68M104 145C76 146 64 119 71 100C94 105 112 121 104 145ZM142 111C121 94 131 66 146 58C161 78 159 98 142 111ZM127 126C148 116 171 126 178 145C154 152 136 146 127 126Z"/> <ellipse cx="108" cy="172" rx="13" ry="18"/>',
  "daily-digest":
    '<rect x="61" y="62" width="135" height="132" rx="7" fill="none" stroke="currentColor" stroke-width="7"/><path d="M83 87H174M83 107H174M83 167H174M83 183H154" fill="none" stroke="currentColor" stroke-width="5"/><rect x="83" y="125" width="36" height="28" rx="2"/><path d="M132 128H174M132 141H174M132 153H164" fill="none" stroke="currentColor" stroke-width="4"/>',
  calculator:
    '<rect x="76" y="51" width="106" height="154" rx="15" fill="none" stroke="currentColor" stroke-width="7"/><rect x="91" y="69" width="76" height="32" rx="5" fill="none" stroke="currentColor" stroke-width="5"/>' +
    [115, 144, 173]
      .map((y) =>
        [93, 122, 151]
          .map((x) => `<rect x="${x}" y="${y}" width="15" height="15" rx="4"/>`)
          .join(""),
      )
      .join(""),
  game: '<path d="M127 202V118M127 149C83 152 67 115 68 88C105 84 129 111 127 149ZM127 123C126 85 149 62 184 58C190 93 164 125 127 123Z"/>',
};
await mkdir(new URL("../public/icons/", import.meta.url), { recursive: true });
for (const [name, path] of Object.entries(paths)) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256"><rect width="256" height="256" fill="#435d35"/><g color="#f5f5e8" fill="currentColor" stroke-linecap="round" stroke-linejoin="round">${path}</g></svg>`;
  for (const size of [192, 512])
    await sharp(Buffer.from(svg))
      .resize(size, size)
      .png()
      .toFile(
        fileURLToPath(
          new URL(`../public/icons/${name}-${size}.png`, import.meta.url),
        ),
      );
  await writeFile(new URL(`../public/icons/${name}.svg`, import.meta.url), svg);
}
