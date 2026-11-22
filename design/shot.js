const { chromium } = require('playwright');
(async()=>{const b=await chromium.launch();const p=await b.newPage({viewport:{width:1080,height:800},deviceScaleFactor:1});
for(const m of ['pair','connect']){await p.goto('file://'+process.cwd()+'/guide.html?mode='+m);await p.waitForTimeout(300);
const el=await p.$('#c');await el.screenshot({path:`guide_${m}.png`});}
await b.close();})();
