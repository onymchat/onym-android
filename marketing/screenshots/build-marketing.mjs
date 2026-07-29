import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "../..");
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "onym-android-marketing-"));
const ffmpeg = process.env.FFMPEG || "ffmpeg";
const W = 1244, H = 2424;
const screen = { x: 182, y: 625, w: 880, h: 1715, r: 82 };
const shell = { x: 164, y: 607, w: 916, h: 1751, r: 101 };

const campaigns = {
  "en-US": [
    ["Private by default.", "End-to-end encrypted. No phone number. No central server."],
    ["Make the group yours.", "Set the tone, choose how it works, and invite with one link."],
    ["No account clutter.", "Private groups and familiar chats. No profile to maintain."],
    ["Only your group reads it.", "Messages are encrypted before they leave your device."],
    ["No contact details required.", "No phone number. No email. Just conversations you control."]
  ],
  "ru-RU": [
    ["Приватность по умолчанию.", "Сквозное шифрование. Без номера и центрального сервера."],
    ["Создайте свою группу.", "Задайте правила, выберите тип и пригласите одной ссылкой."],
    ["Никаких лишних аккаунтов.", "Закрытые группы и привычные чаты. Без профиля."],
    ["Читает только ваша группа.", "Сообщения шифруются ещё на вашем устройстве."],
    ["Контактные данные не нужны.", "Без номера и почты. Только беседы под вашим контролем."]
  ]
};

const xml = s => String(s).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(" ", "&#160;");
function wrap(text, size, max, lines = 2, widthFactor = .535) {
  const estimate = s => [...s].length * size * widthFactor;
  const out = []; let line = "";
  for (const word of text.split(/\s+/)) {
    const next = line ? `${line} ${word}` : word;
    if (line && estimate(next) > max) { out.push(line); line = word; } else line = next;
  }
  if (line) out.push(line);
  if (out.length <= lines) return { out, size };
  return wrap(text, size * .92, max, lines, widthFactor);
}
const textLines = (a, x, y, lh, attrs) => a.map((s, i) => `<text x="${x}" y="${y + i * lh}" ${attrs}>${xml(s)}</text>`).join("");

function headerSvg(locale, i) {
  const [headline, detail] = campaigns[locale][i];
  // Helvetica's Cyrillic glyphs are wider than its Latin glyphs. Keep
  // localized copy comfortably inside Play's preview-safe margins.
  const widthFactor = locale === "ru-RU" ? .59 : .535;
  const title = wrap(headline, 116, 1100, 2, widthFactor);
  const titleLH = Math.round(title.size * 1.02);
  const sub = wrap(detail, 43, 1085, 2, widthFactor);
  const subY = 215 + titleLH * title.out.length + 28;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="2424" height="2424">
<rect width="2424" height="2424" fill="#00ff00"/>
<text x="72" y="76" fill="#6e6e73" font-family="SF Mono,Menlo,monospace" font-size="21" font-weight="600" letter-spacing="5">ONYM&#160;·&#160;PRIVATE&#160;MESSENGER</text>
<text x="1172" y="76" text-anchor="end" fill="#6e6e73" font-family="SF Mono,Menlo,monospace" font-size="21">${String(i + 1).padStart(2, "0")}&#160;/&#160;05</text>
<line x1="72" y1="111" x2="1172" y2="111" stroke="#0a0a0a" stroke-opacity=".10"/>
${textLines(title.out, 72, 215, titleLH, `fill="#0a0a0a" font-family="SF Pro Display,Helvetica Neue,sans-serif" font-size="${title.size}" font-weight="750" letter-spacing="-4.5"`)}
${textLines(sub.out, 76, subY, 52, `fill="#5f5f64" font-family="SF Pro Display,Helvetica Neue,sans-serif" font-size="${sub.size}" font-weight="400"`)}
<circle cx="622" cy="657" r="22" fill="#08080a"/>
</svg>`;
}

function ql(svgText, name) {
  const svg = path.join(tmp, `${name}.svg`);
  fs.writeFileSync(svg, svgText);
  execFileSync("/usr/bin/qlmanage", ["-t", "-s", "2424", "-o", tmp, svg], { stdio: "ignore" });
  return `${svg}.png`;
}

const mask = ql(`<svg xmlns="http://www.w3.org/2000/svg" width="2424" height="2424"><rect width="2424" height="2424" fill="#000"/><rect width="${screen.w}" height="${screen.h}" rx="${screen.r}" fill="#fff"/></svg>`, "mask");
const shellLayer = ql(`<svg xmlns="http://www.w3.org/2000/svg" width="2424" height="2424">
<rect width="2424" height="2424" fill="#00ff00"/>
<rect x="${shell.x}" y="${shell.y}" width="${shell.w}" height="${shell.h}" rx="${shell.r}" fill="#0a0a0c"/>
<rect x="${shell.x + 5}" y="${shell.y + 5}" width="${shell.w - 10}" height="${shell.h - 10}" rx="${shell.r - 5}" fill="none" stroke="#4a4a4e" stroke-width="4"/>
<rect x="${shell.x + shell.w - 4}" y="840" width="10" height="190" rx="5" fill="#3a3a3e"/>
<rect x="${shell.x + shell.w - 4}" y="1070" width="10" height="116" rx="5" fill="#3a3a3e"/>
</svg>`, "shell");

for (const locale of Object.keys(campaigns)) {
  const outDir = path.join(root, "marketing/screenshots", locale);
  fs.mkdirSync(outDir, { recursive: true });
  const actualDir = path.join(root, "fastlane/metadata/android", locale, "images/phoneScreenshots");
  const sources = [
    path.join(actualDir, "04_welcome.png"),
    path.join(actualDir, "02_create_group.png"),
    path.join(actualDir, "03_chats.png"),
    path.join(actualDir, "05_chat.png"),
    path.join(actualDir, "01_identity.png"),
  ];
  for (let i = 0; i < 5; i++) {
    const source = sources[i];
    const rounded = path.join(tmp, `${locale}-${i}-screen.png`);
    execFileSync(ffmpeg, ["-y", "-loglevel", "error", "-i", source, "-i", mask,
      "-filter_complex", `[0:v]scale=${screen.w}:${screen.h}[s];[1:v]crop=${screen.w}:${screen.h}:0:0,format=gray[m];[s][m]alphamerge[o]`,
      "-map", "[o]", "-frames:v", "1", rounded]);
    const header = ql(headerSvg(locale, i), `${locale}-header-${i}`);
    const output = path.join(outDir, `${String(i + 1).padStart(2, "0")}-onym-android.png`);
    execFileSync(ffmpeg, ["-y", "-loglevel", "error",
      "-f", "lavfi", "-i", `color=c=0xf5f5f7:s=${W}x${H}`, "-i", rounded, "-i", header, "-i", shellLayer,
      "-filter_complex",
      `[2:v]crop=${W}:${H}:0:0,format=rgba,colorkey=0x00FF00:0.30:0.10[h];` +
      `[3:v]crop=${W}:${H}:0:0,format=rgba,colorkey=0x00FF00:0.30:0.10[f];` +
      `[0:v][f]overlay=0:0[a];[a][1:v]overlay=${screen.x}:${screen.y}:format=auto[b];[b][h]overlay=0:0:format=auto[o]`,
      "-map", "[o]", "-frames:v", "1", "-pix_fmt", "rgb24", output]);
    console.log(path.relative(root, output));
  }
}
