package com.keepit.common.concurrent

import com.google.inject.{ Provides, Singleton }
import net.codingwell.scalaguice.ScalaModule

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext }

trait ExecutionContextModule extends ScalaModule

case class PlayDefaultExecutionContextModule() extends ExecutionContextModule {

  def configure {}

  @Singleton
  @Provides
  def executionContext: ScalaExecutionContext = play.api.libs.concurrent.Execution.Implicits.defaultContext
}

case class FJExecutionContextModule() extends ExecutionContextModule {

  def configure {}

  @Singleton
  @Provides
  def executionContext: ScalaExecutionContext = ExecutionContext.fj
}

case class MonitoredExecutionContextModule() extends ExecutionContextModule {
  def configure {}

  @Provides @Singleton
  def executionContext: ScalaExecutionContext = {
    val underlyingExecutionContext = play.api.libs.concurrent.Execution.Implicits.defaultContext
    new MonitoredExecutionContext(underlyingExecutionContext, 1.0)
  }
}
