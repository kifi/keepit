package com.keepit.controllers.internal

import com.google.inject.{ Provides, Singleton }

import scala.concurrent.ExecutionContext

case class DevDataPipelineExecutorModule() extends DataPipelineExecutorModule {
  def configure(): Unit = {}

  @Provides @Singleton
  def dataPipelineExecutorProvider(context: ExecutionContext): DataPipelineExecutor = new DataPipelineExecutor(context)

}
