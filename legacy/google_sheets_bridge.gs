/**
 * Fermabets — Euroleague Player Props
 *
 * Εγκατάσταση (μία φορά):
 * 1. Άνοιξε το Google Sheet.
 * 2. Extensions → Apps Script.
 * 3. Σβήσε τον default κώδικα, επικόλλησε αυτό το αρχείο, Save.
 * 4. Deploy → New deployment → Type: Web app
 *      Execute as: Me
 *      Who has access: Anyone
 * 5. Αντίγραψε το Web App URL στο euroleague_player_props.py (SHEET_WEBAPP_URL)
 *    ή τρέξε από το μενού του Sheet: Fermabets → Υπολογισμός στηλών
 *
 * Το Result ΔΕΝ γράφεται ποτέ. Συμπληρώνεται χειροκίνητα μετά τον αγώνα.
 */

var BASE_WEIGHTS = {
  "On Xminutes": 0.30,
  "Last 5": 0.25,
  "Home/Away on Xmins": 0.20,
  "On XFGs": 0.15,
  "H2H": 0.10
};
var DEFAULT_N = { "Last 5": 5 };
var K_PARAM = 5;
var BANKROLL = 100;
var KELLY_FRACTION = 0.25;
var MAX_STAKE_PCT = 0.05;
var WEBAPP_SECRET = "fermabets-props";
var SKIP_COLUMNS = { Result: true };

var COMPUTED_HEADERS = [
  "Model Probability",
  "Edge",
  "EV",
  "Stake EUR",
  "Verdict"
];

function onOpen() {
  SpreadsheetApp.getUi()
    .createMenu("Fermabets")
    .addItem("Υπολογισμός στηλών (εκτός Result)", "fillComputedColumns")
    .addToUi();
}

function openSheet_() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  if (!ss) {
    ss = SpreadsheetApp.openById("1bwA3l_aKJAR5D-1V_idnBIfc1BruZMceqDK92F4_yHo");
  }
  return ss.getSheets()[0];
}

function doPost(e) {
  try {
    var body = JSON.parse(e.postData.contents);
    if (body.secret && body.secret !== WEBAPP_SECRET) {
      return jsonOut_({ ok: false, error: "bad secret" });
    }
    var sheet = openSheet_();
    ensureComputedHeaders_(sheet);
    var written = 0;
    (body.updates || []).forEach(function (u) {
      writeRowValues_(sheet, u.row, u.values || {});
      written++;
    });
    return jsonOut_({ ok: true, written: written });
  } catch (err) {
    return jsonOut_({ ok: false, error: String(err) });
  }
}

function fillComputedColumns() {
  var sheet = openSheet_();
  ensureComputedHeaders_(sheet);
  var data = sheet.getDataRange().getDisplayValues();
  if (data.length < 2) return;
  var headers = data[0];
  var col = indexMap_(headers);
  var written = 0;

  for (var r = 1; r < data.length; r++) {
    var row = data[r];
    var player = String(row[col.Player] || "").trim();
    if (!player) continue;

    var inputs = {
      "Last 5": String(row[col["Last 5"]] || "").trim(),
      "On Xminutes": String(row[col["On Xminutes"]] || "").trim(),
      "On XFGs": String(row[col["On XFGs"]] || "").trim(),
      "H2H": String(row[col["H2H"]] || "").trim(),
      "Home/Away on Xmins": String(row[col["Home/Away on Xmins"]] || "").trim()
    };
    var odds = parseNumber_(row[col.Odds]);
    var values = computeRow_(inputs, odds);
    writeRowValues_(sheet, r + 1, values);
    written++;
  }
  SpreadsheetApp.getActiveSpreadsheet().toast("Ενημερώθηκαν " + written + " γραμμές (Result άθικτο).");
}

function computeRow_(inputs, odds) {
  var parsed = [];
  Object.keys(BASE_WEIGHTS).forEach(function (name) {
    var raw = inputs[name];
    if (!raw) return;
    var st = parseStat_(raw, DEFAULT_N[name]);
    if (!st) return;
    parsed.push({
      name: name,
      p: st.p,
      n: st.n,
      base: BASE_WEIGHTS[name]
    });
  });

  var empty = {
    "Model Probability": "",
    "Edge": "",
    "EV": "",
    "Stake EUR": "",
    "Verdict": ""
  };
  if (!parsed.length) {
    empty.Verdict = "ERROR";
    return empty;
  }

  var rawSum = 0;
  parsed.forEach(function (s) {
    s.z = s.n / (s.n + K_PARAM);
    s.rawW = s.base * s.z;
    rawSum += s.rawW;
  });
  if (rawSum <= 0) {
    empty.Verdict = "ERROR";
    return empty;
  }

  var p = 0;
  parsed.forEach(function (s) {
    p += (s.rawW / rawSum) * s.p;
  });

  var out = {
    "Model Probability": euPct_(p, 2, false),
    "Edge": "",
    "EV": "",
    "Stake EUR": "",
    "Verdict": ""
  };

  if (odds === null || odds <= 1) {
    out.Verdict = "NO ODDS";
    return out;
  }

  var implied = 1 / odds;
  var ev = p * odds - 1;
  var edge = p - implied;
  out.Edge = euPct_(edge, 2, false);
  out.EV = euPct_(ev, 2, true);

  if (ev <= 0) {
    out["Stake EUR"] = "0,00";
    out.Verdict = "NO BET";
    return out;
  }

  var kellyFull = Math.max(0, Math.min(1, ev / (odds - 1)));
  var stake = BANKROLL * KELLY_FRACTION * kellyFull;
  var cap = BANKROLL * MAX_STAKE_PCT;
  if (stake > cap) stake = cap;
  out["Stake EUR"] = euNum_(stake, 2);
  out.Verdict = "VALUE";
  return out;
}

function parseStat_(text, defaultN) {
  var m = String(text).match(/(-?\d+(?:[.,]\d+)?)\s*%(?:\s*\(\s*(\d+)\s*\))?/);
  if (!m) return null;
  var p = parseFloat(m[1].replace(",", ".")) / 100;
  var n = m[2] ? parseInt(m[2], 10) : defaultN;
  if (n === undefined || n === null) return null;
  return { p: p, n: n };
}

function parseNumber_(v) {
  if (v === "" || v === null || v === undefined) return null;
  var n = parseFloat(String(v).replace(",", "."));
  return isNaN(n) ? null : n;
}

function euPct_(x, digits, signed) {
  var val = x * 100;
  var sign = signed && val > 0 ? "+" : "";
  return sign + val.toFixed(digits).replace(".", ",") + "%";
}

function euNum_(x, digits) {
  return x.toFixed(digits).replace(".", ",");
}

function indexMap_(headers) {
  var map = {};
  for (var i = 0; i < headers.length; i++) {
    var name = String(headers[i] || "").replace(/\s+/g, " ").trim();
    if (name) map[name] = i;
  }
  return map;
}

function ensureComputedHeaders_(sheet) {
  var lastCol = Math.max(sheet.getLastColumn(), 1);
  var headers = sheet.getRange(1, 1, 1, lastCol).getDisplayValues()[0];
  var existing = {};
  headers.forEach(function (h) {
    existing[String(h).replace(/\s+/g, " ").trim()] = true;
  });
  COMPUTED_HEADERS.forEach(function (h) {
    if (!existing[h]) {
      lastCol += 1;
      sheet.getRange(1, lastCol).setValue(h);
      existing[h] = true;
    }
  });
}

function writeRowValues_(sheet, rowNumber, values) {
  var lastCol = Math.max(sheet.getLastColumn(), 1);
  var headers = sheet.getRange(1, 1, 1, lastCol).getDisplayValues()[0];
  var col = indexMap_(headers);
  Object.keys(values).forEach(function (name) {
    if (SKIP_COLUMNS[name]) return;
    if (col[name] === undefined) return;
    sheet.getRange(rowNumber, col[name] + 1).setValue(values[name]);
  });
}

function jsonOut_(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
