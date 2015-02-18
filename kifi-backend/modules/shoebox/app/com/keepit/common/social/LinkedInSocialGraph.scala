package com.keepit.common.social

import java.nio.charset.StandardCharsets.US_ASCII
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.concurrent.Future
import scala.util.Try

import oauth.signpost.exception.OAuthExpectationFailedException

import org.apache.commons.codec.binary.Base64

import com.google.inject.Inject
import com.keepit.common.db.State
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ CallTimeouts, NonOKResponseException, HttpClient, DirectUrl }
import com.keepit.model.SocialUserInfoStates._
import com.keepit.model.{ UserValueName, SocialUserInfoRepo, SocialUserInfoStates, SocialUserInfo }
import com.keepit.social.{ SocialUserRawInfo, SocialNetworks, SocialId, SocialGraph }

import play.api.http.Status._
import play.api.libs.json._
import play.api.libs.ws.{ WSResponse, WS }

import securesocial.core.{ IdentityId, OAuth2Settings }
import securesocial.core.providers.LinkedInProvider.LinkedIn
import com.keepit.common.mail.EmailAddress
import play.api.Play.current

object LinkedInSocialGraph {
  val ProfileFields = Seq("id", "firstName", "lastName", "picture-urls::(original);secure=true", "publicProfileUrl")
  val ProfileFieldSelector = ProfileFields.mkString("(", ",", ")")
  val ConnectionsPageSize = 500
}

class LinkedInSocialGraph @Inject() (
  client: HttpClient,
  db: Database,
  socialUserInfoRepo: SocialUserInfoRepo)
    extends SocialGraph with Logging {

  import LinkedInSocialGraph.ProfileFieldSelector

  val networkType = SocialNetworks.LINKEDIN

  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo] = {
    val credentials = socialUserInfo.credentials.get

    def fail(msg: String, state: State[SocialUserInfo] = FETCH_FAIL) {
      log.warn(msg)
      db.readWrite { implicit s =>
        socialUserInfoRepo.save(socialUserInfo.withState(state).withLastGraphRefresh())
      }
    }

    val jsonsOpt = if (credentials.oAuth2Info.isDefined) {
      try {
        Some(getJson(socialUserInfo))
      } catch {
        case e @ NonOKResponseException(url, response, _) =>
          if (response.status == UNAUTHORIZED) {
            fail(s"LinkedIn account $socialUserInfo is unauthorized. Response: ${response.json}", APP_NOT_AUTHORIZED)
            None
          } else {
            fail(s"Error fetching LinkedIn connections for $socialUserInfo. Response: ${response.json}")
            throw e
          }
      }
    } else {
      log.warn(s"LinkedIn app not authorized with OAuth2 for $socialUserInfo; not fetching connections.")
      db.readWrite { implicit s =>
        socialUserInfoRepo.save(socialUserInfo.withState(SocialUserInfoStates.APP_NOT_AUTHORIZED).withLastGraphRefresh())
      }
      None
    }

    jsonsOpt map {
      SocialUserRawInfo(
        socialUserInfo.userId,
        socialUserInfo.id,
        SocialId(credentials.identityId.userId),
        SocialNetworks.LINKEDIN,
        credentials.fullName,
        _)
    }
  }

  def extractEmails(parentJson: JsValue): Seq[EmailAddress] = (parentJson \ "emailAddress").asOpt[EmailAddress].toSeq

  def extractFriends(parentJson: JsValue): Seq[SocialUserInfo] =
    ((parentJson \ "values").asOpt[JsArray] getOrElse JsArray()).value collect {
      case jsv if (jsv \ "id").asOpt[String].exists(_ != "private") => createSocialUserInfo(jsv)
    }

  def sendMessage(from: SocialUserInfo, to: SocialUserInfo, subject: String, message: String): Future[WSResponse] =
    WS.url(sendMessageUrl(getAccessToken(from)))
      .withHeaders("x-li-format" -> "json", "Content-Type" -> "application/json")
      .post(sendMessageBody(to.socialId, subject, message))

  def revokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] = {
    // LinkedIn has no way of doing this through the API
    db.readWrite { implicit s =>
      socialUserInfoRepo.save(socialUserInfo.withState(SocialUserInfoStates.APP_NOT_AUTHORIZED).withLastGraphRefresh())
    }
    Future.successful(())
  }

  private def sendMessageUrl(accessToken: String): String = {
    s"https://api.linkedin.com/v1/people/~/mailbox?oauth2_access_token=$accessToken"
  }

  private def sendMessageBody(id: SocialId, subject: String, body: String): JsObject = Json.obj(
    "recipients" -> Json.obj("values" -> Json.arr(
      Json.obj("person" -> Json.obj("_path" -> s"/people/$id"))
    )),
    "subject" -> subject,
    "body" -> body
  )

  private def connectionsUrl(id: SocialId, accessToken: String, start: Int, count: Int): String = {
    s"https://api.linkedin.com/v1/people/$id/connections:$ProfileFieldSelector?format=json" +
      s"&start=$start&count=$count&oauth2_access_token=$accessToken"
  }

  private def profileUrl(id: SocialId, accessToken: String): String = {
    s"https://api.linkedin.com/v1/people/$id:$ProfileFieldSelector?format=json&oauth2_access_token=$accessToken"
  }

  def extractUserValues(json: JsValue): Map[UserValueName, String] = Map.empty

  def updateSocialUserInfo(sui: SocialUserInfo, json: JsValue): SocialUserInfo = {
    (json \ "id").asOpt[String] map { id =>
      assert(sui.socialId.id == id, s"Social id in profile $id should be equal to the existing id ${sui.socialId}")
      sui.copy(
        fullName = ((json \ "firstName").asOpt[String] ++ (json \ "lastName").asOpt[String]).mkString(" "),
        pictureUrl = (json \ "pictureUrls" \ "values").asOpt[JsArray].map(_(0).asOpt[String]).flatten orElse sui.pictureUrl,
        profileUrl = (json \ "publicProfileUrl").asOpt[String] orElse sui.profileUrl
      )
    } getOrElse sui
  }

  /**
   * @param settings The LinkedIn IdentityProvider settings
   * @param json The LinkedIn JS API secure cookie JSON
   * @return the user's confirmed LinkedIn identity
   */
  def vetJsAccessToken(settings: OAuth2Settings, json: JsValue): Try[IdentityId] = Try {
    (json \ "access_token").as[String] // not a valid OAuth 1.0a or 2.0 token
    val memberId = (json \ "member_id").as[String]

    // verify signature
    // #2 at developer.linkedin.com/documents/exchange-jsapi-tokens-rest-api-oauth-tokens
    val signature = (json \ "signature").as[String]
    val signatureOrder = (json \ "signature_order").as[Seq[String]]
    val signatureBase = signatureOrder.fold("") { case (b, k) => b + (json \ k).as[String] }
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(new SecretKeySpec(settings.clientSecret.getBytes(US_ASCII), "HmacSHA1"))
    val computedSignature = mac.doFinal(signatureBase.getBytes(US_ASCII))
    if (java.util.Arrays.equals(new Base64().decode(signature), computedSignature)) {
      IdentityId(userId = memberId, providerId = LinkedIn)
    } else {
      throw new OAuthExpectationFailedException("signature mismatch")
    }
  } // Note: See this method's revision history for working OAuth 1.0a token exchange code

  val TWO_MINUTES = 2 * 60 * 1000
  private def getJson(url: String): JsValue = client.withTimeout(CallTimeouts(responseTimeout = Some(TWO_MINUTES), maxJsonParseTime = Some(20000))).get(DirectUrl(url), client.ignoreFailure).json

  private def getJson(socialUserInfo: SocialUserInfo): Stream[JsValue] = {
    import LinkedInSocialGraph.{ ConnectionsPageSize => PageSize }
    val token = getAccessToken(socialUserInfo)
    val sid = socialUserInfo.socialId
    val connectionsPages = Stream.from(0).map { n =>
      val getUrl = connectionsUrl(sid, token, n * PageSize, PageSize)
      log.info(s"getting connections from linkedin for sui ${socialUserInfo.id.get} #${n} page using url: $getUrl")
      getJson(getUrl)
    }
    val numPages = 1 + connectionsPages.indexWhere(json => (json \ "_count").asOpt[Int].getOrElse(0) < PageSize)
    getJson(profileUrl(sid, token)) #:: connectionsPages.take(numPages)
  }

  private def createSocialUserInfo(friend: JsValue): SocialUserInfo =
    SocialUserInfo(
      fullName = ((friend \ "firstName").asOpt[String] ++ (friend \ "lastName").asOpt[String]).mkString(" "),
      pictureUrl = (friend \ "pictureUrls" \ "values").asOpt[JsArray].map(_(0).asOpt[String]).flatten,
      profileUrl = (friend \ "publicProfileUrl").asOpt[String],
      socialId = SocialId((friend \ "id").as[String]),
      networkType = SocialNetworks.LINKEDIN,
      state = SocialUserInfoStates.FETCHED_USING_FRIEND
    )

  protected def getAccessToken(socialUserInfo: SocialUserInfo): String = {
    val credentials = socialUserInfo.credentials.getOrElse(throw new Exception("Can't find credentials for %s".format(socialUserInfo)))
    val oAuth2Info = credentials.oAuth2Info.getOrElse(throw new Exception("Can't find oAuth2Info for %s".format(socialUserInfo)))
    oAuth2Info.accessToken
  }
}
