(ns souffleuse.scheduler
  (:require [chime.core :as chime])
  (:import [java.time ZonedDateTime ZoneId Period LocalTime DayOfWeek]))

(defn start-scheduler [scheduled-fn]
  (chime/chime-at (->> (chime/periodic-seq (-> (LocalTime/of 15 45 0)
                                               (.adjustInto (ZonedDateTime/now (ZoneId/of "Europe/Berlin")))
                                               .toInstant)
                                           (Period/ofDays 1))

                       (map #(.atZone % (ZoneId/of "Europe/Berlin")))

                       (filter (comp #{DayOfWeek/THURSDAY}
                                     #(.getDayOfWeek %)))

                       rest)
                  scheduled-fn))
