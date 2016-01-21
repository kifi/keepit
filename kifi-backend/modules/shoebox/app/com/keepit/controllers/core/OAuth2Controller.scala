package com.keepit.controllers.core

import com.google.inject.Inject
import com.keepit.common.cache._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper, UserRequest }
import com.keepit.common.logging.{ AccessLog, LogPrefix, Logging }
import java.net.{ URLDecoder, URLEncoder }
import com.keepit.common.oauth.{ OAuth2Configuration }
import com.kifi.macros.json
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
import scala.concurrent.duration.Duration
import scala.util.{ Try, Failure, Success }
import play.api.libs.ws.WSResponse
import Logging._

case class OAuth2CommonConfig(approvalPrompt: String)

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

@json case class StateToken(token: String, redirectUrl: Option[String])

case class StateTokenKey(userId: Id[User]) extends Key[StateToken] {
  override val version = 1
  val namespace = "oauth2_state_token"
  def toKey(): String = userId.id.toString
}

class StateTokenCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[StateTokenKey, StateToken](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

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

class OAuth2Controller @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    val userActionsHelper: UserActionsHelper,
    stateTokenCache: StateTokenCache,
    abookServiceClient: ABookServiceClient,
    oauth2Config: OAuth2Configuration,
    oauth2CommonConfig: OAuth2CommonConfig) extends UserActions with ShoeboxServiceController with Logging {

  def start(provider: String, stateTokenOpt: Option[String], approvalPromptOpt: Option[String]) = UserAction { implicit request =>
    implicit val prefix = LogPrefix(s"oauth2.start(${request.userId},$provider,$stateTokenOpt,$approvalPromptOpt)")
    log.infoP(s"headers=${request.headers} session=${request.session}")
    val providerConfig = oauth2Config.getProviderConfig(provider).getOrElse {
      throw new IllegalArgumentException(s"[OAuth2Controller.start] provider=$provider not supported")
    }
    val authUrl = providerConfig.authUrl
    stateTokenOpt match {
      case None =>
        log.warnP(s"state token is not provided; body=${request.body} headers=${request.headers}")
        Redirect("/")
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
        val url = authUrl.toString + params.foldLeft("?") { (a, c) => a + c._1 + "=" + URLEncoder.encode(c._2, "UTF-8") + "&" }
        log.infoP(s"REDIRECT to: $url with params: $params")
        Redirect(authUrl.toString, params.map(kv => (kv._1, Seq(kv._2))))
    }
  }

  // redirect/GET
  def callback(provider: String, codeOpt: Option[String], state: String) = UserAction.async { implicit request =>
    implicit val prefix: LogPrefix = LogPrefix(s"oauth2.callback(${request.userId},$provider)")
    log.infoP(s"headers=${request.headers} session=${request.session.data}")
    val redirectHome = Redirect(com.keepit.controllers.website.routes.HomeController.home)
    val redirectInvite = Redirect("/invite")

    val providerConfig = oauth2Config.getProviderConfig(provider) getOrElse {
      throw new IllegalArgumentException(s"[OAuth2Controller.callback] provider=$provider not supported")
    }

    implicit val dca = TransactionalCaching.Implicits.directCacheAccess
    val cachedTokenOpt = stateTokenCache.get(StateTokenKey(request.userId))
    log.infoP(s"code=$codeOpt state=$state cached=$cachedTokenOpt state==cached is ${state.exists(_ == cachedTokenOpt.get.token)}")

    if (cachedTokenOpt.isEmpty) {
      log.warnP(s"invalid state token: callback-state=$state cached=$cachedTokenOpt")
      resolve(redirectInvite)
    } else if (codeOpt.isEmpty) {
      log.warnP(s"code is empty; consent might not have been granted") // for server app
      resolve(redirectInvite)
    } else {
      if (state != cachedTokenOpt.get.token) { // todo: make this a real guard
        airbrake.notify(s"state token mismatch state=$state cached=$cachedTokenOpt")
      }
      val redirectUri = routes.OAuth2Controller.callback(provider).absoluteURL(Play.isProd)
      val params = Map(
        "code" -> codeOpt.get,
        "client_id" -> providerConfig.clientId,
        "client_secret" -> providerConfig.clientSecret,
        "redirect_uri" -> redirectUri,
        "grant_type" -> "authorization_code"
      )
      val call = WS.url(providerConfig.accessTokenUrl.toString).post(params.map(kv => (kv._1, Seq(kv._2))))
      log.infoP(s"POST to: ${providerConfig.accessTokenUrl} with params: $params")

      val tokenRespOptF = call.map { resp: WSResponse =>
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
        }
      }

      val redirectHomeF = resolve(redirectHome)
      tokenRespOptF flatMap { tokenRespOpt =>
        tokenRespOpt match {
          case Some(tokenResp) => {
            provider match {
              case "google" => {
                val resF = abookServiceClient.importContacts(request.userId, tokenResp.toOAuth2Token(request.userId))
                val redirectUrlOpt = cachedTokenOpt flatMap (_.redirectUrl)
                resF map { trRes =>
                  trRes match {
                    case Failure(t) =>
                      airbrake.notify(s"$prefix Caught exception $t while importing contacts", t)
                      val route = com.keepit.controllers.website.routes.ContactsImportController.importContactsFailure(redirectUrlOpt)
                      Redirect(route)
                    case Success(abookInfo) =>
                      log.infoP(s"abook imported: $abookInfo")
                      val route = com.keepit.controllers.website.routes.ContactsImportController.importContactsSuccess(redirectUrlOpt, abookInfo.numContacts)
                      Redirect(route)
                  }
                }
              }
              case _ => redirectHomeF
            }
          }
          case None => redirectHomeF
        }
      }
    }
  }

  def refreshContacts(abookExtId: ExternalId[ABookInfo], provider: Option[String]) = UserAction.async { implicit request =>
    abookServiceClient.getABookInfoByExternalId(abookExtId) flatMap { abookInfoOpt =>
      abookInfoOpt flatMap (_.id) map { abookId =>
        refreshContactsHelper(abookId, provider)
      } getOrElse Future.successful(BadRequest("invalid_id"))
    }
  }

  def refreshContacts(abookId: Id[ABookInfo], provider: Option[String]) = UserAction.async { implicit request =>
    refreshContactsHelper(abookId, provider)
  }

  private def refreshContactsHelper(abookId: Id[ABookInfo], provider: Option[String])(implicit request: UserRequest[_]): Future[Result] = {
    implicit val prefix = LogPrefix(s"oauth2.refreshContacts($abookId,$provider)")
    val redirectInvite = Redirect("/friends/invite/email")
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
              val providerConfig = oauth2Config.getProviderConfig(provider.get).getOrElse {
                throw new IllegalArgumentException(s"[OAuth2Controller.refreshContactsHelper] provider=$provider not supported")
              }
              val params = Map(
                "client_id" -> providerConfig.clientId,
                "client_secret" -> providerConfig.clientSecret,
                "refresh_token" -> refreshTk,
                "grant_type" -> "refresh_token"
              )
              val call = WS.url(providerConfig.accessTokenUrl.toString).post(params.map(kv => (kv._1, Seq(kv._2)))) // POST does not need url encoding
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
        case None => Future { JsNull }
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

  def importContacts(provider: Option[String], approvalPromptOpt: Option[String], redirectUrl: Option[String] = None) = UserPage { implicit request =>
    val stateToken = StateToken(new BigInteger(130, new SecureRandom()).toString(32), redirectUrl)
    implicit val dca = TransactionalCaching.Implicits.directCacheAccess
    stateTokenCache.set(StateTokenKey(request.userId), stateToken)
    val route = routes.OAuth2Controller.start(provider.getOrElse("google"), Some(stateToken.token), approvalPromptOpt)
    log.info(s"[importContacts(${request.userId}, $provider, $approvalPromptOpt)] redirect to $route")
    Redirect(route)
  }

}

object OAuth2Helper extends Logging {
  def getStateToken(tk: String): Option[StateToken] = {
    val decoded = URLDecoder.decode(tk, "UTF-8")
    log.infoP(s"tk=$tk decoded=$decoded")
    Try {
      Json.parse(decoded).asOpt[StateToken]
    } getOrElse None
  }
}
