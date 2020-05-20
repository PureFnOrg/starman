(ns org.purefn.starman.api-test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [is testing deftest]]
            [com.stuartsierra.component :as component]
            [org.purefn.starman.carmine :as carmine]
            [org.purefn.starman.jedis :as jedis]
            [org.purefn.starman :as api]
            [org.purefn.bridges.api :as bridges]
            [org.purefn.bridges.cache.api :as cache]
            [taoensso.nippy :as nippy]))

(stest/instrument [`rand-in-range])

(def nippy-ns "nns")
(def edn-ns "ens")

(def edn-stress-data
  "Had some trouble with objects, and floats appear to be lossy"
  (dissoc nippy/stress-data-comparable :queue :queue-empty :float))

(def system
  (component/system-map
   :carmine (carmine/redis {::carmine/host "localhost"
                            ::carmine/port 6379})
   :jedis (jedis/redis {::jedis/host "localhost"
                        ::jedis/namespaces {nippy-ns
                                            {:encoder :nippy}
                                            edn-ns
                                            {:encoder :edn}}})))

(defn ttl-test
  [rd]
  (let [ns "ttl-test"
        k 111
        val (pr-str {:expire "me"})]
    (api/swap-in rd ns k (constantly val))
    (is (= val (api/fetch rd ns k)))
    (api/expire rd ns k 5)
    (Thread/sleep 6000)
    (is (nil? (api/fetch rd ns k)))))

(defn cache-test
  [rd]
  (let [ns "ttl-test"
        k 222
        val (pr-str {:expire "me-too"})]
    (cache/swap-in rd ns k (constantly val) 5)
    (is (= val (bridges/fetch rd ns k)))
    (Thread/sleep 6000)
    (is (nil? (bridges/fetch rd ns k)))))

(deftest test-carmine
  (let [sys (component/start system)]

    (testing "TTL Expire with Carmine"
      (ttl-test (:carmine sys)))

    (testing "Bridges swap-in TTL with Carmine"
      (cache-test (:carmine sys)))

    (component/stop sys)))

(deftest test-jedis
  (let [sys (component/start system)]

    (testing "TTL Expire with Jedis"
      (ttl-test (:jedis sys)))

    (testing "Bridges swap-in TTL with Jedis"
      (cache-test (:jedis sys)))

    (testing "Full stress data set encodes correctly with Nippy"
      (let [data nippy/stress-data-comparable
            k "stress"]
        (is (= (bridges/write (:jedis sys) nippy-ns k data)
               data))
        (is (= (bridges/fetch (:jedis sys) nippy-ns k)
               data))

        (bridges/destroy (:jedis sys) nippy-ns k)

        (is (nil? (bridges/fetch (:jedis sys) nippy-ns k)))

        (is (= (bridges/swap-in (:jedis sys) nippy-ns k (constantly data))
               data))

        (bridges/destroy (:jedis sys) nippy-ns k)

        (is (nil? (bridges/fetch (:jedis sys) nippy-ns k)))))

    (testing "Data set encodes correctly with edn"
      (let [data edn-stress-data
            k "stress"]
        (is (= (bridges/write (:jedis sys) edn-ns k data)
               data))
        (is (= (bridges/fetch (:jedis sys) edn-ns k)
               data))

        (bridges/destroy (:jedis sys) edn-ns k)

        (is (nil? (bridges/fetch (:jedis sys) edn-ns k)))

        (is (= (bridges/swap-in (:jedis sys) nippy-ns k (constantly data))
               data))

        (bridges/destroy (:jedis sys) edn-ns k)

        (is (nil? (bridges/fetch (:jedis sys) edn-ns k)))))

    (component/stop sys)))
