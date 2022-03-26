(ns lambdaisland.facai.kernel
  "Heart of the Zao factory logic

  This is strictly a Mechanism namespace: generic, unopinionated, verbose.
  See [[lambdaisland.facai]] for an interface meant for human consumption."
  (:refer-clojure :exclude [ref])
  (:require [lambdaisland.data-printers :as data-printers]))

(def ^:dynamic *defer-build?* false)

(defrecord DeferredBuild [var opts])

(defn deferred-build? [o]
  (instance? DeferredBuild o))

(declare build)

;; Factories don't have to be records, a simple map with the right keys will do,
;; but we like it to have invoke so they are callable as a shorthand for calling
;; build. They should have {:type :facai/factory} as metadata.
;; - :facai.factory/id - fully qualified symbol of the factory var
;; - :facai.factory/template - the template we will build, often a map but can be anything
;; - :facai.factory/traits - map of traits (name -> map)
(defrecord Factory []
  clojure.lang.IFn
  (invoke [this]
    ;; When called inside a factory definition we don't actually build the
    ;; value, we defer that to when the outer factory gets built
    (if *defer-build?*
      (->DeferredBuild (resolve (:facai.factory/id this)) nil)
      (:facai.result/value (build nil this nil))))
  (invoke [this opts]
    (if *defer-build?*
      (->DeferredBuild (resolve (:facai.factory/id this)) opts)
      (:facai.result/value (build nil this opts)))))

(defn factory? [o]
  (= :facai/factory (:type (meta o))))

(defn factory-template
  [{:facai.factory/keys [template inherit traits]}
   {with :with selected-traits :traits}]
  (cond->
      (reduce
       (fn [fact trait]
         (merge fact (get traits trait)))
       (if inherit
         (merge (factory-template inherit nil) template)
         template)
       selected-traits)
    with
    (merge with)))

(defn push-path [ctx segment]
  (assert segment)
  (update ctx :facai.build/path (fnil conj []) segment))

(defn add-linked [result path entity]
  (update result :facai.result/linked (fnil assoc {}) path entity))

(defn merge-linked [result linked]
  (update result :facai.result/linked merge linked))

(declare build build-template)

(defn run-hook [hook ctx result]
  (if-let [hook-fn (get ctx hook)]
    (hook-fn ctx result)))

(defn build-factory* [{:facai.hooks/keys [build-factory]
                       :facai.build/keys [path] :as ctx} factory opts]
  (let [{:facai.factory/keys [id]} factory
        ctx (cond-> ctx id (push-path id))
        result (-> ctx
                   (build-template (factory-template factory opts))
                   (assoc :facai.factory/id id))]
    (if path
      (assoc result :facai.build/path path)
      result)))

(defn build-factory [{:facai.hooks/keys [build-factory]
                      :facai.build/keys [path] :as ctx} factory opts]
  (if build-factory
    (build-factory ctx factory opts)
    (let [result (build-factory* ctx factory opts)]
      (cond-> result
        path
        (add-linked path (:facai.result/value result))))))

(defn build-map-entry [{:facai.hooks/keys [build-association] :as ctx} val-acc k v]
  (if (and build-association (or (factory? v) (deferred-build? v)))
    (let [[fact opts] (if (deferred-build? v) [@(:var v) (:opts v)] [v nil])]
      (build-association ctx val-acc k fact opts))
    (let [{:facai.result/keys [value linked] :as result} (build (push-path ctx k) v nil)]
      {:facai.result/value (assoc val-acc k value)
       :facai.result/linked linked})))

(defn build-template [{:facai.build/keys [path] :as ctx} tmpl]
  (cond
    (map? tmpl)
    (reduce-kv
     (fn [acc k v]
       (let [{:facai.result/keys [value linked]}
             (build-map-entry ctx (:facai.result/value acc) k v)]
         (-> acc
             (assoc :facai.result/value value)
             (merge-linked linked))))
     {:facai.result/value {}}
     tmpl)

    (coll? tmpl)
    (let [results (map-indexed (fn [idx qry]
                                 (build (push-path ctx idx) qry nil))
                               tmpl)]
      {:facai.result/value (into (empty tmpl) (map :facai.result/value) results)
       :facai.result/linked (transduce (map :facai.result/linked) merge results)})

    (fn? tmpl)
    {:facai.result/value (tmpl)}

    :else
    {:facai.result/value tmpl}))

(defn build [ctx query opts]
  (cond
    (factory? query)
    (build-factory ctx query opts)

    (deferred-build? query)
    (build-factory ctx @(:var query) (:opts query))

    :else
    (build-template ctx query)))