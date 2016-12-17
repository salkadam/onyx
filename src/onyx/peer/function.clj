(ns ^:no-doc onyx.peer.function
  (:require [clojure.core.async :refer [chan >! go alts!! close! timeout]]
            [onyx.static.planning :refer [find-task]]
            [onyx.peer.operation :as operation]
            [onyx.messaging.protocols.messenger :as m]
            [onyx.log.commands.common :as common]
            [onyx.plugin.protocols.input :as oi]
            [onyx.protocol.task-state :refer :all]
            [clj-tuple :as t]
            [onyx.types :as types]
            [onyx.static.uuid :refer [random-uuid]]
            [onyx.types]
            [onyx.plugin.protocols.plugin :as op]
            [taoensso.timbre :as timbre :refer [debug info]]))

(defrecord FunctionPlugin []
  op/Plugin
  (start [this event] this)
  (stop [this event] this))

(defn read-function-batch [state]
  (let [messenger (get-messenger state)
        batch (m/poll messenger)]
    (-> state 
        (set-event! (assoc (get-event state) :onyx.core/batch batch))
        (advance))))

(defn read-input-batch [state]
  (let [{:keys [onyx.core/task-map onyx.core/id 
                onyx.core/job-id onyx.core/task-id] :as event} (get-event state)
        pipeline (get-pipeline state)
        batch-size (:onyx/batch-size task-map)
        [next-reader batch] (loop [reader pipeline
                                   outgoing (transient [])]
                              (assert pipeline)
                              (if (< (count outgoing) batch-size) 
                                (let [next-reader (oi/next-state reader state)
                                      segment (oi/segment next-reader)]
                                  (if segment 
                                    (recur next-reader (conj! outgoing (types/input segment)))
                                    [next-reader outgoing]))
                                [reader outgoing]))]
    (debug "Reading batch" job-id task-id "peer-id" id batch)
    (-> state
        (set-pipeline! next-reader)
        (set-event! (assoc event :onyx.core/batch (persistent! batch)))
        (advance))))
