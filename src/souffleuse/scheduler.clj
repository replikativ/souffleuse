(ns souffleuse.scheduler
  (:require [chime.core :as chime])
  (:import [java.time ZonedDateTime ZoneId Period LocalTime DayOfWeek]))

(defn start-scheduler [scheduled-fn {:keys [hour minute dow zone]}]
  (let [dow (dow {:monday DayOfWeek/MONDAY
                  :tuesday DayOfWeek/TUESDAY
                  :wednesday DayOfWeek/WEDNESDAY
                  :thursday DayOfWeek/THURSDAY
                  :friday DayOfWeek/FRIDAY
                  :saturday DayOfWeek/SATURDAY
                  :sunday DayOfWeek/SUNDAY})]
    (chime/chime-at (->> (chime/periodic-seq (-> (LocalTime/of hour minute 0)
                                                 (.adjustInto (ZonedDateTime/now (ZoneId/of zone)))
                                                 .toInstant)
                                             (Period/ofDays 1))
                         (map #(.atZone % (ZoneId/of zone)))
                         (filter (comp #{dow} #(.getDayOfWeek %)))
                         rest)
                    scheduled-fn)))
