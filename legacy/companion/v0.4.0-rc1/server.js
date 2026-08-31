#!/usr/bin/env node
"use strict";

/**
 * Echo360 Companion v0.3
 *
 * EchoTransfer:
 * - compara origem local vs destino no Xbox por caminho relativo + tamanho;
 * - modos Aurora FTP (rápido), FTPdll (background) e Auto;
 * - envia apenas arquivos ausentes/diferentes;
 * - progresso por arquivo + total, velocidade e ETA;
 * - pausa e cancelamento seguros após o arquivo atual;
 * - no modo Auto, tenta migrar Aurora FTP -> FTPdll se a conexão cair;
 * - NUNCA apaga arquivo remoto.
 */

const express = require("express");
const ftp = require("basic-ftp");
const fs = require("fs");
const fsp = fs.promises;
const path = require("path");
const net = require("net");
const crypto = require("crypto");
const { once } = require("events");

const ROOT = __dirname;
const CONFIG_PATH = path.join(ROOT, "config.json");
const EXAMPLE_PATH = path.join(ROOT, "config.example.json");

const DATA_DIR = path.join(ROOT, "data");
const HISTORY_PATH = path.join(DATA_DIR, "transfer-history.json");

if (!fs.existsSync(DATA_DIR)) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
}

function readTransferHistory() {
  try {
    const data = JSON.parse(fs.readFileSync(HISTORY_PATH, "utf8"));
    return Array.isArray(data) ? data : [];
  } catch {
    return [];
  }
}

function appendTransferHistory(entry) {
  const history = readTransferHistory();
  history.push(entry);
  fs.writeFileSync(
    HISTORY_PATH,
    JSON.stringify(history.slice(-100), null, 2)
  );
}

function loadConfig() {
  if (!fs.existsSync(CONFIG_PATH)) {
    fs.copyFileSync(EXAMPLE_PATH, CONFIG_PATH);
  }

  const cfg = JSON.parse(fs.readFileSync(CONFIG_PATH, "utf8"));

  // Migração suave de configs antigas v0.2.
  if (cfg.ftp && !cfg.ftpBackground) {
    cfg.ftpBackground = {
      label: "FTPdll",
      port: cfg.ftp.port || 7564,
      username: cfg.ftp.username || "xbox",
      password: cfg.ftp.password || "xbox",
      timeoutMs: cfg.ftp.timeoutMs || 20000,
      allowSeparateTransferHost: true
    };
  }
  if (!cfg.ftpFast) {
    cfg.ftpFast = {
      label: "Aurora FTP",
      port: 21,
      username: "xboxftp",
      password: "xboxftp",
      timeoutMs: 15000,
      allowSeparateTransferHost: false
    };
  }
  if (cfg.ftpFast.allowSeparateTransferHost == null) {
    cfg.ftpFast.allowSeparateTransferHost = false;
  }
  if (cfg.ftpBackground.allowSeparateTransferHost == null) {
    // FTPdll on Xbox 360 commonly answers PASV with 0.0.0.0.
    // On the user's trusted LAN, basic-ftp must accept that transfer host behavior.
    cfg.ftpBackground.allowSeparateTransferHost = true;
  }
  if (!cfg.localRoots) {
    cfg.localRoots = ["/storage/emulated/0/Download"];
  }
  return cfg;
}

const config = loadConfig();
const app = express();
app.use(express.json({ limit: "4mb" }));
app.use(express.static(path.join(ROOT, "public")));

let tokenCache = { value: null, createdAt: 0 };

// =============================
// Helpers
// =============================

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function novaBase() {
  return `http://${config.xbox.host}:${config.nova.port}`;
}

async function novaAuthenticate(force = false) {
  if (!force && tokenCache.value && Date.now() - tokenCache.createdAt < 30 * 60 * 1000) {
    return tokenCache.value;
  }

  const body = new FormData();
  body.append("username", config.nova.username);
  body.append("password", config.nova.password);

  const r = await fetch(`${novaBase()}/authenticate`, {
    method: "POST",
    headers: { Accept: "application/json" },
    body,
    signal: AbortSignal.timeout(config.nova.timeoutMs || 5000)
  });

  if (!r.ok) throw new Error(`NOVA auth HTTP ${r.status}`);

  const data = await r.json();
  if (!data?.token) throw new Error("NOVA nao devolveu token.");

  tokenCache = { value: data.token, createdAt: Date.now() };
  return data.token;
}

async function novaGet(endpoint) {
  let token = await novaAuthenticate(false);

  async function call(jwt) {
    return fetch(`${novaBase()}${endpoint}`, {
      headers: {
        Accept: "application/json, text/html",
        Authorization: `Bearer ${jwt}`
      },
      signal: AbortSignal.timeout(config.nova.timeoutMs || 5000)
    });
  }

  let r = await call(token);
  if (r.status === 401) {
    token = await novaAuthenticate(true);
    r = await call(token);
  }
  if (!r.ok) throw new Error(`${endpoint}: HTTP ${r.status}`);

  const txt = await r.text();
  if (!txt) return {};
  try { return JSON.parse(txt); }
  catch { return { raw: txt }; }
}

function sanitizeSystem(system) {
  if (!system || typeof system !== "object") return null;
  return {
    console: {
      motherboard: system.console?.motherboard ?? null,
      type: system.console?.type ?? null
    },
    version: system.version ?? null
  };
}

function safeMemory(mem) {
  if (!mem || typeof mem !== "object") return null;

  // Aurora/NOVA pode usar letras diferentes conforme build.
  const total = Number(mem.total ?? mem.Total ?? 0) || null;
  const used = Number(mem.used ?? mem.Used ?? 0) || null;
  const free = Number(mem.free ?? mem.Available ?? mem.available ?? 0) || null;

  return {
    total,
    used,
    free,
    usedPercent: total && used != null ? (used / total) * 100 : null
  };
}

function safeTemperature(t) {
  if (!t || typeof t !== "object") return null;
  return {
    cpu: Number(t.cpu ?? t.CPU),
    gpu: Number(t.gpu ?? t.GPU),
    memory: Number(t.memory ?? t.RAM),
    case: Number(t.case ?? t.BRD),
    celsius: t.celsius !== false
  };
}

function safeTitle(t) {
  if (!t || typeof t !== "object") return null;
  return {
    titleid: t.titleid ?? null,
    mediaid: t.mediaid ?? null,
    path: t.path ?? null,
    tuver: t.tuver ?? null,
    resolution: t.resolution ?? null,
    disc: t.disc ?? null,
    version: t.version ?? null
  };
}

function safeDashlaunch(d) {
  if (!d || typeof d !== "object") return null;

  const options = Array.isArray(d.options) ? d.options : [];
  const plugins = options
    .filter(x => x && x.category === "Plugins")
    .map(x => ({ name: x.name, value: x.value }));

  return {
    version: d.version ?? null,
    plugins
  };
}

function tcpProbe(host, port, timeout = 2000) {
  return new Promise(resolve => {
    const socket = net.createConnection({ host, port });
    let finished = false;

    const done = (ok, error = null, latencyMs = null) => {
      if (finished) return;
      finished = true;
      socket.destroy();
      resolve({ ok, error, latencyMs });
    };

    const start = Date.now();
    socket.setTimeout(timeout);
    socket.once("connect", () => done(true, null, Date.now() - start));
    socket.once("timeout", () => done(false, "timeout"));
    socket.once("error", e => done(false, e.message));
  });
}

function normalizeRemote(p) {
  if (!p) return "/";
  let s = String(p).replace(/\\/g, "/");
  if (!s.startsWith("/")) s = "/" + s;
  return s.replace(/\/+/g, "/");
}

function joinRemote(a, b) {
  return normalizeRemote(`${a}/${b}`);
}

function normalizeLocal(p) {
  return path.resolve(String(p || ""));
}

function isLocalPathAllowed(p) {
  const resolved = normalizeLocal(p);
  const roots = (config.localRoots || []).map(normalizeLocal);
  return roots.some(root => resolved === root || resolved.startsWith(root + path.sep));
}


function canonicalToFtpdllPath(p) {
  const n = normalizeRemote(p);

  const mappings = [
    ["/Hdd1", "/fHdd"],
    ["/Usb0", "/fUsb0"],
    ["/Flash", "/fFlash"]
  ];

  for (const [canonical, raw] of mappings) {
    if (n === canonical) return raw;
    if (n.startsWith(canonical + "/")) return raw + n.slice(canonical.length);
  }

  return n;
}

function ftpdllRootNameToCanonical(name) {
  const map = {
    fHdd: "Hdd1",
    fUsb0: "Usb0",
    fFlash: "Flash"
  };
  return map[name] || name;
}

function publicConfig() {
  return {
    xboxHost: config.xbox.host,
    httpPort: config.httpPort,
    novaPort: config.nova.port,
    ftpFastPort: config.ftpFast.port,
    ftpBackgroundPort: config.ftpBackground.port,
    localRoots: config.localRoots
  };
}


// =============================
// EchoActiveFTP
// Minimal active-mode FTP client built for Xbox 360 FTPdll.
// We intentionally own this code because FTPdll does not expose
// a usable passive data endpoint.
// =============================

class EchoActiveFTP {
  constructor(profile) {
    this.profile = profile;
    this.socket = null;
    this.lineBuffer = "";
    this.lines = [];
    this.waiters = [];
    this.closed = false;
  }

  _feed(data) {
    this.lineBuffer += data.toString("utf8");
    while (true) {
      const i = this.lineBuffer.indexOf("\n");
      if (i < 0) break;
      let line = this.lineBuffer.slice(0, i);
      this.lineBuffer = this.lineBuffer.slice(i + 1);
      line = line.replace(/\r$/, "");
      if (this.waiters.length) {
        const w = this.waiters.shift();
        w.resolve(line);
      } else {
        this.lines.push(line);
      }
    }
  }

  _nextLine(timeoutMs = 15000) {
    if (this.lines.length) return Promise.resolve(this.lines.shift());

    return new Promise((resolve, reject) => {
      const waiter = { resolve, reject };
      this.waiters.push(waiter);

      const timer = setTimeout(() => {
        const idx = this.waiters.indexOf(waiter);
        if (idx >= 0) this.waiters.splice(idx, 1);
        reject(new Error("FTP control response timeout"));
      }, timeoutMs);

      waiter.resolve = (line) => {
        clearTimeout(timer);
        resolve(line);
      };
      waiter.reject = (err) => {
        clearTimeout(timer);
        reject(err);
      };
    });
  }

  async _response(timeoutMs = 15000) {
    const first = await this._nextLine(timeoutMs);
    const m = first.match(/^(\d{3})([ -])(.*)$/);
    if (!m) throw new Error(`FTP response invalida: ${first}`);

    const code = Number(m[1]);
    const lines = [first];

    if (m[2] === "-") {
      const endPrefix = `${m[1]} `;
      while (true) {
        const line = await this._nextLine(timeoutMs);
        lines.push(line);
        if (line.startsWith(endPrefix)) break;
      }
    }

    return { code, text: lines.join("\n"), lines };
  }

  _sendRaw(command) {
    if (!this.socket || this.closed) throw new Error("FTP control socket fechado");
    this.socket.write(command + "\r\n");
  }

  async command(command, accepted = null) {
    this._sendRaw(command);
    const r = await this._response(this.profile.timeoutMs || 15000);
    if (accepted && !accepted.includes(r.code)) {
      throw new Error(`${command.split(" ")[0]} falhou: ${r.text}`);
    }
    return r;
  }

  async connect() {
    const timeoutMs = this.profile.timeoutMs || 15000;

    this.socket = net.createConnection({
      host: config.xbox.host,
      port: this.profile.port,
      family: 4
    });
    this.socket.setNoDelay(true);
    this.socket.on("data", d => this._feed(d));
    this.socket.on("error", e => {
      while (this.waiters.length) this.waiters.shift().reject(e);
    });

    await Promise.race([
      once(this.socket, "connect"),
      new Promise((_, reject) => setTimeout(() => reject(new Error("FTP connect timeout")), timeoutMs))
    ]);

    const hello = await this._response(timeoutMs);
    if (hello.code !== 220) throw new Error(`FTP greeting inesperado: ${hello.text}`);

    const user = await this.command(`USER ${this.profile.username}`, [230, 331]);
    if (user.code === 331) {
      await this.command(`PASS ${this.profile.password}`, [230]);
    }

    await this.command("TYPE I", [200]);
    return this;
  }

  async close() {
    if (!this.socket || this.closed) return;
    try {
      await this.command("QUIT", [221]);
    } catch {}
    this.closed = true;
    this.socket.destroy();
  }

  _localIPv4() {
    let ip = this.socket.localAddress || "";
    if (ip.startsWith("::ffff:")) ip = ip.slice(7);
    if (!/^\d+\.\d+\.\d+\.\d+$/.test(ip)) {
      throw new Error(`Nao consegui obter IPv4 local para FTP ativo: ${ip}`);
    }
    return ip;
  }

  async _activeListener() {
    const localIp = this._localIPv4();
    const server = net.createServer();
    server.maxConnections = 1;

    await new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen({ host: "0.0.0.0", port: 0, exclusive: true }, resolve);
    });

    const port = server.address().port;
    const p1 = Math.floor(port / 256);
    const p2 = port % 256;
    const encodedIp = localIp.split(".").join(",");

    const dataPromise = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        server.close();
        reject(new Error("FTP ativo: Xbox nao abriu a conexao de dados"));
      }, this.profile.timeoutMs || 15000);

      server.once("connection", socket => {
        clearTimeout(timer);
        server.close();
        socket.setNoDelay(true);
        resolve(socket);
      });

      server.once("error", err => {
        clearTimeout(timer);
        reject(err);
      });
    });

    await this.command(`PORT ${encodedIp},${p1},${p2}`, [200]);

    return { server, dataPromise };
  }

  async pwd() {
    const r = await this.command("PWD", [257]);
    const m = r.text.match(/257\s+"([^"]+)"/);
    return m ? m[1] : null;
  }

  async cwd(remotePath) {
    const target = canonicalToFtpdllPath(remotePath);
    const r = await this.command(`CWD ${target}`, [250]);
    return r;
  }

  static parseList(text) {
    const items = [];
    for (const raw of text.split(/\r?\n/)) {
      const line = raw.trimEnd();
      if (!line) continue;

      // UNIX: drwxr-xr-x 1 owner group 123 Jan 01 12:00 Name
      let m = line.match(/^([\-dl])\S*\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\w{3}\s+\d{1,2}\s+(?:\d{2}:\d{2}|\d{4})\s+(.+)$/);
      if (m) {
        items.push({
          name: m[3],
          directory: m[1] === "d",
          size: Number(m[2] || 0)
        });
        continue;
      }

      // Alternate UNIX where owner/group columns differ.
      m = line.match(/^([\-dl])\S*\s+.*?\s(\d+)\s+\w{3}\s+\d{1,2}\s+(?:\d{2}:\d{2}|\d{4})\s+(.+)$/);
      if (m) {
        items.push({
          name: m[3],
          directory: m[1] === "d",
          size: Number(m[2] || 0)
        });
        continue;
      }

      // DOS: 08-27-26  10:15PM       <DIR>          Games
      m = line.match(/^\d{2}-\d{2}-\d{2,4}\s+\d{1,2}:\d{2}[AP]M\s+(<DIR>|\d+)\s+(.+)$/i);
      if (m) {
        items.push({
          name: m[2],
          directory: m[1].toUpperCase() === "<DIR>",
          size: m[1].toUpperCase() === "<DIR>" ? 0 : Number(m[1])
        });
        continue;
      }

      // Some Xbox FTP implementations emit: d 0 Name / - 123 Name
      m = line.match(/^([d\-])\s+(\d+)\s+(.+)$/i);
      if (m) {
        items.push({
          name: m[3],
          directory: m[1].toLowerCase() === "d",
          size: Number(m[2] || 0)
        });
      }
    }
    return items.filter(x => x.name !== "." && x.name !== "..");
  }

  async list(remotePath) {
    const target = normalizeRemote(remotePath);

    // UI/API use canonical Xbox names (Hdd1/Usb0/Flash).
    // EchoActiveFTP translates them internally to FTPdll names (fHdd/fUsb0/fFlash).
    await this.cwd(target);

    const { dataPromise } = await this._activeListener();

    this._sendRaw("LIST");
    const prelim = await this._response(this.profile.timeoutMs || 15000);

    if (![125, 150].includes(prelim.code)) {
      throw new Error(`LIST falhou em ${target}: ${prelim.text}`);
    }

    const dataSocket = await dataPromise;
    let content = "";
    dataSocket.setEncoding("utf8");
    dataSocket.on("data", chunk => { content += chunk; });

    await Promise.race([
      once(dataSocket, "close"),
      new Promise((_, reject) =>
        setTimeout(
          () => reject(new Error(`LIST data timeout em ${target}`)),
          this.profile.timeoutMs || 15000
        )
      )
    ]);

    const final = await this._response(this.profile.timeoutMs || 15000);
    if (![226, 250].includes(final.code)) {
      throw new Error(`LIST final falhou em ${target}: ${final.text}`);
    }

    const parsed = EchoActiveFTP.parseList(content);

    // Empty directory is valid. But if there is text we couldn't parse,
    // expose the first lines so we can adapt to this specific FTPdll build.
    if (!parsed.length && content.trim()) {
      const preview = content
        .split(/\r?\n/)
        .filter(Boolean)
        .slice(0, 5)
        .join(" || ");
      throw new Error(
        `FTPdll retornou dados de LIST em ${target}, mas o formato nao foi reconhecido: ${preview}`
      );
    }

    return parsed;
  }

  async size(remotePath) {
    const translated = canonicalToFtpdllPath(normalizeRemote(remotePath));
    this._sendRaw(`SIZE ${translated}`);
    const r = await this._response(this.profile.timeoutMs || 15000);

    if (r.code === 213) {
      const m = r.text.match(/213\s+(\d+)/);
      return m ? Number(m[1]) : null;
    }
    if (r.code === 550) return null;
    throw new Error(`SIZE ${translated} falhou: ${r.text}`);
  }

  async ensureDir(remoteDir) {
    const canonical = normalizeRemote(remoteDir);
    const translated = canonicalToFtpdllPath(canonical);
    const parts = translated.split("/").filter(Boolean);
    let current = "";

    for (const part of parts) {
      current += "/" + part;
      this._sendRaw(`MKD ${current}`);
      const r = await this._response(this.profile.timeoutMs || 15000);
      // 257 = created; 250 accepted; 550 commonly means "already exists".
      if (![250, 257, 550].includes(r.code)) {
        throw new Error(`MKD ${current} falhou: ${r.text}`);
      }
    }
  }

  async upload(localPath, remotePath, onProgress) {
    const canonicalRemote = normalizeRemote(remotePath);
    await this.ensureDir(path.posix.dirname(canonicalRemote));
    const remote = canonicalToFtpdllPath(canonicalRemote);

    const { dataPromise } = await this._activeListener();

    this._sendRaw(`STOR ${remote}`);
    const prelim = await this._response(this.profile.timeoutMs || 15000);
    if (![125, 150].includes(prelim.code)) {
      throw new Error(`STOR falhou: ${prelim.text}`);
    }

    const dataSocket = await dataPromise;
    const rs = fs.createReadStream(localPath);
    let sent = 0;

    try {
      for await (const chunk of rs) {
        if (!dataSocket.write(chunk)) {
          await once(dataSocket, "drain");
        }
        sent += chunk.length;
        if (onProgress) onProgress(sent);
      }

      dataSocket.end();

      await Promise.race([
        once(dataSocket, "close"),
        new Promise((_, reject) => setTimeout(() => reject(new Error("STOR data timeout")), this.profile.timeoutMs || 15000))
      ]);
    } catch (e) {
      dataSocket.destroy();
      rs.destroy();
      throw e;
    }

    const final = await this._response(this.profile.timeoutMs || 15000);
    if (![226, 250].includes(final.code)) {
      throw new Error(`STOR final falhou: ${final.text}`);
    }
  }
}


// =============================
// FTP profiles
// =============================

function ftpProfile(name) {
  if (name === "fast") return config.ftpFast;
  if (name === "background") return config.ftpBackground;
  throw new Error(`Perfil FTP invalido: ${name}`);
}

async function probeFtpProfiles() {
  const [fast, background] = await Promise.all([
    tcpProbe(config.xbox.host, config.ftpFast.port),
    tcpProbe(config.xbox.host, config.ftpBackground.port)
  ]);
  return { fast, background };
}

async function testFastLogin() {
  const profile = ftpProfile("fast");
  const client = new ftp.Client(profile.timeoutMs || 15000);
  client.ftp.verbose = false;

  try {
    await client.access({
      host: config.xbox.host,
      port: profile.port,
      user: profile.username,
      password: profile.password,
      secure: false
    });
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e.message };
  } finally {
    client.close();
  }
}

async function testBackgroundLogin() {
  const client = new EchoActiveFTP(ftpProfile("background"));
  try {
    await client.connect();
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e.message };
  } finally {
    await client.close().catch(() => {});
  }
}

async function resolveFtpMode(requested = "auto") {
  if (requested === "fast") {
    const fast = await testFastLogin();
    if (!fast.ok) throw new Error(`Aurora FTP indisponivel: ${fast.error}`);
    return { mode: "fast", login: fast };
  }

  if (requested === "background") {
    const bg = await testBackgroundLogin();
    if (!bg.ok) throw new Error(`FTPdll indisponivel: ${bg.error}`);
    return { mode: "background", login: bg };
  }

  // Auto first performs a short TCP probe so a closed/unavailable
  // Aurora FTP does not consume the full FTP control timeout.
  const fastProbe = await tcpProbe(
    config.xbox.host,
    config.ftpFast.port,
    1200
  );

  let fast = {
    ok: false,
    error: fastProbe.error || "porta 21 indisponivel"
  };

  if (fastProbe.ok) {
    fast = await testFastLogin();
    if (fast.ok) {
      return { mode: "fast", login: fast, probe: fastProbe };
    }
  }

  const bg = await testBackgroundLogin();
  if (bg.ok) {
    return { mode: "background", login: bg, fastProbe };
  }

  throw new Error(`Nenhum FTP autenticou. Aurora: ${fast.error}; FTPdll: ${bg.error}`);
}

async function connectFtp(mode) {
  const profile = ftpProfile(mode);

  if (mode === "background") {
    return await new EchoActiveFTP(profile).connect();
  }

  const client = new ftp.Client(profile.timeoutMs || 15000);
  client.ftp.verbose = false;
  try {
    await client.access({
      host: config.xbox.host,
      port: profile.port,
      user: profile.username,
      password: profile.password,
      secure: false
    });
  } catch (e) {
    client.close();
    if (/421|too many connections/i.test(String(e.message || e))) {
      throw new Error(
        "421 Too many connections — Aurora atingiu o limite de sessoes FTP. " +
        "Reinicie a Aurora/Xbox uma vez para limpar sessoes antigas."
      );
    }
    throw e;
  }
  return client;
}


async function gracefulCloseFtp(client, mode) {
  if (!client) return;

  if (mode === "background") {
    await client.close().catch(() => {});
    return;
  }

  // Aurora can retain abruptly closed FTP sessions for a while.
  // Send an explicit QUIT before closing the control socket.
  try {
    await Promise.race([
      client.send("QUIT", true),
      new Promise(resolve => setTimeout(resolve, 1200))
    ]);
  } catch {}

  try { client.close(); } catch {}
}

async function withFtpMode(mode, fn) {
  const client = await connectFtp(mode);
  try {
    return await fn(client);
  } finally {
    await gracefulCloseFtp(client, mode);
  }
}


async function withRequestedFtpMode(requestedMode, fn) {
  if (requestedMode === "fast") {
    const result = await withFtpMode(
      "fast",
      client => fn(client, "fast")
    );
    return { mode: "fast", result };
  }

  if (requestedMode === "background") {
    const result = await withFtpMode(
      "background",
      client => fn(client, "background")
    );
    return { mode: "background", result };
  }

  // Auto: first use a short TCP probe. If Aurora FTP is clearly
  // unavailable, skip its full control timeout and use FTPdll immediately.
  const fastProbe = await tcpProbe(
    config.xbox.host,
    config.ftpFast.port,
    1200
  );

  if (!fastProbe.ok) {
    const result = await withFtpMode(
      "background",
      client => fn(client, "background")
    );

    return {
      mode: "background",
      result,
      fallbackFrom: "fast",
      fastError: `probe rapido falhou: ${fastProbe.error || "porta 21 indisponivel"}`
    };
  }

  // Port is reachable: perform the real Aurora operation using one session.
  try {
    const result = await withFtpMode(
      "fast",
      client => fn(client, "fast")
    );
    return { mode: "fast", result };
  } catch (fastError) {
    const result = await withFtpMode(
      "background",
      client => fn(client, "background")
    );
    return {
      mode: "background",
      result,
      fallbackFrom: "fast",
      fastError: fastError.message
    };
  }
}


async function remoteFileSize(client, mode, remotePath) {
  if (mode === "background") {
    return await client.size(remotePath);
  }

  try {
    return Number(await client.size(normalizeRemote(remotePath)));
  } catch (e) {
    if (/550|not found|does not exist/i.test(String(e.message || e))) {
      return null;
    }
    throw e;
  }
}

async function listRemoteEntries(client, mode, remotePath) {
  const remote = normalizeRemote(remotePath);

  if (mode === "background") {
    // EchoActiveFTP already performs CWD + bare LIST.
    return await client.list(remote);
  }

  // Aurora's FTP server is more reliable with:
  // CWD <path> -> LIST
  // than with LIST <absolute-path>.
  await client.cd(remote);
  return await client.list();
}

// =============================
// File walkers / compare
// =============================

async function walkLocal(root) {
  const resolvedRoot = normalizeLocal(root);
  if (!isLocalPathAllowed(resolvedRoot)) {
    throw new Error("Pasta local fora das raizes permitidas.");
  }

  const out = new Map();

  async function walk(current, relBase = "") {
    const entries = await fsp.readdir(current, { withFileTypes: true });

    for (const entry of entries) {
      const abs = path.join(current, entry.name);
      const rel = path.posix.join(relBase, entry.name);

      if (entry.isDirectory()) {
        await walk(abs, rel);
      } else if (entry.isFile()) {
        const st = await fsp.stat(abs);
        out.set(rel, {
          relative: rel,
          localPath: abs,
          size: st.size
        });
      }
    }
  }

  await walk(resolvedRoot);
  return out;
}

async function walkRemote(client, mode, root) {
  const out = new Map();

  async function walk(current, relBase = "") {
    const entries = await listRemoteEntries(client, mode, current);

    for (const entry of entries) {
      const rel = path.posix.join(relBase, entry.name);
      const full = joinRemote(current, entry.name);
      const isDir = entry.directory ?? entry.isDirectory;

      if (isDir) {
        await walk(full, rel);
      } else {
        out.set(rel.toLowerCase(), {
          relative: rel,
          remotePath: full,
          size: Number(entry.size || 0)
        });
      }
    }
  }

  await walk(normalizeRemote(root));
  return out;
}

async function walkRemoteForLocal(client, mode, root, localMap) {
  const out = new Map();
  const normalizedRoot = normalizeRemote(root);

  // Root is scanned once. Only matching top-level folders from the local
  // source are recursively scanned on Xbox.
  //
  // A missing destination root is valid for a new transfer: treat it as
  // an empty remote tree. upload()/ensureDir() will create it later.
  let rootEntries;
  try {
    rootEntries = await listRemoteEntries(client, mode, normalizedRoot);
  } catch (error) {
    const message = String(error?.message || error || "");
    const isMissingPath =
      /\b550\b/i.test(message) &&
      /(path not found|no such file|not found)/i.test(message);

    if (isMissingPath) {
      return out;
    }

    throw error;
  }

  const rootByName = new Map(
    rootEntries.map(e => [String(e.name).toLowerCase(), e])
  );

  const topLevel = new Set();
  for (const item of localMap.values()) {
    const first = item.relative.split("/")[0];
    if (first) topLevel.add(first);
  }

  async function walkDir(current, relBase) {
    const entries = await listRemoteEntries(client, mode, current);

    for (const entry of entries) {
      const rel = path.posix.join(relBase, entry.name);
      const full = joinRemote(current, entry.name);
      const isDir = entry.directory ?? entry.isDirectory;

      if (isDir) {
        await walkDir(full, rel);
      } else {
        out.set(rel.toLowerCase(), {
          relative: rel,
          remotePath: full,
          size: Number(entry.size || 0)
        });
      }
    }
  }

  for (const top of topLevel) {
    const remoteEntry = rootByName.get(String(top).toLowerCase());
    if (!remoteEntry) continue;

    const isDir = remoteEntry.directory ?? remoteEntry.isDirectory;
    if (isDir) {
      await walkDir(
        joinRemote(normalizedRoot, remoteEntry.name),
        remoteEntry.name
      );
    } else {
      out.set(remoteEntry.name.toLowerCase(), {
        relative: remoteEntry.name,
        remotePath: joinRemote(normalizedRoot, remoteEntry.name),
        size: Number(remoteEntry.size || 0)
      });
    }
  }

  return out;
}

async function buildDiff(localRoot, remoteRoot, mode, strategy = "missing-and-different") {
  const local = await walkLocal(localRoot);
  const remote = await withFtpMode(
    mode,
    client => walkRemoteForLocal(client, mode, remoteRoot, local)
  );

  const same = [];
  const missing = [];
  const different = [];
  const queue = [];

  for (const [rel, lf] of local.entries()) {
    const rf = remote.get(rel.toLowerCase());

    if (!rf) {
      const item = {
        relative: rel,
        localPath: lf.localPath,
        remotePath: joinRemote(remoteRoot, rel),
        localSize: lf.size,
        remoteSize: null,
        reason: "missing"
      };
      missing.push(item);
      queue.push(item);
    } else if (Number(lf.size) !== Number(rf.size)) {
      const item = {
        relative: rel,
        localPath: lf.localPath,
        remotePath: rf.remotePath,
        localSize: lf.size,
        remoteSize: rf.size,
        reason: "different-size"
      };
      different.push(item);
      if (strategy !== "missing-only") queue.push(item);
    } else {
      same.push({
        relative: rel,
        localSize: lf.size,
        remoteSize: rf.size
      });
    }
  }

  return {
    localFiles: local.size,
    remoteFiles: remote.size,
    sameCount: same.length,
    missing,
    different,
    queue,
    uploadCount: queue.length,
    uploadBytes: queue.reduce((a, x) => a + x.localSize, 0),
    strategy,
    mode
  };
}

// =============================
// Transfer Job Manager
// =============================

const jobs = new Map();
const analyses = new Map();

function rememberAnalysis(data) {
  const id = crypto.randomBytes(8).toString("hex");
  analyses.set(id, { ...data, createdAtMs: Date.now() });

  if (analyses.size > 50) {
    const ordered = [...analyses.entries()]
      .sort((a, b) => a[1].createdAtMs - b[1].createdAtMs);
    for (const [oldId] of ordered.slice(0, analyses.size - 50)) {
      analyses.delete(oldId);
    }
  }

  return id;
}

function newJobId() {
  return crypto.randomBytes(6).toString("hex");
}

function compactJob(job) {
  const totalBytes = job.totalBytes || 0;
  const bytesDone = Math.min(job.bytesDone || 0, totalBytes || Infinity);
  const percent = totalBytes > 0 ? (bytesDone / totalBytes) * 100 : (job.totalFiles ? (job.completedFiles / job.totalFiles) * 100 : 0);

  return {
    id: job.id,
    status: job.status,
    requestedMode: job.requestedMode,
    activeMode: job.activeMode,
    strategy: job.strategy,
    localRoot: job.localRoot,
    remoteRoot: job.remoteRoot,
    createdAt: job.createdAt,
    startedAt: job.startedAt,
    endedAt: job.endedAt,
    totalFiles: job.totalFiles,
    completedFiles: job.completedFiles,
    verifiedFiles: job.verifiedFiles || 0,
    totalBytes,
    bytesDone,
    percent,
    speedBps: job.speedBps || 0,
    etaSeconds: job.etaSeconds,
    current: job.current ? {
      relative: job.current.relative,
      size: job.current.localSize,
      bytes: job.currentBytes || 0,
      percent: job.current.localSize > 0 ? ((job.currentBytes || 0) / job.current.localSize) * 100 : 0,
      reason: job.current.reason
    } : null,
    pauseRequested: !!job.pauseRequested,
    cancelRequested: !!job.cancelRequested,
    failovers: job.failovers,
    error: job.error,
    log: job.log.slice(-25)
  };
}

function jobLog(job, message) {
  job.log.push({
    at: new Date().toISOString(),
    message
  });
  if (job.log.length > 200) job.log.splice(0, job.log.length - 200);
}

function updateSpeed(job) {
  const now = Date.now();
  job.samples.push({ t: now, b: job.bytesDone });

  const cutoff = now - 8000;
  while (job.samples.length > 2 && job.samples[0].t < cutoff) {
    job.samples.shift();
  }

  if (job.samples.length >= 2) {
    const first = job.samples[0];
    const last = job.samples[job.samples.length - 1];
    const dt = (last.t - first.t) / 1000;
    if (dt > 0) {
      job.speedBps = Math.max(0, (last.b - first.b) / dt);
      const remaining = Math.max(0, job.totalBytes - job.bytesDone);
      job.etaSeconds = job.speedBps > 1 ? remaining / job.speedBps : null;
    }
  }
}

async function waitIfPaused(job) {
  if (!job.pauseRequested) return;

  job.status = "paused";
  jobLog(job, "Pausado com seguranca entre arquivos.");

  while (job.pauseRequested && !job.cancelRequested) {
    await sleep(350);
  }

  if (!job.cancelRequested) {
    job.status = "running";
    job.samples = [{ t: Date.now(), b: job.bytesDone }];
    jobLog(job, "Transferencia retomada.");
  }
}

async function uploadOneWithClient(job, item, mode, client) {
  job.currentBytes = 0;
  job.current = item;

  if (mode === "background") {
    await client.upload(item.localPath, item.remotePath, sent => {
      job.currentBytes = Math.min(Number(sent || 0), item.localSize);
      job.bytesDone = Math.min(job.bytesCompleted + job.currentBytes, job.totalBytes);
      updateSpeed(job);
    });
  } else {
    const remoteDir = path.posix.dirname(normalizeRemote(item.remotePath));
    await client.ensureDir(remoteDir);
    await client.cd("/");

    client.trackProgress(info => {
      if (info.type !== "upload") return;
      job.currentBytes = Math.min(Number(info.bytes || 0), item.localSize);
      job.bytesDone = Math.min(job.bytesCompleted + job.currentBytes, job.totalBytes);
      updateSpeed(job);
    });

    await client.uploadFrom(item.localPath, item.remotePath);
    client.trackProgress();
  }

  const verifiedSize = await remoteFileSize(client, mode, item.remotePath);
  if (verifiedSize !== item.localSize) {
    throw new Error(
      `Verificacao pos-upload falhou em ${item.relative}: ` +
      `local=${item.localSize} remoto=${verifiedSize}`
    );
  }

  job.currentBytes = item.localSize;
  job.bytesCompleted += item.localSize;
  job.bytesDone = job.bytesCompleted;
  job.completedFiles += 1;
  job.verifiedFiles += 1;
  jobLog(job, `Verificado: ${item.relative}`);
  updateSpeed(job);
}

async function runTransferJob(job) {
  job.status = "preparing";
  job.startedAt = new Date().toISOString();
  let transferClient = null;
  let transferClientMode = null;

  async function resetTransferClient() {
    if (transferClient) {
      await gracefulCloseFtp(transferClient, transferClientMode);
    }
    transferClient = null;
    transferClientMode = null;
  }

  async function getTransferClient(mode) {
    if (transferClient && transferClientMode === mode) {
      return transferClient;
    }
    await resetTransferClient();
    transferClient = await connectFtp(mode);
    transferClientMode = mode;
    return transferClient;
  }

  try {
    let diff;

    if (job.requestedMode === "auto") {
      try {
        const fastProbe = await tcpProbe(
          config.xbox.host,
          config.ftpFast.port,
          1200
        );

        if (!fastProbe.ok) {
          throw new Error(
            `probe rapido falhou: ${fastProbe.error || "porta 21 indisponivel"}`
          );
        }

        job.activeMode = "fast";
        jobLog(job, `Aurora FTP detectado em ${fastProbe.latencyMs} ms.`);
        jobLog(job, "Tentando Aurora FTP como rota inicial.");
        diff = await buildDiff(
          job.localRoot,
          job.remoteRoot,
          "fast",
          job.strategy
        );
      } catch (fastError) {
        jobLog(job, `Aurora indisponivel no preparo: ${fastError.message}`);
        job.activeMode = "background";
        job.failovers += 1;
        jobLog(job, "Usando FTPdll como rota inicial.");
        diff = await buildDiff(
          job.localRoot,
          job.remoteRoot,
          "background",
          job.strategy
        );
      }
    } else {
      job.activeMode = job.requestedMode;
      jobLog(job, `FTP inicial: ${job.activeMode}.`);
      diff = await buildDiff(
        job.localRoot,
        job.remoteRoot,
        job.activeMode,
        job.strategy
      );
    }

    job.queue = diff.queue;
    job.totalFiles = diff.queue.length;
    job.totalBytes = diff.uploadBytes;
    job.bytesCompleted = 0;
    job.bytesDone = 0;
    job.completedFiles = 0;
    job.samples = [{ t: Date.now(), b: 0 }];

    if (job.totalFiles === 0) {
      job.status = "completed";
      job.endedAt = new Date().toISOString();
      jobLog(job, "Nada para transferir.");
      return;
    }

    job.status = "running";

    for (let i = 0; i < job.queue.length; i++) {
      if (job.cancelRequested) break;

      await waitIfPaused(job);
      if (job.cancelRequested) break;

      const item = job.queue[i];
      job.current = item;
      job.currentBytes = 0;
      jobLog(job, `${i + 1}/${job.totalFiles}: ${item.relative}`);

      let transferred = false;
      let attempts = 0;

      while (!transferred && attempts < 3 && !job.cancelRequested) {
        attempts += 1;

        try {
          const client = await getTransferClient(job.activeMode);
          await uploadOneWithClient(job, item, job.activeMode, client);
          transferred = true;
        } catch (e) {
          // Any FTP error invalidates the current session. Reconnect cleanly
          // on the next retry/failover instead of stacking more sessions.
          await resetTransferClient();
          jobLog(job, `Falha em ${job.activeMode}: ${e.message}`);

          // O upload pode ter deixado um arquivo parcial.
          // O proximo uploadFrom usa STOR e regrava o arquivo inteiro.
          job.currentBytes = 0;
          job.bytesDone = job.bytesCompleted;
          updateSpeed(job);

          if (job.requestedMode === "auto" && job.activeMode === "fast") {
            const bg = await testBackgroundLogin();
            if (bg.ok) {
              job.activeMode = "background";
              job.failovers += 1;
              jobLog(job, "Aurora FTP caiu; migrando automaticamente para FTPdll.");
              continue;
            }
          }

          if (attempts < 3) {
            jobLog(job, `Tentando novamente (${attempts + 1}/3)...`);
            await sleep(1200);
          } else {
            throw e;
          }
        }
      }

      if (job.pauseRequested) {
        await waitIfPaused(job);
      }
    }

    job.current = null;
    job.currentBytes = 0;

    if (job.cancelRequested) {
      job.status = "cancelled";
      jobLog(job, "Transferencia cancelada entre arquivos.");
    } else {
      job.status = "completed";
      job.bytesDone = job.totalBytes;
      job.etaSeconds = 0;
      jobLog(job, "Transferencia concluida.");
    }

    job.endedAt = new Date().toISOString();
  } catch (e) {
    job.status = "failed";
    job.error = e.message;
    job.endedAt = new Date().toISOString();
    jobLog(job, `ERRO: ${e.message}`);
  } finally {
    await resetTransferClient();

    if (["completed", "failed", "cancelled"].includes(job.status)) {
      appendTransferHistory({
        id: job.id,
        analysisId: job.analysisId || null,
        createdAt: job.createdAt,
        startedAt: job.startedAt,
        endedAt: job.endedAt,
        status: job.status,
        requestedMode: job.requestedMode,
        activeMode: job.activeMode,
        localRoot: job.localRoot,
        remoteRoot: job.remoteRoot,
        totalFiles: job.totalFiles,
        completedFiles: job.completedFiles,
        verifiedFiles: job.verifiedFiles || 0,
        totalBytes: job.totalBytes,
        failovers: job.failovers,
        error: job.error
      });
    }
  }
}

// =============================
// API
// =============================

app.get("/api/overview", async (_req, res) => {
  const start = Date.now();

  const [
    systemR, tempR, memoryR, titleR, dashR,
    fastProbe, bgProbe
  ] = await Promise.all([
    novaGet("/system").then(v => ({ ok: true, v })).catch(e => ({ ok: false, e: e.message })),
    novaGet("/temperature").then(v => ({ ok: true, v })).catch(e => ({ ok: false, e: e.message })),
    novaGet("/memory").then(v => ({ ok: true, v })).catch(e => ({ ok: false, e: e.message })),
    novaGet("/title").then(v => ({ ok: true, v })).catch(e => ({ ok: false, e: e.message })),
    novaGet("/dashlaunch").then(v => ({ ok: true, v })).catch(e => ({ ok: false, e: e.message })),
    tcpProbe(config.xbox.host, config.ftpFast.port),
    tcpProbe(config.xbox.host, config.ftpBackground.port)
  ]);

  const novaOnline = systemR.ok || tempR.ok || memoryR.ok || titleR.ok || dashR.ok;
  const xboxOnline = novaOnline || fastProbe.ok || bgProbe.ok;

  res.json({
    project: "Echo360",
    component: "EchoCompanion",
    version: "0.4.0-rc1",
    timestamp: new Date().toISOString(),
    requestMs: Date.now() - start,
    xbox: {
      host: config.xbox.host,
      online: xboxOnline
    },
    nova: {
      online: novaOnline,
      port: config.nova.port
    },
    ftp: {
      fast: {
        online: fastProbe.ok,
        port: config.ftpFast.port,
        label: config.ftpFast.label || "Aurora FTP"
      },
      background: {
        online: bgProbe.ok,
        port: config.ftpBackground.port,
        label: config.ftpBackground.label || "FTPdll"
      }
    },
    system: systemR.ok ? sanitizeSystem(systemR.v) : null,
    temperature: tempR.ok ? safeTemperature(tempR.v) : null,
    memory: memoryR.ok ? safeMemory(memoryR.v) : null,
    title: titleR.ok ? safeTitle(titleR.v) : null,
    dashlaunch: dashR.ok ? safeDashlaunch(dashR.v) : null,
    privacy: { sensitiveSystemKeysExposed: false }
  });
});

app.get("/api/config/public", (_req, res) => {
  res.json(publicConfig());
});

app.get("/api/local/list", async (req, res) => {
  try {
    const requested = normalizeLocal(req.query.path || config.localRoots[0]);
    if (!isLocalPathAllowed(requested)) {
      return res.status(403).json({ error: "Pasta fora das raizes permitidas." });
    }

    const entries = await fsp.readdir(requested, { withFileTypes: true });
    const items = [];

    for (const x of entries) {
      try {
        const full = path.join(requested, x.name);
        const st = await fsp.stat(full);
        items.push({
          name: x.name,
          directory: x.isDirectory(),
          size: x.isFile() ? st.size : null
        });
      } catch {}
    }

    items.sort((a, b) => Number(b.directory) - Number(a.directory) || a.name.localeCompare(b.name));

    res.json({
      path: requested,
      parent: isLocalPathAllowed(path.dirname(requested)) ? path.dirname(requested) : null,
      items
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get("/api/remote/list", async (req, res) => {
  try {
    const requestedMode = req.query.mode || "auto";
    const remote = normalizeRemote(req.query.path || "/Hdd1");

    const executed = await withRequestedFtpMode(
      requestedMode,
      (client, mode) => listRemoteEntries(client, mode, remote)
    );
    const mode = executed.mode;
    const items = executed.result;

    res.json({
      mode,
      fallbackFrom: executed.fallbackFrom || null,
      fastError: executed.fastError || null,
      path: remote,
      parent: remote === "/" ? null : path.posix.dirname(remote),
      items: items
        .map(x => ({
          name: (mode === "background" && remote === "/")
            ? ftpdllRootNameToCanonical(x.name)
            : x.name,
          directory: !!(x.directory ?? x.isDirectory),
          size: Number(x.size || 0)
        }))
        .sort((a, b) =>
          Number(b.directory) - Number(a.directory) ||
          a.name.localeCompare(b.name)
        )
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post("/api/compare", async (req, res) => {
  try {
    const {
      localRoot,
      remoteRoot,
      mode = "auto",
      strategy = "missing-and-different"
    } = req.body || {};

    if (!localRoot || !remoteRoot) {
      return res.status(400).json({ error: "Informe origem e destino." });
    }

    const st = await fsp.stat(normalizeLocal(localRoot));
    if (!st.isDirectory()) {
      return res.status(400).json({ error: "A origem local nao e uma pasta." });
    }

    let diff;
    let modeUsed;
    let fallbackFrom = null;
    let fastError = null;

    if (mode === "auto") {
      const fastProbe = await tcpProbe(
        config.xbox.host,
        config.ftpFast.port,
        1200
      );

      if (!fastProbe.ok) {
        fastError = `probe rapido falhou: ${fastProbe.error || "porta 21 indisponivel"}`;
        diff = await buildDiff(localRoot, remoteRoot, "background", strategy);
        modeUsed = "background";
        fallbackFrom = "fast";
      } else {
        try {
          diff = await buildDiff(localRoot, remoteRoot, "fast", strategy);
          modeUsed = "fast";
        } catch (e) {
          fastError = e.message;
          diff = await buildDiff(localRoot, remoteRoot, "background", strategy);
          modeUsed = "background";
          fallbackFrom = "fast";
        }
      }
    } else {
      diff = await buildDiff(localRoot, remoteRoot, mode, strategy);
      modeUsed = mode;
    }

    const analysisId = rememberAnalysis({
      localRoot: normalizeLocal(localRoot),
      remoteRoot: normalizeRemote(remoteRoot),
      requestedMode: mode,
      strategy
    });

    const warnings = [];
    if (diff.localFiles > 0 && diff.remoteFiles === 0 && diff.uploadCount === diff.localFiles) {
      warnings.push("O destino remoto parece vazio ou nao foi enumerado.");
    }
    if (diff.uploadBytes > 8 * 1024 * 1024 * 1024) {
      warnings.push("A transferencia planejada passa de 8 GB.");
    }

    res.json({
      analysisId,
      analyzedAt: new Date().toISOString(),
      modeRequested: mode,
      modeUsed,
      fallbackFrom,
      fastError,
      strategy,
      localFiles: diff.localFiles,
      remoteFiles: diff.remoteFiles,
      sameCount: diff.sameCount,
      missing: diff.missing.map(x => ({
        file: x.relative,
        localSize: x.localSize
      })),
      different: diff.different.map(x => ({
        file: x.relative,
        localSize: x.localSize,
        remoteSize: x.remoteSize
      })),
      uploadCount: diff.uploadCount,
      uploadBytes: diff.uploadBytes,
      warnings,
      safety: "Nenhum arquivo remoto foi apagado."
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post("/api/transfer/start", async (req, res) => {
  try {
    const {
      localRoot,
      remoteRoot,
      mode = "auto",
      strategy = "missing-and-different",
      analysisId = null
    } = req.body || {};

    if (!localRoot || !remoteRoot) {
      return res.status(400).json({ error: "Informe origem e destino." });
    }
    if (!isLocalPathAllowed(localRoot)) {
      return res.status(403).json({ error: "Origem local fora das raizes permitidas." });
    }
    if (!["auto", "fast", "background"].includes(mode)) {
      return res.status(400).json({ error: "Modo invalido." });
    }
    if (!["missing-only", "missing-and-different"].includes(strategy)) {
      return res.status(400).json({ error: "Estrategia invalida." });
    }

    if (analysisId) {
      const previous = analyses.get(analysisId);

      if (!previous) {
        return res.status(409).json({
          error: "Analise expirada. Rode Analisar novamente."
        });
      }

      if (Date.now() - previous.createdAtMs > 15 * 60 * 1000) {
        analyses.delete(analysisId);
        return res.status(409).json({
          error: "Analise com mais de 15 minutos. Rode Analisar novamente."
        });
      }

      const unchanged =
        normalizeLocal(localRoot) === previous.localRoot &&
        normalizeRemote(remoteRoot) === previous.remoteRoot &&
        mode === previous.requestedMode &&
        strategy === previous.strategy;

      if (!unchanged) {
        return res.status(409).json({
          error: "Origem, destino ou modo mudaram. Analise novamente."
        });
      }
    }

    const id = newJobId();
    const job = {
      id,
      analysisId,
      status: "queued",
      requestedMode: mode,
      activeMode: null,
      strategy,
      localRoot: normalizeLocal(localRoot),
      remoteRoot: normalizeRemote(remoteRoot),
      createdAt: new Date().toISOString(),
      startedAt: null,
      endedAt: null,
      queue: [],
      totalFiles: 0,
      completedFiles: 0,
      verifiedFiles: 0,
      totalBytes: 0,
      bytesCompleted: 0,
      bytesDone: 0,
      current: null,
      currentBytes: 0,
      speedBps: 0,
      etaSeconds: null,
      pauseRequested: false,
      cancelRequested: false,
      failovers: 0,
      error: null,
      log: [],
      samples: []
    };

    jobs.set(id, job);
    runTransferJob(job); // intencionalmente assíncrono

    res.status(202).json({ id, status: job.status });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get("/api/transfer/:id", (req, res) => {
  const job = jobs.get(req.params.id);
  if (!job) return res.status(404).json({ error: "Job nao encontrado." });
  res.json(compactJob(job));
});

app.post("/api/transfer/:id/pause", (req, res) => {
  const job = jobs.get(req.params.id);
  if (!job) return res.status(404).json({ error: "Job nao encontrado." });

  if (!["running", "preparing"].includes(job.status)) {
    return res.status(409).json({ error: "Job nao esta em execucao." });
  }

  job.pauseRequested = true;
  jobLog(job, "Pausa solicitada: sera aplicada apos o arquivo atual.");
  res.json(compactJob(job));
});

app.post("/api/transfer/:id/resume", (req, res) => {
  const job = jobs.get(req.params.id);
  if (!job) return res.status(404).json({ error: "Job nao encontrado." });

  job.pauseRequested = false;
  if (job.status === "paused") job.status = "running";
  res.json(compactJob(job));
});

app.post("/api/transfer/:id/cancel", (req, res) => {
  const job = jobs.get(req.params.id);
  if (!job) return res.status(404).json({ error: "Job nao encontrado." });

  if (["completed", "failed", "cancelled"].includes(job.status)) {
    return res.status(409).json({ error: "Job ja terminou." });
  }

  job.cancelRequested = true;
  job.pauseRequested = false;
  jobLog(job, "Cancelamento solicitado: sera aplicado apos o arquivo atual.");
  res.json(compactJob(job));
});

app.get("/api/history", (_req, res) => {
  res.json(readTransferHistory().slice().reverse().slice(0, 50));
});

app.get("/api/transfers", (_req, res) => {
  const list = Array.from(jobs.values())
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .slice(0, 20)
    .map(compactJob);

  res.json(list);
});

app.listen(config.httpPort, "127.0.0.1", () => {
  console.log("");
  console.log("===========================================");
  console.log(" Echo360 Entertainment Center v0.4.0-rc1");
  console.log("===========================================");
  console.log(` Painel:  http://127.0.0.1:${config.httpPort}`);
  console.log(` Xbox:    ${config.xbox.host}`);
  console.log(` NOVA:    :${config.nova.port}`);
  console.log(` Rapido:  :${config.ftpFast.port} (${config.ftpFast.label})`);
  console.log(` Fundo:   :${config.ftpBackground.port} (${config.ftpBackground.label})`);
  console.log("===========================================");
  console.log("");
});
