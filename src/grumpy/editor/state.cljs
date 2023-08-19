(ns grumpy.editor.state)


; {:progress :: 0..1     | nil
;  :error    :: <string> | nil
;  :message} :: <string> | nil

(defonce *media-status
  (atom nil))


; {:dragging? :: true | nil
;  :dragover? :: true | nil
;  :dropped?} :: true | nil

(defonce *media-drag
  (atom nil))


; {:post/id             :: <int> | nil
;  :post/author         :: <string>
;  :post/body           :: <string>
;  :post/media          :: | nil
;  {:media/url          :: <string>
;   :media/object-url   :: <string> | nil
;   :media/content-type :: <string>
;   :media/width        :: <int>
;   :media/height}      :: <int>
;  :post/media-full {}} :: same as :post/media

(defonce *post
  (atom nil))


; {:status :: nil | :publishing
;  :error} :: <string> | nil

(defonce *status
  (atom nil))
