(ns zoo-live.events
  (:require [clj-kafka.core :refer [with-resource]]
            [cheshire.core :refer [parse-string generate-string]]
            [clojure.core.async :refer [go <! <!! filter< map< sub chan close! go-loop >!]]
            [zoo-live.web.resp :refer :all]
            [korma.core :refer :all]
            [clojure.string :as str]
            [clj-time.coerce :refer [to-sql-date]]
            [pg-json.core :refer :all]
            [compojure.core :refer [GET]]
            [org.httpkit.server :refer [send! with-channel on-close]]))

(defn filter-user-data
  [ev]
  (dissoc ev :user_name :user_ip))

(defn- ent
  "Creates Korma Entity from event type and project"
  [{:keys [postgres]} type project]
  (-> (create-entity (str "events_" type "_" project))
      (database postgres)
      (transform (fn [obj] (update-in obj [:data] from-json-column)))))

(defn- date-between [w from to]
  (assoc w :created_at ['between from to]))

(def get-date (comp second :created_at))

(defn- params-to-where
  [w [k v]]
  (cond
    (and (contains? w :created_at) (= k :from)) (date-between w (get-date w) (to-sql-date v))
    (and (contains? w :created_at) (= k :to)) (date-between w (to-sql-date v) (get-date w))
    (= k :from) (assoc w :created_at ['> (to-sql-date v)])
    (= k :to) (assoc w :created_at ['< (to-sql-date v)])
    (= k :female) (assoc w :female ['> v])
    (= k :male) (assoc w :male ['> v])
    true (assoc w k v)))

(defn query-from-params
  [ent {:keys [page per_page] :as params :or {page "1" per_page "10"}}]
  (let [where-clause (reduce params-to-where {} (dissoc params :page :per_page))
        page (Integer/parseInt page)
        per_page (Integer/parseInt per_page)]
    (select ent
            (where where-clause)
            (limit per_page)
            (order :created_at :DESC)
            (offset (* (- page 1) per_page)))))

(defn db-response
  [ent params & [mime]]
  (resp-ok (mapv filter-user-data (query-from-params ent params)) mime))

(defn- filter-test
  [ev [k t]]
  (cond 
    (vector? t) ((first t) (k ev) (second t))
    true (= (k ev) t)))

(defn- filter-stream
  [params]
  (let [filter-map (reduce params-to-where {} (dissoc params :from :to :page :per_page))]
    (fn [ev]
      (every? (partial filter-test ev) filter-map))))

(defn- streaming-response
  [msgs type project {:keys [params] :as req}]
  (let [in-chan (chan)
        out-chan (->> (sub msgs (str type "-" project) in-chan)  
                      (map< #(get % :event))
                      (filter< (filter-stream params))
                      (map< filter-user-data)
                      (map< (comp #(str % "\n") generate-string)))]
    (with-channel req channel
      (send! channel (resp-ok (<!! out-chan) stream-mime) false)
      (let [writer (go-loop [msg (<! out-chan)]
                            (send! channel (resp-ok msg stream-mime) false)
                            (recur (<! out-chan)))]
        (on-close channel (fn [status] 
                            (close! writer) 
                            (close! in-chan)
                            (close! out-chan)))))))

(defn- handle-request
  [msgs db-ent type project]
  (fn [{:keys [headers] :as req}]
    (cond
      (= (headers "accept") stream-mime) (streaming-response msgs type project req)
      (= (headers "accept") app-mime) (db-response db-ent (:params req))
      (= (headers "accept") "application/json") (db-response db-ent (:params req) "application/json")
      true (resp-bad-request))))

(defn event-routes
  [config [type project]]
  (let [msgs (:stream config) 
        db-ent (ent config type project)]
    (GET (str "/" type "/" project) [:as req] (handle-request msgs db-ent type project))))
