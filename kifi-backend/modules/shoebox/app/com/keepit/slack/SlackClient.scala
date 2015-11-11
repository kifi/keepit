package com.keepit.slack

import java.net.URLEncoder

import com.keepit.common.crypto.CryptoSupport
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient }
import play.api.Mode.Mode
import play.api.http.Status
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

trait SlackClient {
  def sendToSlack(url: String, msg: SlackMessage): Future[Unit]
  def generateAuthorizationRequest(scopes: Set[SlackAuthScope], state: JsObject): String
  def processAuthorizationResponse(code: SlackAuthorizationCode, state: String): Future[(SlackAuthorizationResponse, JsObject)] // the JsObject is a DeepLink
}

class SlackClientImpl(
  httpClient: HttpClient,
  mode: Mode,
  implicit val ec: ExecutionContext)
    extends SlackClient with Logging {
  // Kifi Slack app properties
  private val SLACK_CLIENT_ID = "2348051170.12884760868"
  private val SLACK_CLIENT_SECRET = "3cfeb40c29a06272bbb159fc1d9d4fb3"
  private val KIFI_SLACK_REDIRECT_URI = URLEncoder.encode("https://www.kifi.com/oauth2/slack", "ascii")

  object Route {
    val OAuthAuthorize = "https://slack.com/oauth/authorize"
    val OAuthAccess = "https://slack.com/api/oauth.access"
    val Search = "https://slack.com/api/search.messages"
  }
  object Param {
    val CLIENT_ID = "client_id" -> SLACK_CLIENT_ID
    val CLIENT_SECRET = "client_secret" -> SLACK_CLIENT_SECRET
    val REDIRECT_URI = "redirect_uri" -> KIFI_SLACK_REDIRECT_URI
    def code(code: SlackAuthorizationCode) = "code" -> code.code
    def state(state: JsObject) = "state" -> CryptoSupport.encodeBase64(Json.stringify(state))
    def scope(scopes: Set[SlackAuthScope]) = "scope" -> scopes.map(_.value).mkString(",")
    def token(token: SlackAccessToken) = "token" -> token.token
    def query(query: SlackSearchQuery) = "query" -> query.queryString
  }

  private def mkUrl(base: String, params: (String, String)*): String = {
    base + "?" + params.map { case (k, v) => s"$k=$v" }.mkString("&")
  }
  def sendToSlack(url: String, msg: SlackMessage): Future[Unit] = {
    httpClient.postFuture(DirectUrl(url), Json.toJson(msg)).flatMap { clientResponse =>
      (clientResponse.status, clientResponse.json) match {
        case (Status.OK, json) if json.asOpt[String].contains("ok") => Future.successful(())
        case (status, payload) => Future.failed(SlackAPIFail.Generic(status, payload))
      }
    }
  }

  def generateAuthorizationRequest(scopes: Set[SlackAuthScope], state: JsObject): String = {
    mkUrl(Route.OAuthAuthorize,
      Param.CLIENT_ID,
      Param.REDIRECT_URI,
      Param.scope(scopes),
      Param.state(state)
    )
  }

  private def slackCall[T](route: String, params: (String, String)*)(implicit reads: Reads[T]): Future[T] = {
    httpClient.getFuture(DirectUrl(mkUrl(route, params: _*))).flatMap { clientResponse =>
      (clientResponse.status, clientResponse.json) match {
        case (Status.OK, payload) if (payload \ "ok").asOpt[Boolean].contains(true) =>
          reads.reads(payload) match {
            case JsSuccess(res, _) => Future.successful(res)
            case errs: JsError => Future.failed(SlackAPIFail.ParseError(payload))
          }
        case (status, payload) => Future.failed(SlackAPIFail.Generic(status, payload))
      }
    }
  }
  def processAuthorizationResponse(code: SlackAuthorizationCode, state: String): Future[(SlackAuthorizationResponse, JsObject)] = {
    val redirStateFut = Try(Json.parse(CryptoSupport.decodeBase64(state)).as[JsObject]).map(Future.successful).getOrElse(Future.failed(SlackAPIFail.StateError(state))) // this should never fail
    val authResponseFut = slackCall[SlackAuthorizationResponse](Route.OAuthAccess, Param.CLIENT_ID, Param.CLIENT_SECRET, Param.REDIRECT_URI, Param.code(code))
    for {
      authResponse <- authResponseFut
      redirState <- redirStateFut
    } yield (authResponse, redirState)
  }

  private def search(token: SlackAccessToken, query: SlackSearchQuery): Future[SlackSearchResponse] = {
    slackCall[SlackSearchResponse](Route.Search, Param.token(token), Param.query(query))
  }

  def channelId(token: SlackAccessToken, channelName: String): Future[Option[String]] = {
    search(token, SlackSearchQuery(s"in:$channelName")).map { response =>
      for {
        matches <- (response.messages \ "matches").asOpt[Seq[JsObject]]
        firstMsg <- matches.headOption
        channelId <- (firstMsg \ "channel" \ "id").asOpt[String]
      } yield channelId
    }
  }
}
