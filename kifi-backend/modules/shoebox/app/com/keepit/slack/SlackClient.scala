package com.keepit.slack

import com.keepit.common.logging.Logging
import com.keepit.common.net.{ DirectUrl, HttpClient }
import com.keepit.slack.models._
import play.api.Mode.Mode
import play.api.http.Status
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Failure

object KifiSlackApp {
  val SLACK_CLIENT_ID = "2348051170.12884760868"
  val SLACK_CLIENT_SECRET = "3cfeb40c29a06272bbb159fc1d9d4fb3"
  val KIFI_SLACK_REDIRECT_URI = "https://www.kifi.com/oauth2/slack"
}

trait SlackClient {
  def sendToSlack(url: String, msg: SlackMessage): Future[Unit]
  def processAuthorizationResponse(code: SlackAuthorizationCode): Future[SlackAuthorizationResponse]
  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse]
  def searchMessages(token: SlackAccessToken, query: SlackSearchQuery, optional: SlackSearchParams*): Future[SlackSearchResponse]
}

class SlackClientImpl(
  httpClient: HttpClient,
  mode: Mode,
  implicit val ec: ExecutionContext)
    extends SlackClient with Logging {

  def sendToSlack(url: String, msg: SlackMessage): Future[Unit] = {
    log.info(s"About to post $msg to the Slack webhook at $url")
    httpClient.postFuture(DirectUrl(url), Json.toJson(msg)).flatMap { clientResponse =>
      (clientResponse.status, clientResponse.json) match {
        case (Status.OK, json) if json.asOpt[String].contains("ok") => Future.successful(())
        case (status, payload) => Future.failed(SlackAPIFailure.Generic(status, payload))
      }
    } andThen {
      case Failure(f: SlackAPIFailure) => log.info(s"Failed to post $msg to the Slack webhook $url because: ${Json.stringify(Json.toJson(f))}")
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
        case (status, payload) => Future.failed(SlackAPIFailure.Generic(status, payload))
      }
    }
  }
  def processAuthorizationResponse(code: SlackAuthorizationCode): Future[SlackAuthorizationResponse] = {
    slackCall[SlackAuthorizationResponse](SlackAPI.OAuthAccess(code))
  }

  def searchMessages(token: SlackAccessToken, query: SlackSearchQuery, optional: SlackSearchParams*): Future[SlackSearchResponse] = {
    slackCall[SlackSearchResponse](SlackAPI.SearchMessages(token, query, optional: _*))
  }

  def identifyUser(token: SlackAccessToken): Future[SlackIdentifyResponse] = {
    slackCall[SlackIdentifyResponse](SlackAPI.Identify(token))
  }
  def channelId(token: SlackAccessToken, channelName: SlackChannelName): Future[Option[String]] = {
    searchMessages(token, SlackSearchQuery.in(channelName), SlackSearchParams.PageSize(1)).map { response =>
      for {
        matches <- (response.messages \ "matches").asOpt[Seq[JsObject]]
        firstMsg <- matches.headOption
        channelId <- (firstMsg \ "channel" \ "id").asOpt[String]
      } yield channelId
    }
  }
}

object SlackAPI {
  import com.keepit.common.routes.{ GET, ServiceRoute, Param, Method }

  case class Route(method: Method, path: String, params: Param*)
  implicit def toServiceRoute(route: Route): ServiceRoute = ServiceRoute(route.method, route.path, route.params: _*)

  object SlackParams {
    val CLIENT_ID = Param("client_id", KifiSlackApp.SLACK_CLIENT_ID)
    val CLIENT_SECRET = Param("client_secret", KifiSlackApp.SLACK_CLIENT_SECRET)
    val REDIRECT_URI = Param("redirect_uri", KifiSlackApp.KIFI_SLACK_REDIRECT_URI)
    implicit def fromCode(code: SlackAuthorizationCode): Param = Param("code", code.code)
    implicit def formState(state: SlackState): Param = Param("state", state.state)
    implicit def fromScope(scopes: Set[SlackAuthScope]): Param = Param("scope", scopes.map(_.value).mkString(","))
    implicit def fromToken(token: SlackAccessToken): Param = Param("token", token.token)
    implicit def fromSearchParam(searchParam: SlackSearchParams): Param = Param(searchParam.name, searchParam.value)
  }

  import SlackParams._

  def OAuthAuthorize(scopes: Set[SlackAuthScope], state: SlackState) = Route(GET, "https://slack.com/oauth/authorize", CLIENT_ID, REDIRECT_URI, scopes, state)
  def OAuthAccess(code: SlackAuthorizationCode) = Route(GET, "https://slack.com/api/oauth.access", CLIENT_ID, CLIENT_SECRET, REDIRECT_URI, code)
  def Identify(token: SlackAccessToken) = Route(GET, "https://slack.com/api/auth.test", token)
  def SearchMessages(token: SlackAccessToken, query: SlackSearchQuery, optional: SlackSearchParams*) = {
    val params = Seq[Param](token, query) ++ optional.map(fromSearchParam)
    Route(GET, "https://slack.com/api/search.messages", params: _*)
  }
}
