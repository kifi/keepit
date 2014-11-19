package com.keepit.social

import _root_.java.net.URLEncoder
import _root_.java.util.UUID
import com.keepit.common.controller.KifiSession
import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.User

import play.api.Play.current
import play.api.mvc._
import securesocial.core._
import play.api.cache.Cache
import scala.collection.JavaConversions._
import play.api.libs.ws.{ WS, WSResponse }
import play.api.libs.json._
import securesocial.core.AccessDeniedException
import securesocial.core.IdentityId
import play.api.libs.json.JsString
import play.api.libs.json.JsNumber
import play.api.mvc.Call
import securesocial.core.OAuth2Info
import securesocial.core.AuthenticationException
import KifiSession._

/**
 * An identity provider which returns UserIdentity instances. This allows us to know the currently logged in user when
 * SecureSocial authenticates the same user via a different social network.
 *
 * All our providers should extend this trait.
 */
trait UserIdentityProvider extends IdentityProvider with Logging {

  abstract override def authenticate[A]()(implicit request: Request[A]): Either[Result, Identity] = {
    log.info(s"UserIdentityProvider got request: $request")
    log.info(s"session data: ${request.session.data}")
    val userIdOpt = request.session.getUserId
    doAuth()(request) match {
      case Right(socialUser) =>
        val filledSocialUser = fillProfile(socialUser)
        val saved = UserService.save(UserIdentity(userIdOpt, filledSocialUser))
        Right(saved)
      case left => left
    }
  }

  override def doAuth[A]()(implicit request: Request[A]): Either[Result, SocialUser] = {
    request.queryString.get(OAuth2Constants.Error).flatMap(_.headOption).map(error => {
      error match {
        case OAuth2Constants.AccessDenied => throw new AccessDeniedException()
        case _ =>
          log.error("[securesocial] error '%s' returned by the authorization server. Provider type is %s".format(error, id))
          throw new AuthenticationException()
      }
      throw new AuthenticationException()
    })

    request.queryString.get(OAuth2Constants.Code).flatMap(_.headOption) match {
      case Some(code) =>
        // we're being redirected back from the authorization server with the access code.
        val user = for (
          // check if the state we sent is equal to the one we're receiving now before continuing the flow.
          sessionId <- request.session.get(IdentityProvider.SessionId);
          // todo: review this -> clustered environments
          originalState <- Cache.getAs[String](sessionId);
          currentState <- request.queryString.get(OAuth2Constants.State).flatMap(_.headOption) if originalState == currentState
        ) yield {
          val accessToken = getAccessToken(code)
          val oauth2Info = Some(
            OAuth2Info(accessToken.accessToken, accessToken.tokenType, accessToken.expiresIn, accessToken.refreshToken)
          )
          SocialUser(IdentityId("", id), "", "", "", None, None, authMethod, oAuth2Info = oauth2Info)
        }
        if (log.isDebugEnabled) {
          log.debug("[securesocial] user = " + user)
        }
        user match {
          case Some(u) => Right(u)
          case _ => throw new AuthenticationException()
        }
      case None =>
        // There's no code in the request, this is the first step in the oauth flow
        val state = UUID.randomUUID().toString
        val sessionId = request.session.get(IdentityProvider.SessionId).getOrElse(UUID.randomUUID().toString)

        Cache.set(sessionId, state, 300)
        var params = List(
          (OAuth2Constants.ClientId, newSettings.clientId),
          (OAuth2Constants.RedirectUri, BetterRoutesHelper.authenticate(id).absoluteURL(IdentityProvider.sslEnabled)),
          (OAuth2Constants.ResponseType, OAuth2Constants.Code),
          (OAuth2Constants.State, state))
        newSettings.scope.foreach(s => { params = (OAuth2Constants.Scope, s) :: params })
        newSettings.authorizationUrlParams.foreach(e => { params = e :: params })
        val url = newSettings.authorizationUrl +
          params.map(p => URLEncoder.encode(p._1, "UTF-8") + "=" + URLEncoder.encode(p._2, "UTF-8")).mkString("?", "&", "")
        if (log.isDebugEnabled) {
          log.debug("[securesocial] authorizationUrl = %s".format(newSettings.authorizationUrl))
          log.debug("[securesocial] redirecting to: [%s]".format(url))
        }
        Left(Results.Redirect(url).withSession(request.session + (IdentityProvider.SessionId, sessionId)))
    }
  }

  lazy val newSettings = createSettings()
  private def createSettings(): PimpedOAuth2Settings = {
    val result = for {
      authorizationUrl <- loadProperty(OAuth2Settings.AuthorizationUrl);
      accessToken <- loadProperty(OAuth2Settings.AccessTokenUrl);
      clientId <- loadProperty(OAuth2Settings.ClientId);
      clientSecret <- loadProperty(OAuth2Settings.ClientSecret)
    } yield {
      val scope = current.configuration.getString(propertyKey + OAuth2Settings.Scope)
      val authorizationUrlParams: Map[String, String] =
        current.configuration.getObject(propertyKey + PimpedOAuth2Settings.AuthorizationUrlParams).map { o =>
          o.unwrapped.mapValues(_.toString).toMap
        }.getOrElse(Map())
      val accessTokenUrlParams: Map[String, String] =
        current.configuration.getObject(propertyKey + PimpedOAuth2Settings.AccessTokenUrlParams).map { o =>
          o.unwrapped.mapValues(_.toString).toMap
        }.getOrElse(Map())
      PimpedOAuth2Settings(authorizationUrl, accessToken, clientId, clientSecret, scope, authorizationUrlParams, accessTokenUrlParams)
    }
    if (!result.isDefined) {
      throwMissingPropertiesException()
    }
    result.get
  }

  private def getAccessToken[A](code: String)(implicit request: Request[A]): OAuth2Info = {
    val params = Map(
      OAuth2Constants.ClientId -> Seq(newSettings.clientId),
      OAuth2Constants.ClientSecret -> Seq(newSettings.clientSecret),
      OAuth2Constants.GrantType -> Seq(OAuth2Constants.AuthorizationCode),
      OAuth2Constants.Code -> Seq(code),
      OAuth2Constants.RedirectUri -> Seq(BetterRoutesHelper.authenticate(id).absoluteURL(IdentityProvider.sslEnabled))
    ) ++ newSettings.accessTokenUrlParams.mapValues(Seq(_))
    val call = WS.url(newSettings.accessTokenUrl).post(params)
    try {
      buildInfo(awaitResult(call))
    } catch {
      case e: Exception => {
        log.error("[securesocial] error trying to get an access token for provider %s".format(id), e)
        throw new AuthenticationException()
      }
    }
  }

  protected def buildInfo(response: WSResponse): OAuth2Info = {
    val parsed = try {
      response.json.as[JsObject].value
    } catch {
      case _: Throwable =>
        response.body.split("&").map { kv =>
          val p = kv.split("=").take(2)
          p(0) -> (if (p.length == 2) {
            try { JsNumber(p(1).toInt) } catch {
              case _: Throwable => JsString(p(1))
            }
          } else JsNull)
        }.toMap
    }

    log.info("[securesocial] got json back [" + parsed + "]")
    if (log.isDebugEnabled) {
      log.debug("[securesocial] got json back [" + parsed + "]")
    }
    OAuth2Info(
      parsed.get(OAuth2Constants.AccessToken).map(_.as[String]).get,
      parsed.get(OAuth2Constants.TokenType).map(_.asOpt[String]).flatten,
      parsed.get(OAuth2Constants.ExpiresIn).map(_.asOpt[Int]).flatten,
      parsed.get(OAuth2Constants.RefreshToken).map(_.asOpt[String]).flatten
    )
  }

}

case class PimpedOAuth2Settings(authorizationUrl: String, accessTokenUrl: String, clientId: String,
  clientSecret: String, scope: Option[String],
  authorizationUrlParams: Map[String, String], accessTokenUrlParams: Map[String, String])

object PimpedOAuth2Settings {
  val AuthorizationUrl = "authorizationUrl"
  val AccessTokenUrl = "accessTokenUrl"
  val AuthorizationUrlParams = "authorizationUrlParams"
  val AccessTokenUrlParams = "accessTokenUrlParams"
  val ClientId = "clientId"
  val ClientSecret = "clientSecret"
  val Scope = "scope"
}

object OAuth2Constants {
  val ClientId = "client_id"
  val ClientSecret = "client_secret"
  val RedirectUri = "redirect_uri"
  val Scope = "scope"
  val ResponseType = "response_type"
  val State = "state"
  val GrantType = "grant_type"
  val AuthorizationCode = "authorization_code"
  val AccessToken = "access_token"
  val Error = "error"
  val Code = "code"
  val TokenType = "token_type"
  val ExpiresIn = "expires_in"
  val RefreshToken = "refresh_token"
  val AccessDenied = "access_denied"
}

object BetterRoutesHelper {
  // todo(andrew): Fix!
  def authenticateByPost(provider: scala.Predef.String): play.api.mvc.Call = {
    Call("POST", s"/authenticate/$provider")
  }
  def authenticate(provider: scala.Predef.String): play.api.mvc.Call = {
    Call("GET", s"/authenticate/$provider")
  }
}
