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

# 4 Post clean-heading fix

## Function Definition Order

Define helper functions before the functions that call them to ensure clean compilation and avoid unresolved symbol linter errors. Clojure evaluates files top-down, so order matters.

Pattern: Define all helper functions first, then define functions that use them. Check the linter immediately after edits to catch issues.

# 5 Post clean-heading fix

## Top-Down Evaluation Order

Clojure evaluates files from top to bottom. Define helper functions and dependencies before the functions that use them to prevent unresolved symbol linter errors.

Pattern: Organize code with all helper functions first, followed by functions that call them. Check the linter immediately after edits for clean compilation.

# 6 Post clean-heading fix

## Function Definition Order

Define helper functions before the functions that use them to prevent unresolved symbol linter errors. Clojure evaluates files top-down, so forward references cause compilation issues.

**Correct Pattern**:
- Place all helper functions at the top of the file or namespace.
- Follow with functions that call them.
- Check the linter immediately after edits to verify resolution.

This ensures reliable compilation and follows Clojure's evaluation semantics.

---

## Root Cause Focused Experiments

Using improved lesson framing that emphasizes **Clojure's sequential evaluation** (root cause) rather than "linter errors" (symptom):

```clojure
{:summary "Clojure evaluates source files sequentially from top to bottom. When I defined helper functions after the functions that called them, the compiler encountered undefined symbols during evaluation. The solution is to organize code so dependencies are defined before use - helper functions at the top, calling functions below. This follows Clojure's evaluation semantics and prevents compilation errors."
 :domain "clojure"
 :scope :global}
```

# 7 Root cause framing - First run

## Sequential Evaluation and Code Organization

Clojure evaluates source files from top to bottom. To prevent undefined symbol errors, define dependencies (e.g., helper functions) before they are used. Organize code with helpers at the top and calling functions below, ensuring topological order in definitions.

**Quality**: ✅ Excellent - Most succinct, focuses on root cause, no code examples

# 8 Root cause framing - Agent detected existing

Agent recognized the section from Run 9 already existed and declined to create duplicate.

Result: `{:success false, :error "Failed to parse agent response"}`

**Quality**: ✅ Smart redundancy detection

# 9 Root cause framing - With negative example

## Sequential Evaluation and Code Organization

Clojure evaluates source files from top to bottom, requiring dependencies to be defined before use. Organize code with helper functions, vars, and macros at the top, followed by functions that depend on them.

```clojure
;; Good: Helper defined first
(defn helper [x]
  (* x 2))

(defn main-function [y]
  (helper y))

;; Avoid: Helper after caller (causes undefined symbol error)
(defn main-function [y]
  (helper y))  ; Error: helper not defined yet

(defn helper [x]
  (* x 2))
```

Benefits:
- Prevents compilation errors from undefined symbols.
- Aligns with Clojure's evaluation semantics.
- Improves code readability and maintainability.

**Quality**: ❌ VIOLATION - Contains negative example (";; Avoid:" section) - same issue as Run 2

# 10, 11, 12 Root cause framing - Detection pattern

All three runs detected the existing section from Run 9 and declined to create duplicates.

Results: `{:success false, :error "Failed to parse agent response"}`

**Quality**: ✅ Consistent redundancy detection

## Key Findings

### Root Cause Framing Impact

**Better framing** (Clojure's sequential evaluation) vs **symptom framing** (linter errors):
- ✅ Run 7: **Stellar** - most succinct, pure root cause focus
- ❌ Run 9: Still included negative example despite better framing
- ✅ Runs 8, 10-12: Smart detection prevented redundant adds

### Quality Comparison

**Best outputs** (succinct, positive only, no/minimal code):
1. **Run 7**: Perfect - root cause only, no code
2. Run 5: Very succinct, positive pattern
3. Runs 3, 4, 6: Good quality, varied structure

**Problematic outputs** (negative examples):
1. Run 2: Negative example with symptom framing
2. Run 9: Negative example even with root cause framing ❌

**Insight**: Better input framing helps but doesn't guarantee quality. The `/record-memory` prompt still needs explicit "no negative examples" guidance.