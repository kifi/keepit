package com.keepit.slack

import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient }
import com.keepit.slack.models._
import play.api.Mode.Mode
import play.api.http.Status
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Failure }

object KifiSlackApp {
  val SLACK_CLIENT_ID = "2348051170.15031499078"
  val SLACK_CLIENT_SECRET = "ad688ad730192eabe0bdc6675975f3fc"
  val KIFI_SLACK_REDIRECT_URI = "https://www.kifi.com/oauth2/slack"
}

object SlackAPI {
  import com.keepit.common.routes.{ GET, ServiceRoute, Param, Method }

  case class Route(method: Method, path: String, params: Param*)
  implicit def toServiceRoute(route: Route): ServiceRoute = ServiceRoute(route.method, route.path, route.params: _*)

  val OK: String = "ok"
  object SlackParams {
    val CLIENT_ID = Param("client_id", KifiSlackApp.SLACK_CLIENT_ID)
    val CLIENT_SECRET = Param("client_secret", KifiSlackApp.SLACK_CLIENT_SECRET)
    val REDIRECT_URI = Param("redirect_uri", KifiSlackApp.KIFI_SLACK_REDIRECT_URI)
    implicit def fromCode(code: SlackAuthorizationCode): Param = Param("code", code.code)
    implicit def formState(state: SlackState): Param = Param("state", state.state)
    implicit def fromScope(scopes: Set[SlackAuthScope]): Param = Param("scope", scopes.map(_.value).mkString(","))
    implicit def fromToken(token: SlackAccessToken): Param = Param("token", token.token)
    implicit def fromSearchParam(searchParam: SlackSearchRequest.Param): Param = Param(searchParam.name, searchParam.value)
  }

  import SlackParams._

  def OAuthAuthorize(scopes: Set[SlackAuthScope], state: SlackState) = Route(GET, "https://slack.com/oauth/authorize", CLIENT_ID, REDIRECT_URI, scopes, state)
  def OAuthAccess(code: SlackAuthorizationCode) = Route(GET, "https://slack.com/api/oauth.access", CLIENT_ID, CLIENT_SECRET, REDIRECT_URI, code)
  def Identify(token: SlackAccessToken) = Route(GET, "https://slack.com/api/auth.test", token)
  def SearchMessages(token: SlackAccessToken, request: SlackSearchRequest) = {
    val params = Seq[Param](token, request.query) ++ request.optional.map(fromSearchParam)
    Route(GET, "https://slack.com/api/search.messages", params: _*)
  }
  def AddReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackMessageTimestamp) = Route(GET, "https://slack.com/api/reactions.add", token, Param("name", reaction.value), Param("channel", channelId.value), Param("timestamp", messageTimestamp.value))
}

trait SlackClient {
  def sendToSlack(url: String, msg: SlackMessageRequest): Future[Unit]
  def processAuthorizationResponse(code: SlackAuthorizationCode): Future[SlackAuthorizationResponse]
  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse]
  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse]
  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackMessageTimestamp): Future[Unit]
}

class SlackClientImpl(
  httpClient: HttpClient,
  implicit val ec: ExecutionContext)
    extends SlackClient with Logging {

  def sendToSlack(url: String, msg: SlackMessageRequest): Future[Unit] = {
    log.info(s"About to post $msg to the Slack webhook at $url")
    httpClient.postFuture(DirectUrl(url), Json.toJson(msg)).flatMap { clientResponse =>
      (clientResponse.status, clientResponse.body) match {
        case (Status.OK, SlackAPI.OK) => Future.successful(())
        case (Status.NOT_FOUND, SlackAPIFailure.Message.REVOKED_WEBHOOK) => Future.failed(SlackAPIFailure.RevokedWebhook)
        case (status, payload) => Future.failed(SlackAPIFailure.Generic(status, JsString(payload)))
      }
    }.andThen {
      case Success(_) => log.error(s"[SLACK-CLIENT] Succeeded in pushing to webhook $url")
      case Failure(f) => log.error(s"[SLACK-CLIENT] Failed to post to webhook $url because $f")
    }
  }

  private def slackCall[T](route: SlackAPI.Route)(implicit reads: Reads[T]): Future[T] = {
    httpClient.getFuture(DirectUrl(route.url)).flatMap { clientResponse =>
      (clientResponse.status, clientResponse.json) match {
        case (Status.OK, payload) if (payload \ "ok").asOpt[Boolean].contains(true) =>
          reads.reads(payload) match {
            case JsSuccess(res, _) =>
              Future.successful(res)
            case errs: JsError =>
              Future.failed(SlackAPIFailure.ParseError(payload))
          }
        case (Status.OK, SlackAPIFailure.Message.REVOKED_TOKEN) => Future.failed(SlackAPIFailure.RevokedWebhook)
        case (status, payload) => Future.failed(SlackAPIFailure.Generic(status, payload))
      }
    }
  }
  def processAuthorizationResponse(code: SlackAuthorizationCode): Future[SlackAuthorizationResponse] = {
    slackCall[SlackAuthorizationResponse](SlackAPI.OAuthAccess(code))
  }

  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse] = {
    slackCall[SlackSearchResponse](SlackAPI.SearchMessages(token, request))
  }

  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse] = {
    slackCall[SlackIdentifyResponse](SlackAPI.Identify(token))
  }

  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackMessageTimestamp): Future[Unit] = {
    slackCall[JsValue](SlackAPI.AddReaction(token, reaction, channelId, messageTimestamp)).imap(_ => ())
  }

  def getChannelId(token: SlackAccessToken, channelName: SlackChannelName): Future[Option[SlackChannelId]] = {
    val searchRequest = SlackSearchRequest(SlackSearchRequest.Query.in(channelName), SlackSearchRequest.PageSize(1))
    searchMessages(token, searchRequest).map { response =>
      response.messages.matches.map(_.channel).collectFirst { case SlackChannel(id, `channelName`) => id }
    }
  }
}
