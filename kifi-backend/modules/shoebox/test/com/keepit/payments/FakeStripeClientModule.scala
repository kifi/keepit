package com.keepit.payments

import com.google.inject.{ Provides, Singleton }

case class FakeStripeClientModule() extends StripeClientModule {

  def configure() {}

  @Singleton
  @Provides
  def stripeClient(): StripeClient = {
    new FakeStripeClientImpl()
  }

}
