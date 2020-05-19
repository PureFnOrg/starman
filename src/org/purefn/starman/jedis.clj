(ns org.purefn.starman.jedis
  (:require [clojure.set :refer [rename-keys]]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.walk :refer [keywordize-keys]]
            [com.stuartsierra.component :as component]
            [org.purefn.bridges.protocol :as bridges]
            [org.purefn.kurosawa.health :as health]
            [org.purefn.kurosawa.k8s :as k8s]
            [org.purefn.starman.common :as common]
            [taoensso.timbre :as log]
            [taoensso.nippy :as nippy])
  (:import [java.util.concurrent ThreadLocalRandom]
           [redis.clients.jedis Jedis JedisPool JedisPoolConfig]))

;;------------------------------------------------------------------------------
;; Config
;;------------------------------------------------------------------------------

(defn- encoder
  "Extracts encoder from config"
  [config ns]
  (get-in config [:namespaces ns ::encoder]))

;;------------------------------------------------------------------------------
;; Encoding
;;------------------------------------------------------------------------------

(defmulti encode (fn [encoder _] encoder))

(defmethod encode :nippy [_ val]
  (nippy/freeze val))

(defmethod encode :default [_ val] val)

(defmulti decode (fn [encoder blob] encoder))

(defmethod decode :nippy [_ ^String s]
  (-> (.getBytes s "UTF-8")
      (nippy/thaw)))

(defmethod decode :default [_ s] s)

(defn- random-sleep
  [max-duration-ms]
  (-> (ThreadLocalRandom/current)
      (.nextInt max-duration-ms)
      (max 1)
      (Thread/sleep)))

(defn with-retries
  [f max-retries delay-ms backoff]
  (loop [cnt 0
         ms delay-ms]
    (if-let [res (f)]
      res
      (when (< cnt max-retries)
        (random-sleep ms)
        (recur (inc cnt) (backoff ms))))))

(defn- set*
  [^Jedis c k v]
  (if (bytes? v)
    (.set c (.getBytes k) v)
    (.set c k v)))

(defn- swap-in*
  [{:keys [config ^JedisPool pool]} ns k f]
  (with-open [^Jedis c (.getResource pool)]
    (let [^String fk (common/full-key ns k)
          cur (some->> (.get c fk) (decode (encoder config ns)))
          _ (.watch c (into-array String [fk]))
          t (.multi c)
          _ (->> (f cur)
                 (encode (encoder config ns))
                 (set* t fk))
          res (.get t fk)]
      (if-not (seq (.exec t))
        (log/warn :temporary-failure "swap-in" :key fk :reason :cas-mismatch)
        (some->> (.get res) (decode (encoder config ns)))))))

;;------------------------------------------------------------------------------
;; Component
;;------------------------------------------------------------------------------

(defrecord RedisJedis
    [config ^JedisPool pool]

  component/Lifecycle
  (start [this]
    (if pool
      (do (log/warn "Redis client (Jedis) has already been initialized.")
          this)
      (let [{:keys [max-total host]} config]
        (log/info "Initializing Redis (Jedis) connection pool for"
                  host)
        (assoc this :pool (JedisPool. (doto (JedisPoolConfig.)
                                        (.setMaxTotal max-total))
                                      host)))))

  (stop [this]
    (if pool
      (do
        (log/info "Stopping Redis (Jedis) client.")
        (.close pool)
        (dissoc this :pool))
      (log/warn "Redis client (Jedis) has already stopped.")))

  bridges/KeyValueStore
  (fetch [this ns k]
    (with-open [c (.getResource pool)]
      (some->> (.get c (common/full-key ns k))
               (decode (encoder config ns)))))

  (destroy [this ns k]
    (with-open [c (.getResource pool)]
      (.del c (common/full-key ns k))))

  (swap-in [this ns k f]
    (if-let [res (with-retries
                   #(swap-in* this ns k f)
                   (:max-retries config)
                   (:busy-delay-ms config)
                   (partial * 2))]
      res
      (throw (ex-info "swap-in failed!"
                      {:fn "swap-in"
                       :key (common/full-key ns k)
                       :reason :cas-mismatch}))))

  (write [this ns k value]
    (with-open [c (.getResource pool)]
      (set* c
            (common/full-key ns k)
            (encode (encoder config ns) value))))

  bridges/Cache
  (expire [this ns k ttl]
    (with-open [c (.getResource pool)]
      (.expire c (common/full-key ns k) ttl)))

  health/HealthCheck
  (healthy? [this]
    (with-open [c (.getResource pool)]
      (if (= "PONG" (.ping c))
        true
        (do (log/error "Redis health check failed!")
            false)))))

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
   (let [{:keys [::host
                 ::port
                 ::max-total
                 ::max-retries
                 ::busy-delay-ms
                 ::namespaces]} config]
     (->RedisJedis {:host host
                    :port (or port common/default-port)
                    :max-total (or max-total common/default-max-total)
                    :max-retries (or max-retries 7)
                    :busy-delay-ms (or busy-delay-ms 20)
                    :namespaces namespaces}
              nil))))

;;------------------------------------------------------------------------------
;; Spec
;;------------------------------------------------------------------------------

(s/def ::host string?)

(s/def ::port pos-int?)

(s/def ::max-total pos-int?)

(s/def ::encoder #{:nippy})
(s/def ::namespace-config (s/keys :req [::encoder]))
(s/def ::namespace string?)
(s/def ::namespaces (s/map-of ::namespace ::namespace-config))

(s/def ::config (s/keys :req [::host]
                        :opt [::port
                              ::max-total
                              ::namespaces]))

(s/fdef redis
        :args (s/alt :0-arity (s/cat)
                     :1-arity (s/cat :config ::config))
        :ret (partial instance? RedisJedis))

(stest/instrument `redis)
