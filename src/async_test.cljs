(ns async-test
  (:require ["vscode" :as vscode]))

;; ========================================
;; ^:async await Testing Suite for SCI
;; Testing Borkdude's new async/await feature
;; ========================================

(comment
  ;; ===================
  ;; ✅ WORKING FEATURES
  ;; ===================

  ;; Basic async function with await
  (defn ^:async simple-async []
    (await (js/Promise.resolve 42)))

  (simple-async)
  ;; => 42

  ;; Multiple awaits in sequence
  (defn ^:async multiple-awaits []
    (let [a (await (js/Promise.resolve 10))
          b (await (js/Promise.resolve 20))
          c (await (js/Promise.resolve 30))]
      (+ a b c)))

  (multiple-awaits)
  ;; => 60

  ;; Nested async calls
  (defn ^:async inner-async []
    (await (js/Promise.resolve "inner")))

  (defn ^:async outer-async []
    (let [result (await (inner-async))]
      (str result " -> outer")))

  (outer-async)
  ;; => "inner -> outer"

  ;; Error handling with try-catch
  (defn ^:async error-handling []
    (try
      (await (js/Promise.reject "Error!"))
      (catch :default e
        (str "Caught: " e))))

  (error-handling)
  ;; => "Caught: Error!"

  ;; await on non-promise values (works fine)
  (defn ^:async non-promise []
    (await 42))

  (non-promise)
  ;; => 42

  ;; Threading macros with await
  (defn ^:async threading []
    (-> (js/Promise.resolve 10)
        await
        (* 2)
        (+ 5)))

  (threading)
  ;; => 25

  ;; loop/recur with await
  (defn ^:async loop-test []
    (loop [n 0
           acc []]
      (if (< n 3)
        (let [value (await (js/Promise.resolve n))]
          (recur (inc n) (conj acc value)))
        acc)))

  (loop-test)
  ;; => [0 1 2]

  ;; Conditional branches
  (defn ^:async conditional []
    (if true
      (await (js/Promise.resolve "then"))
      (await (js/Promise.resolve "else"))))

  (conditional)
  ;; => "then"

  ;; Destructuring with await
  (defn ^:async destructuring []
    (let [{:keys [a b]} (await (js/Promise.resolve {:a 1 :b 2}))]
      (+ a b)))

  (destructuring)
  ;; => 3

  ;; Real VS Code API
  (defn ^:async vscode-api []
    (let [files (await (vscode/workspace.findFiles "**/*.cljs" nil 5))]
      (count files)))

  (vscode-api)
  ;; => number of .cljs files (max 5)

  ;; doseq with await
  (defn ^:async doseq-test []
    (let [results (atom [])]
      (doseq [n (range 3)]
        (let [value (await (js/Promise.resolve n))]
          (swap! results conj value)))
      @results))

  (doseq-test)
  ;; => [0 1 2]

  ;; Anonymous async functions
  ((^:async fn []
     (let [value (await (js/Promise.resolve "anon-fn"))]
       (str "Result: " value))))
  ;; => "Result: anon-fn"

  ;; Multiple awaits in single expression
  (defn ^:async multiple-in-expr []
    (+ (await (js/Promise.resolve 10))
       (await (js/Promise.resolve 20))
       (await (js/Promise.resolve 30))))

  (multiple-in-expr)
  ;; => 60

  ;; Promise.all pattern
  (defn ^:async promise-all []
    (let [promises [(js/Promise.resolve 1)
                    (js/Promise.resolve 2)
                    (js/Promise.resolve 3)]
          results (await (js/Promise.all (to-array promises)))]
      (vec results)))

  (promise-all)
  ;; => [1 2 3]

  ;; ^:async forms don't work
  ;; This is expected - ^:async only works on functions, not bare forms

  ^:async (let [x (await (js/Promise.resolve 42))]
            x)
  ;; => ERROR: Unable to resolve symbol: await

  ;; WORKAROUND: Use immediately-invoked async fn
  ((^:async fn []
     (let [x (await (js/Promise.resolve 42))]
       x)))
  ;; => 42

  ;; =================
  ;; 🐛 BUGS FOUND
  ;; =================

  ;; BUG #1: swap! with async function stores Promise instead of value

  (def test-atom (atom 0))

  (defn ^:async broken-swap []
    (swap! test-atom (^:async fn [current]
                       (let [delta (await (js/Promise.resolve 10))]
                         (+ current delta)))))

  (broken-swap)
  @test-atom
  ;; => #<Promise> instead of 10
  ;; The atom holds a Promise object, not the resolved value!

  ;; BUG #2: reduce with async function produces incorrect results

  (defn ^:async broken-reduce []
    (reduce (^:async fn [acc n]
              (let [value (await (js/Promise.resolve n))]
                (+ acc value)))
            0
            [1 2 3 4 5]))

  (broken-reduce)
  ;; => "[object Promise]5" instead of 15
  ;; The accumulator becomes a Promise string!

  ;; BUG #3: reify with ^:async methods causes stack overflow
  ;; It's the ^:async annotation itself, not await

  (defn ^:async broken-reify []
    (let [obj (reify js/Object
                (^:async toString [this]
                  "just-a-string"))]
      (.toString obj)))

  (broken-reify)
  ;; => ERROR: Maximum call stack size exceeded

  ;; BUG #4: await in preconditions doesn't actually wait

  (defn ^:async broken-precondition [x]
    {:pre [(> (await (js/Promise.resolve x)) 10)]}
    (* x 2))

  (broken-precondition 5)
  ;; => 10 (should have failed the precondition!)
  ;; The precondition doesn't actually await the promise

  ;; ====================
  ;; ⚠️ KNOWN LIMITATIONS
  ;; ====================

  ;; LIMITATION #1: await doesn't work in nested non-async functions

  (defn ^:async limitation-map []
    (let [promises [(js/Promise.resolve 1)
                    (js/Promise.resolve 2)
                    (js/Promise.resolve 3)]
          ;; This doesn't work - await not available in the fn
          results (map (fn [p] (await p)) promises)]
      results))

  (limitation-map)
  ;; => ERROR: Unable to resolve symbol: await

  ;; WORKAROUND: Mark inner fn as ^:async too
  (defn ^:async workaround-map []
    (let [promises [(js/Promise.resolve 1)
                    (js/Promise.resolve 2)
                    (js/Promise.resolve 3)]
          ;; Returns lazy seq of Promises
          results (map (^:async fn [p] (await p)) promises)]
      results))

  (workaround-map)
  ;; => (#<Promise> #<Promise> #<Promise>)
  ;; Returns promises, not values! Need Promise.all pattern instead.

  ;; LIMITATION #2: await doesn't work in for comprehensions

  (defn ^:async limitation-for []
    (let [results (for [n (range 3)]
                    (await (js/Promise.resolve n)))]
      results))

  (limitation-for)
  ;; => ERROR: Unable to resolve symbol: await

  ;; WORKAROUND: Use doseq or loop/recur instead
  (defn ^:async workaround-for []
    (loop [n 0
           acc []]
      (if (< n 3)
        (let [value (await (js/Promise.resolve n))]
          (recur (inc n) (conj acc value)))
        acc)))

  (workaround-for)
  ;; => [0 1 2]

  ;; LIMITATION #3: defmethod doesn't support ^:async

  (defmulti process-async :type)

  (defmethod ^:async process-async :fetch [_]
    (await (js/Promise.resolve "fetched")))

  (process-async {:type :fetch})
  ;; => ERROR: Unable to resolve symbol: await

  ;; WORKAROUND: Return a promise explicitly or use a helper fn
  (defn ^:async fetch-helper []
    (await (js/Promise.resolve "fetched")))

  (defmethod process-async :fetch [_]
    (fetch-helper))

  (await (process-async {:type :fetch}))
  ;; => "fetched" (but you need to await the result)

  ;; LIMITATION #4: Protocol implementations don't support ^:async

  (defprotocol AsyncProtocol
    (fetch-data [this]))

  (defrecord AsyncImpl []
    AsyncProtocol
    (^:async fetch-data [this]
      (await (js/Promise.resolve "data"))))

  (defn ^:async test-protocol []
    (let [impl (->AsyncImpl)]
      (await (fetch-data impl))))

  (test-protocol)
  ;; => ERROR: Unable to resolve symbol: await

  ;; WORKAROUND: Implement method to return a promise manually
  (defrecord AsyncImpl2 []
    AsyncProtocol
    (fetch-data [this]
      (js/Promise.resolve "data")))

  (defn ^:async test-protocol-2 []
    (let [impl (->AsyncImpl2)]
      (await (fetch-data impl))))

  (test-protocol-2)
  ;; => "data"

  :rcf)


(comment
  ;; ================================================
  ;; 🔬 DEEP DIVE: Bugs within specified ^:async behavior
  ;; Only defn ^:async and ^:async fn — per Borkdude
  ;; ================================================

  ;; -------------------------------------------------
  ;; BUG: ex-info data lost after await
  ;; When throw happens after an await, ex-data returns
  ;; SCI internal metadata instead of the user's data map
  ;; -------------------------------------------------

  ;; Sync version works fine:
  (try
    (throw (ex-info "boom" {:val 42}))
    (catch :default e
      {:message (ex-message e)
       :data (ex-data e)}))
  ;; => {:message "boom", :data {:val 42}}

  ;; Async version loses ex-data:
  (defn ^:async ex-data-bug []
    (try
      (let [x (await (js/Promise.resolve 1))]
        (throw (ex-info "boom" {:val x})))
      (catch :default e
        {:message (ex-message e)
         :data (ex-data e)})))

  (ex-data-bug)
  ;; => {:message "boom", :data {:type :sci/error, ...}}
  ;; User's {:val 1} is replaced by SCI internal metadata!

  ;; Without the await before throw, it works:
  (defn ^:async ex-data-no-prior-await []
    (try
      (throw (ex-info "boom" {:val 42}))
      (catch :default e
        {:message (ex-message e)
         :data (ex-data e)})))

  (ex-data-no-prior-await)
  ;; => {:message "boom", :data {:val 42}} — correct!

  ;; -------------------------------------------------
  ;; BUG: binding lost inside async functions
  ;; When await appears anywhere inside a binding form,
  ;; the binding is not visible — even before the await
  ;; -------------------------------------------------

  (def ^:dynamic *my-val* 10)

  ;; Sync binding works:
  (binding [*my-val* 42]
    *my-val*)
  ;; => 42

  ;; Async binding broken:
  (defn ^:async binding-bug []
    (binding [*my-val* 42]
      (let [before *my-val*
            fetched (await (js/Promise.resolve "x"))
            after *my-val*]
        {:before before :after after})))

  (binding-bug)
  ;; => {:before 10, :after 10}
  ;; Both read 10 instead of 42!
  ;; The binding is completely invisible inside async.

  ;; Await outside binding form works:
  (defn ^:async binding-outside-await []
    (let [x (await (js/Promise.resolve "x"))]
      (binding [*my-val* 42]
        *my-val*)))

  (binding-outside-await)
  ;; => 42 — correct! But only because await is outside binding.

  ;; -------------------------------------------------
  ;; BUG: with-redefs broken inside async functions
  ;; await doesn't work inside with-redefs body at all
  ;; -------------------------------------------------

  (defn my-fn [] "original")

  (defn ^:async with-redefs-bug []
    (with-redefs [my-fn (fn [] "replaced")]
      (let [result (await (js/Promise.resolve (my-fn)))]
        result)))

  (with-redefs-bug)
  ;; => ERROR: Unable to resolve symbol: await

  ;; Even with empty bindings:
  (defn ^:async with-redefs-empty []
    (with-redefs []
      (await (js/Promise.resolve 42))))

  (with-redefs-empty)
  ;; => ERROR: Unable to resolve symbol: await

  ;; -------------------------------------------------
  ;; BUG: letfn doesn't support ^:async
  ;; -------------------------------------------------

  (defn ^:async letfn-bug []
    (letfn [(^:async helper []
              (await (js/Promise.resolve 42)))]
      (await (helper))))

  (letfn-bug)
  ;; => ERROR: Unable to resolve symbol: await

  ;; Workaround: use let with ^:async fn
  (defn ^:async letfn-workaround []
    (let [helper (^:async fn []
                   (await (js/Promise.resolve 42)))]
      (await (helper))))

  (letfn-workaround)
  ;; => 42

  ;; -------------------------------------------------
  ;; BUG (maybe): loop/recur off-by-one with many iterations
  ;; 500 iterations yields 499 results
  ;; -------------------------------------------------

  (defn ^:async loop-count-bug []
    (loop [n 0 acc []]
      (if (< n 500)
        (let [value (await (js/Promise.resolve n))]
          (recur (inc n) (conj acc value)))
        (count acc))))

  (loop-count-bug)
  ;; => 499 instead of 500?
  ;; Needs verification — may have been a REPL artifact

  ;; -------------------------------------------------
  ;; ✅ WORKING: multi-arity async defn
  ;; -------------------------------------------------

  (defn ^:async multi-arity
    ([x] (await (js/Promise.resolve x)))
    ([x y] (+ (await (js/Promise.resolve x))
              (await (js/Promise.resolve y)))))

  (multi-arity 42)
  ;; => 42
  (multi-arity 10 20)
  ;; => 30

  ;; -------------------------------------------------
  ;; ✅ WORKING: variadic async defn
  ;; -------------------------------------------------

  (defn ^:async variadic [& args]
    (let [first-val (await (js/Promise.resolve (first args)))]
      {:first first-val :count (count args)}))

  (variadic 1 2 3)
  ;; => {:first 1, :count 3}

  ;; -------------------------------------------------
  ;; ✅ WORKING: recursive async
  ;; -------------------------------------------------

  (defn ^:async factorial [n]
    (if (<= n 1)
      1
      (let [sub (await (factorial (dec n)))]
        (* n sub))))

  (factorial 10)
  ;; => 3628800

  ;; -------------------------------------------------
  ;; ✅ WORKING: closures over awaited values
  ;; -------------------------------------------------

  (defn ^:async closure-test []
    (let [x (await (js/Promise.resolve 10))
          adder (fn [y] (+ x y))]
      (adder 5)))

  (closure-test)
  ;; => 15

  ;; -------------------------------------------------
  ;; ✅ WORKING: await in and/or short-circuit
  ;; -------------------------------------------------

  (defn ^:async and-or-test []
    {:and-result (and (await (js/Promise.resolve true))
                      (await (js/Promise.resolve "yes")))
     :or-result (or (await (js/Promise.resolve nil))
                    (await (js/Promise.resolve "fallback")))})

  (and-or-test)
  ;; => {:and-result "yes", :or-result "fallback"}

  ;; -------------------------------------------------
  ;; ✅ WORKING: thenables (non-Promise objects with .then)
  ;; -------------------------------------------------

  (defn ^:async thenable-test []
    (let [thenable #js {:then (fn [resolve] (resolve 99))}]
      (await thenable)))

  (thenable-test)
  ;; => 99

  ;; -------------------------------------------------
  ;; ✅ WORKING: metadata + docstring on async defn
  ;; -------------------------------------------------

  (defn ^:async ^:my-meta documented-async
    "I have a docstring"
    [x]
    (await (js/Promise.resolve x)))

  (documented-async 42)
  ;; => 42
  (:doc (meta #'documented-async))
  ;; => "I have a docstring"

  :rcf)
