package com.keepit.controllers.internal

import akka.actor.ActorSystem
import com.google.inject.{ Provides, Singleton }
import net.codingwell.scalaguice.ScalaModule

trait DataPipelineExecutorModule extends ScalaModule

case class ProdDataPipelineExecutorModule() extends DataPipelineExecutorModule {

  def configure(): Unit = {}

  @Provides
  @Singleton
  def dataPipelineExecutorProvider(system: ActorSystem): DataPipelineExecutor =
    new DataPipelineExecutor(system.dispatchers.lookup("data-pipeline-dispatcher"))
}
