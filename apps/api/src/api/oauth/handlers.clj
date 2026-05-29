(ns api.oauth.handlers
  (:require [buddy.sign.jwt :as jwt]
            [monger.collection :as mc]
            [monger.operators :refer [$set]]
            [clojure.string :as str]
            [ring.util.codec :as codec])
  (:import [org.bson.types ObjectId]
           [java.util Date]))

(defn find-client [db client-id]
  (mc/find-one-as-map db "clients" {:client_id client-id}))

(defn valid-redirect? [client redirect-uri]
  (some #(= % redirect-uri) (:redirect_uris client)))

(defn authorize-handler [{:keys [params db]}]
  (let [client-id     (get params "client_id")
        redirect-uri  (get params "redirect_uri")
        response-type (get params "response_type")
        challenge     (get params "code_challenge")
        state         (get params "state")
        client        (find-client db client-id)]
    (cond
      (nil? client)
      {:status 400 :body {:error "unknown client_id"}}

      (not (valid-redirect? client redirect-uri))
      {:status 400 :body {:error "invalid redirect_uri"}}

      (not= "code" response-type)
      {:status 400 :body {:error "unsupported response_type"}}

      :else
      {:status  302
       :headers {"Location" (str "/auth/login?"
                                 (codec/form-encode {:client_id      client-id
                                                     :redirect_uri   redirect-uri
                                                     :code_challenge (or challenge "")
                                                     :state          (or state "")}))}})))

(defn- sha256-base64url [s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes s "UTF-8"))]
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bytes)))

(defonce ^:private secure-random (java.security.SecureRandom.))

(defn- generate-token []
  (let [bytes (byte-array 32)]
    (.nextBytes secure-random bytes)
    (.encodeToString (java.util.Base64/getUrlEncoder) bytes)))

(defn- exchange-code [db secret {:keys [code code_verifier client_id redirect_uri]}]
  (let [auth-code (mc/find-one-as-map db "auth_codes" {:code code :used false})]
    (cond
      (nil? auth-code)
      {:status 400 :body {:error "invalid or used code"}}

      (.before ^Date (:expires_at auth-code) (Date.))
      {:status 400 :body {:error "code expired"}}

      (not= client_id (:client_id auth-code))
      {:status 400 :body {:error "client_id mismatch"}}

      (not= redirect_uri (:redirect_uri auth-code))
      {:status 400 :body {:error "redirect_uri mismatch"}}

      (not= (sha256-base64url code_verifier) (:code_challenge auth-code))
      {:status 400 :body {:error "invalid code_verifier"}}

      :else
      (do
        (mc/update db "auth_codes" {:code code} {$set {:used true}})
        (let [user-id       (:user_id auth-code)
              user          (mc/find-one-as-map db "users" {:_id user-id})
              now           (System/currentTimeMillis)
              access-token  (jwt/sign {:sub      (str user-id)
                                       :login    (:login user)
                                       :email    (:email user)
                                       :exp      (Date. (+ now (* 15 60 1000)))}
                                      secret)
              refresh-token (generate-token)]
          (mc/insert db "refresh_tokens"
                     {:_id        (ObjectId.)
                      :token      refresh-token
                      :user_id    user-id
                      :client_id  client_id
                      :expires_at (Date. (+ now (* 7 24 60 60 1000)))
                      :revoked    false})
          {:status  200
           :headers {"Set-Cookie" (str "refresh_token=" refresh-token
                                       "; HttpOnly; SameSite=Strict; Path=/oauth")}
           :body    {:access_token access-token
                     :token_type   "Bearer"
                     :expires_in   900}})))))

(defn token-handler [{:keys [body-params db config] :as request}]
  (let [secret (get-in config [:jwt :secret])]
    (case (:grant_type body-params)
      "authorization_code"
      (exchange-code db secret body-params)

      "refresh_token"
      (let [token-val    (get-in (:cookies request) ["refresh_token" :value])
            stored-token (mc/find-one-as-map db "refresh_tokens"
                                             {:token token-val :revoked false})]
        (if (or (nil? stored-token)
                (.before ^Date (:expires_at stored-token) (Date.)))
          {:status 401 :body {:error "invalid refresh token"}}
          (let [user         (mc/find-one-as-map db "users" {:_id (:user_id stored-token)})
                access-token (jwt/sign {:sub   (str (:_id user))
                                        :login (:login user)
                                        :email (:email user)
                                        :exp   (Date. (+ (System/currentTimeMillis) (* 15 60 1000)))}
                                       secret)]
            {:status 200
             :body   {:access_token access-token
                      :token_type   "Bearer"
                      :expires_in   900}})))

      {:status 400 :body {:error "unsupported grant_type"}})))

(defn revoke-handler [{:keys [body-params cookies db]}]
  (let [token (or (:token body-params)
                  (get-in cookies ["refresh_token" :value]))]
    (mc/update db "refresh_tokens" {:token token} {$set {:revoked true}})
    {:status  200
     :headers {"Set-Cookie" "refresh_token=; HttpOnly; SameSite=Strict; Path=/oauth; Max-Age=0"}
     :body    {:revoked true}}))

(defn- verify-access-token [request]
  (let [auth-header (get-in request [:headers "authorization"] "")
        token       (second (str/split auth-header #" " 2))
        secret      (get-in request [:config :jwt :secret])]
    (when (seq token)
      (try
        (jwt/unsign token secret)
        (catch Exception _ nil)))))

(defn userinfo-handler [{:keys [db] :as request}]
  (let [claims (verify-access-token request)]
    (if (nil? claims)
      {:status 401 :body {:error "invalid or missing token"}}
      (let [user (mc/find-one-as-map db "users" {:_id (ObjectId. (:sub claims))})]
        {:status 200
         :body   {:sub   (:sub claims)
                  :login (:login user)
                  :name  (:name user)
                  :email (:email user)}}))))

(defn metadata-handler [_]
  {:status 200
   :body   {:issuer                           "http://localhost:3000"
            :authorization_endpoint           "http://localhost:3000/oauth/authorize"
            :token_endpoint                   "http://localhost:3000/oauth/token"
            :revocation_endpoint              "http://localhost:3000/oauth/revoke"
            :userinfo_endpoint                "http://localhost:3000/oauth/userinfo"
            :response_types_supported         ["code"]
            :grant_types_supported            ["authorization_code" "refresh_token"]
            :code_challenge_methods_supported ["S256"]}})
