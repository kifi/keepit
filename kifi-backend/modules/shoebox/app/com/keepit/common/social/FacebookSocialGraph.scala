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
  val FRIEND_PROFILE = "name,gender,username,picture"

  object ErrorSubcodes {
    val AppNotInstalled = 458
    val PasswordChanged = 460
    val Expired = 463
    val UnconfirmedUser = 464
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
  val networkType = SocialNetworks.FACEBOOK

  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo] = {
    val jsonsOpt = try {
      Some(fetchJsons(url(socialUserInfo.socialId, getAccessToken(socialUserInfo)), socialUserInfo))
    } catch {
      case e @ NonOKResponseException(url, response, _) =>
        import FacebookSocialGraph.ErrorSubcodes._
        import SocialUserInfoStates._
        def fail(msg: String, state: State[SocialUserInfo] = FETCH_FAIL) {
          log.warn(msg)
          db.readWrite { implicit s =>
            socialRepo.save(socialUserInfo.withState(state).withLastGraphRefresh())
          }
        }

        val errorJson = response.json \ "error"
        val errorCode = (errorJson \ "code").asOpt[Int]
        val errorSub = (errorJson \ "error_subcode").asOpt[Int]
        // see https://developers.facebook.com/docs/reference/api/errors/
        // TODO: deal with other errors as we find them and decide on a reasonable action
        (errorCode, errorSub) match {
          case (_, Some(AppNotInstalled)) =>
            fail(s"App not authorized for social user $socialUserInfo; not fetching connections.", APP_NOT_AUTHORIZED)
            None
          case (_, Some(PasswordChanged)) =>
            fail(s"Facebook password changed for social user $socialUserInfo; not fetching connections.", APP_NOT_AUTHORIZED)
            None
          case (_, Some(Expired)) =>
            fail(s"Token expired for social user $socialUserInfo; not fetching connections.", APP_NOT_AUTHORIZED)
            None
          case (_, Some(UnconfirmedUser)) =>
            // this happens when a user deactivates their facebook account
            fail(s"Sessions not allowed for social user $socialUserInfo; not fetching connections.", INACTIVE)
            None
          case _ =>
            fail(s"Error fetching Facebook connections for $socialUserInfo.")
            throw e
        }
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

  def extractFriends(parentJson: JsValue): Seq[(SocialUserInfo, JsValue)] = {
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

  private def fetchJsons(url: String, socialUserInfo: SocialUserInfo): Seq[JsValue] = {
    val jsons = get(url, socialUserInfo)
    log.info(s"downloaded FB json using $url")
    jsons +: nextPageUrl(jsons).toSeq.flatMap { u => fetchJsons(u, socialUserInfo) }
  }

  private[social] def nextPageUrl(json: JsValue): Option[String] = (json \ "friends" \ "paging" \ "next").asOpt[String]

  private def get(url: String, socialUserInfo: SocialUserInfo): JsValue = timing(s"fetching FB JSON using $url") {
    val client = httpClient.withTimeout(TWO_MINUTES)
    val tracer = new StackTrace()
    val myFailureHandler: Request => PartialFunction[Throwable, Unit] = { url => {
        case nonOkRes: NonOKResponseException =>
          val user = s"${socialUserInfo.id.getOrElse("NO_ID")}:${socialUserInfo.fullName}"
          db.readWrite{ implicit s =>
            postOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.EISHAY, EmailAddresses.ANDREW, EmailAddresses.EFFI),
              subject =  s"fail getting Facebook json for $user",
              htmlBody = s"user: $socialUserInfo\nurl: https://admin.kifi.com/admin/social_user/${socialUserInfo.id.getOrElse("NO_ID")}\nexception: $nonOkRes",
              category = NotificationCategory.System.ADMIN))
          }
        case ex: Exception =>
          val user = s"${socialUserInfo.id.getOrElse("NO_ID")}:${socialUserInfo.fullName}"
          airbrake.notify(s"fail getting json for social user $user using $url", tracer.withCause(ex))
      }
    }
    client.get(DirectUrl(url), myFailureHandler).json
  }

  private def url(id: SocialId, accessToken: String) =
    s"https://graph.facebook.com/${id.id}?access_token=$accessToken&fields=${FacebookSocialGraph.PERSONAL_PROFILE},friends.fields(${FacebookSocialGraph.FRIEND_PROFILE})"

  private def createSocialUserInfo(friend: JsValue): (SocialUserInfo, JsValue) =
    (SocialUserInfo(
      fullName = (friend \ "name").asOpt[String].getOrElse(""),
      socialId = SocialId((friend \ "id").as[String]),
      networkType = SocialNetworks.FACEBOOK,
      state = SocialUserInfoStates.FETCHED_USING_FRIEND
    ), friend)
}
