package com.keepit.slack

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.net.HttpClient
import net.codingwell.scalaguice.ScalaModule
import play.api.Mode.Mode

import scala.concurrent.ExecutionContext

trait SlackClientModule extends ScalaModule

case class ProdSlackClientModule() extends SlackClientModule {
  def configure() {}

  @Singleton
  @Provides
  def stripeClient(httpClient: HttpClient, mode: Mode, ec: ExecutionContext): SlackClient = {
    new SlackClientImpl(httpClient, mode, ec)
  }
}

case class FakeSlackClientModule() extends SlackClientModule {
  def configure() {}

  @Singleton
  @Provides
  def stripeClient(): SlackClient = {
    new FakeSlackClientImpl()
  }
}
