(ns com.verybigthings.penkala.util.decompose
  (:require [clojure.spec.alpha :as s]
            [com.verybigthings.penkala.util.core :refer [as-vec path-prefix-join]]
            [camel-snake-kebab.core :refer [->kebab-case-string ->kebab-case-keyword]]
            [clojure.set :as set]))

(s/def ::decompose-to #{:indexed-by-pk :coll :map :parent})

(s/def ::column-map
  (s/map-of
    keyword?
    (s/or
      :keyword keyword?
      :schema ::schema)))

(s/def ::column-vec
  (s/coll-of
    (s/or
      :keyword keyword?
      :column-map ::column-map)
    :kind vector?))

(s/def ::columns
  (s/or
    :column-map ::column-map
    :column-vec ::column-vec))

(s/def ::pk
  (s/or
    :single keyword?
    :multiple (s/coll-of keyword?)))

(s/def ::schema
  (s/keys
    :req-un [::pk ::columns]
    :opt-un [::decompose-to]))

(declare process-schema)

(defn process-schema-columns [schema]
  (let [columns             (:columns schema)
        ns-name             (when-let [ns (:namespace schema)] (name ns))
        rename              (if ns-name (fn [k] (keyword ns-name (name k))) identity)
        process-map-columns (fn [schema columns]
                              (reduce-kv
                                (fn [acc k v]
                                  (if (map? v)
                                    (assoc-in acc [:schemas (rename k)] (process-schema v))
                                    (assoc-in acc [:renames (rename k)] v)))
                                schema
                                columns))]
    (if (map? columns)
      (process-map-columns schema columns)
      (reduce
        (fn [acc v]
          (if (map? v)
            (process-map-columns acc v)
            (assoc-in acc [:renames v] (rename v))))
        schema
        columns))))

(defn process-schema- [schema]
  (-> schema
    process-schema-columns
    (update :pk as-vec)))

(def process-schema (memoize process-schema-))

(defn assoc-columns [acc renames row]
  (reduce-kv
    (fn [acc' k v]
      (if (contains? row v)
        (assoc acc' k (get row v))
        acc'))
    acc
    renames))

(declare build)

(defn assoc-descendants [acc schemas idx row]
  (reduce-kv
    (fn [m k v]
      (let [descendant (build (get acc k) v idx row)]
        (if descendant
          (assoc m k descendant)
          m)))
    acc
    schemas))

(defn build [acc schema idx row]
  (let [pk              (:pk schema)
        is-composite-pk (< 1 (count pk))
        id              (if is-composite-pk
                          (mapv #(get row %) pk)
                          (get row (first pk)))
        {:keys [renames schemas]} schema]
    (if (or (and is-composite-pk (every? nil? id))
          (and (not is-composite-pk) (nil? id)))
      acc
      (let [current (-> (get acc id {})
                      (vary-meta update ::idx #(or % idx))
                      (assoc-columns renames row)
                      (assoc-descendants schemas idx row))]
        (assoc acc id current)))))

(defn transform [schema mapping]
  (let [decompose-to (get schema :decompose-to :coll)
        schemas      (:schemas schema)
        transformed  (reduce-kv
                       (fn [acc k row]
                         (let [transformed
                               (reduce-kv
                                 (fn [row' k k-schema]
                                   (let [transformed-child (transform k-schema (get row k))]
                                     (if (= :parent (:decompose-to k-schema))
                                       (-> row'
                                         (dissoc k)
                                         (merge transformed-child))
                                       (assoc row' k transformed-child))))
                                 row
                                 schemas)]
                           (cond
                             (= :coll decompose-to)
                             (conj acc transformed)

                             (= :indexed-by-pk decompose-to)
                             (assoc acc k transformed)

                             ;; decompose to :map and :parent should just return the map
                             :else
                             transformed)))
                       (if (= :coll decompose-to) [] {})
                       mapping)]
    (if (= :coll decompose-to)
      (->> transformed
        (sort-by #(-> % meta ::idx))
        vec)
      transformed)))

(defn decompose [schema data]
  (when (and (seq schema) (seq data))
    (let [schema' (process-schema schema)
          mapping (reduce
                    (fn [acc [idx row]]
                      (build acc schema' idx row))
                    {}
                    (map-indexed (fn [idx v] [idx v]) (as-vec data)))]
      (transform schema' mapping))))

(s/fdef decompose
  :args (s/cat ::schema (s/or :map map? :coll sequential?))
  :ret (s/or :map? map? :coll sequential?))

(defn get-prefixed-col-name [path-prefix col-name]
  (->> (conj path-prefix (->kebab-case-string col-name))
    (mapv name)
    path-prefix-join
    keyword))

(defn get-rel-pk [rel]
  (let [pk-ids (:pk rel)
        pk-aliases (mapv #(get-in rel [:ids->aliases %]) pk-ids)
        projection (:projection rel)]
    (if (set/subset? projection (set pk-aliases))
      pk-aliases
      (vec (sort projection)))))

(defn infer-schema
  ([relation] (infer-schema relation nil []))
  ([relation overrides] (infer-schema relation overrides []))
  ([relation overrides path-prefix]
   (println overrides)
   (let [pk                  (->> (or (:pk overrides) (get-rel-pk relation))
                               as-vec
                               (mapv #(get-prefixed-col-name path-prefix %)))
         default-namespace   (:namespace overrides)
         namespace           (if (nil? default-namespace)
                               (->kebab-case-string (get-in relation [:spec :name]))
                               default-namespace)
         decompose-to        (get overrides :decompose-to :coll)
         columns             (reduce
                               (fn [acc col-name]
                                 (assoc acc col-name (get-prefixed-col-name path-prefix col-name)))
                               {}
                               (:projection relation))
         columns-with-joined (reduce-kv
                               (fn [acc alias join]
                                 (let [join-relation  (:relation join)
                                       join-overrides (get-in overrides [:columns alias])
                                       join-path      (conj path-prefix alias)
                                       join-schema    (infer-schema join-relation join-overrides join-path)]
                                   ;; First we build join schema and then check if this level should be omitted.
                                   ;; If it should, we still need to pick up any joins on the levels below it.
                                   ;; This is not super efficient as we'll iterate through some columns multiple
                                   ;; times, but works until a better way is figured out.
                                   (if (= :omit (:decompose-to join-schema))
                                     (let [join-relation-projection (:projection join-relation)]
                                       (reduce-kv
                                         (fn [acc' col col-schema]
                                           (if (contains? join-relation-projection col)
                                             acc'
                                             (assoc acc col col-schema)))
                                         acc
                                         (:columns join-schema)))
                                     (assoc acc alias join-schema))))
                               columns
                               (:joins relation))]
     {:pk pk
      :decompose-to decompose-to
      :namespace namespace
      :columns columns-with-joined})))