import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { execFileSync } from "node:child_process";

const root = "/Users/leadonym/Developer/onym-android";
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "onym-android-marketing-"));
const ffmpeg = "/opt/homebrew/bin/ffmpeg";
const W = 1244, H = 2424;
const screen = { x: 182, y: 625, w: 880, h: 1715, r: 82 };
const shell = { x: 164, y: 607, w: 916, h: 1751, r: 101 };

const campaigns = {
  "en-US": [
    ["Your identity never leaves.", "Keys and history stay on this device. No phone number."],
    ["Open by design.", "Read the code. Build the app. Run the infrastructure."],
    ["Run your own courier.", "Choose the Nostr relay that carries encrypted messages."],
    ["Choose your notary.", "Use Stellar—or deploy and maintain your own contract."],
    ["Own the whole path.", "Identity. Messenger. Courier. Notary. Separate by design."]
  ],
  "ru-RU": [
    ["Личность никуда не уходит.", "Ключи и история остаются на устройстве. Без номера."],
    ["Проверьте. Соберите сами.", "Приложение, контракты и релеер открыты."],
    ["Запустите своего курьера.", "Выберите релей Nostr для зашифрованных сообщений."],
    ["Выберите своего нотариуса.", "Используйте Stellar или разверните свой контракт."],
    ["Владейте всей системой.", "Личность. Мессенджер. Курьер. Нотариус. Всё раздельно."]
  ]
};

const xml = s => String(s).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(" ", "&#160;");
function wrap(text, size, max, lines = 2) {
  const estimate = s => [...s].length * size * .535;
  const out = []; let line = "";
  for (const word of text.split(/\s+/)) {
    const next = line ? `${line} ${word}` : word;
    if (line && estimate(next) > max) { out.push(line); line = word; } else line = next;
  }
  if (line) out.push(line);
  if (out.length <= lines) return { out, size };
  return wrap(text, size * .92, max, lines);
}
const textLines = (a, x, y, lh, attrs) => a.map((s, i) => `<text x="${x}" y="${y + i * lh}" ${attrs}>${xml(s)}</text>`).join("");

function headerSvg(locale, i) {
  const [headline, detail] = campaigns[locale][i];
  const title = wrap(headline, 116, 1100);
  const titleLH = Math.round(title.size * 1.02);
  const sub = wrap(detail, 43, 1085);
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

const ui = {
  "en-US": {
    open: ["Open Source", "ANDROID APP", "Onym Messenger", "View source · Build from source", "INFRASTRUCTURE", "Contracts", "Stellar smart contracts", "Relayer", "Nostr message relayer", "Every component can be inspected and self-hosted."],
    nostr: ["Nostr Relays", "CONFIGURED · 1", "Onym Official", "wss://nostr.onym.app", "ADD CUSTOM RELAY", "wss://relay.example.com", "Add", "Use localhost or any Nostr relay you trust.", "SELF-HOST", "Run your own relay", "Deploy a Nostr relay with Docker"],
    anchor: ["Anchors", "NETWORK", "Stellar · Testnet", "GOVERNANCE TYPE", "Founder", "CUSTOM", "Deploy from source", "Build & publish your own contract", "Use existing address", "Point to a deployed contract"]
  },
  "ru-RU": {
    open: ["Открытый код", "ANDROID-ПРИЛОЖЕНИЕ", "Onym Messenger", "Исходный код · Собрать самостоятельно", "ИНФРАСТРУКТУРА", "Контракты", "Смарт-контракты Stellar", "Релеер", "Релеер сообщений Nostr", "Каждый компонент можно проверить и запустить самому."],
    nostr: ["Релеи Nostr", "НАСТРОЕНО · 1", "Onym Official", "wss://nostr.onym.app", "ДОБАВИТЬ РЕЛЕЙ", "wss://relay.example.com", "Добавить", "Используйте любой релей Nostr, которому доверяете.", "СВОЙ СЕРВЕР", "Запустите свой релей", "Разверните релей Nostr с помощью Docker"],
    anchor: ["Якоря", "СЕТЬ", "Stellar · Testnet", "ТИП УПРАВЛЕНИЯ", "Основатель", "ПРОИЗВОЛЬНЫЙ", "Развернуть из исходников", "Соберите и опубликуйте свой контракт", "Использовать существующий адрес", "Указать на уже развёрнутый контракт"]
  }
};

function basePhone(title, body) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="2424" height="2424">
<rect width="1244" height="2424" fill="#fffafe"/>
<text x="74" y="80" font-family="Roboto,Arial,sans-serif" font-size="34" font-weight="600" fill="#171217">10:47</text>
<text x="1125" y="80" font-family="Arial,sans-serif" font-size="30" fill="#171217">●</text>
<text x="55" y="183" font-family="Arial,sans-serif" font-size="64" fill="#242024">‹</text>
<text x="126" y="173" font-family="Roboto,Arial,sans-serif" font-size="52" font-weight="650" fill="#171217">${xml(title)}</text>
<line x1="0" y1="224" x2="1244" y2="224" stroke="#eadfe8"/>
${body}</svg>`;
}
const label = (s, y) => `<text x="70" y="${y}" font-family="Roboto,Arial,sans-serif" font-size="27" font-weight="700" letter-spacing="2" fill="#736971">${xml(s)}</text>`;
const card = (title, sub, y, icon = "↗") => `<rect x="48" y="${y}" width="1148" height="210" rx="34" fill="#ffffff" stroke="#eadfe8" stroke-width="3"/>
<circle cx="130" cy="${y + 105}" r="48" fill="#f4edf3"/><text x="130" y="${y + 120}" text-anchor="middle" font-family="Arial,sans-serif" font-size="42" fill="#292329">${icon}</text>
<text x="207" y="${y + 87}" font-family="Roboto,Arial,sans-serif" font-size="39" font-weight="600" fill="#171217">${xml(title)}</text>
<text x="207" y="${y + 139}" font-family="Roboto,Arial,sans-serif" font-size="29" fill="#716971">${xml(sub)}</text>`;

function synthetic(locale, kind) {
  const t = ui[locale][kind];
  if (kind === "open") return basePhone(t[0],
    label(t[1], 295) + card(t[2], t[3], 330, "⌘") +
    label(t[4], 625) + card(t[5], t[6], 660, "◇") + card(t[7], t[8], 895, "↗") +
    `<text x="70" y="1190" font-family="Roboto,Arial,sans-serif" font-size="31" fill="#716971">${xml(t[9])}</text>`);
  if (kind === "nostr") return basePhone(t[0],
    label(t[1], 295) + card(t[2], t[3], 330, "↗") + label(t[4], 625) +
    `<rect x="48" y="662" width="1148" height="132" rx="25" fill="#fff" stroke="#9c8797" stroke-width="3"/>
<text x="80" y="745" font-family="Roboto Mono,monospace" font-size="31" fill="#554b53">${xml(t[5])}</text>
<rect x="944" y="682" width="225" height="92" rx="46" fill="#171217"/><text x="1056" y="742" text-anchor="middle" font-family="Roboto,Arial" font-size="31" font-weight="600" fill="#fff">${xml(t[6])}</text>
<text x="70" y="855" font-family="Roboto,Arial" font-size="28" fill="#716971">${xml(t[7])}</text>
${label(t[8], 1000)}${card(t[9], t[10], 1035, "↗")}`);
  return basePhone(t[0],
    label(t[1], 295) + card(t[2], "", 330, "◎") +
    label(t[3], 625) + card(t[4], "", 660, "◇") +
    label(t[5], 955) + card(t[6], t[7], 990, "↗") + card(t[8], t[9], 1225, "→"));
}

function ql(svgText, name) {
  const svg = path.join(tmp, `${name}.svg`);
  fs.writeFileSync(svg, svgText);
  execFileSync("/usr/bin/qlmanage", ["-t", "-s", "2424", "-o", tmp, svg], { stdio: "ignore" });
  return `${svg}.png`;
}
function cropSquare(input, output) {
  execFileSync(ffmpeg, ["-y", "-loglevel", "error", "-i", input, "-vf", "crop=1244:2424:0:0", "-frames:v", "1", output]);
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
    null, null, null,
    path.join(actualDir, "05_chat.png")
  ];
  const kinds = [null, "open", "nostr", "anchor", null];
  for (let i = 0; i < 5; i++) {
    let source = sources[i];
    if (kinds[i]) {
      source = path.join(tmp, `${locale}-${kinds[i]}-source.png`);
      cropSquare(ql(synthetic(locale, kinds[i]), `${locale}-${kinds[i]}`), source);
    }
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
      "-map", "[o]", "-frames:v", "1", output]);
    console.log(path.relative(root, output));
  }
}
