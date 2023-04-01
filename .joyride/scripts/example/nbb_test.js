module = require("module");
req = module.createRequire(__dirname)

async function nbb() {
    console.log('dude');
    resolved = req.resolve("nbb");
    console.log('resolved', resolved);
    var mod;
    try {
        mod = await import(resolved);
    }
    catch (e) {
        console.log(e);
    }
    console.log(mod);
    console.log(typeof(mod))
    return mod;
}

globalThis.nbb = nbb().catch(err => err);

let x = {}