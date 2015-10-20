package com.keepit.payments

import com.keepit.common.logging.Logging
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.model.DollarAmount

import play.api.Mode
import play.api.Mode.Mode

import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.JavaConverters.mapAsJavaMapConverter

import com.stripe.Stripe
import com.stripe.model.{ Charge, Customer, Card }
import com.stripe.exception.CardException

case class CardDetails(number: String, expMonth: Int, expYear: Int, cvc: String, cardholderName: String)

sealed trait StripeChargeResult

case class StripeChargeSuccess(amount: DollarAmount, chargeId: String) extends StripeChargeResult

case class StripeChargeFailure(code: String, message: String) extends StripeChargeResult

trait StripeClient {
  def processCharge(amount: DollarAmount, token: StripeToken, description: String): Future[StripeChargeResult]
  def getPermanentToken(token: String, description: String): Future[StripeToken]
  def getPermanentToken(cardDetails: CardDetails, description: String): Future[StripeToken]

  def getLastFourDigitsOfCard(token: StripeToken): Future[String]
  def getCardInfo(token: StripeToken): Future[CardInfo]
}

class StripeClientImpl(mode: Mode, implicit val ec: ExecutionContext) extends StripeClient with Logging {

  val lock = new ReactiveLock(2)

  Stripe.apiKey = if (mode == Mode.Prod) {
    "sk_test_ljj7nL3XLgIlwxefGVRrRpqg" //"sk_live_ZHRnZXBRKuRqupqRme17ZSry" //this is the live token that will cause stripe to actually process charges
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
      StripeChargeSuccess(DollarAmount(charge.getAmount()), charge.getId())
    } catch {
      case ex: CardException => {
        StripeChargeFailure(ex.getCode(), ex.getMessage())
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

  def getCardInfo(token: StripeToken): Future[CardInfo] = lock.withLock {
    val card = Customer.retrieve(token.token).getSources().getData().get(0).asInstanceOf[Card]
    CardInfo(token, card.getLast4, card.getBrand)
  }

}
