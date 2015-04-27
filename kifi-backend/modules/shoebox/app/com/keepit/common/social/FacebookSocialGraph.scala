package com.keepit.common.social

import java.nio.charset.StandardCharsets.US_ASCII
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.concurrent.Future
import scala.util.Try

import com.google.inject.Inject
import com.keepit.common.db.State
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.performance._
import com.keepit.common.healthcheck.{ StackTrace, AirbrakeNotifier }
import com.keepit.common.mail.{ EmailAddress, LocalPostOffice }
import com.keepit.common.net.{ CallTimeouts, DirectUrl, HttpClient, NonOKResponseException, Request }
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.social.{ SocialId, SocialUserRawInfo, SocialNetworks, SocialGraph }

import oauth.signpost.exception.OAuthExpectationFailedException

import org.apache.commons.codec.binary.Base64

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ Json, JsArray, JsValue }

import securesocial.core.{ IdentityId, OAuth2Settings }
import securesocial.core.providers.FacebookProvider.Facebook

object FacebookSocialGraph {
  val PERSONAL_PROFILE = "name,first_name,middle_name,last_name,gender,email,picture.type(large)"
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
    clock: Clock,
    postOffice: LocalPostOffice,
    socialRepo: SocialUserInfoRepo,
    airbrake: AirbrakeNotifier) extends SocialGraph with Logging {

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
    val res = jsonsOpt.flatMap { jsons =>
      jsons.headOption.map { json =>
        SocialUserRawInfo(
          socialUserInfo.userId,
          socialUserInfo.id,
          SocialId((json \ "id").as[String]),
          SocialNetworks.FACEBOOK,
          (json \ "name").asOpt[String].getOrElse(socialUserInfo.fullName),
          jsons)
      }
    }
    log.info(s"[fetchSocialUserRawInfo] socialUserInfo=$socialUserInfo rawInfo=$res")
    res
  }

  def extractEmails(parentJson: JsValue): Seq[EmailAddress] = (parentJson \ "email").asOpt[EmailAddress].toSeq

  def extractFriends(parentJson: JsValue): Seq[SocialUserInfo] = {
    val friendsArr = ((parentJson \ "friends" \ "data").asOpt[JsArray]
      orElse (parentJson \ "data").asOpt[JsArray]) getOrElse JsArray()
    friendsArr.value map createSocialUserInfo
  }

  def revokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] = {
    val accessToken = getAccessToken(socialUserInfo)
    val url = s"https://graph.facebook.com/v2.0/${socialUserInfo.socialId}/permissions?access_token=$accessToken"
    httpClient.withTimeout(CallTimeouts(responseTimeout = Some(TWO_MINUTES), maxJsonParseTime = Some(20000))).deleteFuture(DirectUrl(url)).map(_ => ())
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

  def extractUserValues(json: JsValue): Map[UserValueName, String] = Seq(
    (json \ "gender").asOpt[String].map(Gender.key -> Gender(_).toString)
  ).flatten.toMap

  /**
   * @param settings The Facebook IdentityProvider settings
   * @param json The Facebook JS API authResponse JSON
   * @return the user's confirmed Facebook identity
   */
  def vetJsAccessToken(settings: OAuth2Settings, json: JsValue): Try[IdentityId] = Try {
    (json \ "accessToken").as[String] // not bothering to persist because it's short-lived

    // verify signature as at developers.facebook.com/docs/facebook-login/using-login-with-games#parsingsr
    val Array(signature, payload, _*) = (json \ "signedRequest").as[String].split('.')
    val base64 = new Base64()
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(settings.clientSecret.getBytes(US_ASCII), "HmacSHA256"))
    val computedSignature = mac.doFinal(payload.getBytes(US_ASCII))
    if (java.util.Arrays.equals(base64.decode(signature), computedSignature)) {
      val o = Json.parse(base64.decode(payload))
      val userId = (o \ "user_id").as[String]
      val issuedAt = (o \ "issued_at").as[Long]
      val now = clock.getMillis / 1000
      if (math.abs(now - issuedAt) < 300) { // less than 5 min old
        IdentityId(userId = userId, providerId = Facebook)
      } else {
        throw new OAuthExpectationFailedException(s"${now - issuedAt}s is too old")
      }
    } else {
      throw new OAuthExpectationFailedException("signature mismatch")
    }
  }

  private def fetchJsons(url: String, socialUserInfo: SocialUserInfo): Stream[JsValue] = {
    val jsons = get(url, socialUserInfo)
    log.info(s"downloaded FB json using $url")
    jsons #:: nextPageUrl(jsons).map(nextUrl => fetchJsons(nextUrl, socialUserInfo)).getOrElse(Stream.empty)
  }

  private[social] def nextPageUrl(json: JsValue): Option[String] = (json \ "friends" \ "paging" \ "next").asOpt[String].orElse((json \ "paging" \ "next").asOpt[String])

  private def get(url: String, socialUserInfo: SocialUserInfo): JsValue = timing(s"fetching FB JSON using $url") {
    val client = httpClient.withTimeout(CallTimeouts(responseTimeout = Some(TWO_MINUTES), maxJsonParseTime = Some(20000)))
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
    s"https://graph.facebook.com/v2.0/${id.id}?access_token=$accessToken&fields=${FacebookSocialGraph.PERSONAL_PROFILE},friends.fields(${FacebookSocialGraph.FRIEND_PROFILE}).limit($FETCH_LIMIT)"

  private def createSocialUserInfo(friend: JsValue): SocialUserInfo =
    SocialUserInfo(
      fullName = (friend \ "name").asOpt[String].getOrElse(""),
      socialId = SocialId((friend \ "id").as[String]),
      networkType = SocialNetworks.FACEBOOK,
      state = SocialUserInfoStates.FETCHED_USING_FRIEND
    )
}
