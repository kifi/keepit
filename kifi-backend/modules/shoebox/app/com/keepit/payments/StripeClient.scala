package com.keepit.payments

import com.keepit.common.logging.Logging
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.util.DollarAmount
import com.kifi.macros.json

import play.api.Mode
import play.api.Mode.Mode

import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.JavaConverters.mapAsJavaMapConverter

import com.stripe.Stripe
import com.stripe.model.{ Charge, Refund, Customer, Card }
import com.stripe.exception.CardException

@json
case class StripeTransactionId(id: String)

case class CardDetails(number: String, expMonth: Int, expYear: Int, cvc: String, cardholderName: String)

sealed trait StripeTransactionResult

sealed trait StripeChargeResult extends StripeTransactionResult
case class StripeChargeSuccess(amount: DollarAmount, chargeId: StripeTransactionId) extends StripeChargeResult
case class StripeChargeFailure(code: String, message: String) extends StripeChargeResult

sealed trait StripeRefundResult extends StripeTransactionResult
case class StripeRefundSuccess(amount: DollarAmount, refundId: StripeTransactionId) extends StripeRefundResult
case class StripeRefundFailure(code: String, message: String) extends StripeRefundResult

case class StripeCardInfo(lastFour: String, brand: String)

trait StripeClient {
  def processCharge(amount: DollarAmount, token: StripeToken, description: String): Future[StripeChargeResult]
  def refundCharge(chargeId: StripeTransactionId): Future[StripeRefundResult]
  def getPermanentToken(token: String, description: String): Future[StripeToken]
  def getPermanentToken(cardDetails: CardDetails, description: String): Future[StripeToken]

  def getLastFourDigitsOfCard(token: StripeToken): Future[String]
  def getCardInfo(token: StripeToken): Future[StripeCardInfo]
}

class StripeClientImpl(mode: Mode, implicit val ec: ExecutionContext) extends StripeClient with Logging {

  val lock = new ReactiveLock(2)

  Stripe.apiKey = if (mode == Mode.Prod) {
    "sk_live_ZHRnZXBRKuRqupqRme17ZSry" //this is the live token that will cause stripe to actually process charges
  } else {
    "sk_test_ljj7nL3XLgIlwxefGVRrRpqg" //this is the stripe test token, which lets us make call to them without actually charging anyone.
  }

  def processCharge(amount: DollarAmount, token: StripeToken, description: String): Future[StripeChargeResult] = lock.withLock {
    require(amount.cents > 0)
    val chargeParams: Map[String, java.lang.Object] = Map(
      "amount" -> new java.lang.Integer(amount.cents),
      "currency" -> "usd",
      "customer" -> token.token,
      "description" -> description, //This is for us. It will show up in the stripe dashboard (and in emails stripe sends to the customer if we opt in to that, which is not planned right now)
      "statement_descriptor" -> "Kifi Pro Plan" //This will show up on the customers credit card bill (limited to 22 characters, with "'<> (4 characters) prohibited")
    )
    try {
      val charge = Charge.create(chargeParams.asJava)
      StripeChargeSuccess(DollarAmount(charge.getAmount()), StripeTransactionId(charge.getId()))
    } catch {
      case ex: CardException => {
        StripeChargeFailure(ex.getCode(), ex.getMessage())
      }
    }
  }

  def refundCharge(chargeId: StripeTransactionId): Future[StripeRefundResult] = lock.withLock {
    val chargeParams: Map[String, java.lang.Object] = Map(
      "charge" -> chargeId.id,
      "reason" -> "requested_by_customer"
    )
    try {
      val refund = Refund.create(chargeParams.asJava)
      StripeRefundSuccess(DollarAmount(refund.getAmount()), StripeTransactionId(refund.getId()))
    } catch {
      case ex: CardException => {
        StripeRefundFailure(ex.getCode(), ex.getMessage())
      }
    }
  }

  //to convert a use-once token from Stripe.js into a permanent customer token
  def getPermanentToken(token: String, description: String): Future[StripeToken] = lock.withLock {
    val customerParams: Map[String, java.lang.Object] = Map(
      "source" -> token,
      "description" -> description //This is for us. It will show up in the stripe dashboard
    )
    val customer = Customer.create(customerParams.asJava);
    StripeToken(customer.getId())
  }

  //to create a permanent customer token directly from card details. Intended for admin use, e.g. when someone calls us with a card.
  def getPermanentToken(cardDetails: CardDetails, description: String): Future[StripeToken] = lock.withLock {
    val cardDetailsMap: Map[String, java.lang.Object] = Map(
      "object" -> "card",
      "number" -> cardDetails.number,
      "exp_month" -> new java.lang.Integer(cardDetails.expMonth),
      "exp_year" -> new java.lang.Integer(cardDetails.expYear),
      "cvc" -> cardDetails.cvc,
      "name" -> cardDetails.cardholderName
    )
    val customerParams: Map[String, java.lang.Object] = Map(
      "source" -> cardDetailsMap.asJava,
      "description" -> description //This is for us. It will show up in the stripe dashboard
    )
    val customer = Customer.create(customerParams.asJava);
    StripeToken(customer.getId())
  }

  def getLastFourDigitsOfCard(token: StripeToken): Future[String] = lock.withLock {
    Customer.retrieve(token.token).getSources().getData().get(0).asInstanceOf[Card].getLast4
  }

  def getCardInfo(token: StripeToken): Future[StripeCardInfo] = lock.withLock {
    val card = Customer.retrieve(token.token).getSources().getData().get(0).asInstanceOf[Card]
    StripeCardInfo(card.getLast4, card.getBrand)
  }

}
