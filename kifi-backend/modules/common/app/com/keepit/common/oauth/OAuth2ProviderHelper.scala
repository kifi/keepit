package com.keepit.common.oauth

import java.net.URLEncoder
import java.util.UUID

import com.keepit.common.auth.AuthException
import com.keepit.common.logging.Logging
import com.keepit.model.OAuth2TokenInfo
import play.api.Play._
import play.api.cache.Cache
import play.api.libs.ws.{ WSResponse, WS }
import play.api.mvc.{ Call, Results, Result, Request }
import securesocial.core._
import scala.collection.JavaConversions._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import OAuth2Configuration._

/**
 * Adapted from UserIdentityProvider -- less coupled with SS, async vs sync, etc.
 * No functional changes.
 */
trait OAuth2ProviderHelper extends Logging {

  def providerConfig: OAuth2ProviderConfiguration
  def buildTokenInfo(response: WSResponse): OAuth2TokenInfo = {
    log.info(s"[buildTokenInfo] response.body=${response.body}")
    try {
      response.json.as[OAuth2TokenInfo]
    } catch {
      case t: Throwable =>
        throw new AuthException(s"[buildTokenInfo] failed to retrieve token; response.status=${response.status}; body=${response.body}")
    }
  }

  // Next: factor out Result
  def doOAuth2[A]()(implicit request: Request[A]): Future[Either[Result, OAuth2TokenInfo]] = {
    request.queryString.get(OAuth2Constants.Error).flatMap(_.headOption).map(error => {
      error match {
        case OAuth2Constants.AccessDenied => throw new AuthException(s"access denied")
        case _ =>
          throw new AuthException(s"[doOAuth2] error $error returned by the authorization server. Provider type is ${providerConfig.name}")
      }
      throw new AuthException(s"[doOAuth2] error=$error")
    })

    request.queryString.get(OAuth2Constants.Code).flatMap(_.headOption) match {
      case Some(code) =>
        log.info(s"[doOAuth2.2] code=$code")
        // we're being redirected back from the authorization server with the access code.
        val currentStateOpt = for {
          // check if the state we sent is equal to the one we're receiving now before continuing the flow.
          sessionId <- request.session.get(IdentityProvider.SessionId)
          // todo: review this -> clustered environments
          originalState <- Cache.getAs[String](sessionId)
          currentState <- request.queryString.get(OAuth2Constants.State).flatMap(_.headOption) if originalState == currentState
        } yield {
          currentState
        }
        currentStateOpt match {
          case None => throw new IllegalStateException(s"[doOAuth2.2] Failed to validate state")
          case Some(_) =>
            getAccessToken(code) map { accessToken =>
              Right(accessToken)
            }
        }
      case None =>
        // There's no code in the request, this is the first step in the oauth flow
        val state = UUID.randomUUID().toString
        val sessionId = request.session.get(IdentityProvider.SessionId).getOrElse(UUID.randomUUID().toString)
        Cache.set(sessionId, state, 300)
        var params = List(
          (OAuth2Constants.ClientId, providerConfig.clientId),
          (OAuth2Constants.RedirectUri, BetterRoutesHelper.authenticate(providerConfig.name).absoluteURL(IdentityProvider.sslEnabled)),
          (OAuth2Constants.ResponseType, OAuth2Constants.Code),
          (OAuth2Constants.State, state))
        params = (OAuth2Constants.Scope, providerConfig.scope) :: params
        val url = providerConfig.authUrl +
          params.map(p => URLEncoder.encode(p._1, "UTF-8") + "=" + URLEncoder.encode(p._2, "UTF-8")).mkString("?", "&", "")
        log.info(s"[doOAuth2.1] authorizationUrl=${providerConfig.authUrl}; redirect to $url")
        Future.successful(Left(Results.Redirect(url).withSession(request.session + (IdentityProvider.SessionId, sessionId))))
    }
  }

  private def getAccessToken[A](code: String)(implicit request: Request[A]): Future[OAuth2TokenInfo] = {
    val params = Map(
      OAuth2Constants.ClientId -> Seq(providerConfig.clientId),
      OAuth2Constants.ClientSecret -> Seq(providerConfig.clientSecret),
      OAuth2Constants.GrantType -> Seq(OAuth2Constants.AuthorizationCode),
      OAuth2Constants.Code -> Seq(code),
      OAuth2Constants.RedirectUri -> Seq(BetterRoutesHelper.authenticate(providerConfig.name).absoluteURL(IdentityProvider.sslEnabled))
    )
    WS.url(providerConfig.accessTokenUrl).post(params) map { response =>
      buildTokenInfo(response)
    }
  }

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

