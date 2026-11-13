// 합성 페이지·녹화 mp4·타임라인을 제공하는 최소 정적 서버 (Range 지원 — <video> 시킹에 필요)
import fs from "node:fs";
import http from "node:http";
import path from "node:path";

const types = { ".html": "text/html; charset=utf-8", ".json": "application/json", ".mp4": "video/mp4", ".js": "text/javascript", ".mjs": "text/javascript", ".wav": "audio/wav" };

export function serve(dir, port) {
  const root = path.resolve(dir);
  const server = http.createServer((req, res) => {
    const p = path.join(root, decodeURIComponent(new URL(req.url, "http://x").pathname));
    if (!p.startsWith(root) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); res.end(); return; }
    const stat = fs.statSync(p); const type = types[path.extname(p)] || "application/octet-stream";
    const range = req.headers.range;
    if (range) {
      const [s, e] = range.replace("bytes=", "").split("-").map((v) => (v === "" ? null : Number(v)));
      const start = s ?? 0, end = Math.min(e ?? stat.size - 1, stat.size - 1);
      res.writeHead(206, { "Content-Type": type, "Content-Range": `bytes ${start}-${end}/${stat.size}`, "Accept-Ranges": "bytes", "Content-Length": end - start + 1 });
      fs.createReadStream(p, { start, end }).pipe(res); return;
    }
    res.writeHead(200, { "Content-Type": type, "Content-Length": stat.size, "Accept-Ranges": "bytes" });
    fs.createReadStream(p).pipe(res);
  });
  return new Promise((resolve) => server.listen(port, "127.0.0.1", () => resolve(server)));
}

if (process.argv[1] && path.resolve(process.argv[1]) === new URL(import.meta.url).pathname) {
  const dir = process.argv[2] || "."; const port = Number(process.argv[3] || 8787);
  await serve(dir, port); console.error(`serving ${path.resolve(dir)} on http://127.0.0.1:${port}`);
}
