package com.keepit.slack.models

import play.api.http.Status
import play.api.http.Status._
import play.api.libs.json._

case class SlackAPIFailure(status: Int, error: String, payload: JsValue) extends Exception(s"$status response: $error ($payload)") {
  import play.api.mvc.Results.Status
  def asResponse = Status(status)(Json.toJson(this)(SlackAPIFailure.format))
}
object SlackAPIFailure {
  implicit val format: Format[SlackAPIFailure] = Json.format[SlackAPIFailure]

  object Error {
    val parse = "unparseable_payload"
    val state = "broken_state"
    val invalidAuth = "invalid_auth"
    val webhookRevoked = "webhook_revoked"
    val channelNotFound = "channel_not_found"
    val tokenRevoked = "token_revoked"
    val accountInactive = "account_inactive"
    val alreadyReacted = "already_reacted"
    val noAuthCode = "no_auth_code"
    val invalidAuthState = "invalid_auth_state"
  }
  def ApiError(status: Int, payload: JsValue) = SlackAPIFailure(status, (payload \ "error").asOpt[String] getOrElse "api_error", payload)
  def ParseError(payload: JsValue) = SlackAPIFailure(OK, Error.parse, payload)
  def StateError(state: SlackAuthState) = SlackAPIFailure(OK, Error.state, JsString(state.state))
  val TokenRevoked = SlackAPIFailure(Status.NOT_FOUND, SlackAPIFailure.Error.tokenRevoked, JsNull)
  val WebhookRevoked = SlackAPIFailure(Status.NOT_FOUND, SlackAPIFailure.Error.webhookRevoked, JsNull)
  val NoAuthCode = SlackAPIFailure(Status.BAD_REQUEST, SlackAPIFailure.Error.noAuthCode, JsNull)
  val InvalidAuthState = SlackAPIFailure(Status.BAD_REQUEST, SlackAPIFailure.Error.invalidAuthState, JsNull)
  val NoValidWebhooks = SlackAPIFailure(Status.BAD_REQUEST, "no_valid_webhooks", JsNull)
  val NoValidToken = SlackAPIFailure(Status.BAD_REQUEST, "no_valid_token", JsNull)
  val NoValidBotToken = SlackAPIFailure(Status.BAD_REQUEST, "no_valid_bot_token", JsNull)
  val NoValidPushMethod = SlackAPIFailure(Status.BAD_REQUEST, "no_valid_push_method", JsNull)
}

