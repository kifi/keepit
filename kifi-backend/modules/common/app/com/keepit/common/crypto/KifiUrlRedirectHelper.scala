package com.keepit.common.crypto

import java.net.URLEncoder
import javax.crypto.spec.IvParameterSpec

import com.google.inject.{ Inject, Singleton }
import com.keepit.heimdal.EventType
import com.keepit.inject.FortyTwoConfig
import com.keepit.slack.models.{ SlackTeamId, SlackUserId }
import com.kifi.macros.json
import play.api.Application
import play.api.libs.Crypto
import play.utils.UriEncoding

import scala.util.Try

case class RedirectTrackingParameters( // can be augmented, fields can be made optional as needed
  eventType: EventType,
  action: String,
  slackUserId: SlackUserId,
  slackTeamId: SlackTeamId)

@Singleton
class KifiUrlRedirectHelper @Inject() (app: Application) {
  val secretKey = "HKAzRBIAVf"
  val ivSpec = new IvParameterSpec(Array(120, -106, 75, 66, -66, -23, 109, 102, 71, -45, 10, -4, -121, 75, -83, 44))
  val cipher = Aes64BitCipher(secretKey, ivSpec)

  // returns a "www.kifi.com/url?url=www.foo.com&s={{encryptedValue}}" url such that we can redirect home from third party clients (e.g. Slack search results)
  def generateKifiUrlRedirect(url: String, trackingParams: RedirectTrackingParameters)(implicit appConfig: FortyTwoConfig): String = {
    val signedUrl = Crypto.signToken(url)
    val signedParams = signTrackingParams(trackingParams)
    val completeUrl = appConfig.applicationBaseUrl + s"/url?s=$signedUrl&t=$signedParams"
    completeUrl
  }

  private def signTrackingParams(params: RedirectTrackingParameters): String = {
    val keyValueString = s"eventType=${params.eventType.name}&action=${params.action}&slackUserId=${params.slackUserId.value}&slackTeamId=${params.slackTeamId.value}"
    val urlEncodedString = URLEncoder.encode(keyValueString, "ascii")
    val signedTrackingParams = cipher.encrypt(urlEncodedString)
    signedTrackingParams
  }

  def parseKifiUrlRedirect(signedUrl: String, signedTrackingParams: Option[String]): Option[(String, Option[RedirectTrackingParameters])] = {
    val urlOpt = Crypto.extractSignedToken(signedUrl)
    val trackingParams = signedTrackingParams.flatMap(params => extractTrackingParams(params))
    urlOpt.map((_, trackingParams))
  }

  private def extractTrackingParams(signedTrackingParams: String): Option[RedirectTrackingParameters] = Try {
    val paramString = UriEncoding.decodePathSegment(cipher.decrypt(signedTrackingParams), "ascii")
    val kvs = paramString.split("&").foldLeft(Map.empty[String, String]) {
      case (map, kv) =>
        val Array(key, value) = kv.split("=").take(2)
        map + (key -> value)
    }
    RedirectTrackingParameters(
      eventType = EventType(kvs("eventType")),
      action = kvs("action"),
      slackUserId = SlackUserId(kvs("slackUserId")),
      slackTeamId = SlackTeamId(kvs("slackTeamId"))
    )
  }.toOption
}
