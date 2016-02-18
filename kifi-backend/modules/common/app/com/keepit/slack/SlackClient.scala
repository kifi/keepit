package com.keepit.slack

import com.keepit.common.core._
import com.keepit.common.json.readUnit
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient, NonOKResponseException }
import com.keepit.slack.models._
import play.api.http.Status
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object SlackAPI {
  import com.keepit.common.routes.{ GET, Method, Param, ServiceRoute }

  case class Route(method: Method, path: String, params: Param*)
  implicit def toServiceRoute(route: Route): ServiceRoute = ServiceRoute(route.method, route.path, route.params: _*)

  val OK: String = "ok"
  val NoService: String = "No service"
  object SlackParams {
    val CLIENT_ID = Param("client_id", KifiSlackApp.SLACK_CLIENT_ID)
    val CLIENT_SECRET = Param("client_secret", KifiSlackApp.SLACK_CLIENT_SECRET)
    implicit def fromCode(code: SlackAuthorizationCode): Param = Param("code", code.code)
    implicit def formState(state: SlackAuthState): Param = Param("state", state.state)
    implicit def fromScope(scopes: Set[SlackAuthScope]): Param = Param("scope", scopes.map(_.value).mkString(","))
    implicit def fromToken(token: SlackAccessToken): Param = Param("token", token.token)
    implicit def fromChannelId(channelId: SlackChannelId): Param = Param("channel", channelId.value)
    implicit def fromTimestamp(timestamp: SlackTimestamp): Param = Param("timestamp", timestamp.value) // TODO(ryan): definitely not "ts", right?
    implicit def fromUserId(userId: SlackUserId): Param = Param("user", userId.value)
    implicit def fromSearchParam(searchParam: SlackSearchRequest.Param): Param = Param(searchParam.name, searchParam.value)
  }

  import com.keepit.slack.SlackAPI.SlackParams._

  def Test(token: SlackAccessToken) = Route(GET, "https://slack.com/api/api.test", token)
  def OAuthAuthorize(scopes: Set[SlackAuthScope], state: SlackAuthState, teamId: Option[SlackTeamId], redirectUri: String) = Route(GET, "https://slack.com/oauth/authorize", CLIENT_ID, scopes, state, "redirect_uri" -> redirectUri, "team" -> teamId.map(_.value))
  def OAuthAccess(code: SlackAuthorizationCode, redirectUri: String) = Route(GET, "https://slack.com/api/oauth.access", CLIENT_ID, CLIENT_SECRET, code, "redirect_uri" -> redirectUri)
  def Identify(token: SlackAccessToken) = Route(GET, "https://slack.com/api/auth.test", token)
  def SearchMessages(token: SlackAccessToken, request: SlackSearchRequest) = {
    val params = Seq[Param](token, request.query) ++ request.optional.map(fromSearchParam)
    Route(GET, "https://slack.com/api/search.messages", params: _*)
  }
  def ChannelsList(token: SlackAccessToken, excludeArchived: Boolean) = Route(GET, "https://slack.com/api/channels.list", token, "exclude_archived" -> (if (excludeArchived) 1 else 0))
  def ChannelInfo(token: SlackAccessToken, channelId: SlackChannelId) = Route(GET, "https://slack.com/api/channels.info", token, channelId)
  def AddReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackTimestamp) = Route(GET, "https://slack.com/api/reactions.add", token, "name" -> reaction.value, "channel" -> channelId.value, "timestamp" -> messageTimestamp.value)
  def PostMessage(token: SlackAccessToken, channelId: SlackChannelId, msg: SlackMessageRequest) =
    Route(GET, "https://slack.com/api/chat.postMessage", Seq[Param](token, channelId) ++ msg.asUrlParams: _*)
  def UpdateMessage(token: SlackAccessToken, timestamp: SlackTimestamp, msg: SlackMessageRequest) =
    Route(GET, "https://slack.com/api/chat.update", Seq[Param](token, timestamp) ++ msg.asUrlParams: _*) // TODO(ryan): definitely not "chat.updateMessage", right?
  def TeamInfo(token: SlackAccessToken) = Route(GET, "https://slack.com/api/team.info", token)
  def UserInfo(token: SlackAccessToken, userId: SlackUserId) = Route(GET, "https://slack.com/api/users.info", token, userId)
  def UsersList(token: SlackAccessToken) = Route(GET, "https://slack.com/api/users.list", token)
}

trait SlackClient {
  def pushToWebhook(url: String, msg: SlackMessageRequest): Future[Unit]
  def postToChannel(token: SlackAccessToken, channelId: SlackChannelId, msg: SlackMessageRequest): Future[SlackMessage]
  def processAuthorizationResponse(code: SlackAuthorizationCode, redirectUri: String): Future[SlackAuthorizationResponse]
  def updateMessage(token: SlackAccessToken, timestamp: SlackTimestamp, newMsg: SlackMessageRequest): Future[SlackMessage]
  def testToken(token: SlackAccessToken): Future[Unit]
  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse]
  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse]
  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackTimestamp): Future[Unit]
  def getChannelId(token: SlackAccessToken, channelName: SlackChannelName): Future[Option[SlackChannelId]]
  def getTeamInfo(token: SlackAccessToken): Future[SlackTeamInfo]
  def getPublicChannels(token: SlackAccessToken, excludeArchived: Boolean): Future[Seq[SlackPublicChannelInfo]]
  def getPublicChannelInfo(token: SlackAccessToken, channelId: SlackChannelId): Future[SlackPublicChannelInfo]
  def getUserInfo(token: SlackAccessToken, userId: SlackUserId): Future[SlackUserInfo]
  def getUsersList(token: SlackAccessToken, userId: SlackUserId): Future[Seq[SlackUserInfo]]
}

class SlackClientImpl(
  httpClient: HttpClient,
  implicit val ec: ExecutionContext)
    extends SlackClient with Logging {

  def pushToWebhook(url: String, msg: SlackMessageRequest): Future[Unit] = {
    log.info(s"About to post $msg to the Slack webhook at $url")
    httpClient.postFuture(DirectUrl(url), Json.toJson(msg)).flatMap { clientResponse =>
      (clientResponse.status, clientResponse.body) match {
        case (Status.OK, SlackAPI.OK) => Future.successful(())
        case (Status.NOT_FOUND, SlackAPI.NoService) => Future.failed(SlackAPIFailure.TokenRevoked)
        case (status, payload) => Future.failed(SlackAPIFailure.ApiError(status, Json.obj("error" -> payload)))
      }
    }.recoverWith {
      case f: NonOKResponseException =>
        log.error(s"Caught a non-OK response exception to $url, recognizing that it's a revoked webhook")
        Future.failed(SlackAPIFailure.WebhookRevoked)
    }.andThen {
      case Success(_) => log.info(s"[SLACK-CLIENT] Succeeded in pushing to webhook $url")
      case Failure(f) => log.error(s"[SLACK-CLIENT] Failed to post to webhook $url because $f")
    }
  }

  def postToChannel(token: SlackAccessToken, channelId: SlackChannelId, msg: SlackMessageRequest): Future[SlackMessage] = {
    slackCall[SlackMessage](SlackAPI.PostMessage(token, channelId, msg)).andThen {
      case Success(_) => log.info(s"[SLACK-CLIENT] Succeeded in pushing to $channelId via token $token")
      case Failure(f) => log.error(s"[SLACK-CLIENT] Failed to post to $channelId via token $token because of ${f.getMessage}")
    }
  }

  private def slackCall[T](route: SlackAPI.Route)(implicit reads: Reads[T]): Future[T] = {
    httpClient.getFuture(DirectUrl(route.url)).flatMap { clientResponse =>
      (clientResponse.status, clientResponse.json) match {
        case (Status.OK, payload) if (payload \ "ok").asOpt[Boolean].contains(true) =>
          reads.reads(payload) match {
            case JsSuccess(res, _) => Future.successful(res)
            case errs: JsError => Future.failed(SlackAPIFailure.ParseError(payload))
          }
        case (status, payload) => Future.failed(SlackAPIFailure.ApiError(status, payload))
      }
    }
  }
  def processAuthorizationResponse(code: SlackAuthorizationCode, redirectUri: String): Future[SlackAuthorizationResponse] = {
    slackCall[SlackAuthorizationResponse](SlackAPI.OAuthAccess(code, redirectUri))
  }

  def updateMessage(token: SlackAccessToken, timestamp: SlackTimestamp, newMsg: SlackMessageRequest): Future[SlackMessage] = {
    slackCall[SlackMessage](SlackAPI.UpdateMessage(token, timestamp, newMsg))
  }
  def testToken(token: SlackAccessToken): Future[Unit] = {
    slackCall[Unit](SlackAPI.Test(token))(readUnit)
  }

  def searchMessages(token: SlackAccessToken, request: SlackSearchRequest): Future[SlackSearchResponse] = {
    slackCall[SlackSearchResponse](SlackAPI.SearchMessages(token, request))
  }

  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse] = {
    slackCall[SlackIdentifyResponse](SlackAPI.Identify(token))
  }

  def addReaction(token: SlackAccessToken, reaction: SlackReaction, channelId: SlackChannelId, messageTimestamp: SlackTimestamp): Future[Unit] = {
    slackCall[JsValue](SlackAPI.AddReaction(token, reaction, channelId, messageTimestamp)).imap(_ => ())
  }

  def getChannelId(token: SlackAccessToken, channelName: SlackChannelName): Future[Option[SlackChannelId]] = {
    val searchRequest = SlackSearchRequest(SlackSearchRequest.Query.in(channelName), SlackSearchRequest.PageSize(1))
    searchMessages(token, searchRequest).flatMap { response =>
      response.messages.matches.headOption.map(_.channel.id) match {
        case Some(channelId) => Future.successful(Some(channelId))
        case None => getPublicChannels(token, excludeArchived = false).map { channels =>
          channels.collectFirst { case channel if channel.channelName == channelName => channel.channelId }
        }
      }
    }
  }

  def getTeamInfo(token: SlackAccessToken): Future[SlackTeamInfo] = {
    slackCall[SlackTeamInfo](SlackAPI.TeamInfo(token))((__ \ 'team).read)
  }
  def getPublicChannels(token: SlackAccessToken, excludeArchived: Boolean): Future[Seq[SlackPublicChannelInfo]] = {
    slackCall[Seq[SlackPublicChannelInfo]](SlackAPI.ChannelsList(token, excludeArchived))((__ \ 'channels).read)
  }
  def getPublicChannelInfo(token: SlackAccessToken, channelId: SlackChannelId): Future[SlackPublicChannelInfo] = {
    slackCall[SlackPublicChannelInfo](SlackAPI.ChannelInfo(token, channelId))((__ \ 'channel).read)
  }

  def getUserInfo(token: SlackAccessToken, userId: SlackUserId): Future[SlackUserInfo] = {
    slackCall[SlackUserInfo](SlackAPI.UserInfo(token, userId))((__ \ 'user).read)
  }

  def getUsersList(token: SlackAccessToken, userId: SlackUserId): Future[Seq[SlackUserInfo]] = {
    slackCall[Seq[SlackUserInfo]](SlackAPI.UsersList(token))((__ \ 'members).read)
  }
}
