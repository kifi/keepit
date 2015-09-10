package com.keepit.payments

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

import java.util.concurrent.atomic.AtomicInteger

case class FakeTransaction(id: String, amount: DollarAmount, token: StripeToken, description: String)

class FakeStripeClientImpl extends StripeClient {

  private val tokenCounter = new AtomicInteger(0)
  private val chargeCounter = new AtomicInteger(0)

  var transactions = Map[StripeToken, ArrayBuffer[FakeTransaction]]()

  private def newToken(): Future[StripeToken] = Future.successful {
    val num = tokenCounter.getAndIncrement()
    val token = StripeToken(s"faketoken_$num")
    transactions = transactions + (token -> ArrayBuffer.empty)
    token
  }

  def processCharge(amount: DollarAmount, token: StripeToken, description: String): Future[StripeChargeResult] = Future.successful {
    val num = chargeCounter.getAndIncrement()
    val trans = FakeTransaction(s"faketransaction_$num", amount, token, description)
    transactions(token).append(trans)
    StripeChargeSuccess(trans.id)
  }

  def getPermanentToken(token: String, description: String): Future[StripeToken] = newToken()
  def getPermanentToken(cardDetails: CardDetails, description: String): Future[StripeToken] = newToken()

}
