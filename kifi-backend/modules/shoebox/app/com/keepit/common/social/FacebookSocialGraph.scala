package com.keepit.common.social

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import com.google.inject.Inject
import com.keepit.common.db.State
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.{NonOKResponseException, HttpClient}
import com.keepit.model.{SocialUserInfoRepo, SocialUserInfoStates, SocialUserInfo}
import com.keepit.social.{SocialUserRawInfo, SocialNetworks, SocialId, SocialGraph}

import play.api.libs.json._

object FacebookSocialGraph {
  val FULL_PROFILE = "name,first_name,middle_name,last_name,gender,username,languages,installed,devices,email,picture"

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
    socialRepo: SocialUserInfoRepo
  ) extends SocialGraph with Logging {

  val networkType = SocialNetworks.FACEBOOK

  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo] = {
    val jsonsOpt = try {
      Some(fetchJsons(url(socialUserInfo.socialId, getAccessToken(socialUserInfo))))
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
    httpClient.deleteFuture(url).map(_ => ())
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

  private def fetchJsons(url: String): Seq[JsValue] = {
    val jsons = get(url)
    jsons +: nextPageUrl(jsons).toSeq.flatMap(fetchJsons)
  }

  private[social] def nextPageUrl(json: JsValue): Option[String] = (json \ "friends" \ "paging" \ "next").asOpt[String]

  private def get(url: String): JsValue = httpClient.longTimeout().get(url, httpClient.ignoreFailure).json

  private def url(id: SocialId, accessToken: String) = "https://graph.facebook.com/%s?access_token=%s&fields=%s,friends.fields(%s)".format(
      id.id, accessToken, FacebookSocialGraph.FULL_PROFILE, FacebookSocialGraph.FULL_PROFILE)

  private def createSocialUserInfo(friend: JsValue): (SocialUserInfo, JsValue) =
    (SocialUserInfo(
      fullName = (friend \ "name").asOpt[String].getOrElse(""),
      socialId = SocialId((friend \ "id").as[String]),
      networkType = SocialNetworks.FACEBOOK,
      state = SocialUserInfoStates.FETCHED_USING_FRIEND
    ), friend)
}
