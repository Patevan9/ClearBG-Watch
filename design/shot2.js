const { chromium } = require('playwright');
(async()=>{const b=await chromium.launch();const p=await b.newPage({viewport:{width:1080,height:800},deviceScaleFactor:1});
await p.goto('file://'+process.cwd()+'/devoptions.html');await p.waitForTimeout(300);
await (await p.$('#c')).screenshot({path:'guide_devoptions.png'});await b.close();})();
