(ns cljs-async
  (:require [cljs.core.async :refer [<! >!] :as async])
  (:require-macros [cljs.core.async.macros :refer [go]]))


(defn throwable?
  "Check if it is throwable."
  [x]
  (instance? js/Error x))

(defn throw-if-throwable
  "Helper method that checks if x is Throwable and if yes, wraps it in a new
  exception, passing though ex-data if any, and throws it. The wrapping is done
  to maintain a full stack trace when jumping between multiple contexts."
  [e]
  (when (throwable? e) (throw e)) e)

(defmacro <?
  "Same as core.async <! but throws an exception if the channel returns a
  throwable object. Also will not crash if channel is nil."
  [ch]
  `(throw-if-throwable (let [ch# ~ch] (when ch# (<! ch#)))))

(defmacro go-try
  "Asynchronously executes the body in a go block. Returns a channel which
  will receive the result of the body when completed or an exception if one
  is thrown."
  [& body]
  `(go (try ~@body (catch js/Error e# e#))))

(defmacro alts?
  "Same as core.async alts! but throws an exception if the channel returns a
  throwable object."
  [ports]
  `(throw-if-throwable (alts! ~ports)))

(defmacro go-retry
  [{:keys [exception retries delay error-fn]
    :or {error-fn nil, exception js/Error, retries 5, delay 1}} & body]
  `(let [error-fn# ~error-fn]
     (go-loop
       [retries# ~retries]
       (let [res# (try ~@body (catch js/Error e# e#))]
         (if (and (instance? ~exception res#)
                  (or (not error-fn#) (error-fn# res#))
                  (> retries# 0))
           (do
             (<! (async/timeout (* ~delay 1000)))
             (recur (dec retries#)))
           res#)))))

(defmacro <<!
  "Takes multiple results from a channel and returns them as a vector.
  The input channel must be closed."
  [ch]
  `(let [ch# ~ch]
     (<! (async/into [] ch#))))

(defmacro <!*
  "Takes one result from each channel and returns them as a collection.
  The results maintain the order of channels."
  [chs]
  `(let [chs# ~chs]
     (loop [chs# chs#
            results# (cljs.core.PersistentQueue/EMPTY)]
       (if-let [head# (first chs#)]
         (->> (<! head#)
              (conj results#)
              (recur (rest chs#)))
         results#))))

(defmacro <?*
  "Takes one result from each channel and returns them as a collection.
  The results maintain the order of channels. Throws if any of the
  channels returns an exception."
  [chs]
  `(let [chs# ~chs]
     (loop [chs# chs#
            results# (cljs.core.PersistentQueue/EMPTY)]
       (if-let [head# (first chs#)]
         (->> (<? head#)
              (conj results#)
              (recur (rest chs#)))
         results#))))

(defmacro go>? [err-chan & body]
  `(go (try
         ~@body
         (catch js/Error e#
           (>! ~err-chan e#)))))

(defmacro go-loop>? [err-chan bindings & body]
  `(go (try
         (loop ~bindings
           ~@body)
         (catch js/Error e#
           (>! ~err-chan e#)))))

(defmacro go-loop<? [bindings & body]
  `(go<? (loop ~bindings ~@body) ))

(defn pmap>>
  "Takes objects from ch, asynchrously applies function f> (function should
  return channel), takes the result from the returned channel and if it's not
  nil, puts it in the results channel. Returns the results channel. Closes the
  returned channel when the input channel has been completely consumed and all
  objects have been processed."
  [f> parallelism ch]
  (let [results (async/chan)
        threads (atom parallelism)]
    (dotimes [_ parallelism]
      (go
        (loop []
          (when-let [obj (<! ch)]
            (if (throwable? obj)
              (do
                (>! results obj)
                (async/close! results))
              (do
                (when-let [result (<! (f> obj))]
                  (>! results result))
                (recur)))))
        (when (zero? (swap! threads dec))
          (async/close! results))))
    results))

(defn reduce>
  "Performs a reduce on objects from ch with the function f> (which should return
  a channel). Returns a channel with the resulting value."
  [f> acc ch]
  (let [result (chan)]
    (go-loop [acc acc]
      (if-let [x (<! ch)]
        (if (throawable? x)
          (do
            (>! result x)
            (async/close! result))
          (->> (f> acc x) <! recur))
        (do
          (>! result acc)
          (async/close! result))))
    result))
