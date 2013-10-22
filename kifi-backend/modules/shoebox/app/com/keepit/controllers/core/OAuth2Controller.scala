package com.keepit.controllers.core

import com.google.inject.Inject
import com.keepit.common.controller.{AuthenticatedRequest, ActionAuthenticator, WebsiteController}
import com.keepit.common.logging.Logging
import java.net.URLEncoder
import play.api.mvc.{Action, Results}
import play.api.libs.ws.WS
import scala.concurrent.Await
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.db.slick.Database
import play.api.Play
import play.api.Play.current
import com.keepit.abook.ABookServiceClient
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scala.concurrent.duration._

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

case class OAuth2AccessTokenResponse(
  accessToken:String,
  expiresIn:Int = -1,
  refreshToken:Option[String] = None,
  tokenType:Option[String] = None,
  idToken:Option[String] = None
)

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
) extends WebsiteController(actionAuthenticator) with Logging {

  import OAuth2Providers._
  def start(provider:String, stateTokenOpt:Option[String]) = AuthenticatedJsonAction { implicit request =>
    log.info(s"[oauth2.start]\n\trequest.hdrs=${request.headers}\n\trequest.session=${request.session}")
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
      "login_hint" -> "email address"
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
      throw new IllegalStateException("state token mismatch")
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

    val tokenResp = Await.result(call.map { resp =>
      log.info(s"[oauth2.callback] body=${resp.body}")
      provider match {
        case "google" => {
          val json = resp.json
          log.info(s"[oauth2.callback] $resp json=${Json.prettyPrint(json)}")
          val tokenResp = json.as[OAuth2AccessTokenResponse]
          log.info(s"[oauth2.callback] tokenResp=$tokenResp")
          tokenResp
        }
        case "facebook" => {
          val splitted = resp.body.split("=")
          log.info(s"[oauth2.callback] splitted=${splitted.mkString}")
          if (splitted.length > 1)
            OAuth2AccessTokenResponse(splitted(1))
          else
            OAuth2AccessTokenResponse.EMPTY
        }
      }
    }, 5 seconds)

    provider match {
      case "google" => {
//        val resF = abookServiceClient.importContacts(request.userId, provider, tokenResp.accessToken)
        val res = Await.result(abookServiceClient.importContacts(request.userId, provider, tokenResp.accessToken), 10 seconds)
        log.info(s"[g-contacts] abook uploaded: ${Json.prettyPrint(res)}")
        Redirect(com.keepit.controllers.admin.routes.AdminUserController.userView(request.userId))
      }
      case _ => { // testing only
      val friendsUrl = s"https://graph.facebook.com/me/friends?access_token=${tokenResp.accessToken}&fields=id,name,first_name,last_name,username,picture,email"
        val friends = Await.result(WS.url(friendsUrl).get, 5 seconds).json
        log.info(s"[facebook] friends:\n${Json.prettyPrint(friends)}")
        Ok(friends)
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

  // TODO: move out
  def importContacts(provider:Option[String]) = AuthenticatedHtmlAction { implicit request =>
    // val stateToken = "abk$" + new BigInteger(130, new SecureRandom()).toString(32)
    val stateToken = request.session.get("stateToken")
    val route = routes.OAuth2Controller.start(provider.getOrElse("google"), stateToken)
    log.info(s"[importContacts($provider)] redirect to $route")
    Redirect(route)
  }

}
