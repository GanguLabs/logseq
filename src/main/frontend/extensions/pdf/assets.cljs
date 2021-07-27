(ns frontend.extensions.pdf.assets
  (:require [rum.core :as rum]
            [frontend.util :as utils]
            [frontend.db.model :as db-model]
            [frontend.db.utils :as db-utils]
            [frontend.handler.page :as page-handler]
            [frontend.handler.editor :as editor-handler]
            [frontend.state :as state]
            [frontend.config :as config]
            [frontend.fs :as fs]
            [medley.core :as medley]
            [frontend.components.svg :as svg]
            [cljs.reader :as reader]
            [promesa.core :as p]
            [clojure.string :as string]
            [cljs-bean.core :as bean]))

(defonce *asset-uploading? (atom false))

(defn inflate-asset
  [filename]
  (when-let [key (and
                   (string/ends-with? filename ".pdf")
                   (string/replace-first filename ".pdf" ""))]
    {:key      key
     :identity (subs key (- (count key) 15))
     :filename filename
     :url      (utils/node-path.join
                 "file://"                                  ;; TODO: bfs
                 (config/get-repo-dir (state/get-current-repo))
                 "assets" filename)

     :hls-file (str "assets/" key ".edn")}))

(defn load-hls-data$
  [{:keys [hls-file]}]
  (when hls-file
    (let [repo-cur (state/get-current-repo)
          repo-dir (config/get-repo-dir repo-cur)]
      (p/let [_ (fs/create-if-not-exists repo-cur repo-dir hls-file "{:highlights []}")
              res (fs/read-file repo-dir hls-file)
              data (if res (reader/read-string res) {})]
        data))))

(defn persist-hls-data$
  [{:keys [hls-file]} highlights]
  (when hls-file
    (let [repo-cur (state/get-current-repo)
          repo-dir (config/get-repo-dir repo-cur)
          data (pr-str {:highlights highlights})]
      (fs/write-file! repo-cur repo-dir hls-file data {:skip-mtime? true}))))

(defn resolve-hls-data-by-key$
  [target-key]
  ;; TODO: fuzzy match
  (when-let [hls-file (and target-key (str config/local-assets-dir "/" target-key ".edn"))]
    (load-hls-data$ {:hls-file hls-file})))

(defn resolve-ref-page
  [page-name]
  (let [page-name (str "hls__" page-name)
        page (db-model/get-page page-name)]
    (if-not page
      (do
        (page-handler/create! page-name {:redirect? false :create-first-block? false})
        ;; refresh to file
        (editor-handler/api-insert-new-block! page-name {:page page-name})
        (db-model/get-page page-name))
      page)))

(defn create-ref-block!
  [{:keys [id content]}]
  (when-let [pdf-current (:pdf/current @state/state)]
    (when-let [ref-page (resolve-ref-page (:key pdf-current))]
      (if-let [ref-block (db-model/get-block-by-uuid id)]
        (do
          (js/console.debug "[existed ref block]" ref-block)
          ref-block)
        (let [text (:text content)]                         ;; TODO: image
          (editor-handler/api-insert-new-block!
            text {:page        (:block/name ref-page)
                  :custom-uuid id
                  :properties  {:type "annotation"
                                :id   (str id)              ;; force custom uuid
                                }}))))))

(defn del-ref-block!
  [{:keys [id]}]
  (when-let [repo (state/get-current-repo)]
    (when-let [block (db-model/get-block-by-uuid id)]
      (editor-handler/delete-block-aux! block true))))

(defn copy-hl-ref!
  [highlight]
  (when-let [ref-block (create-ref-block! highlight)]
    (utils/copy-to-clipboard! (str "((" (:block/uuid ref-block) "))"))))

(defn upload-asset!
  [page-block files refresh-file!]
  (let [repo (state/get-current-repo)
        block-uuid (:block/uuid page-block)]

    (when (config/local-db? repo)
      (reset! *asset-uploading? true)
      (-> (editor-handler/save-assets! page-block repo (bean/->clj files))
          (p/then
            (fn [res]
              (when-let [first-item (first res)]
                (let [[file-path file] first-item]
                  (refresh-file! (utils/node-path.basename file-path))))))
          (p/finally
            (fn []
              (reset! *asset-uploading? false))))
      )))

(defn open-block-ref!
  [block]
  (let [id (:block/uuid block)
        page (db-utils/pull (:db/id (:block/page block)))
        page-name (:block/original-name page)]
    (when-let [target-key (and page-name (subs page-name 5))]
      (p/let [hls (resolve-hls-data-by-key$ target-key)
              hls (and hls (:highlights hls))]
        (if-let [matched (and hls (medley/find-first #(= id (:id %)) hls))]
          (do
            (state/set-state! :pdf/ref-highlight matched)
            ;; open pdf viewer
            (state/set-state! :pdf/current (inflate-asset (str target-key ".pdf"))))
          (js/console.warn "[Unmatched highlight ref]" block))))))

(rum/defc uploader
  [page-name]
  (when-let [page (and page-name (db-model/get-page page-name))]
    (let [page-uuid (:block/uuid page)
          [files, set-files!] (rum/use-state (get-in page [:block/properties :files]))
          files (if (string? files) [files] files)
          refresh-file! (rum/use-callback
                          (fn [file-name]
                            (let [files' (if-not (vector? files)
                                           [file-name]
                                           (conj files file-name))]

                              ;; sync
                              (editor-handler/set-block-property!
                                page-uuid
                                :files files'               ;;(string/join "," files')
                                )

                              (let [props (db-model/get-page-properties page-name)]
                                (set-files! (:files props)))))
                          [files])]

      (let [block-uuid (:block/uuid page)]
        [:div.extensions__pdf-assets-uploader
         (for [file files]
           [:a.ui__button
            {:key      file
             :intent   "logseq"
             :on-click (fn []
                         (when-let [current (inflate-asset file)]
                           (state/set-state! :pdf/current current)))}
            svg/external-link
            file])

         [:label.ui__button.is-link
          {:for "upload-page-assets"}

          svg/plus

          [:input.hidden
           {:id     "upload-page-assets"
            :type   "file"
            :accept ".pdf"
            :on-change
                    (fn [e]
                      (let [files (.-files (.-target e))]
                        (upload-asset! page files refresh-file!))
                      )}]]]))))