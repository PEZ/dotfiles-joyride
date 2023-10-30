const s = "World";
hello = function (strings, ...values) {
  return strings[0] + values[0] + strings[1];
};
hello`Hello ${s}!`;

async function testAwait() {
  const promise = new Promise((resolve, reject) => {
    setTimeout(() => {
      resolve("Promise resolved");
    }, 1000);
  });

  console.log("Before await");
  const result = await promise;
  console.log(result); // Output: Promise resolved
  console.log("After await");
  return result;
}

testAwait();

// ES6 not supported
//import { readFile } from 'fs/promises';
const { readFile } = require("fs").promises;
// Top level await not supported
// const packageJson = await readFile('/Users/pez/.config/joyride/sidecar/package.json', 'utf-8');
// const config = JSON.parse(packageJson);
const config = (async () => {
  const config = JSON.parse(
    await readFile("/Users/pez/.config/joyride/sidecar/package.json", "utf-8")
  );
  return config;
})();

config.then((config) => config);

var {
  hello_fine,
  hello_borked,
} = require("/Users/pez/.config/joyride/test-files/has-errors.js");

hello_fine("World");
hello_borked("World");
