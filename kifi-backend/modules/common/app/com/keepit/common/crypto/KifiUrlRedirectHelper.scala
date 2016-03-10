package com.keepit.common.crypto

import java.net.{ URLDecoder, URLEncoder }
import javax.crypto.spec.IvParameterSpec

import com.keepit.common.net.Query
import com.keepit.inject.FortyTwoConfig
import play.api.libs.Crypto // requires a Play application to be running, start one in test if you get "There is no started application" exceptions.

object KifiUrlRedirectHelper {
  val secretKey = "HKAzRBIAVf"
  val ivSpec = new IvParameterSpec(Array(120, -106, 75, 66, -66, -23, 109, 102, 71, -45, 10, -4, -121, 75, -83, 44))
  val cipher = Aes64BitCipher(secretKey, ivSpec)

  // returns a "www.kifi.com/url?url=www.foo.com&s={{encryptedValue}}" url such that we can redirect home from third party clients (e.g. Slack search results)
  def generateKifiUrlRedirect(url: String, trackingParams: Query)(implicit appConfig: FortyTwoConfig): String = {
    val signedUrl = URLEncoder.encode(Crypto.signToken(url), "ascii")
    val signedParams = URLEncoder.encode(signTrackingParams(trackingParams), "ascii")
    val completeUrl = appConfig.applicationBaseUrl + s"/url?s=$signedUrl&t=$signedParams"
    completeUrl
  }
  def parseKifiUrlRedirect(signedUrl: String, signedTrackingParams: Option[String]): Option[(String, Option[Query])] = {
    val urlOpt = Crypto.extractSignedToken(URLDecoder.decode(signedUrl, "ascii"))
    val trackingParams = signedTrackingParams.map(params => extractTrackingParams(URLDecoder.decode(params, "ascii")))
    urlOpt.map((_, trackingParams))
  }

  def signTrackingParams(params: Query): String = cipher.encrypt(params.toString())
  def extractTrackingParams(signedTrackingParams: String): Query = Query.parse(cipher.decrypt(signedTrackingParams))

}
