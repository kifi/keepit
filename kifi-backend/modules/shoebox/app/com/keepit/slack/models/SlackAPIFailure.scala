package com.keepit.slack.models

import play.api.http.Status._
import play.api.mvc.Results.Status
import play.api.libs.json._

case class SlackAPIFailure(status: Int, error: String, payload: JsValue) extends Exception(s"$status response: $error ($payload)") {
  def asResponse = Status(status)(Json.toJson(this)(SlackAPIFailure.format))
}
object SlackAPIFailure {
  implicit val format: Format[SlackAPIFailure] = Json.format[SlackAPIFailure]

  object Message {
    val REVOKED_WEBHOOK: String = "No service"
    val REVOKED_TOKEN: JsValue = Json.obj("ok" -> false, "error" -> "token_revoked")
  }

  object Error {
    val generic = "api_error"
    val parse = "unparseable_payload"
    val state = "broken_state"
    val invalidAuth = "invalid_auth"
    val revokedWebhook = "revoked_webhook"
    val revokedToken = "revoked_webhook"
    val alreadyReacted = "already_reacted"
  }
  def Generic(status: Int, payload: JsValue) = SlackAPIFailure(status, Error.generic, payload)
  def ParseError(payload: JsValue) = SlackAPIFailure(OK, Error.parse, payload)
  def StateError(state: SlackState) = SlackAPIFailure(OK, Error.state, JsString(state.state))
  val RevokedWebhook = SlackAPIFailure(NOT_FOUND, Error.revokedWebhook, JsNull)
  val RevokedToken = SlackAPIFailure(OK, Error.revokedToken, JsNull)
}

