package com.keepit.common.social

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.{NonOKResponseException, HttpClient}
import com.keepit.model.{SocialUserInfoRepo, SocialUserInfoStates, SocialUserInfo}

import play.api.libs.json._

object FacebookSocialGraph {
  val FULL_PROFILE = "name,first_name,middle_name,last_name,gender,username,languages,installed,devices,email,picture"

  object ErrorSubcodes {
    val AppNotInstalled = 458
  }
}

class FacebookSocialGraph @Inject() (
    httpClient: HttpClient,
    db: Database,
    socialRepo: SocialUserInfoRepo
  ) extends SocialGraph with Logging {

  val networkType = SocialNetworks.FACEBOOK

  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo] = {
    val jsons = try {
      fetchJsons(url(socialUserInfo.socialId, getAccessToken(socialUserInfo)))
    } catch {
      case e @ NonOKResponseException(url, response) =>
        val errorJson = response.json \ "error"
        val errorCode = (errorJson \ "code").asOpt[Int]
        val errorSub = (errorJson \ "error_subcode").asOpt[Int]

        // see https://developers.facebook.com/docs/reference/api/errors/
        // TODO: deal with other errors as we find them and decide on a reasonable action
        import FacebookSocialGraph.ErrorSubcodes._
        (errorCode, errorSub) match {
          case (_, Some(AppNotInstalled)) =>
            log.warn(s"App not authorized for social user $socialUserInfo; not fetching connections.")
            db.readWrite { implicit s =>
              socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.APP_NOT_AUTHORIZED).withLastGraphRefresh())
            }
            Seq()
          case _ =>
            db.readWrite { implicit s =>
              socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.FETCH_FAIL).withLastGraphRefresh())
            }
            throw e
        }
    }
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

  def extractEmails(parentJson: JsValue): Seq[String] = (parentJson \ "email").asOpt[String].toSeq

  def extractFriends(parentJson: JsValue): Seq[(SocialUserInfo, JsValue)] = {
    val friendsArr = ((parentJson \ "friends" \ "data").asOpt[JsArray]
        orElse (parentJson \ "data").asOpt[JsArray]) getOrElse JsArray()
    friendsArr.value map createSocialUserInfo
  }

  protected def getAccessToken(socialUserInfo: SocialUserInfo): String = {
    val credentials = socialUserInfo.credentials.getOrElse(throw new Exception("Can't find credentials for %s".format(socialUserInfo)))
    val oAuth2Info = credentials.oAuth2Info.getOrElse(throw new Exception("Can't find oAuth2Info for %s".format(socialUserInfo)))
    oAuth2Info.accessToken
  }

  private def fetchJsons(url: String): List[JsValue] = {
    val jsons = get(url)
    nextPageUrl(jsons) match {
      case None => List(jsons)
      case Some(nextUrl) => jsons :: fetchJsons(nextUrl)
    }
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
