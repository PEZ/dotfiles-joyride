const s = "World";
hello = function (strings, ...values) {
  return strings[0] + values[0] + strings[1];
};
hello`Hello ${s}!`;

async function testAwait() {
  const promise = new Promise((resolve, reject) => {
    setTimeout(() => {
      const t2 = performance.now();
      resolve(`Promise resolved after ${t2 - t1} ms`);
    }, 1500);
  });

  console.log("Before await");
  const t1 = performance.now();
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

// This makes `packageJson` print as a string in the repl, but it's a promise
const packageJson = (async () => {
  return await readFile("/Users/pez/.config/joyride/sidecar/package.json", "utf-8");
})();
// this gives SyntaxError: Unexpected token 'o', "[object Promise]" is not valid JSON
// const config = JSON.parse(packageJson); 
// (You can select it, and evaluate and prove it for yourself)

(async () => {
  var config = JSON.parse(
    await readFile("/Users/pez/.config/joyride/sidecar/package.json", "utf-8")
  );
  globalThis.config = config;
  return config;
})();
config; // prints as an object in the repl, but it's a promise
config.name; // null
//c_name = config.then(x => x.name); // we need to do this to see the name
typeof c_name; // object (it's still a promise)

var {
  hello_fine,
  hello_borked,
} = require("/Users/pez/.config/joyride/test-files/has-errors.js");

hello_fine("World");
hello_borked("World");
