// Validates parser-spec/templates.json against parser-spec/fixtures.json.
// Usage: node parser-spec/run.mjs
// This is a dev harness for the language-neutral spec; the Kotlin :core module will run the same fixtures.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const dir = dirname(fileURLToPath(import.meta.url));
const spec = JSON.parse(readFileSync(join(dir, 'templates.json'), 'utf8'));
const fixtures = JSON.parse(readFileSync(join(dir, 'fixtures.json'), 'utf8'));

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];

function expandMacros(pattern) {
  let out = pattern;
  for (let i = 0; i < 10 && /\{\{\w+\}\}/.test(out); i++) {
    out = out.replace(/\{\{(\w+)\}\}/g, (_, name) => {
      if (!(name in spec.macros)) throw new Error(`Unknown macro ${name}`);
      return spec.macros[name];
    });
  }
  return out;
}

// Exact decimal parsing, no floating point: "1,00,000.5" -> 10000050 paise.
export function toPaise(text) {
  const [whole, frac = ''] = text.replace(/,/g, '').split('.');
  if (!/^\d+$/.test(whole) || !/^\d*$/.test(frac)) throw new Error(`Bad amount ${text}`);
  return Number(whole) * 100 + Number((frac + '00').slice(0, 2));
}

// Supports tokens dd, MM, MMM, yy, yyyy and literal separators.
export function parseDate(text, format) {
  const tokens = format.match(/dd|MMM|MM|yyyy|yy|[^dMy]+/g);
  let pos = 0;
  let d, m, y;
  for (const tok of tokens) {
    const part = text.substr(pos, tok.length);
    pos += tok.length;
    if (tok === 'dd') d = Number(part);
    else if (tok === 'MM') m = Number(part);
    else if (tok === 'MMM') m = MONTHS.indexOf(part.toLowerCase()) + 1;
    else if (tok === 'yy') y = 2000 + Number(part);
    else if (tok === 'yyyy') y = Number(part);
  }
  const probe = new Date(Date.UTC(y, m - 1, d));
  if (!m || probe.getUTCFullYear() !== y || probe.getUTCMonth() !== m - 1 || probe.getUTCDate() !== d) {
    throw new Error(`Invalid date "${text}" for format ${format}`);
  }
  return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

const templates = spec.templates.map((t) => ({ ...t, re: new RegExp(expandMacros(t.regex), 'i') }));
const ignores = spec.ignore.map((r) => ({ ...r, re: new RegExp(expandMacros(r.regex), 'i') }));

const senders = JSON.parse(readFileSync(join(dir, 'senders.json'), 'utf8'));

// "AX-KOTAKB-S" -> { prefix: "AX", brand: "KOTAKB", kind: "S" }; phone numbers and free text -> null.
export function parseSender(sender) {
  const m = /^([A-Z]{2})-([A-Z0-9]+?)(?:-([A-Z]))?$/.exec((sender ?? '').trim().toUpperCase());
  return m ? { prefix: m[1], brand: m[2], kind: m[3] ?? null } : null;
}

// "10:56 AM" -> "10:56", "1:05 PM" -> "13:05"; 24-hour input passes through.
export function to24h(text) {
  const m = /^(\d{1,2}):(\d{2})(?: ([AP]M))?$/i.exec(text);
  if (!m) throw new Error(`Invalid time "${text}"`);
  let h = Number(m[1]);
  if (m[3]) h = (h % 12) + (m[3].toUpperCase() === 'PM' ? 12 : 0);
  return `${String(h).padStart(2, '0')}:${m[2]}`;
}

// Repeated reminders (same notice sent on several days) must collapse into one scheduled item.
function noticeKey(out) {
  if (out.isTransaction) return null;
  const who = out.counterpartyName ?? out.policyRef ?? out.accountSuffix ?? '';
  return [out.eventKind, out.amountPaise, out.dueDate ?? '', who].join('|');
}

export function parse(rawBody, sender) {
  const header = parseSender(sender);
  if (header && senders.muted.includes(header.brand)) {
    return { result: 'IGNORED', reason: 'MUTED_SENDER', senderBrand: header.brand };
  }
  let result;
  try {
    result = parseBody(rawBody);
  } catch (e) {
    // A matched template with an invalid field (e.g. 31-02-26) is kept for review, never guessed or dropped.
    result = { result: 'UNPARSED', reason: 'INVALID_FIELD' };
  }
  if (result.result === 'PARSED') {
    const key = noticeKey(result);
    if (key) result.noticeKey = key;
  }
  if (header && result.result !== 'AMBIGUOUS') {
    result.senderBrand = header.brand;
    const known = senders.brands[header.brand];
    if (known) {
      result.institution = known.institution;
      result.institutionKind = known.kind;
    }
  }
  return result;
}

function parseBody(rawBody) {
  const body = rawBody.replace(/\s+/g, ' ').trim();
  const hits = templates.map((t) => ({ t, m: t.re.exec(body) })).filter((h) => h.m);
  if (hits.length > 1) {
    return { result: 'AMBIGUOUS', templateIds: hits.map((h) => h.t.id) };
  }
  if (hits.length === 1) {
    const { t, m } = hits[0];
    const g = m.groups;
    const out = {
      result: 'PARSED',
      templateId: t.id,
      eventKind: t.eventKind,
      method: t.method,
      isTransaction: t.isTransaction !== false,
      amountPaise: toPaise(g.amount),
      currency: 'INR',
    };
    if (t.direction) out.direction = t.direction;
    if (g.minDue) out.minDuePaise = toPaise(g.minDue);
    if (t.accountKindHint) out.accountKindHint = t.accountKindHint;
    if (t.typeHint) out.typeHint = t.typeHint;
    if (g.acct) out.accountSuffix = g.acct;
    if (g.cpAcct) out.counterpartyAcctSuffix = g.cpAcct;
    const bank = g.bank ?? g.bankTag;
    if (bank) out.bank = bank.trim();
    const counterparty = g.payee ?? g.payer;
    if (counterparty) out.counterpartyName = counterparty.trim();
    if (g.ref) out.ref = g.ref;
    if (g.umn) out.umn = g.umn;
    if (g.desc) out.description = g.desc.trim();
    if (g.balance) out.balancePaise = toPaise(g.balance);
    if (g.date && t.dateFormat) out[t.dateAs ?? 'date'] = parseDate(g.date, t.dateFormat);
    if (g.time) out.time = to24h(g.time);
    if (g.cpVpa) out.counterpartyVpa = g.cpVpa;
    if (g.cpBankCode) out.counterpartyBankCode = g.cpBankCode;
    if (g.policy) out.policyRef = g.policy;
    return out;
  }
  const ignored = ignores.find((r) => r.re.test(body));
  if (ignored) return { result: 'IGNORED', reason: ignored.reason };
  return { result: 'UNPARSED' };
}

function main() {
  let failures = 0;
  const fail = (msg) => {
    failures++;
    console.log(`  FAIL ${msg}`);
  };

  console.log('paise conversion');
  for (const [text, expected] of fixtures.paiseCases) {
    const actual = toPaise(text);
    if (actual !== expected) fail(`toPaise("${text}") = ${actual}, expected ${expected}`);
  }
  console.log(`  ${fixtures.paiseCases.length} cases checked`);

  console.log('sender headers');
  for (const [header, expected] of fixtures.senderCases) {
    const actual = parseSender(header);
    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
      fail(`parseSender("${header}") = ${JSON.stringify(actual)}, expected ${JSON.stringify(expected)}`);
    }
  }
  console.log(`  ${fixtures.senderCases.length} cases checked`);

  console.log('time conversion');
  for (const [text, expected] of fixtures.timeCases) {
    const actual = to24h(text);
    if (actual !== expected) fail(`to24h("${text}") = ${actual}, expected ${expected}`);
  }
  console.log(`  ${fixtures.timeCases.length} cases checked`);

  console.log('repeated notices collapse to one key');
  for (const g of fixtures.noticeDedup) {
    const keys = (bodies) => bodies.map((b) => parse(b, g.sender).noticeKey);
    const same = new Set(keys(g.same));
    if (same.size !== 1 || [...same][0] == null) fail(`${g.id}: "same" bodies gave keys ${JSON.stringify([...same])}`);
    const other = keys(g.different);
    if (other.some((k) => k == null || same.has(k))) fail(`${g.id}: "different" bodies share a key with "same"`);
  }
  console.log(`  ${fixtures.noticeDedup.length} groups checked`);

  console.log('message fixtures');
  for (const c of fixtures.cases) {
    const actual = parse(c.body, c.sender);
    const diffs = Object.entries(c.expect)
      .filter(([k, v]) => (v === null ? actual[k] != null : actual[k] !== v)) // null means "field absent"
      .map(([k, v]) => `${k}: expected ${JSON.stringify(v)}, got ${JSON.stringify(actual[k])}`);
    if (diffs.length) {
      console.log(`  FAIL ${c.id}`);
      diffs.forEach((d) => console.log(`       ${d}`));
      failures++;
    } else {
      console.log(`  ok   ${c.id}`);
    }
  }

  console.log(failures ? `\n${failures} failure(s)` : '\nall passed');
  process.exit(failures ? 1 : 0);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
