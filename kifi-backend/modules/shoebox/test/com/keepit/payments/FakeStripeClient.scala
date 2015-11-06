package com.keepit.payments

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

import java.util.concurrent.atomic.AtomicInteger
import com.stripe.exception.APIConnectionException
import com.stripe.exception.InvalidRequestException

case class FakeTransaction(id: StripeTransactionId, amount: DollarAmount, token: StripeToken, description: String)

class FakeStripeClientImpl extends StripeClient {

  private val tokenCounter = new AtomicInteger(0)
  private val chargeCounter = new AtomicInteger(0)

  var transactions = Map[StripeToken, ArrayBuffer[FakeTransaction]]()

  var cardFailureMode = false
  var stripeDownMode = false

  private def newToken(): Future[StripeToken] = Future.successful {
    val num = tokenCounter.getAndIncrement()
    val token = StripeToken(s"faketoken_$num")
    transactions = transactions + (token -> ArrayBuffer.empty)
    token
  }

  def processCharge(amount: DollarAmount, token: StripeToken, description: String): Future[StripeChargeResult] = {
    if (stripeDownMode) Future.failed(new APIConnectionException("Stripe is down!"))
    else if (cardFailureMode) {
      Future.successful(StripeChargeFailure("boom", "boom"))
    } else {
      val num = chargeCounter.getAndIncrement()
      val trans = FakeTransaction(StripeTransactionId(s"ch_$num"), amount, token, description)
      transactions(token).append(trans)
      Future.successful(StripeChargeSuccess(amount, trans.id))
    }
  }

  def refundCharge(chargeId: StripeTransactionId): Future[StripeRefundResult] = {
    if (stripeDownMode) Future.failed(new APIConnectionException("Stripe is down!"))
    else if (cardFailureMode) {
      Future.successful(StripeRefundFailure("boom", "boom"))
    } else {
      transactions.valuesIterator.flatten.filter(_.id == chargeId).toSeq match {
        case Seq(charge) =>
          val num = chargeCounter.getAndIncrement()
          val trans = FakeTransaction(StripeTransactionId(s"re_$num"), -charge.amount, charge.token, "Refund")
          transactions(charge.token).append(trans)
          Future.successful(StripeRefundSuccess(charge.amount, trans.id))
        case _ => Future.failed(new APIConnectionException(s"Charge ${chargeId.id} not found!"))
      }
    }
  }

  def getPermanentToken(token: String, description: String): Future[StripeToken] = newToken()
  def getPermanentToken(cardDetails: CardDetails, description: String): Future[StripeToken] = newToken()

  def getLastFourDigitsOfCard(token: StripeToken): Future[String] = Future.successful("1234")
  def getCardInfo(token: StripeToken): Future[CardInfo] = Future.successful(CardInfo("1234", "Kifi"))
}
