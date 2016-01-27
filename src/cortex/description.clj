(ns cortex.description
  (:require [cortex.layers :as layers]
            [cortex.impl.layers :as impl]
            [cortex.core :as core]))


(defn input
  ([output-size] [{:type :input :output-size output-size}])
  ([width height channels] [{:type :input :output-size (* width height channels)
                             :output-width width
                             :output-height height
                             :output-channels channels}]))

(defn linear [num-output] [{:type :linear :output-size num-output}])

(defn softmax [num-classes] [{:type :linear :output-size num-classes}
                             {:type :softmax}])

(defn relu [] [{:type :relu}])
(defn linear->relu [num-output] [{:type :linear :output-size num-output}
                                 {:type :relu}])

(defn logistic [] {:type :logistic})
(defn linear->logistic [num-output] [{:type :linear :output-size num-output}
                                     {:type :logistic}])


(defn convolutional
  ([kernel-width kernel-height pad-x pad-y stride-x stride-y num-kernels]
   (when (or (= 0 stride-x)
             (= 0 stride-y))
     (throw (Exception. "Convolutional layers must of stride >= 1")))
   (when (or (= 0 kernel-width)
             (= 0 kernel-height))
     (throw (Exception. "Convolutional layers must of kernel dimensions >= 1")))
   (when (= 0 num-kernels)
     (throw (Exception. "Convolutional layers must of num-kernels >= 1")))
   [{:type :convolutional :kernel-width kernel-width :kernel-height kernel-height
     :pad-x pad-x :pad-y pad-y :stride-x stride-x :stride-y stride-y
     :num-kernels num-kernels}])
  ([kernel-dim pad stride num-kernels]
   (convolutional kernel-dim kernel-dim pad pad stride stride num-kernels)))


(defn max-pooling
  ([kernel-width kernel-height pad-x pad-y stride-x stride-y]
   (when (or (= 0 stride-x)
             (= 0 stride-y))
     (throw (Exception. "Convolutional layers must of stride >= 1")))
   (when (or (= 0 kernel-width)
             (= 0 kernel-height))
     (throw (Exception. "Convolutional layers must of kernel dimensions >= 1")))
   [{:type :max-pooling :kernel-width kernel-width :kernel-height kernel-height
     :pad-x pad-x :pad-y pad-y :stride-x stride-x :stride-y stride-y}])
  ([kernel-dim pad stride]
   (max-pooling kernel-dim kernel-dim pad pad stride stride)))


(def example-mnist-description
  [(input 28 28 1)
   (convolutional 5 0 1 20)
   (max-pooling 2 0 2)
   (convolutional 5 0 1 50)
   (max-pooling 2 0 2)
   (linear->relu 500)
   (softmax 10)])


(defmulti build-desc (fn [result item]
                       (:type item)))

(defmethod build-desc :input
  [previous item]
  item)

(defmethod build-desc :linear
  [previous item]
  (let [input-size (:output-size previous)]
    (assoc item :input-size input-size)))

(defn carry-image-dims-forward
  [previous item]
  (if-let [channels (:output-channels previous)]
    (assoc item :output-channels channels
           :output-width (:output-width previous)
           :output-height (:output-height previous))
    item))

;;Pure activation layers can be placed on images as well as
;;on vectors.
(defmethod build-desc :relu
  [previous item]
  (let [io-size (:output-size previous)]
    (assoc (carry-image-dims-forward previous item)
           :input-size io-size :output-size io-size)))

(defmethod build-desc :logistic
  [previous item]
  (let [io-size (:output-size previous)]
    (assoc (carry-image-dims-forward previous item)
           :input-size io-size :output-size io-size)))


(defmethod build-desc :softmax
  [previous item]
  (let [io-size (:output-size previous)]
    (assoc item :input-size io-size :output-size io-size)))


(defmethod build-desc :convolutional
  [previous item]
  ;;unpack the item
  (let [{:keys [kernel-width kernel-height pad-x pad-y stride-x stride-y
                num-kernels]} item
        input-width (:output-width previous)
        input-height (:output-height previous)
        input-channels (:output-channels previous)
        output-width (impl/get-padded-strided-dimension input-width pad-x kernel-width stride-x)
        output-height (impl/get-padded-strided-dimension input-height pad-y kernel-height stride-y)
        output-channels num-kernels
        output-size (* output-width output-height output-channels)]
    (assoc item :input-width input-width :input-height input-height :input-channels input-channels
           :output-width output-width :output-height output-height :output-channels output-channels
           :output-size output-size)))

(defmethod build-desc :max-pooling
  [previous item]
  (let [{:keys [kernel-width kernel-height pad-x pad-y stride-x stride-y]} item
        input-width (:output-width previous)
        input-height (:output-height previous)
        input-channels (:output-channels previous)
        output-width (impl/get-padded-strided-dimension input-width pad-x kernel-width stride-x)
        output-height (impl/get-padded-strided-dimension input-height pad-y kernel-height stride-y)
        output-channels input-channels
        output-size (* output-width output-height output-channels)]
    (assoc item :input-width input-width :input-height input-height :input-channels input-channels
           :output-width output-width :output-height output-height :output-channels output-channels
           :output-size output-size)))

(defmulti create-module :type)

(defmethod create-module :input [desc] nil)

(defmethod create-module :linear
  [desc]
  (layers/linear-layer (:input-size desc) (:output-size desc)))

(defmethod create-module :logistic
  [desc]
  (layers/logistic [(:output-size desc)]))

(defmethod create-module :relu
  [desc]
  (layers/relu [(:output-size desc)]))

(defmethod create-module :softmax
  [desc]
  (layers/softmax [(:output-size desc)]))

(defmethod create-module :convolutional
  [{:keys [input-width input-height input-channels
           kernel-width kernel-height pad-x pad-y
           stride-x stride-y num-kernels]}]
  (layers/convolutional input-width input-height input-channels
                        kernel-width kernel-height pad-x pad-y
                        stride-x stride-y num-kernels))

(defmethod create-module :max-pooling
  [{:keys [input-width input-height input-channels
           kernel-width kernel-height pad-x pad-y
           stride-x stride-y]}]
  (layers/max-pooling input-width input-height input-channels
                      kernel-width kernel-height pad-x pad-y
                      stride-x stride-y))



(defn build-full-network-description
  "build step verifies the network and fills in the implicit entries calculating
  things like the convolutional layer's output size."
  [input-desc-seq]
  (let [input-desc-seq (flatten input-desc-seq)]
    (reduce (fn [accum item]
              (let [previous (last accum)]
                (conj accum (build-desc previous item))))
            [(first input-desc-seq)]
            (rest input-desc-seq))))



(defn create-network
  "Create the live network modules from the built description"
  [built-descriptions]
  (let [modules (filterv identity (map create-module built-descriptions))]
    (core/stack-module modules)))