package com.keepit.slack

import com.keepit.common.crypto.CryptoSupport
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient }
import play.api.Mode.Mode
import play.api.http.Status
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

trait SlackClient {
  def sendToSlack(url: String, msg: SlackMessage): Future[Try[Unit]]
  def generateAuthorizationRequest(scopes: Set[SlackAuthScope], state: JsObject, redirectUri: Option[String] = None): String
}

class SlackClientImpl(
  httpClient: HttpClient,
  mode: Mode,
  implicit val ec: ExecutionContext)
    extends SlackClient with Logging {

  object Route {
    val OAuthAuthorize = "https://slack.com/oauth/authorize"
    val OAuthAccess = "https://slack.com/api/oauth.access"
  }
  // Kifi Slack app properties
  private val SLACK_CLIENT_ID = "garbage_client_id"
  private val SLACK_CLIENT_SECRET = "garbage_client_secret"

  private def mkUrlOpt(base: String, params: (String, Option[String])*): String = {
    base + "?" + params.collect { case (k, Some(v)) => s"$k=$v" }.mkString("&")
  }
  private def mkUrl(base: String, params: (String, String)*): String = {
    base + "?" + params.map { case (k, v) => s"$k=$v" }.mkString("&")
  }
  def sendToSlack(url: String, msg: SlackMessage): Future[Try[Unit]] = {
    httpClient.postFuture(DirectUrl(url), Json.toJson(msg)).map { clientResponse =>
      (clientResponse.status, clientResponse.json) match {
        case (Status.OK, ok) if ok.asOpt[String].contains("ok") => Success(())
        case (status, payload) => Failure(SlackAPIFail(status, payload))
      }
    }
  }

  def generateAuthorizationRequest(scopes: Set[SlackAuthScope], state: JsObject, redirectUri: Option[String] = None): String = {
    mkUrlOpt(Route.OAuthAuthorize,
      "client_id" -> Some(SLACK_CLIENT_ID),
      "scope" -> Some(scopes.map(_.value).mkString(",")),
      "state" -> Some(CryptoSupport.toBase64(state.toString())),
      "redirect_uri" -> redirectUri
    )
  }

  def processAuthorizationResponse(code: SlackAuthorizationCode): Future[Try[SlackAuthorizationResponse]] = {
    val authResponse = httpClient.getFuture(DirectUrl(mkUrl(Route.OAuthAccess, "client_id" -> SLACK_CLIENT_ID, "client_secret" -> SLACK_CLIENT_SECRET, "code" -> code.code)))
    authResponse.map { clientResponse =>
      (clientResponse.status, clientResponse.json) match {
        case (Status.OK, payload) if (payload \ "ok").asOpt[String].contains("ok") => ???
        case (status, payload) => Failure(SlackAPIFail(status, payload))
      }
    }
  }
}
