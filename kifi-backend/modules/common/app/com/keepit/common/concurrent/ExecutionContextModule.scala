package com.keepit.common.concurrent

import com.google.inject.{ Provides, Singleton }
import net.codingwell.scalaguice.ScalaModule

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext }

case class ExecutionContextModule() extends ScalaModule {

  def configure {}

  @Singleton
  @Provides
  def executionContext: ScalaExecutionContext = play.api.libs.concurrent.Execution.Implicits.defaultContext
}

case class FJExecutionContextModule() extends ScalaModule {

  def configure {}

  @Singleton
  @Provides
  def executionContext: ScalaExecutionContext = ExecutionContext.fj
}
