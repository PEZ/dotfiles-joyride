(ns webview-editor
  (:require ["vscode" :as vscode]
            [joyride.core :as joyride]
            [promesa.core :as p]))

(defn webview-content []
  "<!DOCTYPE html>
  <html lang='en'>
  <head>
    <meta charset='UTF-8'>
    <meta name='viewport' content='width=device-width, initial-scale=1.0'>
    <title>Webview Editor</title>
    <style>
      body, html { height: 100%; margin: 0; display: flex; flex-direction: column; }
      #editor { flex: 1; }
    </style>
  </head>
  <body>
    <div id='editor'></div>
    <script src='https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.21.2/min/vs/loader.js'></script>
    <script>
      require.config({ paths: { 'vs': 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.21.2/min/vs' }});
      require(['vs/editor/editor.main'], function() {
        monaco.editor.create(document.getElementById('editor'), {
          value: '(ns user-activate)\\n\\n(+ 1 2 3)',
          language: 'clojure',
          readOnly: true
        });
      });
    </script>
  </body>
  </html>")

(defn create-webview! []
  (let [panel (vscode/window.createWebviewPanel
               "joyride-webview"
               "Joyride Webview"
               vscode/ViewColumn.One
               #js {:enableScripts true
                    :localResourceRoots #js [(-> vscode/workspace.workspaceFolders first .-uri)]
                    })]
    (set! (.-html panel.webview) (webview-content))))

(comment
  (create-webview!)
  :rcf)


(defn get-webview-content-2 []
  "<!DOCTYPE html>
  <html lang='en'>
  <head>
    <meta charset='UTF-8'>
    <meta name='viewport' content='width=device-width, initial-scale=1.0'>
    <title>Embedded Editors</title>
    <style>
      body { font-family: sans-serif; }
      .code-block { margin: 20px 0; }
    </style>
  </head>
  <body>
    <h1>Embedded VS Code Editors</h1>
    <div class='code-block' id='code-block-1'></div>
    <div class='code-block' id='code-block-2'></div>
    <script>
      console.log('1. hello')

      const vscode = acquireVsCodeApi();

      console.log('2. hello')

      window.addEventListener('message', event => {
        const message = event.data;
        if (message.command === 'setCode') {
          const codeBlock = document.getElementById(message.id);
          if (codeBlock) {
            const editor = monaco.editor.create(codeBlock, {
              value: message.code,
              language: message.language,
              readOnly: true
            });
          }
        }
      });

      console.log('3. hello')

      require.config({ paths: { 'vs': 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.21.2/min/vs' }});

      console.log('4. hello')

      require(['vs/editor/editor.main'], function() {
        vscode.postMessage({ command: 'ready' });
      });

      console.log('5. hello')
    </script>
  </body>
  </html>")

(defn open-embedded-editors! []
  (let [panel (vscode/window.createWebviewPanel
               "embeddedEditors"
               "Embedded Editors"
               vscode/ViewColumn.One
               #js {:enableScripts true})]
    (set! (.-html panel.webview) (get-webview-content-2))
    (.onDidReceiveMessage panel.webview
                          (fn [message]
                            (when (= (.-command message) "ready")
                              (.postMessage panel.webview #js {:command "setCode"
                                                               :id "code-block-1"
                                                               :code "(ns user-activate)\n\n(+ 1 2 3)"
                                                               :language "clojure"})
                              (.postMessage panel.webview #js {:command "setCode"
                                                               :id "code-block-2"
                                                               :code "(defn add [a b] (+ a b))\n\n(add 1 2)"
                                                               :language "clojure"}))))))

(comment
  (open-embedded-editors!)
  :rcf)