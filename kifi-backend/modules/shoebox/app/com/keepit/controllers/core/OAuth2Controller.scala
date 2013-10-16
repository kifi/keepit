package com.keepit.controllers.core

import com.google.inject.Inject
import com.keepit.common.controller.{ActionAuthenticator, WebsiteController}
import com.keepit.common.logging.Logging
import java.net.URLEncoder
import play.api.mvc.{Action, Results}
import play.api.libs.ws.WS
import scala.concurrent.Await
import scala.collection.immutable
import play.api.libs.json.{Json, JsObject, JsArray}
import com.keepit.common.routes.ABook
import com.keepit.model.ABookOrigins
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.db.slick.Database
import play.api.Play
import play.api.Play.current

case class OAuth2Config(provider:String, authUrl:String, accessTokenUrl:String, redirectUri:String, clientId:String, clientSecret:String, scope:String)

object OAuth2Providers { // TODO: wire-in (securesocial) config
  val GOOGLE = OAuth2Config(
    provider = "google",
    authUrl = "https://accounts.google.com/o/oauth2/auth",
    accessTokenUrl = "https://accounts.google.com/o/oauth2/token",
    redirectUri = "https://dev.ezkeep.com/oauth2/callback/google",
    clientId = "572465886361.apps.googleusercontent.com", // "991651710157.apps.googleusercontent.com",
    clientSecret = "heYhp5R2Q0lH26VkrJ1NAMZr", // "vt9BrxsxM6iIG4EQNkm18L-m",
    scope = "https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/plus.me https://www.google.com/m8/feeds"
  )
  val FACEBOOK = OAuth2Config(
    provider = "facebook",
    authUrl = "https://www.facebook.com/dialog/oauth",
    accessTokenUrl = "https://graph.facebook.com/oauth/access_token",
    redirectUri = "https://dev.ezkeep.com/oauth2/callback/facebook",
    clientId = "186718368182474",
    clientSecret = "36e9faa11e215e9b595bf82459288a41",
    scope = "email"
  )
  val SUPPORTED = Map("google" -> GOOGLE, "facebook" -> FACEBOOK)
}

class OAuth2Controller @Inject() (
  db: Database,
  actionAuthenticator:ActionAuthenticator
) extends WebsiteController(actionAuthenticator) with Logging {

  import OAuth2Providers._
  import scala.concurrent.duration._
  def start(provider:String, stateTokenOpt:Option[String]) = Action { implicit request =>
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
  def callback(provider:String) = Action { implicit request =>
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

    val accToken = Await.result(call.map { resp =>
      log.info(s"[oauth2.callback] body=${resp.body}")
      provider match {
        case "google" => {
          val json = resp.json
          log.info(s"[oauth2.callback] $resp json=$json")
          val accessToken = (json \ "access_token").as[String]
          val refreshToken = (json \ "refresh_token").asOpt[String]
          val expiresIn = (json \ "expires_in").asOpt[String]
          val tokenType = (json \ "token_type").as[String]
          log.info(s"[oauth2.callback] accessToken=$accessToken, refreshToken=$refreshToken tokenType=$tokenType expiresIn=$expiresIn")
          accessToken
        }
        case "facebook" => { // non-std?
          val splitted = resp.body.split("=")
          log.info(s"[oauth2.callback] splitted=${splitted.mkString}")
          if (splitted.length > 1)
            splitted(1)
          else
            "NO_TOKEN"
        }
      }
    }, 5 seconds)

    // TODO: factor out
    provider match {
      case "google" => {
//        val userInfoUrl = s"https://www.googleapis.com/oauth2/v1/userinfo?access_token=$accToken" // testing only
//        val userInfo = Await.result(WS.url(userInfoUrl).get, 5 seconds).json
//        log.info(s"[contacts] userInfo=${Json.prettyPrint(userInfo)}")

        val contactsUrl = s"https://www.google.com/m8/feeds/contacts/default/full?access_token=$accToken" // TODO: alt=json
        val contacts = Await.result(WS.url(contactsUrl).get, 5 seconds).xml
        val jsArrays: immutable.Seq[JsArray] = (contacts \\ "feed").map { feed =>
          val entries: Seq[JsObject] = (feed \\ "entry").map { entry =>
            val title = (entry \\ "title").text
            val emails = (entry \\ "email").map(_ \\ "@address")
            log.info(s"[contacts] title=$title email=$emails")
            Json.obj("name" -> title, "emails" -> Json.toJson(emails.seq.map(_.toString)))
          }
          JsArray(entries)
        }

        // hack: go ahead and add to contacts
        val prefix = if (Play.isDev) "http://" + request.host else "https://" + request.host
        val uploadRoute = prefix + ABook.internal.upload(ABookOrigins.GMAIL).url // no absolute url
        val abookUpload = Json.obj("origin" -> "gmail", "contacts" -> jsArrays(0))
        log.info(Json.prettyPrint(abookUpload))
        val call = WS.url(uploadRoute).withHeaders(request.headers.toSimpleMap.iterator.toArray:_*).post(abookUpload) // hack
        val res = Await.result(call.map(r => r.json), 5 seconds)
        // Ok(res)
        Ok(jsArrays(0))
      }
      case "facebook" => { // testing only
        val friendsUrl = s"https://graph.facebook.com/me/friends?access_token=$accToken&fields=id,name,first_name,last_name,username,picture,email"
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
