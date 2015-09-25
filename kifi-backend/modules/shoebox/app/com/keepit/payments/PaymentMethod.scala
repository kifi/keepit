package com.keepit.payments

import com.keepit.common.db.{ States, ModelWithState, Id, State }
import com.keepit.common.crypto.{ ModelWithPublicId, ModelWithPublicIdCompanion }
import com.keepit.common.time._

import scala.slick.lifted.MappedTo

import org.joda.time.DateTime

import javax.crypto.spec.IvParameterSpec

case class StripeToken(token: String)

object StripeToken {
  val DELETED = StripeToken("deleted")
}

case class PaymentMethod(
    id: Option[Id[PaymentMethod]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[PaymentMethod] = PaymentMethodStates.ACTIVE,
    accountId: Id[PaidAccount],
    default: Boolean,
    stripeToken: StripeToken) extends ModelWithPublicId[PaymentMethod] with ModelWithState[PaymentMethod] {

  def withId(id: Id[PaymentMethod]): PaymentMethod = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): PaymentMethod = this.copy(updatedAt = now)
  def withState(state: State[PaymentMethod]): PaymentMethod = this.copy(state = state)
}

object PaymentMethod extends ModelWithPublicIdCompanion[PaymentMethod] {

  protected[this] val publicIdPrefix = "pm"
  protected[this] val publicIdIvSpec = new IvParameterSpec(Array(-27, 11, 110, 93, 103, 55, -9, -3, -10, 73, -17, -49, -97, -78, 29, 78))

  def applyFromDbRow(id: Option[Id[PaymentMethod]], createdAt: DateTime, updatedAt: DateTime, state: State[PaymentMethod], accountId: Id[PaidAccount], default: Option[Boolean], stripeToken: StripeToken) = {
    PaymentMethod(id, createdAt, updatedAt, state, accountId, default.exists(b => b), stripeToken)
  }

  def unapplyFromDbRow(obj: PaymentMethod) = {
    Some((obj.id, obj.createdAt, obj.updatedAt, obj.state, obj.accountId, if (obj.default) Some(true) else None, obj.stripeToken))
  }
}

object PaymentMethodStates extends States[PaymentMethod]
