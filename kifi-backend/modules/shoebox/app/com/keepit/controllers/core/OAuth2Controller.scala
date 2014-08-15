package com.keepit.controllers.core

import com.google.inject.Inject
import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, WebsiteController }
import com.keepit.common.logging.{ LogPrefix, Logging }
import java.net.URLEncoder
import play.api.mvc._
import play.api.libs.ws.WS
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.db.slick.Database
import play.api.Play
import play.api.Play.current
import com.keepit.abook.ABookServiceClient
import play.api.libs.functional.syntax._
import play.api.libs.json._
import java.security.SecureRandom
import java.math.BigInteger
import com.keepit.model.{ ABookInfo, User, OAuth2Token }
import com.keepit.common.db.{ ExternalId, Id }
import scala.concurrent._
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.util.Failure
import scala.Some
import scala.util.Success
import com.keepit.common.controller.AuthenticatedRequest
import play.api.libs.ws.Response

case class OAuth2Config(provider: String, authUrl: String, accessTokenUrl: String, clientId: String, clientSecret: String, scope: String)

case class OAuth2CommonConfig(approvalPrompt: String)

object OAuth2 {
  val STATE_TOKEN_KEY = "stateToken"
}

object OAuth2Providers { // TODO: wire-in (securesocial) config
  val GOOGLE = OAuth2Config(
    provider = "google",
    authUrl = "https://accounts.google.com/o/oauth2/auth",
    accessTokenUrl = "https://accounts.google.com/o/oauth2/token",
    clientId = "572465886361.apps.googleusercontent.com",
    clientSecret = "heYhp5R2Q0lH26VkrJ1NAMZr",
    scope = "email https://www.googleapis.com/auth/contacts.readonly"
  )
  val FACEBOOK = OAuth2Config(
    provider = "facebook",
    authUrl = "https://www.facebook.com/dialog/oauth",
    accessTokenUrl = "https://graph.facebook.com/oauth/access_token",
    clientId = "186718368182474",
    clientSecret = "36e9faa11e215e9b595bf82459288a41",
    scope = "email"
  )
  val SUPPORTED = Map("google" -> GOOGLE, "facebook" -> FACEBOOK)
}

case class OAuth2AccessTokenRequest(
  clientId: String,
  responseType: String, // code for server
  scope: String,
  redirectUri: String,
  state: Option[String] = None,
  prompt: Option[String] = None,
  accessType: Option[String] = None, // online or offline
  approvalPrompt: Option[String] = None, // force or auto
  loginHint: Option[String] = None // email address or sub
  )

case class OAuth2OfflineAccessTokenRequest(
  refreshToken: String,
  clientId: String,
  clientSecret: String,
  grantType: String = "refresh_token")

case class OAuth2AccessTokenResponse(
    accessToken: String,
    expiresIn: Int = -1,
    refreshToken: Option[String] = None,
    tokenType: Option[String] = None,
    idToken: Option[String] = None) {
  def toOAuth2Token(userId: Id[User]): OAuth2Token =
    OAuth2Token(
      userId = userId,
      accessToken = accessToken,
      expiresIn = Some(expiresIn),
      refreshToken = refreshToken,
      tokenType = tokenType,
      idToken = idToken
    )
}

case class StateToken(token: String, redirectUrl: Option[String])
object StateToken {
  implicit val formatStateToken: Format[StateToken] = (
    (__ \ 'token).format[String] and
    (__ \ 'redirectUrl).formatNullable[String]
  )(StateToken.apply, unlift(StateToken.unapply))
}

object OAuth2AccessTokenResponse {

  val EMPTY = OAuth2AccessTokenResponse("")

  implicit val format = (
    (__ \ 'access_token).format[String] and
    (__ \ 'expires_in).format[Int] and
    (__ \ 'refresh_token).formatNullable[String] and
    (__ \ 'token_type).formatNullable[String] and
    (__ \ 'id_token).formatNullable[String]
  )(OAuth2AccessTokenResponse.apply, unlift(OAuth2AccessTokenResponse.unapply))

}

object OAuth2Controller {
  implicit class RequestWithQueryVal[T](val underlying: Request[T]) extends AnyVal { // todo(ray): move to common
    def queryVal(key: String) = underlying.queryString.get(key).flatMap(_.headOption)
  }
}

import Logging._
import OAuth2._
class OAuth2Controller @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    actionAuthenticator: ActionAuthenticator,
    abookServiceClient: ABookServiceClient,
    oauth2CommonConfig: OAuth2CommonConfig) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController with Logging {

  import OAuth2Providers._
  def start(provider: String, stateTokenOpt: Option[String], approvalPromptOpt: Option[String]) = JsonAction.authenticated { implicit request =>
    implicit val prefix = LogPrefix(s"oauth2.start(${request.userId},$provider,$stateTokenOpt,$approvalPromptOpt)")
    log.infoP(s"headers=${request.headers} session=${request.session}")
    val providerConfig = OAuth2Providers.SUPPORTED.get(provider).getOrElse(GOOGLE) // may enforce stricter check
    val authUrl = providerConfig.authUrl
    stateTokenOpt match {
      case None =>
        log.warnP(s"state token is not provided; body=${request.body} headers=${request.headers}")
        Redirect("/").withSession(session - STATE_TOKEN_KEY)
      case Some(stateToken) =>
        val redirectUri = routes.OAuth2Controller.callback(provider).absoluteURL(Play.isProd)
        val params = Map(
          "response_type" -> "code",
          "client_id" -> providerConfig.clientId,
          "redirect_uri" -> redirectUri,
          "scope" -> providerConfig.scope,
          "state" -> stateToken,
          "access_type" -> "offline",
          "login_hint" -> "email address",
          "approval_prompt" -> approvalPromptOpt.getOrElse(oauth2CommonConfig.approvalPrompt)
        )
        val url = authUrl + params.foldLeft("?") { (a, c) => a + c._1 + "=" + URLEncoder.encode(c._2, "UTF-8") + "&" }
        log.infoP(s"REDIRECT to: $url with params: $params")
        Redirect(authUrl, params.map(kv => (kv._1, Seq(kv._2)))).withSession(session + (STATE_TOKEN_KEY -> stateToken))
    }
  }

  // redirect/GET
  def callback(provider: String) = JsonAction.authenticatedAsync { implicit request =>
    implicit val prefix: LogPrefix = LogPrefix(s"oauth2.callback(${request.userId},$provider)")
    log.infoP(s"headers=${request.headers} session=${request.session}")
    val redirectHome = Redirect(com.keepit.controllers.website.routes.KifiSiteRouter.home).withSession(session - STATE_TOKEN_KEY)
    val redirectInvite = Redirect("/invite").withSession(session - STATE_TOKEN_KEY) // todo: make configurable

    val providerConfig = OAuth2Providers.SUPPORTED.get(provider).getOrElse(GOOGLE)
    val stateOpt = request.queryString.get("state").flatMap(_.headOption)
    val codeOpt = request.queryString.get("code").flatMap(_.headOption)
    val stateOptFromSession = request.session.get("stateToken") orElse Some("")
    log.infoP(s"code=$codeOpt state=$stateOpt stateFromSession=$stateOptFromSession")

    val stateTokenOpt = stateOpt flatMap { Json.parse(_).asOpt[StateToken] }
    val stateTokenOptFromSession = stateOptFromSession flatMap { Json.parse(_).asOpt[StateToken] }

    if (stateTokenOpt.isEmpty || stateTokenOptFromSession.isEmpty || stateTokenOpt.get.token != stateTokenOptFromSession.get.token) {
      log.warnP(s"invalid state token: callback-state=$stateOpt session-stateToken=$stateOptFromSession headers=${request.headers}")
      resolve(redirectInvite)
    } else if (codeOpt.isEmpty) {
      log.warnP(s"code is empty; consent might not have been granted") // for server app
      resolve(redirectInvite)
    } else {
      val redirectUri = routes.OAuth2Controller.callback(provider).absoluteURL(Play.isProd)
      val params = Map(
        "code" -> codeOpt.get,
        "client_id" -> providerConfig.clientId,
        "client_secret" -> providerConfig.clientSecret,
        "redirect_uri" -> redirectUri,
        "grant_type" -> "authorization_code"
      )
      val call = WS.url(providerConfig.accessTokenUrl).post(params.map(kv => (kv._1, Seq(kv._2)))) // POST does not need url encoding
      log.infoP(s"POST to: ${providerConfig.accessTokenUrl} with params: $params")

      val tokenRespOptF = call.map { resp: Response =>
        log.infoP(s"$provider's access token response: ${resp.body}")
        provider match {
          case "google" => resp.status match {
            case OK =>
              resp.json.asOpt[OAuth2AccessTokenResponse] match {
                case Some(tokenResp) =>
                  log.infoP(s"successfully obtained access token; tokenResp=$tokenResp")
                  Some(tokenResp)
                case None =>
                  airbrake.notify(s"$prefix failed to parse/get access token; $provider's response: ${resp.body}")
                  None
              }
            case _ =>
              airbrake.notify(s"$prefix $provider reported failure: status=${resp.status} body=${resp.body}")
              None
          }
          case "facebook" => {
            if (Play.maybeApplication.isDefined && !Play.isProd) { // not supported in prod
              val splitted = resp.body.split("=")
              log.infoP(s"splitted=${splitted.mkString}")
              if (splitted.length > 1)
                Some(OAuth2AccessTokenResponse(splitted(1)))
              else
                None
            } else None
          }
        }
      }

      val redirectHomeF = resolve(redirectHome)
      tokenRespOptF flatMap { tokenRespOpt =>
        tokenRespOpt match {
          case Some(tokenResp) => {
            provider match {
              case "google" => {
                val resF = abookServiceClient.importContacts(request.userId, tokenResp.toOAuth2Token(request.userId))
                val redirectUrlOpt = stateTokenOpt flatMap (_.redirectUrl)
                resF map { trRes =>
                  trRes match {
                    case Failure(t) =>
                      airbrake.notify(s"$prefix Caught exception $t while importing contacts", t)
                      val route = com.keepit.controllers.website.routes.ContactsImportController.importContactsFailure(redirectUrlOpt)
                      Redirect(route).withSession(session - STATE_TOKEN_KEY)
                    case Success(abookInfo) =>
                      log.infoP(s"abook imported: $abookInfo")
                      val route = com.keepit.controllers.website.routes.ContactsImportController.importContactsSuccess(redirectUrlOpt, abookInfo.numContacts)
                      Redirect(route).withSession(session - STATE_TOKEN_KEY)
                  }
                }
              }
              case "facebook" => {
                if (Play.maybeApplication.isDefined && !Play.isProd) {
                  val friendsUrl = "https://graph.facebook.com/me/friends"
                  val friendsF = WS.url(friendsUrl).withQueryString(("access_token", tokenResp.accessToken), ("fields", "id,name,first_name,last_name,username,picture,email")).get
                  friendsF.map { friendsResp =>
                    val friends = friendsResp.json
                    log.infoP("friends:\n${Json.prettyPrint(friends)}")
                    Ok(friends).withSession(session - STATE_TOKEN_KEY)
                  }
                } else redirectHomeF
              }
              case _ => redirectHomeF
            }
          }
          case None => redirectHomeF
        }
      }
    }
  }

  def accessTokenCallback(provider: String) = Action(parse.tolerantJson) { implicit request =>
    log.info(s"[oauth2.accessTokenCallback]\n\trequest.hdrs=${request.headers}\n\trequest.session=${request.session}")
    val providerConfig = OAuth2Providers.SUPPORTED.get(provider).getOrElse(GOOGLE)
    val json = request.body
    log.info(s"[oauth2.accessTokenCallback] provider=$provider json=$json")
    // TODO: persist
    Ok(json)
  }

  def refreshContacts(abookExtId: ExternalId[ABookInfo], provider: Option[String]) = JsonAction.authenticatedAsync { implicit request =>
    abookServiceClient.getABookInfoByExternalId(abookExtId) flatMap { abookInfoOpt =>
      abookInfoOpt flatMap (_.id) map { abookId =>
        refreshContactsHelper(abookId, provider)
      } getOrElse Future.successful(BadRequest("invalid_id"))
    }
  }

  def refreshContacts(abookId: Id[ABookInfo], provider: Option[String]) = JsonAction.authenticatedAsync { implicit request =>
    refreshContactsHelper(abookId, provider)
  }

  private def refreshContactsHelper(abookId: Id[ABookInfo], provider: Option[String])(implicit request: AuthenticatedRequest[AnyContent]): Future[SimpleResult] = {
    implicit val prefix = LogPrefix(s"oauth2.refreshContacts($abookId,$provider)")
    val redirectInvite = Redirect("/friends/invite/email").withSession(session - STATE_TOKEN_KEY)
    log.infoP(s"userId=${request.userId}")
    val userId = request.userId
    val tokenRespOptF = abookServiceClient.getOAuth2Token(userId, abookId) flatMap { tokenOpt =>
      log.infoP(s"abook.getOAuth2Token $tokenOpt")
      tokenOpt match {
        case Some(tk) =>
          val resTK = tk.refreshToken match {
            case None => {
              log.infoP(s"NO refresh token stored") // todo: force
              resolve(None)
            }
            case Some(refreshTk) => {
              val providerConfig = OAuth2Providers.SUPPORTED.get("google").get
              val params = Map(
                "client_id" -> providerConfig.clientId,
                "client_secret" -> providerConfig.clientSecret,
                "refresh_token" -> refreshTk,
                "grant_type" -> "refresh_token"
              )
              val call = WS.url(GOOGLE.accessTokenUrl).post(params.map(kv => (kv._1, Seq(kv._2)))) // POST does not need url encoding
              val tokenRespOptF = call map { resp =>
                if (resp.status == OK) {
                  val tokenResp = resp.json.asOpt[OAuth2AccessTokenResponse]
                  log.infoP(s"tokenResp=$tokenResp")
                  tokenResp
                } else None
              }
              tokenRespOptF
            }
          }
          resTK
        case None => resolve(None)
      }
    }

    val jsF = tokenRespOptF flatMap { tokenRespOpt =>
      tokenRespOpt match {
        case None => future { JsNull }
        case Some(tokenResp) =>
          log.infoP(s"invoking importContacts(${request.userId}, ${tokenResp})")
          abookServiceClient.importContacts(request.userId, tokenResp.toOAuth2Token(request.userId)) map { trRes =>
            trRes match {
              case Failure(t) => log.error(s"[oauth2.refreshContacts] Caught exception $t", t)
              case Success(abookInfo) => abookInfo
            }
            redirectInvite
          }
      }
    }
    jsF map { js =>
      log.info(s"[oauth2.refreshContacts] import result: $js")
      Redirect("/friends/invite/email") // @see InviteController.invite
    }
  }

  def importContacts(provider: Option[String], approvalPromptOpt: Option[String], redirectUrl: Option[String] = None) = HtmlAction.authenticated { implicit request =>
    val stateToken = Json.toJson(StateToken(new BigInteger(130, new SecureRandom()).toString(32), redirectUrl)).toString()
    val route = routes.OAuth2Controller.start(provider.getOrElse("google"), Some(stateToken), approvalPromptOpt)
    log.info(s"[importContacts(${request.userId}, $provider, $approvalPromptOpt)] redirect to $route")
    Redirect(route).withSession(session - STATE_TOKEN_KEY)
  }

}
