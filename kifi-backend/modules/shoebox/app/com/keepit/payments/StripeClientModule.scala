package com.keepit.payments

import com.google.inject.{ Provides, Singleton }
import net.codingwell.scalaguice.ScalaModule
import play.api.Mode.Mode

import scala.concurrent.ExecutionContext

trait StripeClientModule extends ScalaModule

case class ProdStripeClientModule() extends StripeClientModule {

  def configure() {}

  @Singleton
  @Provides
  def stripeClient(mode: Mode): StripeClient = {
    new StripeClientImpl(mode)
  }

}
