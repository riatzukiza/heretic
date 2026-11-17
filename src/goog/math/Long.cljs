(ns goog.math.Long
  "64-bit integer shim for nbb. Closure's goog.math.Long is absent there.
   test.check's RNG uses fromBits, fromNumber, fromString, getOne, and the
   low_/high_ fields plus add, xor, or, shiftRightUnsigned,
   getLowBitsUnsigned, and getHighBits."
  (:require [nbb.core :as nbb]
            [sci.core :as sci]))

(def ^:private bits
  (js/eval (str "({mask:(1n<<64n)-1n,"
                "combine:function(low,high){"
                "var l=BigInt.asUintN(32,BigInt(low));"
                "var h=BigInt.asUintN(32,BigInt(high));"
                "return (h<<32n)|l;},"
                "parts:function(n){"
                "n=BigInt.asUintN(64,n);"
                "return [Number(BigInt.asIntN(32,n)), Number(BigInt.asIntN(32,n>>32n))];},"
                "add:function(a,b){return (a+b)&((1n<<64n)-1n);},"
                "xor:function(a,b){return a^b;},"
                "or:function(a,b){return a|b;},"
                "ushr:function(a,n){return a>>BigInt(n);}})")))

(def ^:private Ctor
  (js/Function. "low" "high"
                "this.low_=low|0; this.high_=high|0;"))

(defn- value-of [x]
  (.combine bits (.-low_ x) (.-high_ x)))

(defn- box [n]
  (let [[low high] (js->clj (.parts bits n))
        obj (new Ctor low high)]
    (set! (.-add obj) (fn [other] (box (.add bits (value-of obj) (value-of other)))))
    (set! (.-xor obj) (fn [other] (box (.xor bits (value-of obj) (value-of other)))))
    (set! (.-or obj) (fn [other] (box (.or bits (value-of obj) (value-of other)))))
    (set! (.-shiftRightUnsigned obj)
          (fn [n] (box (.ushr bits (value-of obj) n))))
    (set! (.-getLowBitsUnsigned obj)
          (fn [] (js/Number (js/BigInt.asUintN 32 (value-of obj)))))
    (set! (.-getHighBits obj) (fn [] (.-high_ obj)))
    obj))

(defn fromBits [low high]
  (box (.combine bits low high)))

(defn fromNumber [n]
  (box (js/BigInt (js/Math.trunc n))))

(defn fromString [s radix]
  (box (js/BigInt (js/parseInt s radix))))

(defn getOne [] (fromBits 1 0))

(defn getZero [] (fromBits 0 0))

(sci/add-class! (nbb/get-sci-ctx) 'goog.math.Long Ctor)
