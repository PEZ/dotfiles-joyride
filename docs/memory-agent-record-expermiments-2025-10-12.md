Recording  function order definition:

```clojure
(require '[ai-workflow.memory-agent :as memory-agent]
         '["vscode" :as vscode]
         :reload)
(memory-agent/record-memory!+
 {:summary "When working with Clojure files, I repeatedly defined helper functions after they were used in calling functions. This caused Unresolved symbol linter errors because Clojure evaluates files top-down. The fix: always define helper functions before the functions that call them. Check the linter immediately after edits to catch these issues. The correct pattern: define all helper functions first, then define functions that use them. This ensures clean compilation and follows Clojure evaluation order."
  :domain "clojure"
  :scope :global})
```

# 1 Header trim fail

## ## Function Definition Order

### Define Helpers Before Callers
Always define helper functions before the functions that call them to follow Clojure's top-down evaluation order and avoid unresolved symbol linter errors. Check the linter immediately after edits to ensure clean compilation.

```clojure
;; Correct pattern
(defn helper [x]
  (* x 2))

(defn caller [y]
  (helper y))
```

# 2 Header trim fail (before fix)

## ## Function Definition Order

### Define Helper Functions First

Due to Clojure's top-down evaluation, define all helper functions before the functions that call them to avoid "Unresolved symbol" linter errors.

Correct pattern:

```clojure
(defn helper [x]
  (* x 2))

(defn main [y]
  (helper y))
```

Incorrect:

```clojure
(defn main [y]
  (helper y))  ; Unresolved symbol helper

(defn helper [x]
  (* x 2))
```

Check the linter immediately after edits to catch these issues.

# 3 Header trim not necessary (but using the heading correctly)

## Function Definition Order

Define helper functions before the functions that call them to avoid unresolved symbol linter errors. Clojure evaluates files top-down, so ensure dependencies are defined first for clean compilation. Always check the linter immediately after edits to catch these issues.

Correct pattern: Define all helper functions first, then define functions that use them.