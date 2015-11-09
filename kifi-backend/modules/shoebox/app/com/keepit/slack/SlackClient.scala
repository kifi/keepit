package com.keepit.slack

import java.net.URLEncoder

import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.common.net.{DirectUrl, HttpClient}
import com.keepit.model.User
import play.api.Mode.Mode
import play.api.http.Status
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait SlackClient {
  def sendToSlack(url: String, msg: SlackMessage): Future[Try[Unit]]
  def generateAuthorizationRequest(scopes: Set[SlackAuthScope], redirectUri: Option[String] = None): SlackAuthorizationRequest
}

class SlackClientImpl(
  httpClient: HttpClient,
  mode: Mode,
  implicit val ec: ExecutionContext)
    extends SlackClient with Logging {

  // Kifi Slack app properties
  private val SLACK_CLIENT_ID = "garbage_client_id"
  private val SLACK_CLIENT_SECRET = "garbage_client_secret"

  def sendToSlack(url: String, msg: SlackMessage): Future[Try[Unit]] = {
    httpClient.postFuture(DirectUrl(url), Json.toJson(msg)).map { clientResponse =>
      (clientResponse.status, clientResponse.json) match {
        case (Status.OK, ok) if ok.asOpt[String].contains("ok") => Success(())
        case (status, payload) => Failure(SlackAPIFail(status, payload))
      }
    }
  }

  def generateAuthorizationRequest(scopes: Set[SlackAuthScope], userId: ExternalId[User], redirectUri: Option[String] = None): SlackAuthorizationRequest = {
    val uniqueToken = userId.id
    val scopesStr = scopes.map(_.value).mkString(",")
    val redirectStr = redirectUri.map(r => "&" + URLEncoder.encode(r, "ascii")).getOrElse("")
    val url = s"https://slack.com/oauth/authorize?client_id=$SLACK_CLIENT_ID&scope=$scopesStr&state=$uniqueToken$redirectStr"
    SlackAuthorizationRequest(url, scopes, uniqueToken, redirectUri)
  }

  private def processAuthorizationResponse(payload: JsValue): Try[SlackAuthorizationResponse] = {
    payload.validate[SlackAuthorizationResponse].fold(
      errs => Failure(errs.head._2.head),
      sar => Success(sar)
    )
  }
}
