package com.keepit.common.oauth

import play.api.mvc.Call

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
