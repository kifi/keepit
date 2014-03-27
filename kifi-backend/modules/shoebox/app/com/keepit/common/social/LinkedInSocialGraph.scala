package com.keepit.common.social

import java.math.BigInteger
import java.security.SecureRandom

import scala.collection.JavaConversions._
import scala.concurrent.Future
import oauth.signpost.OAuth
import javax.crypto.{Mac, SecretKey}
import javax.crypto.spec.SecretKeySpec
import org.apache.commons.codec.binary.Base64
import oauth.signpost.exception.{OAuthExpectationFailedException, OAuthCommunicationException, OAuthException}

//import scala.util.{Failure, Success, Try}

import com.google.inject.Inject
import com.keepit.common.db.State
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.net.{CallTimeouts, NonOKResponseException, HttpClient, DirectUrl}
import com.keepit.common.time._
import com.keepit.model.SocialUserInfoStates._
import com.keepit.model.{SocialUserInfoRepo, SocialUserInfoStates, SocialUserInfo}
//import com.keepit.serializer.SocialUserSerializer.oAuth2InfoSerializer
import com.keepit.social.{SocialUserRawInfo, SocialNetworks, SocialId, SocialGraph, SecureSocialClientIds}

// import net.oauth.{OAuth, OAuthAccessor, OAuthConsumer, OAuthMessage}
import oauth.signpost.http.{HttpParameters, HttpRequest}
import oauth.signpost.signature.HmacSha1MessageSigner

import play.api.http.Status._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
//import play.api.libs.oauth.{ConsumerKey, OAuthCalculator, RequestToken}
import play.api.libs.ws.{Response, WS}

import securesocial.core.{IdentityId, IdentityProvider, OAuth1Info, OAuth2Constants, OAuth2Info, OAuth2Provider}

object LinkedInSocialGraph {
  val ProfileFields = Seq("id","firstName","lastName","picture-urls::(original);secure=true","publicProfileUrl")
  val ProfileFieldSelector = ProfileFields.mkString("(",",",")")
  val ConnectionsPageSize = 500
  val SecureRandom = new SecureRandom()
}

class LinkedInSocialGraph @Inject() (
    client: HttpClient,
    db: Database,
    clock: Clock,
    socialRepo: SocialUserInfoRepo,
    secureSocialClientIds: SecureSocialClientIds)
  extends SocialGraph with Logging {

  import LinkedInSocialGraph.ProfileFieldSelector

  val networkType = SocialNetworks.LINKEDIN

  def fetchSocialUserRawInfo(socialUserInfo: SocialUserInfo): Option[SocialUserRawInfo] = {
    val credentials = socialUserInfo.credentials.get

    def fail(msg: String, state: State[SocialUserInfo] = FETCH_FAIL) {
      log.warn(msg)
      db.readWrite { implicit s =>
        socialRepo.save(socialUserInfo.withState(state).withLastGraphRefresh())
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
        socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.APP_NOT_AUTHORIZED).withLastGraphRefresh())
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

  def extractEmails(parentJson: JsValue): Seq[String] = (parentJson \ "emailAddress").asOpt[String].toSeq

  def extractFriends(parentJson: JsValue): Seq[SocialUserInfo] =
    ((parentJson \ "values").asOpt[JsArray] getOrElse JsArray()).value collect {
      case jsv if (jsv \ "id").asOpt[String].exists(_ != "private") => createSocialUserInfo(jsv)
    }

  def sendMessage(from: SocialUserInfo, to: SocialUserInfo, subject: String, message: String): Future[Response] =
    WS.url(sendMessageUrl(getAccessToken(from)))
      .withHeaders("x-li-format" -> "json", "Content-Type" -> "application/json")
      .post(sendMessageBody(to.socialId, subject, message))

  def revokePermissions(socialUserInfo: SocialUserInfo): Future[Unit] = {
    // LinkedIn has no way of doing this through the API
    db.readWrite { implicit s =>
      socialRepo.save(socialUserInfo.withState(SocialUserInfoStates.APP_NOT_AUTHORIZED).withLastGraphRefresh())
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

  // from developer.linkedin.com/documents/exchange-jsapi-tokens-rest-api-oauth-tokens
  private val tokenExchangeUrl = "https://api.linkedin.com/uas/oauth/accessToken"

  def extractUserValues(json: JsValue): Map[String, String] = Map.empty

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
   * @param provider The LinkedIn IdentityProvider
   * @param json The LinkedIn JS API secure cookie JSON, which looks like:
   * {{{
   *   {
   *     "access_token": "JLHAX38MUY45Jwng4IQ3Md09UCy-_SxyZx4z",
   *     "member_id": "Aor3grqQ9s",
   *     "signature": "REe+slSueMZGhkc7PaMy6x7os14=",
   *     "signature_method": "HMAC-SHA1",
   *     "signature_order": ["access_token","member_id"],
   *     "signature_version": "1"
   *   }
   * }}}
   * @return the user's confirmed LinkedIn identity
   */
  def vetJsAccessToken(provider: IdentityProvider, json: JsValue): Future[IdentityId] = {
    val settings = provider.asInstanceOf[OAuth2Provider].settings
    val memberId = (json \ "member_id").as[String]
    val accessToken = (json \ "access_token").as[String]

    // verify signature
    // #2 at developer.linkedin.com/documents/exchange-jsapi-tokens-rest-api-oauth-tokens
    val signature = (json \ "signature").as[String]
    val signatureOrder = (json \ "signature_order").as[Seq[String]]
    val signatureBase = signatureOrder.fold(""){ case (b, k) => b + (json \ k).as[String] }
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(new SecretKeySpec(OAuth.percentEncode(settings.clientSecret).getBytes(OAuth.ENCODING), "HmacSHA1"))
    val computedSignature = mac.doFinal(signatureBase.getBytes(OAuth.ENCODING))
    if (!java.util.Arrays.equals(new Base64().decode(signature), computedSignature)) {
      return Future.failed(new OAuthExpectationFailedException("signature mismatch"))
    }

    // exchange JS API bearer token for OAuth 1.0a access token (LinkedIn does not yet support exchange for 2.0 access token)
    // #3 at developer.linkedin.com/documents/exchange-jsapi-tokens-rest-api-oauth-tokens
    val params = new HttpParameters()
    params.put("oauth_consumer_key", settings.clientId)
    params.put("xoauth_oauth2_access_token", accessToken)
    params.put("oauth_timestamp", (clock.getMillis() / 1000).toString)
    params.put("oauth_nonce", java.lang.Long.toString(math.abs(LinkedInSocialGraph.SecureRandom.nextLong()), 36))
    params.put("oauth_signature_method", "HMAC-SHA1")
    params.put("oauth_version", "1.0")
    val signer = new HmacSha1MessageSigner()
    signer.setConsumerSecret(settings.clientSecret)
    val url = tokenExchangeUrl +
      params.keySet.toSet.fold(""){ case (s, k) => (if (s == "") "?" else s + "&") + params.getAsQueryString(k) } +
      "&oauth_signature=" + signer.sign(TokenExchangeRequest, params)
    WS.url(url)
      .post("")
      .transform({ r =>
        if (r.status == 200) {
          val p: Map[String, Seq[String]] = play.core.parsers.FormUrlEncodedParser.parse(r.body)
          // TODO: persist these credentials?
          OAuth1Info(
            token = p("oauth_token").head,
            secret = p("oauth_token_secret").head)
          p("oauth_expires_in").head.toInt
          IdentityId(userId = memberId, providerId = provider.id)
        } else {
          // println(s"-----------: ${r.status}\n${r.body}")
          throw new OAuthCommunicationException("Status: " + r.status, r.body)
        }
      }, { t =>
        // println(s"-----------: ${t.getClass} ${t.getMessage}")
        t
      })
  }

  val TWO_MINUTES = 2 * 60 * 1000
  private def getJson(url: String): JsValue = client.withTimeout(CallTimeouts(responseTimeout = Some(TWO_MINUTES), maxJsonParseTime = Some(20000))).get(DirectUrl(url), client.ignoreFailure).json

  private def getJson(socialUserInfo: SocialUserInfo): Stream[JsValue] = {
    import LinkedInSocialGraph.{ConnectionsPageSize => PageSize}
    val token = getAccessToken(socialUserInfo)
    val sid = socialUserInfo.socialId
    val connectionsPages = Stream.from(0).map { n => getJson(connectionsUrl(sid, token, n*PageSize, PageSize)) }
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

  private object TokenExchangeRequest extends HttpRequest {
    def getMethod = "POST"
    def getRequestUrl = tokenExchangeUrl
    def setRequestUrl(url: String): Unit = ???
    def getAllHeaders() = ??? //java.util.Collections.emptyMap[String, String]
    def getHeader(name: String): String = ???
    def setHeader(name: String, value: String): Unit = ???
    def getContentType(): String = ???
    def getMessagePayload(): java.io.InputStream = ???
    def unwrap(): Object = ???
  }
}
