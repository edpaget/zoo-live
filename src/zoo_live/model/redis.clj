(ns zoo-live.model.redis
  (:use [clj-time.core :only [day month now minus days]])
  (:require [taoensso.carmine :as car]
            [clj-http.client :as http]
            [zoo-live.model.postgres :as p]))

(def connection {})

(defn connect!
  [opts]
  (alter-var-root #'connection (constantly opts)))


(defmacro wcar* [& body] `(car/wcar connection ~@body))

(defn save-cpm
  []
  (let [cpm (wcar* (car/get "zoo-live:cpm"))]
    (if cpm
      {:value cpm}
      (let [cpm (-> "https://rpm.newrelic.com/public/charts/dmjm5GoMgAW/data.json?tw[dur]=last_30_minutes"
                    (http/get {:as :json})
                    (get-in [:body :series])
                    first
                    :data
                    last
                    :y)]
        (wcar* (car/set "zoo-live:cpm" cpm)
               (car/expire "zoo-live:cpm" 60))
        {:value cpm}))))

(defn save
  [classification]
  (let [id (wcar* (car/incr "id"))
        location (first (p/find-ips (:user_ip classification)))
        country (:country location)
        today (now)
        day-ago (minus today (days 2))
        date (str (month today) "-" (day today))
        prev-date (str (month day-ago) "-" (day day-ago))
        record (dissoc (merge classification 
                              {:location location 
                               :id id}) 
                       :user_ip)]
    (wcar*
      (car/sadd "countries" country)
      (car/incr country)
      (car/lpush "classifications" record)
      (car/ltrim "classifications" 0 99)
      (car/lpush (str "classifications-" date) record)
      (if (= 1 (wcar* (car/exists (str "classifications-" prev-date))))
        (car/del (str "classifications-" prev-date))))))

(defn get-all
  ([]
   (get-all 99))
  ([n]
   (wcar* (car/lrange "classifications" 0 n))))

(defn get-date
  [date]
  (let [key (str "classifications-" date)] 
    (wcar* (car/lrange key 0 (wcar* (car/llen key))))))

(defn get-countries
  []
  (let [result (wcar* 
                 (car/smembers "countries")  
                 (mapv #(car/get %) (wcar* (car/smembers "countries"))))
        [countries & counts] result
        counts (map #(Integer. %) counts)]
    (sort-by #(- (last %)) (into [] (zipmap countries counts)))))

