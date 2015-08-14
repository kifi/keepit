package com.keepit.payments

import com.keepit.common.db.{ States, ModelWithState, Id, State }

import com.keepit.common.time._

import scala.slick.lifted.MappedTo

import org.joda.time.DateTime

case class StripeToken(token: String)

case class PaymentMethod(
    id: Option[Id[PaymentMethod]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[PaymentMethod] = PaymentMethodStates.ACTIVE,
    accountId: Id[PaidAccount],
    default: Boolean,
    stripeToken: StripeToken) extends ModelWithState[PaymentMethod] {

  def withId(id: Id[PaymentMethod]): PaymentMethod = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): PaymentMethod = this.copy(updatedAt = now)
}

object PaymentMethod {
  def applyFromDbRow(id: Option[Id[PaymentMethod]], createdAt: DateTime, updatedAt: DateTime, state: State[PaymentMethod], accountId: Id[PaidAccount], default: Option[Boolean], stripeToken: StripeToken) = {
    PaymentMethod(id, createdAt, updatedAt, state, accountId, default.exists(b => b), stripeToken)
  }

  def unapplyFromDbRow(obj: PaymentMethod) = {
    Some((obj.id, obj.createdAt, obj.updatedAt, obj.state, obj.accountId, if (obj.default) Some(true) else None, obj.stripeToken))
  }
}

object PaymentMethodStates extends States[PaymentMethod]
