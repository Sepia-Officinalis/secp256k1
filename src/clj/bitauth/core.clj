(ns bitauth.core
  "A Clojure implementation of BitPay's BitAuth protocol

  https://github.com/bitpay/bitauth
  http://blog.bitpay.com/2014/07/01/bitauth-for-decentralized-authentication.html"

  (:require [base58.core :refer [int-to-base58] :as base58]
            [bitauth.schema :refer [Hex Base58]]
            [schema.core :as schema])
  (:import java.io.ByteArrayOutputStream
           java.security.MessageDigest
           java.security.SecureRandom
           java.util.Arrays
           org.spongycastle.asn1.ASN1InputStream
           org.spongycastle.asn1.DERInteger
           org.spongycastle.asn1.DERSequenceGenerator
           org.spongycastle.asn1.sec.SECNamedCurves
           org.spongycastle.crypto.digests.RIPEMD160Digest
           org.spongycastle.crypto.generators.ECKeyPairGenerator
           org.spongycastle.crypto.params.ECDomainParameters
           org.spongycastle.crypto.params.ECKeyGenerationParameters
           org.spongycastle.crypto.params.ECPrivateKeyParameters
           org.spongycastle.crypto.params.ECPublicKeyParameters
           org.spongycastle.crypto.signers.ECDSASigner))

(defonce
  ^:private
  ^{:doc "The secp256k1 curve object provided by SpongyCastle that is used often"}
  curve
  (let [params (SECNamedCurves/getByName "secp256k1")]
    (ECDomainParameters. (.getCurve params)
                         (.getG params)
                         (.getN params)
                         (.getH params))))

(defn- strip-0x
  "Gets rid of the leading `0x` identifier of hex strings"
  [s]
  (if (.startsWith (.toLowerCase s) "0x") (.substring s 2) s))

(defn- add-leading-zero-if-necessary
  "Adds a leading zero to a hex string if it is of odd length"
  [s]
  (if (odd? (count s)) (str "0" s) s))

(defn- hex-char-to-byte
  "Convert a single hexdecimal character to a byte"
  [c]
  (-> c (Character/digit 16) byte))

(schema/defn ^:private hex-to-array
  "Convert a string to a byte array, discarding leading zeros as necessary"
  [s :- Hex]
  (->> s
       strip-0x
       add-leading-zero-if-necessary
       (partition 2)
       (map
         (fn [[a b]]
           (+ (bit-shift-left (hex-char-to-byte a) 4)
              (hex-char-to-byte b))))
       byte-array))

(def ^:private hex-chars-string "0123456789abcdef")

(schema/defn ^:private array-to-hex :- Hex
  "Encode an collection of bytes as hex"
  [b]
  (apply str (for [x    b
                   :let [v (bit-and x 0xFF)]
                   y    [(get hex-chars-string (bit-shift-right v 4))
                         (get hex-chars-string (bit-and v 0x0F))]]
               y)))

(schema/defn ^:private hex-sha256 :- Hex
  "Get the SHA256 hash of a hex string"
  [s :- Hex]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (hex-to-array s))
      array-to-hex))

(schema/defn ^:private sha256 :- Hex
  "Get the SHA256 hash of a string"
  [s :- String]
  (-> (MessageDigest/getInstance "SHA-256")
      (.digest (.getBytes s "UTF-8"))
      array-to-hex))

(schema/defn ^:private ripemd-160-hex :- Hex
  "Get the ripemd-160-hex hash of a hex string"
  [s :- Hex]
  (let [a (hex-to-array s)
        d (doto (RIPEMD160Digest.) (.update a 0 (count a)))
        o (byte-array (.getDigestSize d))]
    (.doFinal d o 0)
    (array-to-hex o)))

(schema/defn ^:private x962-point-encode :- Hex
  "Encode a public key as hex using X9.62 compression"
  [pub-key]
  (let [x (-> pub-key .getX .toBigInteger (.toString 16))
        y-even? (-> pub-key .getY .toBigInteger even?)]
    (str (if y-even? "02" "03") x)))

(schema/defn ^:private x962-point-decode
  "Decode a public key using X9.62 compression"
  [encoded-key :- Hex]
  (->> encoded-key
       hex-to-array
       (.decodePoint (.getCurve curve))))

(schema/defn get-public-key-from-private-key :- Hex
  "Generate a public key from a private key"
  [priv-key :- Hex]
  (-> curve
      .getG
      (.multiply (BigInteger. priv-key 16))
      x962-point-encode))

(schema/defn get-sin-from-public-key :- Base58
  "Generate a SIN from a compressed public key"
  [pub-key :- Hex]
  (let [pub-hash (hex-sha256 pub-key)
        pub-prefixed (str "0f02" (ripemd-160-hex pub-hash))
        checksum (-> pub-prefixed hex-sha256 hex-sha256 (subs 0 8))]
    (int-to-base58
      (BigInteger. (str pub-prefixed checksum) 16) 0)))

(defn generate-sin
  "Generate a new private key, new public key, SIN and timestamp"
  []
  (let [key-pair
        (-> (ECKeyPairGenerator.)
            (doto (.init (ECKeyGenerationParameters. curve (SecureRandom.))))
            .generateKeyPair)
        priv (-> key-pair .getPrivate .getD (.toString 16))
        pub (get-public-key-from-private-key priv)]
    {:created (-> (System/currentTimeMillis) (/ 1000)),
     :priv priv,
     :pub pub,
     :sin (get-sin-from-public-key pub)}))

(schema/defn sign :- Hex
  "Sign some data with a private-key"
  [data :- String, priv-key :- Hex]
  (let [input (-> data sha256 hex-to-array)
        spongy-priv-key (-> priv-key
                            (BigInteger. 16)
                            (ECPrivateKeyParameters. curve))
        sigs (->
               (ECDSASigner.)
               (doto (.init true spongy-priv-key))
               (.generateSignature input))
        bos (ByteArrayOutputStream.)]
    (with-open [s (DERSequenceGenerator. bos)]
      (doto s
        (.addObject (DERInteger. (get sigs 0)))
        (.addObject (DERInteger. (get sigs 1)))))
    (-> bos .toByteArray array-to-hex)))

(schema/defn ^:private verify :- Boolean
  "Verifies the given ASN.1 encoded ECDSA signature against a hash (byte-array) using a specified public key."
  [input, pub-key :- Hex, hex-signature :- Hex]
  (let [spongy-pub-key (-> pub-key
                           x962-point-decode
                           (ECPublicKeyParameters. curve))
        signature (hex-to-array hex-signature)
        verifier (doto (ECDSASigner.) (.init false spongy-pub-key))]
    (with-open [decoder (ASN1InputStream. signature)]
      (let [sequence (.readObject decoder)
            r (-> sequence (.getObjectAt 0) .getValue)
            s (-> sequence (.getObjectAt 1) .getValue)]
        (.verifySignature verifier input r s)))))

(schema/defn verify-signature :- Boolean
  "Verifies that a string of data has been signed."
  [data :- String, pub-key :- Hex, hex-signature :- Hex]
  (try
    (verify (-> data sha256 hex-to-array) pub-key hex-signature)
    (catch Exception _ false)))

(schema/defn validate-sin :- Boolean
  "Verify that a SIN is valid"
  [sin :- Base58]
  (try
    (let [pub-with-checksum (-> sin base58/decode array-to-hex)
          len (count pub-with-checksum)
          expected-checksum (-> pub-with-checksum (subs (- len 8) len))
          actual-checksum (-> pub-with-checksum
                              (subs 0 (- len 8))
                              hex-sha256
                              hex-sha256
                              (subs 0 8))]
      (= expected-checksum actual-checksum))
    (catch Exception _ false)))
