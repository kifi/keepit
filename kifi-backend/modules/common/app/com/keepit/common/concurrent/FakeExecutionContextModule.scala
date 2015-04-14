package com.keepit.common.concurrent

import com.google.inject.{ Provides, Singleton }
import net.codingwell.scalaguice.ScalaModule
import play.api.Mode

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext }

// This code is available in production but should be used only in dev mode for now
// WatchableExecutionContext will throw runtime exception if running on prod to make sure
case class FakeExecutionContextModule() extends ExecutionContextModule {

  def configure {}

  @Singleton
  @Provides
  def executionContext(context: WatchableExecutionContext): ScalaExecutionContext = {
    context
  }

  @Singleton
  @Provides
  def watchableExecutionContext(mode: Mode.Mode): WatchableExecutionContext = {
    new WatchableExecutionContext(mode)
  }

}
