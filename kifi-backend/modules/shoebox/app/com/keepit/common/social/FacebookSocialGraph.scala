package com.keepit.common.social

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future

import com.google.inject.Inject
import com.keepit.common.db.State
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.social.{SocialUserRawInfo, SocialNetworks, SocialGraph}
import com.keepit.common.performance._
import com.keepit.common.net._


import play.api.libs.json._
import com.keepit.common.healthcheck.{StackTrace, AirbrakeNotifier}
import com.keepit.common.mail.{EmailAddresses, ElectronicMail, LocalPostOffice}
import com.keepit.common.net.NonOKResponseException
import play.api.libs.json.JsArray
import com.keepit.common.net.DirectUrl
import scala.Some
import com.keepit.social.SocialId

object FacebookSocialGraph {
  val PERSONAL_PROFILE = "name,first_name,middle_name,last_name,gender,username,email,picture"
  val FRIEND_PROFILE = "name"

  object ErrorCodes {
    val OAuth = 190
    val ApiSession = 102
    val ApiUnknown = 1
    val ApiService = 2
    val ApiTooManyCalls = 4
    val ApiUserTooManyCalls = 17
    val ApiPermissionDenied = 10

    def userNeedsToReAuth(code: Int) = {
      Set(OAuth, ApiSession, ApiPermissionDenied).contains(code) || (code >= 200 && code <= 299)
    }

    def temporaryFailure(code: Int) = {
      Set(ApiUnknown, ApiService, ApiTooManyCalls, ApiUserTooManyCalls).contains(code)
    }
  }
  object ErrorSubcodes {
    val AppNotInstalled = 458
    val PasswordChanged = 460
    val Expired = 463
    val UnconfirmedUser = 464
    val InvalidToken = 467
  }
}

class FacebookSocialGraph @Inject() (
    httpClient: HttpClient,
    db: Database,
    postOffice: LocalPostOffice,
    socialRepo: SocialUserInfoRepo,
    airbrake: AirbrakeNotifier
  ) extends SocialGraph with Logging {

  val TWO_MINUTES = 2 * 60 * 1000
  val FETCH_LIMIT = 500
  val networkType = SocialNetworks.FACEBOOK

  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo] = {
    val jsonsOpt = try {
      Some(fetchJsons(url(socialUserInfo.socialId, getAccessToken(socialUserInfo)), socialUserInfo))
    } catch {
      case e @ NonOKResponseException(url, response, _) =>
        import FacebookSocialGraph.ErrorSubcodes._
        import FacebookSocialGraph.ErrorCodes._
        import SocialUserInfoStates._

        def fail(msg: String, state: State[SocialUserInfo]) {
          log.warn(msg)
          db.readWrite { implicit s =>
            socialRepo.save(socialUserInfo.withState(state).withLastGraphRefresh())
          }
        }

        val errorJson = response.json \ "error"
        val errorCode = (errorJson \ "code").asOpt[Int]
        val errorSub = (errorJson \ "error_subcode").asOpt[Int]
        val errorMessage = (errorJson \ "message").asOpt[String].getOrElse("")
        val errorType = (errorJson \ "type").asOpt[String].getOrElse("")
        // see https://developers.facebook.com/docs/reference/api/errors/
        // TODO: deal with other errors as we find them and decide on a reasonable action
        (response.res.status, errorCode, errorSub) match {
          case (_, _, Some(AppNotInstalled)) =>
            fail(s"App not authorized for social user $socialUserInfo; not fetching connections.", APP_NOT_AUTHORIZED)
            None
          case (_, _, Some(PasswordChanged)) =>
            fail(s"Facebook password changed for social user $socialUserInfo; not fetching connections.", APP_NOT_AUTHORIZED)
            None
          case (_, _, Some(Expired)) =>
            fail(s"Token expired for social user $socialUserInfo; not fetching connections.", APP_NOT_AUTHORIZED)
            None
          case (_, _, Some(UnconfirmedUser)) =>
            // this happens when a user deactivates their facebook account
            fail(s"Sessions not allowed for social user $socialUserInfo; not fetching connections.", APP_NOT_AUTHORIZED)
            None
          case (_, _, Some(InvalidToken)) =>
            // this happens when a user deactivates their facebook account
            fail(s"Sessions not allowed for social user $socialUserInfo; not fetching connections.", APP_NOT_AUTHORIZED)
            None
          case (_, Some(code), _) if userNeedsToReAuth(code) =>
            fail(s"Facebook rejecting token for $errorType reasons: $errorMessage", APP_NOT_AUTHORIZED)
            None
          case (_, Some(code), _) if temporaryFailure(code) =>
            fail(s"Facebook rejecting token for $errorType reasons: $errorMessage", FETCH_FAIL)
            None
          case (500, _, _) =>
            fail(s"Facebook is having problems.", FETCH_FAIL)
          case _ =>
            fail(s"Error fetching Facebook connections for $socialUserInfo. $errorType ($errorCode / $errorSub): $errorMessage", FETCH_FAIL)
            throw e
        }
        None
    }
    jsonsOpt.flatMap { jsons =>
      jsons.headOption.map { json =>
        SocialUserRawInfo(
          socialUserInfo.userId,
          socialUserInfo.id,
          SocialId((json \ "username").asOpt[String].getOrElse((json \ "id").as[String])),
          SocialNetworks.FACEBOOK,
          (json \ "name").asOpt[String].getOrElse(socialUserInfo.fullName),
          jsons)
      }
    }
  }

  def extractEmails(parentJson: JsValue): Seq[String] = (parentJson \ "email").asOpt[String].toSeq

  def extractFriends(parentJson: JsValue): Seq[SocialUserInfo] = {
    val friendsArr = ((parentJson \ "friends" \ "data").asOpt[JsArray]
        orElse (parentJson \ "data").asOpt[JsArray]) getOrElse JsArray()
    friendsArr.value map createSocialUserInfo
  }

  def revokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] = {
    val accessToken = getAccessToken(socialUserInfo)
    val url = s"https://graph.facebook.com/${socialUserInfo.socialId}/permissions?access_token=$accessToken"
    httpClient.withTimeout(TWO_MINUTES).deleteFuture(DirectUrl(url)).map(_ => ())
  }

  def updateSocialUserInfo(sui: SocialUserInfo, json: JsValue) = {
    (json \ "id").asOpt[String] map { id =>
      assert(sui.socialId.id == id, s"Social id in profile $id should be equal to the existing id ${sui.socialId}")
      sui.copy(fullName = (json \ "name").as[String])
    } getOrElse sui
  }

  protected def getAccessToken(socialUserInfo: SocialUserInfo): String = {
    val credentials = socialUserInfo.credentials.getOrElse(throw new Exception("Can't find credentials for %s".format(socialUserInfo)))
    val oAuth2Info = credentials.oAuth2Info.getOrElse(throw new Exception("Can't find oAuth2Info for %s".format(socialUserInfo)))
    oAuth2Info.accessToken
  }

  def extractUserValues(json: JsValue): Map[String, String] = Seq(
    (json \ "gender").asOpt[String].map(Gender.key -> Gender(_).toString)
  ).flatten.toMap

  private def fetchJsons(url: String, socialUserInfo: SocialUserInfo): Stream[JsValue] = {
    val jsons = get(url, socialUserInfo)
    log.info(s"downloaded FB json using $url")
    jsons #:: nextPageUrl(jsons).map(nextUrl => fetchJsons(nextUrl, socialUserInfo)).getOrElse(Stream.empty)
  }

  private[social] def nextPageUrl(json: JsValue): Option[String] = (json \ "friends" \ "paging" \ "next").asOpt[String].orElse((json \ "paging" \ "next").asOpt[String])

  private def get(url: String, socialUserInfo: SocialUserInfo): JsValue = timing(s"fetching FB JSON using $url") {
    val client = httpClient.withTimeout(TWO_MINUTES)
    val tracer = new StackTrace()
    val myFailureHandler: Request => PartialFunction[Throwable, Unit] = url => {
      case nonOkRes: NonOKResponseException =>
        // This is handled separately elsewhere
      case ex: Exception =>
        val user = s"${socialUserInfo.id.getOrElse("NO_ID")}:${socialUserInfo.fullName}"
        airbrake.notify(s"fail getting json for social user $user using $url", tracer.withCause(ex))
    }

    client.get(DirectUrl(url), myFailureHandler).json
  }

  private def url(id: SocialId, accessToken: String) =
    s"https://graph.facebook.com/${id.id}?access_token=$accessToken&fields=${FacebookSocialGraph.PERSONAL_PROFILE},friends.fields(${FacebookSocialGraph.FRIEND_PROFILE}).limit($FETCH_LIMIT)"

  private def createSocialUserInfo(friend: JsValue): SocialUserInfo =
    SocialUserInfo(
      fullName = (friend \ "name").asOpt[String].getOrElse(""),
      socialId = SocialId((friend \ "id").as[String]),
      networkType = SocialNetworks.FACEBOOK,
      state = SocialUserInfoStates.FETCHED_USING_FRIEND
    )
}
