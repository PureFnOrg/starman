(ns org.purefn.starman
  (:require [org.purefn.bridges.api :as bridges]))

;;------------------------------------------------------------------------------
;; API functions
;;------------------------------------------------------------------------------

(defn fetch
  "Returns the single value at an ns and key using redis's GET."
  [rd ns k]
  (bridges/fetch rd ns k))

(defn destroy
  "Deletes the key-value pair for the given ns/key. Just returns 0 if the key
   does not exist."
  [rd ns k]
  (bridges/destroy rd ns k))

(defn swap-in
  "Calls f on the value for key in a namespace and swaps the result in
   as the new value. Uses redis's WATCH command so that the transaction fails
   if another write on the key is detected. Transaction failures are indicated by
   a nil returned from exec (after with-replying and parsing)."
  [rd ns key f]
  (bridges/swap-in rd ns key f))

(defn write
  "Writes a value to a key without the optimistic locking semantics used in `swap-in`.

   Internally only calls SET."
  [rd ns key value]
  (bridges/write rd ns key value))

(defn expire
  "Sets a TTL for a key."
  [rd ns k ttl]
  (bridges/expire rd ns k ttl))
