package com.keepit.common.concurrent

import com.google.inject.{ Provides, Singleton }
import net.codingwell.scalaguice.ScalaModule

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext }

case class FakeExecutionContextModule() extends ScalaModule {

  def configure {}

  @Singleton
  @Provides
  def executionContext(context: WatchableExecutionContext): ScalaExecutionContext = {
    context
  }

  @Singleton
  @Provides
  def watchableExecutionContext: WatchableExecutionContext = {
    new WatchableExecutionContext()
  }

}
