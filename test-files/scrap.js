const s = 'World';
hello = function (strings, ...values) {
  return strings[0] + values[0] + strings[1];
}
hello`Hello ${s}!`;

async function testAwait() {
  const promise = new Promise((resolve, reject) => {
    setTimeout(() => {
      resolve("Promise resolved");
    }, 1000);
  });

  console.log("Before await");
  const result = await promise;
  console.log(result);  // Output: Promise resolved
  console.log("After await");
  return result;
}

testAwait();

// ES6 not supported
//import { readFile } from 'fs/promises';
const { readFile } = require('fs').promises;
const packageJson = await readFile('/Users/pez/.config/joyride/sidecar/package.json', 'utf-8');
const config = JSON.parse(packageJson);
config;

(async () => {
  const config = JSON.parse(await readFile('/Users/pez/.config/joyride/sidecar/package.json', 'utf-8'));
  return config;

  // Other module code
})();