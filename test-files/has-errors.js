function hello_fine(s) {
  return `Hello ${s}!!!!!`;
}

function hello_borked(s) {
  return `Hello ${x}!`;
}

exports.hello_fine = hello_fine;
exports.hello_borked = hello_borked;