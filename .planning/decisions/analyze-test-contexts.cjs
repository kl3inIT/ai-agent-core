const fs = require("fs"), path = require("path");
const root = "ai-agent/ai-agent/src/test/java/com/vn/agent";
const files = [];
(function w(d){ for (const e of fs.readdirSync(d,{withFileTypes:true})){ const p=path.join(d,e.name); if(e.isDirectory())w(p); else if(e.name.endsWith("Test.java"))files.push(p);} })(root);
const groups = {};
for (const f of files){
  const x = fs.readFileSync(f,"utf8");
  if (!/@SpringBootTest/.test(x)) continue;
  const body = x.split("class ").slice(1).join("class ");
  const mock = /@MockitoBean|@MockBean/.test(x);
  const fault = /@TestConfiguration|ThrowingSaving/.test(body);
  const norm = s => s ? s.replace(/\s+/g,"").replace(/com\.vn\.(autoconfigure\.)?agent\./g,"").replace(/com\.vn\.agent\.(test_support\.)?/g,"") : "";
  const g = re => { const m = x.match(re); return norm(m ? m[1] : ""); };
  const cls = g(/@SpringBootTest\s*\(\s*classes\s*=\s*\{?([^})]*)\}?/);
  const props = g(/properties\s*=\s*\{([^}]*)\}/);
  const iac = g(/@ImportAutoConfiguration\s*\(\s*\{([^}]*)\}/);
  const imp = g(/@Import\s*\(\s*\{?([^})]*)\}?\)/);
  const sig = "cls[" + cls + "] iac[" + iac + "] imp[" + imp + "] props[" + props + "]";
  const key = (mock ? "MOCK " : "") + (fault && !mock ? "FAULT " : "") + sig;
  const rel = path.relative(root, f).replace(/\.java$/,"").split(path.sep).join("/");
  (groups[key] = groups[key] || []).push(rel);
}
const ent = Object.entries(groups).sort((a,b)=>b[1].length-a[1].length);
console.log("=== SHAREABLE groups (no mock/fault), >=2 members = collapse targets ===");
for (const [sig,fs_] of ent){ if(/^(MOCK|FAULT)/.test(sig)) continue; if(fs_.length<2) continue;
  console.log("\n[" + fs_.length + "] " + sig.slice(0,170)); fs_.forEach(n=>console.log("    "+n)); }
console.log("\n=== singletons (no mock/fault) ===");
ent.filter(([s,f])=>!/^(MOCK|FAULT)/.test(s)&&f.length===1).forEach(([s,f])=>console.log("    "+f[0]+"   "+s.slice(0,120)));
const iso = ent.filter(([s])=>/^(MOCK|FAULT)/.test(s)).reduce((a,[,f])=>a+f.length,0);
console.log("\n=== isolated (mock/fault): " + iso + " files (leave as-is) ===");
