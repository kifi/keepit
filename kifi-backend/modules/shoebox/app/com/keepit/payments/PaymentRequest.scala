package com.keepit.payments

import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model._
import com.kifi.macros.json
import play.api.libs.json._
import play.api.mvc.Results.Status
import play.api.http.Status._

import scala.util.control.NoStackTrace

sealed abstract class PaymentRequest

@json
case class SimpleAccountContactSettingRequest(id: ExternalId[User], enabled: Boolean) extends PaymentRequest

sealed abstract class PaymentFail(val status: Int, val message: String) extends Exception(message) with NoStackTrace {
  def asErrorResponse = Status(status)(Json.obj("error" -> message))
}

object PaymentFail {
  case object INSUFFICIENT_PERMISSIONS extends PaymentFail(FORBIDDEN, "insufficient_permissions")
}
