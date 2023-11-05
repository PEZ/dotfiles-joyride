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

// This makes `packageJson` print as a string in the repl, but it's a promise
// const packageJson = (async () => {
//   return await readFile("/Users/pez/.config/joyride/sidecar/package.json", "utf-8");
// })();
// this gives SyntaxError: Unexpected token 'o', "[object Promise]" is not valid JSON
// const config = JSON.parse(packageJson); 

// But we can do this:
packageJson = undefined;
(async () => {
  packageJson = await readFile("/Users/pez/.config/joyride/sidecar/package.json", "utf-8");
})();
const config = JSON.parse(packageJson);
// Although, now `packageJson` is global...

var {
  hello_fine,
  hello_borked,
} = require("/Users/pez/.config/joyride/test-files/has-errors.js");

hello_fine("World");
hello_borked("World");

///////////////////////

let f = () => console.log("f 1");
let g = () => f();
g();

f = () => console.log("f 2");
g();

setTimeout(() => {
  f = () => console.log("f 3");
  g();
}, 1000);

f = () => console.log("f 4");
g();

///////////////////////

let f = () => console.log("f outside");

let fInside = () => console.log("f inside");

let g;
with ({ f: fInside }) {
  g = () => f();
  g();
}

setTimeout(() => {
  fInside = () => console.log("f inside 2");
  g();
}, 1000);

g();

f = () => console.log("f outside 2");
g();

///////////////////////

console.log("\n\n\n-------------");
let f = () => console.log("f outside");
let g = () => f();

let obj = { };
obj.f = () => console.log("f inside");

console.log("1, expecting f outside")
g();

with (obj) {
  console.log("2, expecting f inside")
  g = () => f();
  g();
}

setTimeout(() => {
  console.log("3, expecting f inside 2")
  obj.f = () => console.log("f inside 2");
  g();
}, 1000);

with (obj) {
  setTimeout(() => {
    console.log("4, expecting f inside 2")
    obj.f = () => console.log("f inside 2");
    g();
  }, 1000);
}

console.log("5, expecting f inside")
g();

console.log("6, expecting f outside 2")
obj.f = () => console.log("f outside 2");
g();  
console.log("-------------");