(ns ai-workflow-old.memory-agent-old
  "Background memory creation agent using Language Model API"
  (:require
   [promesa.core :as p]
   [joyride.core :as joy]
   [ai-workflow.agents :as agents]
   ["vscode" :as vscode]))

(def agent-model "grok-code")

(defn user-data-uri [relative-path]
  (let [path (js/require "path")
        global-storage-path (-> (joy/extension-context)
                                .-globalStorageUri .-fsPath)
        user-dir (.join path global-storage-path ".." "..")
        full-path (.join path user-dir relative-path)]
    (vscode/Uri.file full-path)))

(p/let [uri (user-data-uri "prompts/remember.prompt.md")
        content (vscode/workspace.fs.readFile uri)
        decoder (js/TextDecoder. "utf-8")
        prompt (.decode decoder content)]
  (def remember-prompt prompt))

(defn async-iterator-seq
  "Consumes an async generator/iterator and returns a promise of all values.
  Works with any JavaScript object that implements Symbol.asyncIterator.

  Example:
    (p/let [values (async-iterator-seq some-async-generator)]
      (prn values)) ; => [val1 val2 val3 ...]"
  [generator]
  (let [iter (.call (aget generator js/Symbol.asyncIterator) generator)
        results (atom [])]
    (p/loop []
      (p/let [v (.next iter)]
        (if (.-done v)
          @results
          (do (swap! results conj (.-value v))
              (p/recur)))))))

(defn consume-lm-response
  "Consumes a Language Model response stream and returns the complete text.

  Example:
    (p/let [response (make-lm-request model messages)
            text (consume-lm-response response)]
      (prn text))"
  [response]
  (p/let [chunks (async-iterator-seq (.-text response))]
    (apply str chunks)))

(defn create-memory-from-context!
  "Spawns an LLM request to create a memory entry from mistake/correction context.

  Returns a promise of:
    {:success true :memory \"...\"} on success
    {:error \"...\"} on failure

  Args:
    context - String containing the mistake/correction/pattern to remember

  Example:
    (p/let [result (create-memory-from-context!
                     \"Mistake: Did X. Correction: Do Y instead.\")]
      (when (:success result)
        (prn (:memory result))))"
  ([context]
   (p/let [[model] (vscode/lm.selectChatModels #js {:vendor "copilot" :family agent-model})]
     (if model
       (let [memory-prompt (str "You are a memory creation assistant for AI coding agents.

Task: Create a concise, actionable memory entry for a Copilot instructions file.

Context provided:
" context "

Format your response as a brief markdown entry (2-4 sentences) that captures:
1. What the mistake/discovery was
2. The correct approach
3. When this pattern applies

Keep it concise and actionable. Do not include file paths or metadata headers.")
             messages [(vscode/LanguageModelChatMessage.User memory-prompt)]
             token-source (vscode/CancellationTokenSource.)]
         (p/let [response (.sendRequest model
                                        (clj->js messages)
                                        #js {}
                                        (.-token token-source))
                 result (consume-lm-response response)]
           {:success true
            :memory result
            :model-id (.-id model)}))
       {:error "No language model available"}))))

(defn create-and-show-memory!
  "Creates memory and shows it in a new editor for review/editing."
  [context]
  (p/let [result (create-memory-from-context! context)]
    (if (:success result)
      (p/do
        (p/let [doc (vscode/workspace.openTextDocument
                     #js {:content (:memory result)
                          :language "markdown"})]
          (vscode/window.showTextDocument doc))
        result)
      (do
        (vscode/window.showErrorMessage
         (str "Failed to create memory: " (:error result)))
        result))))

(defn create-and-copy-memory!
  "Creates memory and copies it to clipboard."
  [context]
  (p/let [result (create-memory-from-context! context)]
    (if (:success result)
      (p/do
        (vscode/env.clipboard.writeText (:memory result))
        (vscode/window.showInformationMessage "📋 Memory copied to clipboard!")
        result)
      (do
        (vscode/window.showErrorMessage
         (str "Failed to create memory: " (:error result)))
        result))))

(defn create-and-handle-memory!
  "Complete workflow: create memory and offer user actions.

  Presents user with options:
    - Copy to Clipboard
    - Show in Editor
    - Append to File"
  [context]
  (p/let [result (create-memory-from-context! context)]
    (if (:success result)
      (p/let [choice (vscode/window.showInformationMessage
                      "✨ Memory entry created! What next?"
                      "Copy to Clipboard"
                      "Show in Editor"
                      "Append to File")]
        (case choice
          "Copy to Clipboard"
          (p/do
            (vscode/env.clipboard.writeText (:memory result))
            (vscode/window.showInformationMessage "📋 Copied to clipboard!")
            result)

          "Show in Editor"
          (p/let [doc (vscode/workspace.openTextDocument
                       #js {:content (:memory result)
                            :language "markdown"})]
            (vscode/window.showTextDocument doc)
            result)

          "Append to File"
          (p/let [file-uri (vscode/window.showOpenDialog
                            #js {:canSelectMany false
                                 :filters #js {:Markdown #js ["md"]}
                                 :openLabel "Append Memory To"})]
            (if (seq file-uri)
              (let [uri (first file-uri)]
                (p/let [content (vscode/workspace.fs.readFile uri)
                        text (js/TextDecoder. "utf-8")
                        existing (.decode text content)
                        new-content (str existing "\n\n" (:memory result))]
                  (vscode/workspace.fs.writeFile
                   uri
                   (.encode (js/TextEncoder.) new-content))
                  (vscode/window.showInformationMessage "✅ Memory appended!")
                  result))
              result))

          nil))
      (do
        (vscode/window.showErrorMessage
         (str "Failed to create memory: " (:error result)))
        result))))

(defn append-to-instructions-file!
  "Appends memory to a specific instructions file (fire-and-forget).

  Args:
    memory - The memory text to append
    file-path - Absolute path or workspace-relative path

  Example:
    (p/let [result (create-memory-from-context! context)]
      (when (:success result)
        (append-to-instructions-file!
          (:memory result)
          \".github/instructions/memory.instructions.md\")))"
  [memory file-path]
  (p/let [workspace-folders vscode/workspace.workspaceFolders]
    (when (seq workspace-folders)
      (let [path (js/require "path")
            workspace-path (-> workspace-folders first .-uri .-fsPath)
            full-path (if (.isAbsolute path file-path)
                        file-path
                        (.join path workspace-path file-path))
            uri (vscode/Uri.file full-path)]
        (p/let [content (vscode/workspace.fs.readFile uri)
                text (js/TextDecoder. "utf-8")
                existing (.decode text content)
                new-content (str existing "\n\n" memory)]
          (vscode/workspace.fs.writeFile
           uri
           (.encode (js/TextEncoder.) new-content))
          (vscode/window.showInformationMessage
           (str "✅ Memory appended to " file-path)))))))

(comment
  (create-and-copy-memory!
   "Mistake: Did X. Correction: Do Y instead.")

  (create-and-handle-memory!
   "Discovery: Found new pattern Z that improves performance.")

  (p/let [result (create-memory-from-context! "Context...")]
    (when (:success result)
      (append-to-instructions-file!
       (:memory result)
       ".github/instructions/patterns.instructions.md")))

  :rcf)
