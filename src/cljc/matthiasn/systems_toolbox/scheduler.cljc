(ns matthiasn.systems-toolbox.scheduler
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go-loop]]))
  (:require [matthiasn.systems-toolbox.component :as comp]
    #?(:clj [clojure.core.async :refer [<! go-loop timeout]])
    #?(:cljs [cljs.core.async :refer [<! timeout]])))

;;; Systems Toolbox - Scheduler Subsystem

;;; This namespace describes a component / subsystem for scheduling the sending of messages that can then elsewhere
;;; trigger some action.

;;; Example: we want to let web clients know how many documents we have in a database so they can update the UI
;;; accordingly. The subsystem handling the database connectivity has the logic for figuring out how many documents
;;; there are when receiving a request, but no notion of repeatedly emitting this information itself. Now say we want
;;; this every 10 seconds. We tell the scheduler to emit the message type that will trigger the request every 10 seconds,
;;; and that's it.

;;; Internally, each scheduled event starts a go-loop with a timeout of the specified duration while recording
;;; the scheduled event in the state atom. Post-timeout, it is checked if the message is still scheduled to be sent
;;; and if so, the specified message is sent.

;;; Scheduled events can be deleted. TODO: implement

;;; When the same, optional :id is set on multiple message sent to scheduler component, only first of those messages
;;; will result in scheduling a new timer.

;;; TODO: record start time so that the scheduled time can be shown in UI. Platform-specific implementation.

;;; WARNING: timeouts specified here are not precise unless proven otherwise. Even if timeouts happen to have a
;;; sufficiently precise duration, the go-loop in which they run (and the associated thread pool) may be busy
;;; otherwise and delay the next iteration.

(defn start-loop
  "Starts a loop for sending messages at set intervals."
  [{:keys [cmp-state put-fn msg-payload]}]
  (let [timout-ms (:timeout msg-payload)
        msg-to-send (:message msg-payload)
        scheduler-id (or (:id msg-payload) (first msg-to-send))
        existing-timer (get-in @cmp-state [:active-timers scheduler-id])]
    (when existing-timer
      (put-fn [:log/info (str "Timer " (:id msg-payload) " already scheduled - ignoring.")]))
    (when-not existing-timer
      (put-fn [:log/info (str "Scheduling:" msg-payload)])
      (swap! cmp-state assoc-in [:active-timers scheduler-id] msg-payload)
      (when (:initial msg-payload) (put-fn msg-to-send))
      (go-loop []
        (<! (timeout timout-ms))
        (if-let [active-timer (get-in @cmp-state [:active-timers scheduler-id])]
          (do
            (put-fn msg-to-send)
            (if (:repeat active-timer)
              (recur)
              (do
                (swap! cmp-state update :active-timers dissoc scheduler-id)
                (put-fn [:info/completed-timer scheduler-id]))))
          (put-fn [:info/deleted-timer scheduler-id]))))))

(defn stop-loop
  "Stops a an loop that was previously scheduled."
  [{:keys [cmp-state put-fn msg-payload]}]
  (let [scheduler-id (:id msg-payload)
        existing-timer (get-in @cmp-state [:active-timers scheduler-id])]
    (if existing-timer
      (do (put-fn [:log/info (str "Stopping timer: " (:id msg-payload) " already scheduled - ignoring.")])
          (swap! cmp-state update :active-timers dissoc scheduler-id))
      (put-fn [:log/info (str "Timer with id: " (:id msg-payload) " not found - did not stop.")]))))

(defn cmp-map
  {:added "0.3.1"}
  [cmp-id]
  {:cmp-id      cmp-id
   :state-fn    (fn [_] {:state (atom {:active-timers {}})})
   :handler-map {:cmd/schedule-new    start-loop
                 :cmd/schedule-delete stop-loop}
   :opts        {:reload-cmp false}})

(defn component
  {:deprecated "0.3.1"}
  [cmp-id]
  (comp/make-component (cmp-map cmp-id)))
