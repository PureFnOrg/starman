(ns dev
  (:require [clojure.tools.namespace.repl :refer [refresh refresh-all]]
            [com.stuartsierra.component :as component]
            [org.purefn.bridges.api :as bridges]
            [org.purefn.bridges.protocol :as proto]
            [org.purefn.starman.carmine :as carmine]
            [org.purefn.starman.jedis :as jedis]
            [taoensso.nippy :as nippy]
            [taoensso.nippy.encryption :as enc]
            [taoensso.timbre :as log]))

(defn default-system
  []
  (component/system-map
   :carmine (carmine/redis {::carmine/host "localhost"
                            ::carmine/port 6379})
   :jedis (jedis/redis {::jedis/host "localhost"
                        ::jedis/namespaces {"test" {::jedis/encoder :nippy}}})))

(def system
  "A Var containing an object representing the application under
  development."
  nil)

(defn init
  "Constructs the current development system."
  []
  (alter-var-root #'system
    (constantly (default-system))))

(defn start
  "Starts the current development system."
  []
  (alter-var-root #'system component/start))

(defn stop
  "Shuts down and destroys the current development system."
  []
  (alter-var-root #'system
    (fn [s] (when s (component/stop s)))))

(defn go
  "Initializes and starts the system running."
  []
  (init)
  (start)
  :ready)

(defn reset
  "Stops the system, reloads modified source files, and restarts it."
  []
  (stop)
  (refresh :after `go))

(defn cause-collision
  []
  (->> (range)
       (map #(future
               (do (Thread/sleep (* 1000 (rand)))
                   (try 
                     (proto/swap-in
                      (:jedis system)
                      "mtk-test" "concurrently"
                      (constantly (str "qux" %)))
                     (catch Exception ex
                       (log/error "permanent failure" ex))))))
       (take 100)))
