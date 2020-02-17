(ns grumpy.core.time
  (:import
   [java.time Instant Duration LocalDate LocalDateTime ZoneId]
   [java.time.format DateTimeFormatter]
   [java.time.temporal ChronoUnit]))


(def ^:private ^ZoneId UTC (ZoneId/of "UTC"))


(def ^:private ^DateTimeFormatter date-formatter (DateTimeFormatter/ofPattern "MMMM d, YYYY"))


(defn format-date [^Instant inst]
  (.format date-formatter (LocalDate/ofInstant inst UTC)))


(def ^:private ^DateTimeFormatter iso-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss'Z'"))


(defn format-iso-inst [^Instant inst]
  (.format iso-formatter (LocalDateTime/ofInstant inst UTC)))


(def ^:private ^DateTimeFormatter log-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.SSS"))


(defn format-log-inst []
  (.format log-formatter (LocalDateTime/now UTC)))


(def ^:private ^DateTimeFormatter reader-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS-00:00"))


(defn- print-inst
  "Print a java.time.Instant as RFC3339 timestamp, always in UTC."
  [^Instant i, ^java.io.Writer w]
  (.write w "#inst \"")
  (.write w (.format reader-formatter (LocalDateTime/ofInstant i UTC)))
  (.write w "\""))


(defmethod print-method Instant
  [^Instant i, ^java.io.Writer w]
  (print-inst i w))


(defmethod print-dup Instant
  [^Instant i, ^java.io.Writer w]
  (print-inst i w))


(defn ^Instant parse-iso-inst [^String s]
  (Instant/parse s))


(defn now ^Instant []
  (.truncatedTo (Instant/now) ChronoUnit/MILLIS))


(defn age [^Instant inst]
  (-> (Duration/between inst (now))
    (.toMillis)))