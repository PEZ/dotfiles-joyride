const vscode = require("vscode");

(async () => {
  const toJS = await vscode.commands.executeCommand(
    "joyride.runCode",
    "clj->js"
  );
  const exData = await vscode.commands.executeCommand(
    "joyride.runCode",
    "ex-data"
  );

  const r = vscode.commands
    .executeCommand("joyride.runCode", "{:a (some-fn)}")
    .catch((e) =>
      vscode.window.showErrorMessage(JSON.stringify(toJS(exData(e))))
    );
  if (r) {
    const js_r = await toJS(r);
    console.log(js_r);
    vscode.window.showInformationMessage(js_r);
  }
})();
