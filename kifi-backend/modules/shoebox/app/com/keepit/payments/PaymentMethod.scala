package com.keepit.payments

import com.keepit.common.db.{ States, ModelWithState, Id, State }

import com.keepit.common.time._

import scala.slick.lifted.MappedTo

import org.joda.time.DateTime

//Put this in a common package?
//The only thing that distinguishes this from a regular boolen, is that at the DB level "false" will be stored as "null"
//thus allowing "only-one-true" constriants. See the corresponding type mapper.
case class TrueOrNull(v: Boolean)

object TrueOrNull {
  implicit def toBoolean(t: TrueOrNull): Boolean = t.v
  implicit def fromBoolean(b: Boolean): TrueOrNull = TrueOrNull(b)
}

case class StripeToken(token: String)

case class PaymentMethod(
    id: Option[Id[PaymentMethod]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[PaymentMethod] = PaymentMethodStates.ACTIVE,
    accountId: Id[PaidAccount],
    default: TrueOrNull,
    stripeToken: StripeToken) extends ModelWithState[PaymentMethod] {

  def withId(id: Id[PaymentMethod]): PaymentMethod = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): PaymentMethod = this.copy(updatedAt = now)
}

object PaymentMethodStates extends States[PaymentMethod] {
  val test: TrueOrNull = false
}
