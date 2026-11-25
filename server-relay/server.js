const http = require("http");
const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 10000;

function buildIceConfig() {
  const iceServers = [
    { urls: "stun:stun.l.google.com:19302" },
    { urls: "stun:stun1.l.google.com:19302" },
  ];
  let turnEnabled = false;
  if (process.env.TURN_URL) {
    const turn = { urls: process.env.TURN_URL };
    if (process.env.TURN_USERNAME) turn.username = process.env.TURN_USERNAME;
    if (process.env.TURN_CREDENTIAL) turn.credential = process.env.TURN_CREDENTIAL;
    iceServers.push(turn);
    turnEnabled = true;
  }
  return { iceServers, turnEnabled };
}

// Oda yapısı: rooms.get(roomKey) -> { car, mobiles: Set, lastTelemetry, lastFrame }
const rooms = new Map();

function getRoom(key) {
  if (!rooms.has(key)) {
    rooms.set(key, { car: null, mobiles: new Set(), lastTelemetry: null, lastFrame: null });
  }
  return rooms.get(key);
}

const PORTAL_HTML = `<!doctype html>
<html lang="tr">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>ULAK v4.0 • BYD Seal U DM-i Web Portal</title>
<style>
  body { margin:0; background:#060B14; color:#F8FAFC; font-family:system-ui,-apple-system,sans-serif; padding:18px; }
  .wrap { max-width:920px; margin:0 auto; display:grid; gap:14px; }
  .card { background:#0F172A; border:1px solid #1E293B; border-radius:16px; padding:16px; }
  h1 { margin:0 0 6px; color:#10B981; font-size:22px; }
  .row { display:flex; gap:8px; flex-wrap:wrap; align-items:center; }
  input { background:#060B14; color:#fff; border:1px solid #334155; border-radius:10px; padding:10px 12px; flex:1; min-width:140px; }
  button { background:#10B981; color:#060B14; font-weight:700; border:0; border-radius:10px; padding:10px 14px; cursor:pointer; }
  button.sec { background:#1E293B; color:#E2E8F0; }
  button.cyan { background:#0891B2; color:#fff; }
  .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr)); gap:10px; }
  .metric { background:#060B14; border-radius:12px; padding:12px; }
  .lbl { color:#94A3B8; font-size:11px; }
  .val { font-size:18px; font-weight:700; margin-top:4px; }
  #camImg { width:100%; max-height:360px; object-fit:contain; background:#060B14; border-radius:12px; }
  pre { background:#060B14; padding:10px; border-radius:10px; font-size:12px; color:#22D3EE; overflow:auto; max-height:140px; }
</style>
</head>
<body>
<div class="wrap">
  <div class="card">
    <h1>ULAK v4.0 • BYD Seal U DM-i Web Portal</h1>
    <div class="row">
      <input id="vid" value="BYD-SEAL-U-DMI-2025" placeholder="Vehicle ID" />
      <input id="tok" value="ULAK2025" placeholder="PIN (auth_token)" type="password" />
      <button onclick="connectWs()">Bağlan</button>
      <span id="status" style="color:#94A3B8;font-size:13px;">Beklemede</span>
    </div>
  </div>
  <div class="card grid">
    <div class="metric"><div class="lbl">LFP BLADE EV</div><div id="mEv" class="val">--</div></div>
    <div class="metric"><div class="lbl">HEV YAKIT</div><div id="mHev" class="val">--</div></div>
    <div class="metric"><div class="lbl">12V & KABİN</div><div id="mAux" class="val">--</div></div>
    <div class="metric"><div class="lbl">KİLİT / NÖBETÇİ</div><div id="mSec" class="val">--</div></div>
  </div>
  <div class="card">
    <div class="row" style="margin-bottom:10px;">
      <button class="cyan" onclick="sendCmd({command:'START_CLOUD_STREAM'})">Canlı Kamerayı Başlat</button>
      <button class="sec" onclick="sendCmd({command:'STOP_CLOUD_STREAM'})">Durdur</button>
      <button class="sec" onclick="sendCmd({command:'SET_LOCK',locked:true})">Kilitle</button>
      <button class="sec" onclick="sendCmd({command:'SET_LOCK',locked:false})">Kilit Aç</button>
      <button class="sec" onclick="sendCmd({command:'SET_AC',enable:true,temp:21.5})">Klima 21.5°C</button>
      <button class="sec" onclick="sendCmd({command:'FIND_CAR'})">Araç Bul</button>
    </div>
    <img id="camImg" alt="Canlı kamera bekleniyor..." />
  </div>
  <div class="card">
    <div class="row">
      <input id="voiceInput" placeholder="Hey Ulak'a sor: Örn. Menzilim ne kadar? veya Klimayı 22 derece aç" />
      <button onclick="askVoice()">Hey Ulak Gönder</button>
    </div>
    <pre id="log">Günlük hazır...</pre>
  </div>
</div>
<script>
let ws = null;
function log(m){ document.getElementById('log').textContent = new Date().toLocaleTimeString() + ' ' + m + '\n' + document.getElementById('log').textContent; }
function connectWs(){
  if(ws) ws.close();
  const vid = encodeURIComponent(document.getElementById('vid').value.trim());
  const tok = encodeURIComponent(document.getElementById('tok').value.trim());
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WebSocket(proto + '//' + location.host + '/ws?role=mobile&vehicleId=' + vid + '&token=' + tok);
  ws.onopen = () => { document.getElementById('status').textContent = 'BAĞLI'; log('Render bulut rölesine bağlanıldı.'); };
  ws.onclose = () => { document.getElementById('status').textContent = 'KESİLDİ'; };
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if(msg.type === 'TELEMETRY'){
      const p = msg.payload || {};
      document.getElementById('mEv').textContent = '%' + (p.soc_percent ?? '--') + ' • ' + (p.ev_range_km ?? '--') + ' km';
      document.getElementById('mHev').textContent = '%' + (p.fuel_percent ?? '--') + ' • ' + (p.hev_range_km ?? '--') + ' km';
      document.getElementById('mAux').textContent = (p.aux_12v_voltage ?? '--') + 'V • ' + (p.cabin_temp_c ?? '--') + '°C';
      document.getElementById('mSec').textContent = (p.is_locked ? 'KİLİTLİ' : 'AÇIK') + ' / ' + (p.sentry_active ? 'NÖBETÇİ' : 'PASİF');
    } else if(msg.type === 'CAMERA_FRAME' && msg.data){
      document.getElementById('camImg').src = 'data:image/jpeg;base64,' + msg.data;
    } else if(msg.type === 'ACK'){
      log('Yanıt: ' + (msg.payload?.message || JSON.stringify(msg.payload)));
    } else if(msg.type === 'ALERT'){
      log('UYARI: ' + msg.title + ' - ' + msg.message);
    }
  };
}
function sendCmd(payload){
  if(!ws || ws.readyState !== 1) return log('Önce Bağlan butonuna basın.');
  ws.send(JSON.stringify({ type:'COMMAND', request_id:String(Date.now()), payload }));
}
function askVoice(){
  const p = document.getElementById('voiceInput').value.trim();
  if(!p) return;
  sendCmd({ command:'VOICE_ASK', prompt:p, speak_on_car:true });
  document.getElementById('voiceInput').value = '';
}
</script>
</body>
</html>`;

const server = http.createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(
      JSON.stringify({
        service: "ULAK BYD Cloud Relay & WebRTC Signaling",
        version: "4.0.0",
        status: "ok",
        activeRooms: rooms.size,
        turnEnabled: Boolean(process.env.TURN_URL),
      })
    );
    return;
  }

  if (req.url === "/api/ice-config") {
    res.writeHead(200, {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
    });
    res.end(JSON.stringify(buildIceConfig()));
    return;
  }

  if (req.url === "/" || req.url === "/portal") {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(PORTAL_HTML);
    return;
  }

  res.writeHead(404);
  res.end("Not found");
});

const wss = new WebSocketServer({ server, path: "/ws", maxPayload: 2 * 1024 * 1024 });

wss.on("connection", (ws, req) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const role = url.searchParams.get("role");
  const vehicleId = (url.searchParams.get("vehicleId") || "").trim();
  const token = (url.searchParams.get("token") || "").trim();

  if (!vehicleId || !token || (role !== "car" && role !== "mobile")) {
    ws.close(1008, "Missing role, vehicleId or token");
    return;
  }

  const roomKey = `${vehicleId}::${token}`;
  const room = getRoom(roomKey);

  ws.isAlive = true;
  ws.on("pong", () => {
    ws.isAlive = true;
  });

  if (role === "car") {
    if (room.car && room.car.readyState === 1) {
      room.car.close(1000, "Replaced by new car connection");
    }
    room.car = ws;
    console.log(`[CAR CONNECTED] ${vehicleId}`);
  } else {
    room.mobiles.add(ws);
    console.log(`[MOBILE CONNECTED] ${vehicleId} (total: ${room.mobiles.size})`);
    if (room.lastTelemetry) ws.send(room.lastTelemetry);
    if (room.lastFrame) ws.send(room.lastFrame);
  }

  ws.on("message", (raw) => {
    const text = raw.toString("utf8");
    if (role === "car") {
      try {
        const parsed = JSON.parse(text);
        if (parsed.type === "TELEMETRY") room.lastTelemetry = text;
        if (parsed.type === "CAMERA_FRAME") room.lastFrame = text;
      } catch (_) {}
      for (const m of room.mobiles) {
        if (m.readyState === 1 && m.bufferedAmount < 512 * 1024) {
          m.send(text);
        }
      }
    } else {
      if (room.car && room.car.readyState === 1) {
        room.car.send(text);
      } else {
        try {
          const parsed = JSON.parse(text);
          ws.send(
            JSON.stringify({
              type: "ACK",
              request_id: parsed.request_id || "",
              payload: {
                ok: false,
                hardware_executed: false,
                message: "Araç şu an Render bulut rölesine bağlı değil.",
              },
            })
          );
        } catch (_) {}
      }
    }
  });

  ws.on("close", () => {
    if (role === "car" && room.car === ws) {
      room.car = null;
      console.log(`[CAR DISCONNECTED] ${vehicleId}`);
    } else {
      room.mobiles.delete(ws);
      // Tüm mobil istemciler çıktıysa araçtaki bulut kamera akışını durdur
      if (room.mobiles.size === 0 && room.car && room.car.readyState === 1) {
        room.car.send(
          JSON.stringify({
            type: "COMMAND",
            request_id: "auto_stop_stream",
            payload: { command: "STOP_CLOUD_STREAM" },
          })
        );
      }
    }
    if (!room.car && room.mobiles.size === 0) {
      rooms.delete(roomKey);
    }
  });
});

setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.isAlive) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

server.listen(PORT, () => {
  console.log(`ULAK v4.0 Cloud Relay & WebRTC Server listening on port ${PORT}`);
});
