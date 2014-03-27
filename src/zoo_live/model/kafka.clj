(ns zoo-live.model.kafka
  (:require [clj-kafka.consumer.zk :refer :all]
            [clojure.core.async :refer [>! chan pub go]]
            [cheshire.core :refer [parse-string]]))

(defn- kafka-config
  [zk]
  {"zookeeper.connect" zk 
   "group.id" "zoo-live"
   "auto.offset.reset" "largest"
   "auto.commit.enable" "true"})

(defn- kafka-json-string-to-map
  [msg]
  (parse-string (->> (:value msg)
                     (map #(char (bit-and % 255))) 
                     (apply str))
                true))

(defn kafka-stream
  [zk]
  (let [conf (kafka-config zk) 
        msgs (messages (consumer conf) ["events"]) 
        channel (chan)]
    (go (doseq [m msgs] (>! channel (kafka-json-string-to-map m))))
    (pub channel (fn [{:strs [type project]}] (str type "-" project)))))