(ns org.purefn.starman.carmine
  "Redis/Elasticache implementation"
  (:require [clojure.set :refer [rename-keys]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.walk :refer [keywordize-keys]]
            [com.stuartsierra.component :as component]
            [org.purefn.bridges.protocol :as bridges]
            [org.purefn.starman.common :as common]
            [org.purefn.kurosawa.k8s :as k8s]
            [taoensso.carmine :as redis :refer (wcar)]
            [taoensso.timbre :as log]))

;;------------------------------------------------------------------------------
;; Helpers
;;------------------------------------------------------------------------------

(defn- call-redis
  "Takes a redis function and calls it with the redis-ified full-key and
   any values that were given. Does this inside the context of the connection."
  [conn f ns key & args]
  (wcar conn (apply f (common/full-key ns key) args)))

;;------------------------------------------------------------------------------
;; Component
;;------------------------------------------------------------------------------

(defrecord RedisCarmine
    [config conn]

  component/Lifecycle
  (start [this]
    (if conn
      (do (log/warn "Redis client (carmine) has already been initialized.")
          this)
      (do
        (log/info "Initializing Redis (carmine) client for" (:host config))
        (assoc this :conn {:pool {} :spec config}))))

  (stop [this]
    (log/info "Stopping Redis client (carmine).")
    (assoc this :conn nil))

  bridges/KeyValueStore
  (fetch [this ns k]
    (call-redis conn redis/get ns k))

  (destroy [this ns k]
    (call-redis conn redis/del ns k))

  (swap-in [this ns k f]
    (let [fk (common/full-key ns k)
          [_ [_ response]]
          (redis/atomic conn (:max-retries config)
                        (redis/watch fk)
                        (let [cur (redis/with-replies (redis/get fk))]
                          (redis/multi)
                          (redis/set fk (f cur))
                          (redis/get fk)))]
      response))

  bridges/Cache
  (expire [this ns k ttl]
    (= 1 (call-redis conn redis/expire ns k ttl))))

;;------------------------------------------------------------------------------
;; Creation
;;------------------------------------------------------------------------------

(defn default-config
  "k8s config based on env"
  []
  (when (k8s/kubernetes?)
    (-> (k8s/config-map "redis")
        keywordize-keys
        (rename-keys {:host ::host}))))

(defn redis
  "Creates a Redis component from a config."
  ([]
    (redis (default-config)))
  ([config]
   (let [{:keys [::host ::port ::max-retries]} config]
     (->RedisCarmine {:host host
                      :port (or port common/default-port)
                      :max-retries (or max-retries 12)}
              nil))))

;;------------------------------------------------------------------------------
;; Spec
;;------------------------------------------------------------------------------

(s/def ::host (s/or :fqdn common/fqdn? :ip-addr common/ip4-addr?))

(s/def ::port pos-int?)

(s/def ::config (s/keys :req [::host]
                        :opt [::port]))

(s/fdef redis
        :args (s/alt :0-arity (s/cat)
                     :1-arity (s/cat :config ::config))
        :ret (partial instance? RedisCarmine))


(stest/instrument `redis)
