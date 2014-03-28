(ns zoo-live.system
  (:require [zoo-live.web.routes :as r]
            [zoo-live.web.server :as s]
            [korma.db :as kdb]
            [clojure.string :as str]
            [zoo-live.model.postgres :as post]
            [zoo-live.model.kafka :as k])
  (:gen-class :main true))

(defn- uri-to-db-map
  [uri]
  (let [uri (java.net.URI. uri)
        [username password] (str/split (.getUserInfo uri) #":")]
    {:db (apply str (drop 1 (.getPath uri)))
     :user username
     :password password
     :host (.getHost uri)
     :port (.getPort uri)}))

(defn- db-connection
  [postgres]
  (let [conn (kdb/create-db
               (kdb/postgres 
                 (if (string? postgres) (uri-to-db-map postgres) postgres)))]
    (kdb/default-connection conn)
    conn))

(defn system
  "Returns a new instance of the whole application"
  []
  (let [env (System/getenv) ]
    {:postgres (or (get env "DATABASE_URL") "postgres://storm:storm@localhost:5433/events")
     :handler r/routes
     :zookeeper (or (get env "ZOOKEEPER_CLUSTER") "33.33.33.10:2181") 
     :port 8080
     :projects ["andromeda"
                "asteroid"
                "bat_detective"
                "cancer_cells"
                "cancer_gene_runner"
                "condor"
                "cyclone_center"
                "galaxy_zoo" 
                "leaf"
                "m83"
                "milky_way"
                "notes_from_nature"
                "penguin"
                "planet_four"
                "plankton"
                "radio"
                "sea_floor"
                "serengeti"
                "spacewarp"
                "sunspot"
                "war_diary"
                "wise"
                "worms"]
     :types ["classifications"]}))

(defn start
  [system]
  (let [system (update-in system [:postgres] db-connection)
        system (assoc system :stream (k/kafka-stream (:zookeeper system)))
        system (update-in system [:handler] apply [system])
        server (s/create (:handler system)
                         :port (:port system))]
    (post/connect! (:postgres system))
    (into system {:server server})))

(defn stop
  [system]
  (when (:server system)
    (s/stop (:server system)))
  (dissoc system :server))

(defn -main
  [& [conf]]
  (let [sys (if conf (merge (system) (read-string (slurp conf))) (system))] 
    (start sys)))
