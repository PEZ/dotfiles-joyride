(ns example.nbb-test
  (:require [promesa.core :as p]
            [joyride.core :as joyride]
            ["path" :as path]
            ["fs" :as fs]))

(require '["./nbb_test.js" :as nbb] :reload)

(def script (path/resolve (path/dirname joyride/*file*) "nbb_script.cljs"))
(fs/existsSync script)


(p/let [nbb js/globalThis.nbb
        res (.loadFile nbb script
                       (fn [err]
                         (prn :err err)))]
  (prn :res res)
  res)

js/globalThis.yo