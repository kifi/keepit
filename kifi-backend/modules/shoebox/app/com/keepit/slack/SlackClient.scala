package com.keepit.slack

import java.net.URLEncoder

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
  def processAuthorizationResponse(code: SlackAuthorizationCode, state: String): Future[Try[(SlackAuthorizationResponse, JsObject)]] // the JsObject is a DeepLink
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
  private val SLACK_CLIENT_ID = "2348051170.12884760868"
  private val SLACK_CLIENT_SECRET = "3cfeb40c29a06272bbb159fc1d9d4fb3"

  private val REDIRECT_URI = URLEncoder.encode("https://www.kifi.com/oauth2/slack", "ascii")

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
        case (status, payload) => Failure(SlackAPIFail.Generic(status, payload))
      }
    }
  }

  def generateAuthorizationRequest(scopes: Set[SlackAuthScope], state: JsObject, redirectUri: Option[String] = None): String = {
    mkUrlOpt(Route.OAuthAuthorize,
      "client_id" -> Some(SLACK_CLIENT_ID),
      "scope" -> Some(scopes.map(_.value).mkString(",")),
      "state" -> Some(CryptoSupport.encodeBase64(Json.stringify(state))),
      "redirect_uri" -> REDIRECT_URI
    )
  }

  def processAuthorizationResponse(code: SlackAuthorizationCode, state: String): Future[Try[(SlackAuthorizationResponse, JsObject)]] = {
    val authResponse = httpClient.getFuture(DirectUrl(mkUrl(Route.OAuthAccess, "client_id" -> SLACK_CLIENT_ID, "client_secret" -> SLACK_CLIENT_SECRET, "code" -> code.code, "redirect_uri" -> REDIRECT_URI)))
    authResponse.map { clientResponse =>
      val responseTry = (clientResponse.status, clientResponse.json) match {
        case (Status.OK, payload) if (payload \ "ok").asOpt[String].contains("ok") =>
          payload.validate[SlackAuthorizationResponse] match {
            case JsSuccess(res, _) => Success(res)
            case errs: JsError => Failure(SlackAPIFail.ParseError(payload, errs))
          }
        case (status, payload) => Failure(SlackAPIFail.Generic(status, payload))
      }
      val redirStateTry = Try(Json.parse(state).as[JsObject]).orElse(Failure(SlackAPIFail.StateError(state)))
      for {
        response <- responseTry
        redirState <- redirStateTry
      } yield (response, redirState)
    }
  }
}
