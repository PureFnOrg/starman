(ns org.purefn.starman.common
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as str]))

;;------------------------------------------------------------------------------
;; Defaults and settings
;;------------------------------------------------------------------------------

(def default-port 6379)
(def default-max-total 32)
(def default-max-idle 32)
(def default-timeout 2000)

;;------------------------------------------------------------------------------
;; Helpers
;;------------------------------------------------------------------------------

(defn full-key
  "Combines a namespace and key into a single string. Needed because redis has
   no concept of namespaces. This has the additional effect of converting
   everything into strings."
  [ns key]
  (str ns "/" key))

;;------------------------------------------------------------------------------
;; Spec
;;------------------------------------------------------------------------------

(def ip4-addr-regex #"^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$")

(def ip4-addr?
  (s/with-gen
    (s/and string? (partial re-matches ip4-addr-regex)
           (fn [s]
             (when-let [ss (str/split s #"\.")]
               (and (= (count ss) 4) ;; regex already does this
                    (every? #(try (<= 0 (Integer/parseInt %) 255)
                                  (catch Exception ex false))
                            ss)))))
    #(-> (gen/choose 0 255)
         (->> (gen/fmap str))
         (gen/vector 4)
         (->> (gen/fmap (partial str/join "."))))))

(def fqdn-regex #"[a-z][a-z0-9-]*[a-z0-9](\.[a-z][a-z0-9-]*[a-z0-9])*")

(def fqdn?
  (s/with-gen
    (s/and string? (partial re-matches fqdn-regex))
    #(-> (gen/string-alphanumeric)
         (->> (gen/fmap str/lower-case))
         (gen/not-empty)
         (gen/vector 1 3)
         (->> (gen/fmap (partial str/join "-")))
         (gen/vector 1 5)
         (->> (gen/fmap (partial str/join "."))))))
