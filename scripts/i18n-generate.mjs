import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, "..");
const locales = ["en-US", "zh-CN"];

const paths = {
  source: path.join(repoRoot, "locales", "source"),
  manifest: path.join(repoRoot, "locales", "generated", "manifest.json"),
  android: {
    "en-US": path.join(repoRoot, "apps", "android", "src", "main", "res", "values", "strings.xml"),
    "zh-CN": path.join(repoRoot, "apps", "android", "src", "main", "res", "values-zh-rCN", "strings.xml"),
  },
  react: {
    "en-US": path.join(repoRoot, "apps", "tauri", "src", "i18n", "generated", "en-US.json"),
    "zh-CN": path.join(repoRoot, "apps", "tauri", "src", "i18n", "generated", "zh-CN.json"),
  },
  fluent: {
    "en-US": path.join(repoRoot, "apps", "tauri", "src-tauri", "locales", "en-US", "desktop.ftl"),
    "zh-CN": path.join(repoRoot, "apps", "tauri", "src-tauri", "locales", "zh-CN", "desktop.ftl"),
  },
};

async function main() {
  const catalogs = await readCatalogs();
  validateCatalogs(catalogs);

  const outputs = [];
  for (const locale of locales) {
    const catalog = catalogs[locale];
    outputs.push([paths.android[locale], androidXml(catalog)]);
    outputs.push([paths.react[locale], `${JSON.stringify(reactJson(catalog), null, 2)}\n`]);
    outputs.push([paths.fluent[locale], fluentMessages(catalog)]);
  }

  const manifest = manifestJson(outputs);
  outputs.push([paths.manifest, `${JSON.stringify(manifest, null, 2)}\n`]);

  for (const [filePath, contents] of outputs) {
    await writeGeneratedFile(filePath, contents);
  }
}

async function readCatalogs() {
  const catalogs = {};
  for (const locale of locales) {
    const filePath = path.join(paths.source, `${locale}.json`);
    catalogs[locale] = JSON.parse(await readFile(filePath, "utf8"));
  }
  return catalogs;
}

function validateCatalogs(catalogs) {
  const referenceLocale = locales[0];
  const referenceKeys = Object.keys(catalogs[referenceLocale]);
  validateTargetIds(referenceKeys, androidName, "Android string name");
  validateTargetIds(referenceKeys, fluentId, "Fluent message id");

  for (const locale of locales) {
    const catalog = catalogs[locale];
    if (!catalog || typeof catalog !== "object" || Array.isArray(catalog)) {
      throw new Error(`${locale} source catalog must be a JSON object`);
    }

    const keys = Object.keys(catalog);
    assertSameList(
      sorted(keys),
      sorted(referenceKeys),
      `${locale} keys must match ${referenceLocale}`,
    );

    for (const key of keys) {
      if (typeof catalog[key] !== "string") {
        throw new Error(`${locale}.${key} must be a string`);
      }
      validateKey(key);
      assertSameList(
        placeholderNames(catalog[key], `${locale}.${key}`),
        placeholderNames(catalogs[referenceLocale][key], `${referenceLocale}.${key}`),
        `${locale}.${key} placeholders must match ${referenceLocale}.${key}`,
      );
    }
  }
}

function validateKey(key) {
  if (!/^[a-z][A-Za-z0-9]*(\.[a-z][A-Za-z0-9]*)+$/.test(key)) {
    throw new Error(`invalid locale key: ${key}`);
  }
}

function validateTargetIds(keys, normalize, targetName) {
  const sourceByTarget = new Map();
  for (const key of keys) {
    const targetId = normalize(key);
    const existing = sourceByTarget.get(targetId);
    if (existing) {
      throw new Error(`${targetName} collision: "${existing}" and "${key}" both normalize to "${targetId}"`);
    }
    sourceByTarget.set(targetId, key);
  }
}

function placeholderNames(value, label) {
  const names = [];
  let index = 0;

  while (index < value.length) {
    const character = value[index];
    if (character === "}") {
      throw new Error(`${label} contains an unmatched closing brace`);
    }
    if (character !== "{") {
      index += 1;
      continue;
    }

    const end = value.indexOf("}", index + 1);
    if (end === -1) {
      throw new Error(`${label} contains an unmatched opening brace`);
    }

    const expression = value.slice(index, end + 1);
    const match = expression.match(/^\{([A-Za-z_][A-Za-z0-9_]*)\}$/);
    if (!match) {
      throw new Error(`${label} contains unsupported placeholder syntax: ${expression}`);
    }

    names.push(match[1]);
    index = end + 1;
  }

  return names.sort();
}

function sorted(values) {
  return [...values].sort();
}

function assertSameList(actual, expected, message) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${message}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

function androidXml(catalog) {
  const lines = [
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
    "<resources>",
    ...Object.entries(catalog).map(
      ([key, value]) => `    <string name="${androidName(key)}">${androidText(value)}</string>`,
    ),
    "</resources>",
    "",
  ];
  return lines.join("\n");
}

function androidName(key) {
  return key.replaceAll(".", "_").replaceAll(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
}

function androidText(value) {
  return xmlEscape(value).replaceAll(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g, "{$1}");
}

function xmlEscape(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\"", "&quot;")
    .replaceAll("'", "\\'");
}

function reactJson(catalog) {
  const flat = {};
  for (const [key, value] of Object.entries(catalog)) {
    flat[key] = reactText(value);
  }
  return flat;
}

function reactText(value) {
  return value.replaceAll(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g, "{{$1}}");
}

function fluentMessages(catalog) {
  return `${Object.entries(catalog)
    .map(([key, value]) => `${fluentId(key)} = ${fluentText(value)}`)
    .join("\n")}\n`;
}

function fluentId(key) {
  return key.replaceAll(".", "-").replaceAll(/([a-z0-9])([A-Z])/g, "$1-$2").toLowerCase();
}

function fluentText(value) {
  return preserveFluentBoundarySpaces(
    value
      .replaceAll(/\r\n?/g, "\n")
      .replaceAll("\n", "\\n")
      .replaceAll(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g, (_, name) => `{ $${name} }`),
  );
}

function preserveFluentBoundarySpaces(value) {
  const leading = value.match(/^ +/)?.[0] ?? "";
  const trailing = value.match(/ +$/)?.[0] ?? "";
  let inner = value.slice(leading.length, value.length - trailing.length);
  if (leading) inner = `{ "${leading}" }${inner}`;
  if (trailing) inner = `${inner}{ "${trailing}" }`;
  return inner;
}

function manifestJson(outputs) {
  const files = Object.fromEntries(
    outputs.map(([filePath, contents]) => [
      slashPath(path.relative(repoRoot, filePath)),
      {
        sha256: sha256(contents),
      },
    ]),
  );

  return {
    locales,
    source: Object.fromEntries(
      locales.map((locale) => [
        locale,
        slashPath(path.relative(repoRoot, path.join(paths.source, `${locale}.json`))),
      ]),
    ),
    generated: files,
  };
}

function sha256(contents) {
  return createHash("sha256").update(contents).digest("hex");
}

function slashPath(filePath) {
  return filePath.split(path.sep).join("/");
}

async function writeGeneratedFile(filePath, contents) {
  await mkdir(path.dirname(filePath), { recursive: true });
  await writeFile(filePath, contents);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
