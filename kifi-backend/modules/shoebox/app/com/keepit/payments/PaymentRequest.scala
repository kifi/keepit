package com.keepit.payments

import com.keepit.common.crypto.PublicId
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

@json
case class CardInfo(last4: String, brand: String)
@json
case class AccountStateResponse(
  users: Int,
  credit: DollarAmount,
  plan: PaidPlanInfo,
  card: Option[CardInfo])

case class AvailablePlansResponse(
  currentPlan: PublicId[PaidPlan],
  allPlans: Map[String, Seq[PaidPlanInfo]])
object AvailablePlansResponse {
  implicit val writes = new Writes[AvailablePlansResponse] {
    def writes(o: AvailablePlansResponse) = Json.obj("plans" -> Json.toJson(o.allPlans), "current" -> Json.toJson(o.currentPlan))
  }
}

sealed abstract class PaymentFail(val status: Int, val message: String) extends Exception(message) with NoStackTrace {
  def asErrorResponse = Status(status)(Json.obj("error" -> message))
}

object PaymentFail {
  case object INSUFFICIENT_PERMISSIONS extends PaymentFail(FORBIDDEN, "insufficient_permissions")
}
