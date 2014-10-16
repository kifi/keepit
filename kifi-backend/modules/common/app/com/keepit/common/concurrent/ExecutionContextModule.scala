package com.keepit.common.concurrent

import com.google.inject.{ Provides, Singleton }
import net.codingwell.scalaguice.ScalaModule

import scala.concurrent.ExecutionContext

case class ExecutionContextModule() extends ScalaModule {

  def configure {}

  @Singleton
  @Provides
  def executionContext: ExecutionContext = play.api.libs.concurrent.Execution.Implicits.defaultContext
}
