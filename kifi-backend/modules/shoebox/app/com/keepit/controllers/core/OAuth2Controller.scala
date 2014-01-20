package com.keepit.controllers.core

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, ActionAuthenticator, WebsiteController}
import com.keepit.common.logging.Logging
import java.net.URLEncoder
import play.api.mvc.{Action, Results}
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
import com.keepit.model.{ABookInfo, User, OAuth2Token}
import com.keepit.common.db.Id
import scala.concurrent._

case class OAuth2Config(provider:String, authUrl:String, accessTokenUrl:String, clientId:String, clientSecret:String, scope:String)

object OAuth2Providers { // TODO: wire-in (securesocial) config
  val GOOGLE = OAuth2Config(
    provider = "google",
    authUrl = "https://accounts.google.com/o/oauth2/auth",
    accessTokenUrl = "https://accounts.google.com/o/oauth2/token",
    clientId = "572465886361.apps.googleusercontent.com", // "991651710157.apps.googleusercontent.com",
    clientSecret = "heYhp5R2Q0lH26VkrJ1NAMZr", // "vt9BrxsxM6iIG4EQNkm18L-m",
    scope = "https://www.googleapis.com/auth/userinfo.email https://www.google.com/m8/feeds"// "https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/plus.me"
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
  clientId:String,
  responseType:String, // code for server
  scope:String,
  redirectUri:String,
  state:Option[String] = None,
  prompt:Option[String] = None,
  accessType:Option[String] = None, // online or offline
  approvalPrompt:Option[String] = None, // force or auto
  loginHint:Option[String] = None // email address or sub
)

case class OAuth2OfflineAccessTokenRequest(
  refreshToken:String,
  clientId:String,
  clientSecret:String,
  grantType:String = "refresh_token"
)

case class OAuth2AccessTokenResponse(
  accessToken:String,
  expiresIn:Int = -1,
  refreshToken:Option[String] = None,
  tokenType:Option[String] = None,
  idToken:Option[String] = None
) {
  def toOAuth2Token(userId:Id[User]):OAuth2Token =
    OAuth2Token(
      userId = userId,
      accessToken = accessToken,
      expiresIn = Some(expiresIn),
      refreshToken = refreshToken,
      tokenType = tokenType,
      idToken = idToken
    )
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

class OAuth2Controller @Inject() (
  db: Database,
  actionAuthenticator:ActionAuthenticator,
  abookServiceClient:ABookServiceClient
) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController with Logging {

  val approvalPrompt = sys.props.getOrElse("oauth2.approval.prompt", "force")

  import OAuth2Providers._
  def start(provider:String, stateTokenOpt:Option[String], approvalPromptOpt:Option[String]) = AuthenticatedJsonAction { implicit request =>
    log.info(s"[oauth2.start($provider, $stateTokenOpt, $approvalPromptOpt)]\n\trequest.hdrs=${request.headers}\n\trequest.session=${request.session}")
    val providerConfig = OAuth2Providers.SUPPORTED.get(provider).getOrElse(GOOGLE)
    val authUrl = providerConfig.authUrl
    val stateToken:String = stateTokenOpt.getOrElse(request.session.get("stateToken").getOrElse(throw new IllegalStateException("stateToken not set")))
    val redirectUri = routes.OAuth2Controller.callback(provider).absoluteURL(Play.isProd)
    val params = Map(
      "response_type" -> "code",
      "client_id" -> providerConfig.clientId,
      "redirect_uri" -> redirectUri,
      "scope" -> providerConfig.scope,
      "state" -> stateToken,
      "access_type" -> "offline",
      "login_hint" -> "email address",
      "approval_prompt" -> approvalPromptOpt.getOrElse(approvalPrompt)
    )
    val url = authUrl + params.foldLeft("?"){(a,c) => a + c._1 + "=" + URLEncoder.encode(c._2, "UTF-8") + "&"}
    log.info(s"[oauth2start($provider, $stateToken)] REDIRECT to: $url with params: $params")
    Results.Redirect(authUrl, params.map(kv => (kv._1, Seq(kv._2))))
  }

  // redirect/GET
  def callback(provider:String) = AuthenticatedJsonAction { implicit request =>
    log.info(s"[oauth2.callback]\n\trequest.hdrs=${request.headers}\n\trequest.session=${request.session}")
    val providerConfig = OAuth2Providers.SUPPORTED.get(provider).getOrElse(GOOGLE)
    val state = request.queryString.get("state").getOrElse(Seq.empty[String]) // see oauth2
    val stateFromSession = request.session.get("stateToken").getOrElse("")
    log.info(s"[oauth2.callback] state=$state stateFromSession=$stateFromSession")
    if (state.isEmpty || state(0) != stateFromSession) {
      log.warn(s"[oauth2.callback] state token mismatch")
//      throw new IllegalStateException("state token mismatch")
    }

    val code = request.queryString.get("code").getOrElse(Seq(""))(0)
    log.info(s"code=$code")

    val redirectUri = routes.OAuth2Controller.callback(provider).absoluteURL(Play.isProd)
    val params = Map(
      "code" -> code,
      "client_id" -> providerConfig.clientId,
      "client_secret" -> providerConfig.clientSecret,
      "redirect_uri" -> redirectUri,
      "grant_type" -> "authorization_code"
    )
    val call = WS.url(providerConfig.accessTokenUrl).post(params.map(kv => (kv._1, Seq(kv._2)))) // POST does not need url encoding
    log.info(s"[oauth2.callback($provider)] POST to: ${providerConfig.accessTokenUrl} with params: $params")

    val redirectHomeF = Future { Redirect(com.keepit.controllers.website.routes.HomeController.home) }

    val tokenRespOptF = call.map { resp =>
      log.info(s"[oauth2.callback] body=${resp.body}")
      provider match {
        case "google" => {
          if (resp.status == OK) {
            val json = resp.json
            log.info(s"[oauth2.callback] $resp json=${Json.prettyPrint(json)}")
            val tokenResp = json.asOpt[OAuth2AccessTokenResponse]
            log.info(s"[oauth2.callback] tokenResp=$tokenResp")
            tokenResp
          } else {
            log.warn(s"OAuth2 failure from $provider: $resp") // TODO: retry
            None
          }
        }
        case "facebook" => {
          if (Play.maybeApplication.isDefined && !Play.isProd) { // not supported in prod
            val splitted = resp.body.split("=")
            log.info(s"[oauth2.callback] splitted=${splitted.mkString}")
            if (splitted.length > 1)
              Some(OAuth2AccessTokenResponse(splitted(1)))
            else
              None
          } else None
        }
      }
    }

    Async {
      tokenRespOptF flatMap { tokenRespOpt =>
        tokenRespOpt match {
          case Some(tokenResp) => {
            provider match {
              case "google" => {
                val resF = abookServiceClient.importContactsP(request.userId, tokenResp.toOAuth2Token(request.userId))
                resF map { res =>
                  log.info(s"[google] abook import $res")
                  // Redirect(com.keepit.controllers.admin.routes.AdminUserController.userView(request.userId)) // todo: for admin page
                  Redirect("/friends/invite/email") // @see InviteController.invite
                }
              }
              case "facebook" => {
                if (Play.maybeApplication.isDefined && !Play.isProd) {
                  val friendsUrl = "https://graph.facebook.com/me/friends"
                  val friendsF = WS.url(friendsUrl).withQueryString(("access_token", tokenResp.accessToken),("fields","id,name,first_name,last_name,username,picture,email")).get
                  friendsF.map { friendsResp =>
                    val friends = friendsResp.json
                    log.info(s"[facebook] friends:\n${Json.prettyPrint(friends)}")
                    Ok(friends)
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

  def accessTokenCallback(provider:String) = Action(parse.json) { implicit request =>
    log.info(s"[oauth2.accessTokenCallback]\n\trequest.hdrs=${request.headers}\n\trequest.session=${request.session}")
    val providerConfig = OAuth2Providers.SUPPORTED.get(provider).getOrElse(GOOGLE)
    val json = request.body
    log.info(s"[oauth2.accessTokenCallback] provider=$provider json=$json")
    // TODO: persist
    Ok(json)
  }

  def refreshContacts(abookId:Id[ABookInfo], provider:Option[String]) = AuthenticatedJsonAction { implicit request =>
    log.info(s"[oauth2.refreshContacts] abookId=$abookId provider=$provider userId=${request.userId}")
    val userId = request.userId
    val tokenRespOptF = abookServiceClient.getOAuth2Token(userId, abookId) flatMap { tokenOpt =>
      log.info(s"[oauth2.refreshContacts] abook.getOAuth2Token $tokenOpt")
      tokenOpt match {
        case Some(tk) =>
          val resTK = tk.refreshToken match {
            case None => {
              log.info(s"[oauth2.refreshContacts] NO refresh token stored") // todo: force
              future { None }
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
                    log.info(s"[oauth2.refreshContacts] tokenResp=$tokenResp")
                    tokenResp
                  } else None
                }
              tokenRespOptF
            }
          }
          resTK
        case None => future { None }
      }
    }

    val jsF = tokenRespOptF flatMap { tokenRespOpt =>
      tokenRespOpt match {
        case None => future { JsNull }
        case Some(tokenResp) =>
          log.info(s"[oauth2.refreshContacts] invoking importContacts(${request.userId}, ${tokenResp})")
          abookServiceClient.importContactsP(request.userId, tokenResp.toOAuth2Token(request.userId))
      }
    }

    Async {
      jsF map { js =>
        log.info(s"[oauth2.refreshContacts] import result: $js")
        Redirect("/friends/invite/email") // @see InviteController.invite
      }
    }
  }

  // TODO: move out
  def importContacts(provider:Option[String], approvalPromptOpt:Option[String]) = AuthenticatedHtmlAction { implicit request =>
    val stateToken = request.session.get("stateToken").getOrElse {
      "/friends/invite$" + new BigInteger(130, new SecureRandom()).toString(32)
    }
    val route = routes.OAuth2Controller.start(provider.getOrElse("google"), Some(stateToken), approvalPromptOpt)
    log.info(s"[importContacts($provider, $approvalPromptOpt)] redirect to $route")
    Redirect(route).withSession(session + ("stateToken" -> stateToken))
  }

}
