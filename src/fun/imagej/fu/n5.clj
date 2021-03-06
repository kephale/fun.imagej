(ns fun.imagej.fu.n5
  (:require [fun.imagej.img :as img]
            [clojure.string :as string])
  (:import [org.janelia.saalfeldlab.n5.imglib2 N5Utils]
           [org.janelia.saalfeldlab.n5 N5FSReader N5FSWriter GzipCompression]
           (java.util.concurrent Executors)
           (net.imglib2.view Views)))

(defn block-size
  "Return the block size of a dataset in a n5"
  [n5 dataset]
  (.getAttribute
    n5
    dataset
    "blockSize"
    (class (long-array 0))))

(defn dimensions
  "Return the dimensions of a dataset in a n5"
  [n5 dataset]
  (.getAttribute
    n5
    dataset
    "dimensions"
    (class (long-array 0))))

(defn open-with-disk-cache
  "Open a N5 dataset with a disk cache"
  [filename data-dir]
  (let [n5 (N5FSReader. filename)]
    (N5Utils/openWithDiskCache n5 data-dir)))

(defn initialize-memory
  "Initialize the memory of a n5 cache from file.
  keys are image hashes
  values are n5 internal paths/keys
  Returns the value of (:memory n5-cache)"
  [reader]
  (atom {}))

(defn open-n5-cache
  "Create a new cache backed by a N5 dataset.
  Filename is the .n5 and data-directory is where the data actually is"
  [filename]
  (let [writer (N5FSWriter. filename)
        reader (N5FSReader. filename)]
    {:reader reader
     :writer writer
     :memory (initialize-memory reader)}))

(defn image?
  "Is a thing an image?"
  [thing]
  (instance? net.imglib2.RandomAccessibleInterval thing))

(defn get-memory-key
  "Currently wraps hash"
  [x]
  (hash x))

(defn discover-path
  "Discover the path that corresponds to an image"
  [n5-cache im]
  (@(:memory n5-cache) (get-memory-key im)))

(defn contains-image?
  "Discover the key that corresponds to an image"
  [n5-cache im]
  (and (discover-path n5-cache im); Everything is currently assumed to be loaded into in-memory
       (.exists (:reader n5-cache)
                (discover-path n5-cache im)))); The path for the image exists and is readable

(defn args-to-n5-path
  "Construct a n5 path from a function and its args"
  [n5-cache f args]
  (string/join java.io.File/separator
               (concat [(.getName (class f))]
                       (map #(str (cond (number? %) %
                                        (string? %) %
                                        ; If an image was opened or computed with this n5-cache, then we can track its filepath
                                        (and (image? %) (contains-image? n5-cache %)) (discover-path n5-cache %)
                                        :else (hash %)))
                            args))))

; If you do an in-memory *and* disk memoize, then it is possible to
;   create a cache that has composite elements that are expressed in terms of existing
;   variables. Ignore the dependency complexity for now, assume well-behaved coders and loop-free usage.

(defn memoize-with-n5-cache
  "Returns a memoized version of a referentially transparent function. The
  memoized version of the function keeps a cache of the mapping from arguments
  to results and, when calls with the same arguments are repeated often, has
  higher performance at the expense of higher memory use. [From clojure.core added v1.0]"
  {:static true}
  [f n5-cache]
  (let [exec (Executors/newFixedThreadPool 4)]
    (fn [& args]
      (let [im
            (if-let [e (when (.exists (:reader n5-cache)
                                      (args-to-n5-path n5-cache f args))
                         (N5Utils/open (:reader n5-cache)
                                       (args-to-n5-path n5-cache f args)))]
              e
              (let [ret (apply f args)]; This is an image that needs to be saved to n5
                (N5Utils/save ret
                              (:writer n5-cache)
                              (args-to-n5-path n5-cache f args)
                              (int-array (repeat (img/num-dimensions ret) 64))
                              (GzipCompression. 6)
                              exec)
                ret))]
        (swap! (:memory n5-cache)
               assoc (get-memory-key im) (args-to-n5-path n5-cache f args))
        im))))

(defn copy-crop
  "Copy a crop of source-dataset in source-n5 into destination-dataset of destination-n5"
  [source-n5 source-dataset interval destination-n5 destination-dataset]
  (let [source (N5Utils/open source-n5 source-dataset)
        crop (Views/interval source interval)
        block-size (block-size source-n5 source-dataset)]
    (N5Utils/save crop
                  destination-n5
                  destination-dataset
                  block-size
                  (GzipCompression.))))