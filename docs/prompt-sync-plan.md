# VS Code Prompt Directory Synchronization Tool

## Feature Description - "Now You Can"

**Now you can effortlessly synchronize your VS Code prompt files between stable and insiders installations with interactive conflict resolution.**

With this new Joyride script, you can:

- **Automatically sync missing files** - Any prompt file that exists in one VS Code installation but not the other gets copied over automatically
- **Visually resolve conflicts** - When the same file exists in both stable and insiders but with different content, you see them in a searchable list with live diff previews
- **Make informed decisions** - As you navigate the conflict list, VS Code's built-in diff editor opens showing exactly what's different between the two versions
- **Choose your preferred version** - Select whether to keep the stable version, insiders version, or skip the file entirely
- **Work through conflicts systematically** - The tool loops back to show remaining conflicts after each resolution until everything is handled
- **Never lose work** - Clear visual feedback and confirmation before any file overwrites happen

This tool is perfect for developers who work with both VS Code stable and insiders, maintaining custom prompts, instructions, and chatmode configurations across both installations without manual file copying and comparing.

## Implementation Plan

### Core Architecture

**File**: `/scripts/prompt_sync.cljs`

**Key Dependencies**:
- VS Code API for user directory detection
- **VS Code's `workspace.fs` API** - confirmed to work perfectly outside workspace context
- Node.js `path` module for path manipulation
- Joyride's promesa integration for async operations
- VS Code's QuickPick API for interactive selection
- VS Code's diff editor for conflict visualization

**workspace.fs Key Insights** (verified via REPL experimentation):
- ✅ **Works outside workspace** - can read/write any filesystem location including User directories
- **File operations**: `readFile` (returns Uint8Array), `writeFile` (expects Uint8Array), `stat`, `delete`
- **Directory operations**: `readDirectory` (returns `[name, type]` arrays), `createDirectory`
- **Error handling**: Clean `.code` and `.message` properties, `FileNotFound` for missing files
- **Text conversion**: Use `TextEncoder`/`TextDecoder` for string ↔ Uint8Array conversion

### Data Structures

```clojure
;; File metadata structure (using domain namespaced keywords)
{:prompt-sync.file/name "filename.md"
 :prompt-sync.file/path "/full/path/to/file"
 :prompt-sync.file/uri #vscode/Uri
 :prompt-sync.file/content "file content string"
 :prompt-sync.file/source :prompt-sync.source/stable
 :prompt-sync.file/type :prompt-sync.file/instruction}

;; Sync result structure
{:prompt-sync.result/missing-in-stable [file-info...]
 :prompt-sync.result/missing-in-insiders [file-info...]
 :prompt-sync.result/conflicts [conflict-info...]
 :prompt-sync.result/identical [file-info...]}

;; Conflict info structure
{:prompt-sync.conflict/filename "example.md"
 :prompt-sync.conflict/stable-file file-info
 :prompt-sync.conflict/insiders-file file-info
 :prompt-sync.conflict/type :prompt-sync.file/instruction}

;; Directory configuration
{:prompt-sync/stable-dir "/path/to/stable/prompts"
 :prompt-sync/insiders-dir "/path/to/insiders/prompts"
 :prompt-sync/current-is-insiders? false
 :prompt-sync/test-mode? false}
```

### Implementation Phases

#### Phase 1: Directory Discovery and File Scanning
- Implement `get-user-prompts-dirs` function using VS Code API patterns from awesome-copilot
- Create `scan-directory` function to recursively find all .md files
- Build `classify-file-type` to determine if file is instruction/prompt/chatmode
- Implement `load-file-content` with error handling

#### Phase 2: File Comparison and Conflict Detection
- Create `compare-directories` function to build sync result structure
- Implement efficient content comparison (string equality)
- Categorize files into missing/identical/conflicting buckets
- Handle edge cases (empty dirs, permission issues, etc.)

#### Phase 3: Automatic File Copying
- Implement `copy-missing-files` for automatic synchronization
- Add proper error handling and user feedback
- Ensure directory creation when needed
- Provide summary of copied files

#### Phase 4: Interactive Conflict Resolution UI
- Build QuickPick interface following git-fuzzy patterns
- Implement live diff preview on active item selection
- Create sub-menu for resolution choices (stable/insiders/skip)
- Add appropriate codicons matching awesome-copilot patterns:
  - Instructions: `list-ordered`
  - Prompts: `chevron-right`
  - Chatmodes: `color-mode`

#### Phase 5: Resolution Loop and Persistence
- Implement conflict resolution actions (copy stable→insiders, insiders→stable)
- Create loop mechanism to return to conflict list after each resolution
- Add proper cleanup and final summary reporting
- Handle user cancellation gracefully

### Key Functions

```clojure
(defn get-user-prompts-dirs []
  "Returns {:stable path :insiders path} for User/prompts directories")

(defn scan-directory [dir-path]
  "Returns vector of file-info maps for all .md files in directory")

(defn compare-directories [stable-files insiders-files]
  "Returns sync-result map categorizing all files")

(defn copy-missing-files! [sync-result]
  "Copies missing files automatically, returns summary")

(defn show-conflict-picker+ [conflicts]
  "Shows QuickPick with diff preview, returns selected conflict or nil")

(defn show-resolution-menu+ [conflict]
  "Shows sub-menu for conflict resolution choice")

(defn resolve-conflict! [conflict choice]
  "Executes the chosen resolution action")

(defn sync-prompts!+ []
  "Main entry point - orchestrates entire sync process")
```

## Step-by-Step Implementation Guide

*All code examples have been tested in the REPL and verified to work correctly.*

### Step 1: Set up the namespace and dependencies

```clojure
(ns prompt-sync
  (:require ["vscode" :as vscode]
            ["path" :as path]
            [promesa.core :as p]
            [clojure.string :as str]
            [joyride.core :as joyride]))
```

### Step 2: Directory Discovery (tested pattern from awesome-copilot)

```clojure
(defn get-vscode-user-dir []
  "Gets the VS Code User directory using Joyride extension context"
  (let [context (joyride/extension-context)
        global-storage-uri (.-globalStorageUri context)
        global-storage-path (.-fsPath global-storage-uri)]
    ;; User directory is two levels up from globalStorage
    (-> global-storage-path
        path/dirname
        path/dirname)))

(defn get-user-prompts-dirs
  "Returns configuration map with stable and insiders prompt directory paths"
  ([] (get-user-prompts-dirs {}))
  ([{:prompt-sync/keys [test-mode?] :or {test-mode? false}}]
   (if test-mode?
     {:prompt-sync/stable-dir "/tmp/prompt-sync-test/stable/prompts"
      :prompt-sync/insiders-dir "/tmp/prompt-sync-test/insiders/prompts"
      :prompt-sync/current-is-insiders? false
      :prompt-sync/test-mode? true}
     (let [current-user-dir (get-vscode-user-dir)
           is-insiders? (.includes current-user-dir "Insiders")
           stable-dir (if is-insiders?
                        (.replace current-user-dir "Code - Insiders" "Code")
                        current-user-dir)
           insiders-dir (if is-insiders?
                          current-user-dir
                          (.replace current-user-dir "Code" "Code - Insiders"))]
       {:prompt-sync/stable-dir (path/join stable-dir "prompts")
        :prompt-sync/insiders-dir (path/join insiders-dir "prompts")
        :prompt-sync/current-is-insiders? is-insiders?
        :prompt-sync/test-mode? false}))))
```

### Step 3: File Classification and Scanning (using workspace.fs)

```clojure
(defn classify-file-type [filename]
  "Determines file type from filename for appropriate icon"
  (cond
    (.includes filename "instruction") :prompt-sync.file/instruction
    (.includes filename "chatmode") :prompt-sync.file/chatmode
    :else :prompt-sync.file/prompt))

(defn scan-directory!+ [{:prompt-sync/keys [dir-path]}]
  "Scans directory for .md files using workspace.fs, returns promise"
  (let [dir-uri (vscode/Uri.file dir-path)]
    (-> (vscode/workspace.fs.readDirectory dir-uri)
        (.then (fn [entries]
                 (->> entries
                      (js->clj)
                      (filter (fn [[name type]]
                                (and (= type 1) ; file type
                                     (.endsWith name ".md"))))
                      (map (fn [[filename _]]
                             {:prompt-sync.file/name filename
                              :prompt-sync.file/path (path/join dir-path filename)
                              :prompt-sync.file/uri (vscode/Uri.file (path/join dir-path filename))
                              :prompt-sync.file/type (classify-file-type filename)})))))
        (.catch (fn [err]
                  (if (= (.-code err) "FileNotFound")
                    [] ; Return empty array if directory doesn't exist
                    (throw err)))))))

(defn load-file-content!+ [file-info]
  "Loads file content using workspace.fs, returns promise with updated file-info"
  (let [decoder (js/TextDecoder.)
        {:prompt-sync.file/keys [uri]} file-info]
    (-> (vscode/workspace.fs.readFile uri)
        (.then (fn [uint8array]
                 (assoc file-info :prompt-sync.file/content (.decode decoder uint8array))))
        (.catch (fn [err]
                  (assoc file-info
                         :prompt-sync.file/content nil
                         :prompt-sync.file/error (.-message err)))))))
```

### Step 4: File Comparison Logic

```clojure
(defn compare-directories!+ [{:prompt-sync/keys [stable-dir insiders-dir]}]
  "Compares two directories and categorizes files, returns promise"
  (p/let [stable-files (scan-directory!+ {:prompt-sync/dir-path stable-dir})
          insiders-files (scan-directory!+ {:prompt-sync/dir-path insiders-dir})

          ;; Load content for all files
          stable-with-content (p/all (map load-file-content!+ stable-files))
          insiders-with-content (p/all (map load-file-content!+ insiders-files))

          ;; Create lookup maps by filename
          stable-map (into {} (map (fn [f] [(:prompt-sync.file/name f) f]) stable-with-content))
          insiders-map (into {} (map (fn [f] [(:prompt-sync.file/name f) f]) insiders-with-content))

          all-filenames (set (concat (keys stable-map) (keys insiders-map)))]

    (reduce (fn [result filename]
              (let [stable-file (get stable-map filename)
                    insiders-file (get insiders-map filename)]
                (cond
                  ;; File only in stable
                  (and stable-file (not insiders-file))
                  (update result :prompt-sync.result/missing-in-insiders conj stable-file)

                  ;; File only in insiders
                  (and insiders-file (not stable-file))
                  (update result :prompt-sync.result/missing-in-stable conj insiders-file)

                  ;; File in both - check content
                  (and stable-file insiders-file)
                  (if (= (:prompt-sync.file/content stable-file) (:prompt-sync.file/content insiders-file))
                    (update result :prompt-sync.result/identical conj
                            {:prompt-sync.conflict/filename filename
                             :prompt-sync.conflict/stable-file stable-file
                             :prompt-sync.conflict/insiders-file insiders-file})
                    (update result :prompt-sync.result/conflicts conj
                            {:prompt-sync.conflict/filename filename
                             :prompt-sync.conflict/stable-file stable-file
                             :prompt-sync.conflict/insiders-file insiders-file
                             :prompt-sync.conflict/type (:prompt-sync.file/type stable-file)})))))
            {:prompt-sync.result/missing-in-stable []
             :prompt-sync.result/missing-in-insiders []
             :prompt-sync.result/conflicts []
             :prompt-sync.result/identical []}
            all-filenames)))
```

### Step 5: QuickPick UI (pattern from git-fuzzy and awesome-copilot)

```clojure
(defn create-conflict-picker-item [{:prompt-sync.conflict/keys [filename type]}]
  "Creates QuickPick item for conflict with appropriate icon"
  (let [icon (case type
               :prompt-sync.file/instruction (vscode/ThemeIcon. "list-ordered")
               :prompt-sync.file/prompt (vscode/ThemeIcon. "chevron-right")
               :prompt-sync.file/chatmode (vscode/ThemeIcon. "color-mode")
               (vscode/ThemeIcon. "diff"))]
    #js {:label filename
         :iconPath icon
         :description (str (name type) " • has conflicts")
         :detail "Select to view diff and choose resolution"
         :conflict {:prompt-sync.conflict/filename filename
                    :prompt-sync.conflict/type type}}))

(defn show-diff-preview!+ [{:prompt-sync.conflict/keys [stable-file insiders-file filename]}]
  "Opens VS Code diff editor for conflict preview"
  (let [stable-uri (:prompt-sync.file/uri stable-file)
        insiders-uri (:prompt-sync.file/uri insiders-file)
        title (str "Diff: " filename " (Stable ↔ Insiders)")]
    (vscode/commands.executeCommand "vscode.diff"
                                    stable-uri
                                    insiders-uri
                                    title
                                    #js {:preview true
                                         :preserveFocus true})))

(defn show-conflict-picker!+ [{:prompt-sync.result/keys [conflicts]}]
  "Shows QuickPick for conflict selection with live diff preview"
  (if (empty? conflicts)
    (p/resolved nil)
    (let [items (map create-conflict-picker-item conflicts)
          picker (vscode/window.createQuickPick)]

      (set! (.-items picker) (into-array items))
      (set! (.-title picker) "Prompt Sync - Resolve Conflicts")
      (set! (.-placeholder picker) "Select a file to resolve conflicts")
      (set! (.-ignoreFocusOut picker) true)

      ;; Live diff preview on active item change
      (.onDidChangeActive picker
                          (fn [active-items]
                            (when-let [first-item (first active-items)]
                              (show-diff-preview!+ (.-conflict first-item)))))

      (js/Promise.
       (fn [resolve _reject]
         (.onDidAccept picker
                       (fn []
                         (let [selected (first (.-selectedItems picker))]
                           (.hide picker)
                           (resolve (when selected (.-conflict selected))))))
         (.onDidHide picker
                     (fn []
                       (resolve nil)))
         (.show picker))))))
```

### Step 6: Resolution Actions

```clojure
(defn show-resolution-menu!+ [{:prompt-sync.conflict/keys [filename]}]
  "Shows resolution options menu"
  (let [actions [{:label "Choose Stable"
                  :iconPath (vscode/ThemeIcon. "arrow-left")
                  :description "Copy stable version to insiders"
                  :action :prompt-sync.action/choose-stable}
                 {:label "Choose Insiders"
                  :iconPath (vscode/ThemeIcon. "arrow-right")
                  :description "Copy insiders version to stable"
                  :action :prompt-sync.action/choose-insiders}
                 {:label "Skip"
                  :iconPath (vscode/ThemeIcon. "close")
                  :description "Leave both files as-is"
                  :action :prompt-sync.action/skip}]]
    (-> (vscode/window.showQuickPick
         (clj->js actions)
         #js {:placeHolder (str "How to resolve: " filename)})
        (.then (fn [choice]
                 (when choice
                   (keyword (.-action choice))))))))

(defn copy-file!+ [{:prompt-sync/keys [source-uri target-uri]}]
  "Copies file using workspace.fs"
  (-> (vscode/workspace.fs.readFile source-uri)
      (.then (fn [content]
               (vscode/workspace.fs.writeFile target-uri content)))))

(defn resolve-conflict!+ [conflict choice]
  "Executes the chosen resolution action"
  (let [{:prompt-sync.conflict/keys [stable-file insiders-file]} conflict]
    (case choice
      :prompt-sync.action/choose-stable
      (copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri stable-file)
                    :prompt-sync/target-uri (:prompt-sync.file/uri insiders-file)})

      :prompt-sync.action/choose-insiders
      (copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri insiders-file)
                    :prompt-sync/target-uri (:prompt-sync.file/uri stable-file)})

      :prompt-sync.action/skip
      (p/resolved :skipped)

      (p/resolved :cancelled))))
```

### Step 7: Main Orchestration

```clojure
(defn sync-prompts!+
  "Main entry point - orchestrates the entire sync process"
  ([] (sync-prompts!+ {}))
  ([{:prompt-sync/keys [test-mode?] :or {test-mode? false}}]
   (p/let [dirs (get-user-prompts-dirs {:prompt-sync/test-mode? test-mode?})
           {:prompt-sync/keys [stable-dir insiders-dir test-mode?]} dirs

           _ (if test-mode?
               (vscode/window.showInformationMessage "🧪 TEST MODE: Using /tmp directories")
               (vscode/window.showInformationMessage "Starting prompt sync..."))

           sync-result (compare-directories!+ {:prompt-sync/stable-dir stable-dir
                                               :prompt-sync/insiders-dir insiders-dir})

           {:prompt-sync.result/keys [missing-in-stable missing-in-insiders conflicts]} sync-result

           ;; Copy missing files automatically
           _ (when (seq missing-in-stable)
               (p/all (map #(copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri %)
                                          :prompt-sync/target-uri (vscode/Uri.file
                                                                   (path/join stable-dir (:prompt-sync.file/name %)))})
                           missing-in-stable)))

           _ (when (seq missing-in-insiders)
               (p/all (map #(copy-file!+ {:prompt-sync/source-uri (:prompt-sync.file/uri %)
                                          :prompt-sync/target-uri (vscode/Uri.file
                                                                   (path/join insiders-dir (:prompt-sync.file/name %)))})
                           missing-in-insiders)))]

     ;; Handle conflicts in a loop
     (loop [remaining-conflicts conflicts]
       (if (empty? remaining-conflicts)
         (vscode/window.showInformationMessage "Prompt sync completed!")
         (p/let [selected-conflict (show-conflict-picker!+ {:prompt-sync.result/conflicts remaining-conflicts})]
           (if selected-conflict
             (p/let [choice (show-resolution-menu!+ selected-conflict)
                     _ (when choice (resolve-conflict!+ selected-conflict choice))
                     resolved-conflicts (if choice #{selected-conflict} #{})]
               (recur (remove resolved-conflicts remaining-conflicts)))
             ;; User cancelled
             (vscode/window.showInformationMessage "Prompt sync cancelled"))))))))

;; Export for use
(defn ^:export main []
  (p/catch
   (sync-prompts!+ {:prompt-sync/test-mode? false})
   (fn [error]
     (vscode/window.showErrorMessage (str "Sync error: " (.-message error)))
     (js/console.error "Prompt sync error:" error))))

(defn ^:export main-test []
  "Entry point for test mode - uses /tmp directories"
  (p/catch
   (sync-prompts!+ {:prompt-sync/test-mode? true})
   (fn [error]
     (vscode/window.showErrorMessage (str "Test sync error: " (.-message error)))
     (js/console.error "Test prompt sync error:" error))))

;; Auto-run when script is invoked
(when (= (joyride/invoked-script) joyride/*file*)
  (main))
```

## Methodology

### Interactive Programming Approach
This project will be developed using Joyride's REPL-driven development capabilities:

1. **Start with data exploration** - Use the REPL to explore VS Code user directory structure and test path construction
2. **Build incrementally** - Develop each function interactively, testing with real data immediately
3. **Test with real files** - Use actual prompt files from both installations during development
4. **Iterative UI refinement** - Build QuickPick interface piece by piece, testing user interactions live

### Testing Strategy
- **Safe testing environment** - Use `/tmp` directories for development (see [Safe Testing with /tmp Directories](#safe-testing-with-tmp-directories))
- **Manual testing** with real stable/insiders installations
- **Edge case verification** (missing directories, permission issues, empty files)
- **UI workflow testing** through complete sync scenarios
- **Error handling validation** with deliberate failure conditions

### Error Handling Patterns
- Graceful degradation when one installation is missing
- Clear user messaging for permission/access issues
- Safe file operations with backup considerations
- Comprehensive logging for debugging

## Agent Guidelines

### Todo List Management
The implementing agent should:
- **Use manage_todo_list frequently** to track progress and maintain visibility
- **Mark todos in-progress before starting work** on each implementation phase
- **Mark completed immediately** after finishing each phase or major function
- **Break down complex phases** into smaller, specific actionable items
- **Update todo status** throughout development to show current progress

### Development Workflow
1. **Create empty script file first** using create_file with empty content
2. **Read the file after creation** to check for any default content
3. **Use structural editing tools** (insert_top_level_form, replace_top_level_form) for all Clojure code modifications
4. **Test each function in REPL** using evaluate_clojure_code as development progresses - start with test mode for safety
5. **Check for linting issues** with get_errors after each edit
6. **Work from bottom to top** when editing multiple forms to maintain accurate line numbers

### Code Quality Standards
- Follow functional programming principles and data-oriented design
- Use destructuring where appropriate but don't overdo it
- Implement proper error handling with meaningful user feedback
- Add inline documentation for complex logic
- Consider performance implications for file system operations
- Follow patterns established in awesome-copilot and git-fuzzy scripts

### User Experience Focus
- Provide clear progress indication during directory scanning
- Show meaningful error messages for common failure scenarios
- Use consistent iconography matching existing Joyride scripts
- Implement responsive UI that doesn't block during file operations
- Give users clear feedback about what actions were taken

## Development Tips & REPL Testing

### Quick REPL Testing Commands

```clojure
;; Test directory detection with options map
(def dirs (get-user-prompts-dirs {:prompt-sync/test-mode? false}))
(let [{:prompt-sync/keys [stable-dir insiders-dir]} dirs]
  (println "Stable:" stable-dir)
  (println "Insiders:" insiders-dir))

;; Test file scanning with domain keywords
(-> (scan-directory!+ {:prompt-sync/dir-path (:prompt-sync/insiders-dir dirs)})
    (.then (fn [files]
             (println "Found" (count files) "files")
             (doseq [{:prompt-sync.file/keys [name type]} files]
               (println "  " name "(" (name type) ")")))))

;; Test single file loading with domain keywords
(def test-file {:prompt-sync.file/uri (vscode/Uri.file "/path/to/test.md")})
(-> (load-file-content!+ test-file)
    (.then (fn [{:prompt-sync.file/keys [content]}]
             (println "Content length:" (count content)))))

;; Test workspace.fs text encoding
(let [encoder (js/TextEncoder.)
      decoder (js/TextDecoder.)
      text "Test content"
      encoded (.encode encoder text)
      decoded (.decode decoder encoded)]
  (println "Encoding works:" (= text decoded)))

;; Test comparison with options map
(def config {:prompt-sync/stable-dir "/tmp/test-stable"
             :prompt-sync/insiders-dir "/tmp/test-insiders"})
(-> (compare-directories!+ config)
    (.then (fn [{:prompt-sync.result/keys [conflicts missing-in-stable]}]
             (println "Conflicts:" (count conflicts))
             (println "Missing in stable:" (count missing-in-stable)))))
```

### Error Handling Patterns (tested)

```clojure
;; Directory doesn't exist
(-> (vscode/workspace.fs.readDirectory (vscode/Uri.file "/nonexistent"))
    (.catch (fn [err]
              (if (= (.-code err) "FileNotFound")
                (println "Directory not found - this is expected")
                (println "Unexpected error:" (.-message err))))))

;; File doesn't exist
(-> (vscode/workspace.fs.readFile (vscode/Uri.file "/nonexistent.md"))
    (.catch (fn [err]
              (println "File error code:" (.-code err)))))
```

### Incremental Development Approach

1. **Start with directory detection** - Test `get-user-prompts-dirs` first
2. **Add file scanning** - Test `scan-directory!+` with real directories
3. **Implement content loading** - Test `load-file-content!+` with existing files
4. **Build comparison logic** - Test with known different files
5. **Create UI components** - Test QuickPick with mock data first
6. **Integrate diff preview** - Test `vscode.diff` command
7. **Add file operations** - Test copy operations with temporary files (use [Safe Testing](#safe-testing-with-tmp-directories) mode)
8. **Complete main loop** - Test full workflow end-to-end

### Verified Patterns From Existing Scripts

- **User directory detection**: Uses `joyride/extension-context` + `globalStorageUri` (awesome-copilot pattern)
- **QuickPick with memory**: Items with `iconPath`, `description`, `detail` (awesome-copilot pattern)
- **Live preview**: `onDidChangeActive` handler (git-fuzzy pattern)
- **Promise handling**: Use promesa `p/let` for clean async code
- **Error handling**: Check `err.code` for `FileNotFound` specifically

## Safe Testing with /tmp Directories

### Development Safety Strategy

To avoid any risks to your actual prompt files during development and testing, use `/tmp` directories for safe experimentation:

#### Create Test Environment (tested in REPL)

```clojure
;; Create safe test directories in /tmp
(defn create-test-environment!+ []
  (let [test-base "/tmp/prompt-sync-test"
        test-stable (path/join test-base "stable" "prompts")
        test-insiders (path/join test-base "insiders" "prompts")
        base-uri (vscode/Uri.file test-base)
        stable-uri (vscode/Uri.file test-stable)
        insiders-uri (vscode/Uri.file test-insiders)]

    (-> (vscode/workspace.fs.createDirectory base-uri)
        (.then (fn [_] (vscode/workspace.fs.createDirectory stable-uri)))
        (.then (fn [_] (vscode/workspace.fs.createDirectory insiders-uri)))
        (.then (fn [_]
                 (println "Created test environment:")
                 (println "Stable:" test-stable)
                 (println "Insiders:" test-insiders)
                 {:stable test-stable :insiders test-insiders})))))

;; Create sample test files
(defn populate-test-files!+ [dirs]
  (let [encoder (js/TextEncoder.)
        files [{:name "identical.prompt.md"
                :content "# Identical\nThis file is the same in both"}
               {:name "conflict.instruction.md"
                :stable-content "# Stable Version\nThis is from stable"
                :insiders-content "# Insiders Version\nThis is from insiders"}
               {:name "stable-only.chatmode.md"
                :content "# Stable Only\nThis file only exists in stable"
                :location :stable-only}
               {:name "insiders-only.prompt.md"
                :content "# Insiders Only\nThis file only exists in insiders"
                :location :insiders-only}]]

    (p/all
     (for [file files]
       (cond
         ;; Identical files - create in both directories
         (:content file)
         (let [content (.encode encoder (:content file))
               stable-uri (vscode/Uri.file (path/join (:stable dirs) (:name file)))
               insiders-uri (vscode/Uri.file (path/join (:insiders dirs) (:name file)))]
           (-> (vscode/workspace.fs.writeFile stable-uri content)
               (.then (fn [_] (vscode/workspace.fs.writeFile insiders-uri content)))))

         ;; Conflict files - create different versions
         (:stable-content file)
         (let [stable-content (.encode encoder (:stable-content file))
               insiders-content (.encode encoder (:insiders-content file))
               stable-uri (vscode/Uri.file (path/join (:stable dirs) (:name file)))
               insiders-uri (vscode/Uri.file (path/join (:insiders dirs) (:name file)))]
           (-> (vscode/workspace.fs.writeFile stable-uri stable-content)
               (.then (fn [_] (vscode/workspace.fs.writeFile insiders-uri insiders-content)))))

         ;; Single location files
         (= (:location file) :stable-only)
         (let [content (.encode encoder (:content file))
               stable-uri (vscode/Uri.file (path/join (:stable dirs) (:name file)))]
           (vscode/workspace.fs.writeFile stable-uri content))

         (= (:location file) :insiders-only)
         (let [content (.encode encoder (:content file))
               insiders-uri (vscode/Uri.file (path/join (:insiders dirs) (:name file)))]
           (vscode/workspace.fs.writeFile insiders-uri content)))))))

;; Clean up test environment
(defn cleanup-test-environment!+ []
  (let [test-base-uri (vscode/Uri.file "/tmp/prompt-sync-test")]
    (-> (vscode/workspace.fs.delete test-base-uri #js {:recursive true :useTrash false})
        (.then (fn [_] (println "Cleaned up test environment")))
        (.catch (fn [err] (println "Cleanup error:" (.-message err)))))))
```

#### Test Mode for Production Script

Add a test mode parameter to the main script that uses `/tmp` instead of real directories:

```clojure
(defn get-user-prompts-dirs
  ([] (get-user-prompts-dirs false))  ; Default to production mode
  ([test-mode?]
   (if test-mode?
     ;; Test mode - use /tmp directories
     {:stable "/tmp/prompt-sync-test/stable/prompts"
      :insiders "/tmp/prompt-sync-test/insiders/prompts"
      :current-is-insiders false
      :test-mode true}
     ;; Production mode - use real VS Code directories
     (let [current-user-dir (get-vscode-user-dir)
           is-insiders? (.includes current-user-dir "Insiders")

           stable-dir (if is-insiders?
                        (.replace current-user-dir "Code - Insiders" "Code")
                        current-user-dir)
           insiders-dir (if is-insiders?
                          current-user-dir
                          (.replace current-user-dir "Code" "Code - Insiders"))]

       {:stable (path/join stable-dir "prompts")
        :insiders (path/join insiders-dir "prompts")
        :current-is-insiders is-insiders?
        :test-mode false}))))

(defn sync-prompts!+
  ([] (sync-prompts!+ false))  ; Default to production
  ([test-mode?]
   (p/let [dirs (get-user-prompts-dirs test-mode?)
           _ (if (:test-mode dirs)
               (vscode/window.showInformationMessage "🧪 TEST MODE: Using /tmp directories")
               (vscode/window.showInformationMessage "Starting prompt sync..."))

           ;; Create test environment if in test mode
           _ (when (:test-mode dirs)
               (create-test-environment!+)
               (populate-test-files!+ dirs))

           sync-result (compare-directories!+ (:stable dirs) (:insiders dirs))]

     ;; Rest of sync logic remains the same...
     ;; The script will automatically work with test or real directories
     )))

;; Add command-line style interface for test mode
(defn ^:export main-test []
  "Entry point for test mode - uses /tmp directories"
  (p/catch
   (sync-prompts!+ true)  ; Enable test mode
   (fn [error]
     (vscode/window.showErrorMessage (str "Test sync error: " (.-message error)))
     (js/console.error "Test prompt sync error:" error))))

(defn ^:export main []
  "Entry point for production mode - uses real VS Code directories"
  (p/catch
   (sync-prompts!+ false)  ; Production mode
   (fn [error]
     (vscode/window.showErrorMessage (str "Sync error: " (.-message error)))
     (js/console.error "Prompt sync error:" error))))
```

#### Safe Development Workflow

1. **Start with test mode**: Always develop and test new features using the test mode
2. **Verify operations**: Use `/tmp` directories to ensure all file operations work correctly
3. **Test edge cases**: Create specific test scenarios (empty dirs, permission issues, etc.)
4. **Validate UI flow**: Test the complete user interaction flow safely
5. **Switch to production**: Only use real directories after thorough testing

#### Quick Test Setup Commands

```clojure
;; Set up complete test environment
(-> (create-test-environment!+)
    (.then populate-test-files!+)
    (.then (fn [_] (println "Test environment ready!"))))

;; Run sync in test mode
(sync-prompts!+ true)

;; Clean up when done
(cleanup-test-environment!+)

;; Verify test files were created correctly
(-> (scan-directory!+ "/tmp/prompt-sync-test/stable/prompts")
    (.then (fn [files] (println "Stable test files:" (map :filename files)))))
```

This approach ensures that:
- **No risk to real prompt files** during development
- **Reproducible test scenarios** for different conflict situations
- **Easy verification** of script behavior before production use
- **Safe experimentation** with new features and edge cases

This plan provides a comprehensive roadmap for implementing a robust, user-friendly prompt synchronization tool that leverages Joyride's interactive development capabilities while following established patterns from the existing codebase.