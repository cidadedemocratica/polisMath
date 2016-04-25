(ns polismath.export
  (:require [taoensso.timbre.profiling :as profiling
               :refer (pspy pspy* profile defnp p p*)]
            [clojure.java.io :as io]
            [korma.core :as ko]
            [korma.db :as kdb]
            [polismath.db :as db]
            [polismath.utils :as utils]
            [polismath.clusters :as clust]
            [polismath.named-matrix :as nm]
            [polismath.microscope :as micro]
            [polismath.conversation :as conv]
            [clojure.math.numeric-tower :as math]
            [clojure.core.matrix :as mat]
            [clj-time.core :as t]
            [clj-time.coerce :as co]
            ;; Think I'm going to use the second one here, since it's simpler (less mutable)
            [dk.ative.docjure.spreadsheet :as spreadsheet]
            [clj-excel.core :as excel]
            [semantic-csv.core :as scsv]
            [clojure-csv.core :as csv]
            [clojure.pprint :refer [pprint]]
            [clojure.core.matrix :as mat]
            [clojure.tools.trace :as tr]
            [clojure.tools.logging :as log]
            [clojure.newtools.cli :refer [parse-opts]])
  (:import [java.util.zip ZipOutputStream ZipEntry]))

(mat/set-current-implementation :vectorz)

;; Here's rougly what we want for data export. We have the following sheets per conversation.
;; 
;; Summary:
;; * N Views
;; * N Voters
;; * N Voters "in conv"
;; * N Commenters
;; * N Groups
;; * url
;; 
;; 
;; Stats History
;; time, votes, comments, unique-hits, voters, commenters
;; 
;; 
;; Votes (full votes matrix):
;; participant-id, group-id, n-votes, n-comments, n-aggre, n-disagree, <comments...>
;; 
;; 
;; Comments:
;; cid, author, aggrees, disagrees, mod, text



;; Database calls for various things

(defn get-zids-for-uid
  [uid]
  (map :zid
    (kdb/with-db (db/db-spec)
      (ko/select "conversations"
        (ko/fields :zid)
        (ko/where {:owner uid})))))

;(get-zids-for-uid 118877)


(defn get-zinvite-from-zid
  [zid]
  (-> 
    (kdb/with-db (db/db-spec)
      (ko/select "zinvites"
        (ko/fields :zid :zinvite)
        (ko/where {:zid zid})))
    first
    :zinvite))


(defn get-conversation-votes*
  ([zid]
   (kdb/with-db (db/db-spec)
     (ko/select db/votes
       (ko/where {:zid zid})
       (ko/order [:zid :tid :pid :created] :asc))))
  ([zid final-vote-timestamp]
   (kdb/with-db (db/db-spec)
     (ko/select db/votes
       (ko/where {:zid zid :created [<= final-vote-timestamp]})
       ; ordering by tid is important, since we rely on this ordering to determine the index within the comps, which needs to correspond to the tid
       (ko/order [:zid :tid :pid :created] :asc)))))

(defn get-conversation-votes
  [& args]
  ;; Flip the signs on the votes XXX (remove when we switch)
  (map #(update-in % [:vote] (partial * -1))
       (apply get-conversation-votes* args)))

(defn get-conversation-data
  "Return a map with :topic and :description keys"
  [zid]
  (->
    (kdb/with-db (db/db-spec)
      (ko/select "conversations"
        (ko/fields :zid :topic :description :created)
        (ko/where {:zid zid})))
    first))

(defn get-participation-data
  ([zid]
   (kdb/with-db (db/db-spec)
     (ko/select "participants"
       (ko/fields :zid :pid :vote_count :created)
       (ko/where {:zid zid}))))
  ([zid final-timestamp]
   (kdb/with-db (db/db-spec)
     (ko/select "participants"
       (ko/fields :zid :pid :vote_count :created)
       (ko/where {:zid zid :created [<= final-timestamp]})))))



(defn get-comments-data
  ([zid]
   (kdb/with-db (db/db-spec)
     (ko/select "comments"
       (ko/fields :zid :tid :pid :txt :mod :created)
       (ko/where {:zid zid}))))
  ([zid final-timestamp]
   (kdb/with-db (db/db-spec)
     (ko/select "comments"
       (ko/fields :zid :tid :pid :txt :mod :created)
       (ko/where {:zid zid :created [<= final-timestamp]})))))



;; First the summary data
;; ======================

(defn- count-distinct-col
  ([data]
   (->> data distinct count))
  ([f data]
   (->> data (map f) distinct count)))

(defn summary-data
  "Takes in rating matrix and set of all votes, and computes the summary stats for the
  conversation"
  [{:keys [n n-cmts group-clusters base-clusters zid rating-mat] :as conv}
   votes
   comments-data
   participants]
  ;; Fire off a bunch of database calls
  (let [zinvite (future (get-zinvite-from-zid zid))
        conv-data (future (get-conversation-data zid))
        ;; Do anything needed with the data to prep
        {:keys [topic description]} @conv-data
        url (str "https://pol.is/" @zinvite)]
    ;; Return the table of stuff to go into excel
    {:topic        topic
     :url          url
     :n-views      (count-distinct-col :pid participants)
     :n-voters     (count-distinct-col :pid votes)
     :n-voters-in  n
     :n-commenters (count-distinct-col :pid comments-data)
     :n-comments   n-cmts
     :n-groups     (count group-clusters)
     :description  description}))

(defn render-summary-with
  "Intended for formatting the summary data's psuedo-headers for either excel or csv. Takes the
  data as produced by summary data, and a key-mapping collection of keys as in the data to header
  names to output, returning a collection of rows to be spit out."
  [data key-mapping]
  (for [[k v] key-mapping]
    [v (get data k)]))



;; Now the history data
;; ====================

(defn merge-histories
  [votes participants comments]
  (sort-by :created
           (mapcat
             (fn [collection tag]
               (map #(assoc % :tag tag) collection))
             [votes participants comments]
             [:vote :participant :comment])))

(defmulti update-history*
  (fn [last-history datom] (:tag datom)))

(defn update-history
  "For stats-history; takes the last history value and updates it with the given record. Delegates
  to a multimethod which dispatches on :tag, to determine what values need to be updated, and how."
  [history datom]
  (conj history
        (-> datom
            (->> (update-history* (or (last history)
                                   {:n-votes 0 :n-comments 0 :n-visitors 0 :n-voters 0 :n-commenters 0 :ctxt {:voters #{}
                                                                                                            :commenters #{}}})))
            (assoc :time (:created datom)))))

(defmethod update-history* :vote
  [last-history datom]
  (-> last-history
      (update-in [:n-votes] inc)
      (update-in [:n-voters]
                 (fn [n-voters] (if-not (get-in datom [:ctxt :voters (:pid datom)])
                                  (inc n-voters)
                                  n-voters)))
      (update-in [:ctxt :voters] conj (:pid datom))))

(defmethod update-history* :participant
  [last-history datom]
  (-> last-history
      (update-in [:n-visitors] inc)))

(defmethod update-history* :comment
  [last-history datom]
  (-> last-history
      (update-in [:n-comments] inc)
      (update-in [:n-commenters]
                 (fn [n-commenters] (if-not (get-in datom [:ctxt :commenters (:pid datom)])
                                      (inc n-commenters)
                                      n-commenters)))
      (update-in [:ctxt :commenters] conj (:pid datom))))

(defn stats-history
  "Returns rows of {time, n-votes, n-comments, n-visitors, n-voters, n-commenters}"
  [votes participants comments]
  (reduce update-history [] (merge-histories votes participants comments)))



;; Full votes matrix (plus some other participant summaries)
;; =================

(defn reconstruct-vote-matrix
  [votes]
  (let [new-nmat (nm/named-matrix)
        vote-tuples (map #(map % [:pid :tid :vote]) votes)]
    (nm/update-nmat new-nmat vote-tuples)))

(defn ffilter
  "Given pred and coll, return the first x in coll for which (pred x) is truthy"
  [pred coll]
  (first (filter pred coll)))

(defn flatten-clusters
  "Takes group clusters and base clusters and flattens them out into a cluster mapping to ptpt ids directly"
  [group-clusters base-clusters]
  (map
    (fn [gc]
      (update-in gc
                 [:members]
                 (fn [members]
                   (mapcat
                     (fn [bid]
                       ;; get the base cluster, then get it's members, mapcat them (a level up)
                       (:members (ffilter #(= (:id %) bid) base-clusters)))
                     members))))
    group-clusters))

;; participant-id, group-id, n-votes, n-comments, n-aggre, n-disagree, <comments...>
(defn participants-votes-table
  [conv votes comments]
  (let [mat (reconstruct-vote-matrix votes)
        flattened-clusters (flatten-clusters (:group-clusters conv) (:base-clusters conv))]
    (concat
      ;; The header
      [(into ["participant" "group-id" "n-comments" "n-votes" "n-agree" "n-disagree"] (nm/colnames mat))]
      ;; The rest of the data
      (map
        (fn [ptpt row]
          (into [ptpt
                 (:id (ffilter #(some #{ptpt} (:members %)) flattened-clusters))
                 (count (filter #(= (:pid %) ptpt) comments))
                 (count (remove nil? row))
                 ;; XXX God damn aggree vs disagree...
                 ;; Fixed this upstream, for now; so should be good to go once we've fixed it at the source. But
                 ;; keep an eye on it for now... XXX
                 (count (filter #{1} row))
                 (count (filter #{-1} row))]
                row))
        (nm/rownames mat)
        (.matrix mat)))))

(defn format-vote-matrix-header
  "Apply format-header function to each element of header in a collection of row vectors"
  [data format-header]
  (concat [(mapv format-header (first data))]
          (rest data)))


;; Comments
;; ========


(defn enriched-comments-data
  "Just adds vote counts to the comments data"
  [comments votes]
  (map
    (fn [{:keys [tid] :as comment-data}]
      (let [comment-votes (filter #(= tid (:tid %)) votes)
            ;; Fixed this upstream, for now; so should be good to go once we've fixed it at the source. But
            ;; keep an eye on it for now... XXX
            aggrees (filter #(= 1 (:vote %)) comment-votes)
            disagrees (filter #(= -1 (:vote %)) comment-votes)]
        (assoc comment-data :aggrees (count aggrees) :disagrees (count disagrees))))
    comments))


;; Now some reshaping stuff for excel; mostly just applying headers here
;; Actaul excel things

(defn stringify-keys
  ([m]
   (stringify-keys m #(-> % str (clojure.string/replace ":" ""))))
  ([m f]
   (into {} (map (fn [[k v]]
                   [(if-not (string? k) (f k) k) v])
                 m))))


(defn update-from-map-or-leave
  "Little utility for generating a function that either updates based on a map (closed over) or leaves value unchanged"
  [m]
  (fn [k]
    (if-let [v (get m k)]
      v
      k)))

(defn excel-format
  [export-data]
  (-> export-data
      (update-in [:summary]
                 render-summary-with
                 [[:topic        "Topic"]
                  [:url          "URL"]
                  [:n-views      "Views"]
                  [:n-voters     "Voters"]
                  [:n-voters-in  "Voters (in conv)"]
                  [:n-commenters "Commenters"]
                  [:n-comments   "Comments"]
                  [:n-groups     "Groups"]
                  [:description  "Conversation Description"]])
      (update-in [:stats-history]
                 (partial scsv/vectorize {:header [:n-votes :n-comments :n-visitors :n-voters :n-commenters]
                                          :format-header {:n-votes      "Votes"
                                                          :n-visitors   "Visitors"
                                                          :n-voters     "Voters"
                                                          :n-comments   "Comments"
                                                          :n-commenters "Commenters"}}))
      (update-in [:comments]
                 (partial scsv/vectorize {:header [:tid :pid :aggrees :disagrees :mod :txt]
                                          :format-header {:tid       "Comment ID"
                                                          :pid       "Author"
                                                          :aggrees   "Aggrees"
                                                          :disagrees "Disagrees"
                                                          :mod       "Moderated"
                                                          :txt       "Comment body"}}))
      (update-in [:participants-votes]
                 format-vote-matrix-header
                 ;; flesh out...
                 (update-from-map-or-leave {"participant" "Participant"
                                            "group-id"    "Group ID"
                                            "n-comments"  "Comments"
                                            "n-votes"     "Votes"
                                            "n-agree"     "Agrees"
                                            "n-disagree"  "Disagrees"}))
      (stringify-keys (array-map :summary "Summary"
                                 :stats-history "Stats History"
                                 :comments "Comments"
                                 :participants-votes "Participants Votes"))))


(defn csv-format
  [export-data]
  (-> export-data
      (update-in [:summary]
                 render-summary-with
                 [[:topic        "topic"]
                  [:url          "url"]
                  [:n-views      "views"]
                  [:n-voters     "voters"]
                  [:n-voters-in  "voters-in-conv"]
                  [:n-commenters "commenters"]
                  [:n-comments   "comments"]
                  [:n-groups     "groups"]
                  [:description  "conversation-description"]])
      (update-in [:stats-history]
                 (partial scsv/vectorize {:header [:n-votes :n-comments :n-visitors :n-voters :n-commenters]}))
      (update-in [:comments]
                 (partial scsv/vectorize {:header [:tid :pid :aggrees :disagrees :mod :txt]
                                          :format-header {:tid       "comment-id"
                                                          :pid       "author-id"
                                                          :aggrees   "agrees"
                                                          :disagrees "disagrees"
                                                          :mod       "moderated"
                                                          :txt       "comment-body"}}))))



;; This zip nonsense is stolen from http://stackoverflow.com/questions/17965763/zip-a-file-in-clojure
(defmacro ^:private with-entry
  [zip entry-name & body]
  `(let [^ZipOutputStream zip# ~zip]
     (.putNextEntry zip# (ZipEntry. ~entry-name))
     ~@body
     (flush)
     (.closeEntry zip#)))

(defn move-to-zip-stream
  [zip-stream input-filename entry-point]
  (with-open [input  (io/input-stream input-filename)]
    (with-entry zip-stream entry-point
      (io/copy input zip-stream))))

(defn print-csv
  [table]
  (->> table
       (map (partial mapv str))
       csv/write-csv
       print))

(defn zipfile-basename
  [filename]
  (-> filename 
      (clojure.string/split #"\/")
      last
      (clojure.string/replace #"\.zip$" "")))


;; Must assert .zip in filenames or things will break on unzipping XXX
(defn save-to-csv-zip
  ([filename data]
   (with-open [file (io/output-stream filename)
               zip  (ZipOutputStream. file)]
     (save-to-csv-zip zip (zipfile-basename filename) data)))
  ([zip-stream entry-point-base data]
   (with-open [wrt  (io/writer zip-stream)]
     (binding [*out* wrt]
       (doto zip-stream
         (with-entry (str entry-point-base "/summary.csv")
           (print-csv (:summary data)))
         (with-entry (str entry-point-base "/stats-history.csv")
           (print-csv (:stats-history data)))
         (with-entry (str entry-point-base "/comments.csv")
           (print-csv (:comments data)))
         (with-entry (str entry-point-base "/participants-votes.csv")
           (print-csv (:participants-votes data))))))))

   
(defn save-to-excel
  ([filename data]
   (-> (excel/build-workbook data)
       (excel/save filename)))
  ;; Should really change both this and the above to use the .zip filename, and take basename for the main dir
  ;; XXX
  ([zip-stream entry-point data]
   ;; Would be nice if we could write directly to the zip stream, but the excel library seems to be doing
   ;; weird things...
   (let [tmp-file-path (str "tmp/rand-" (rand-int Integer/MAX_VALUE) ".xml")]
     (save-to-excel tmp-file-path data)
     (move-to-zip-stream zip-stream tmp-file-path entry-point))))


;; Putting it all together
;; =======================

(defn get-export-data
  [{:keys [zid zinvite env-overrides] :as kw-args}]
  (let [zid (or zid (micro/get-zid-from-zinvite zinvite))
        ;; assert zid
        votes (get-conversation-votes zid)
        comments (enriched-comments-data (get-comments-data zid) votes)
        participants (get-participation-data zid)
        ;; Should factor out into separate function
        conv (utils/apply-kwargs micro/load-conv kw-args)]
    {:summary (summary-data conv votes comments participants)
     :stats-history (stats-history votes participants comments)
     :participants-votes (participants-votes-table conv votes comments)
     :comments comments}))


(defn get-export-data-at-date
  [{:keys [zid zinvite env-overrides at-date] :as kw-args}]
  (let [zid (or zid (micro/get-zid-from-zinvite zinvite))
        votes (get-conversation-votes zid at-date)
        conv (assoc (conv/new-conv) :zid zid)
        conv (conv/conv-update conv votes)
        _ (println "Done with conv update")
        comments (enriched-comments-data (get-comments-data zid at-date) votes)
        participants (get-participation-data zid at-date)
        ]
    {:summary (assoc (summary-data conv votes comments participants) :at-date at-date)
     :stats-history (stats-history votes participants comments)
     :participants-votes (participants-votes-table conv votes comments)
     :comments comments}))



(defn export-conversation
  "This is the main API endpoint for the export functionality. Given either :zid or :zinvite, export data to
  the specified :format and spit results out to :filename. Optionally, a :zip-stream and :entry point may be
  specified, which can be used for biulding up items in a zip file. This is used in export/-main to export all
  convs for a given uid, for example."
  ;; Don't forget env-overrides {:math-env "prod"}; should clean up with system
  [{:keys [zid zinvite format filename zip-stream entry-point env-overrides at-date] :as kw-args}]
  (log/info "Exporting data for zid =" zid ", zinvite =" zinvite)
  (let [export-data (if at-date 
                      (get-export-data-at-date kw-args)
                      (get-export-data kw-args))
        [formatter saver] (case format :excel [excel-format save-to-excel] :csv [csv-format save-to-csv-zip])
        formatted (formatter export-data)]
    (if zip-stream
      (if (-> export-data :summary :n-voters (> 0))
        (saver zip-stream entry-point formatted)
        (println "Skipping conv" zid zinvite ", since no votes"))
      (saver filename formatted))))


(defn parse-date
  [s]
  (->> (clojure.string/split s #"\s+")
       (map #(Integer/parseInt %))
       (apply t/date-time)
       co/to-long))


(def cli-options
  [["-z" "--zid ZID"           "ZID on which to do a rerun" :parse-fn #(Integer/parseInt %)]
   ["-Z" "--zinvite ZINVITE"   "ZINVITE code on which to perform a rerun"]
   ["-u" "--user-id USER_ID"   "Export all conversations associated with USER_ID, and place in zip file" :parse-fn #(Integer/parseInt %)]
   ["-a" "--at-date AT_DATE"   "A string of YYYY MM DD HH MM SS (in UTC)" :parse-fn parse-date]
   ["-f" "--format FORMAT"     "Either csv, excel or (soon) json" :parse-fn keyword :validate [#{:csv :excel} "Must be either csv or excel"]]
   ;; -U ;utc offset?
   ["-h" "--help"              "Print help and exit"]])

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(defn help-msg [options]
  (str "Export a conversation or set of conversations according to the options below:\n\n"
       \tab
       "filename" \tab "Filename (or file basename, in case of zip output, implicit or explicit" \newline
       (clojure.string/join \newline
                            (for [opt cli-options]
                              (apply str (interleave (repeat \tab) (take 3 opt)))))))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (println options arguments)
    (cond
      (:help options) (exit 0 (help-msg cli-options))
      errors          (exit 1 (error-msg errors)))
    (let [filename (first arguments)
          options (assoc options :env-overrides {:math-env "prod"} :filename filename)]
      (if-let [uid (:user-id options)]
        ;; maybe here check if filename ends in zip and add if not; safest, and easiest... XXX
        (with-open [file (io/output-stream filename)
                    zip  (ZipOutputStream. file)]
          (doseq [zid (get-zids-for-uid uid)]
            (let [zinvite (get-zinvite-from-zid zid)
                  ext (case (:format options) :excel "xls" :csv "csv")]
              (println "Now working on conv:" zid zinvite)
              (export-conversation (assoc options
                                          :zid zid
                                          :zip-stream zip
                                          :entry-point (str (zipfile-basename filename) "/" zinvite "." ext))))))
        (export-conversation options))
      (exit 0 "Export complete"))))


:ok

