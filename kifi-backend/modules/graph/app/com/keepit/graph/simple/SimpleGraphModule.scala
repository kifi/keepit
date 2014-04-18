package com.keepit.graph.simple

import com.keepit.graph.manager._
import com.google.inject.{Singleton, Provides}
import play.api.Play.current
import scala.util.Try
import com.kifi.franz.SQSQueue
import scala.util.Success
import scala.util.Failure
import com.keepit.common.zookeeper.ServiceDiscovery
import java.io.File

trait SimpleGraphModule extends GraphManagerModule {

  @Provides @Singleton
  def simpleGraphManager(graphDirectory: SimpleGraphDirectory, graphQueue: SQSQueue[GraphUpdate], graphUpdater: GraphUpdater, serviceDiscovery: ServiceDiscovery): GraphManager = {
    val (simpleGraph, state) = Try(graphDirectory.load()) match {
      case Success((graph, state)) =>
        log.info(s"Successfully loaded SimpleGraph from disk. State:\n$state")
        (graph, state)
      case Failure(ex) =>
        log.error(s"Failed to load SimpleGraph from disk - ${ex}")
        log.info(s"Rebuilding SimpleGraph from scratch")
        (SimpleGraph(), GraphUpdaterState.empty)
    }

    new SimpleGraphManager(simpleGraph, state, graphDirectory, graphUpdater, serviceDiscovery)
  }

  protected def getArchivedSimpleGraphDirectory(path: String, graphStore: GraphStore): ArchivedSimpleGraphDirectory = {
    val archivedDirectory = new ArchivedSimpleGraphDirectory(new File(path), graphStore)
    archivedDirectory.init()
    archivedDirectory
  }
}

case class SimpleGraphProdModule() extends SimpleGraphModule {

  @Provides @Singleton
  def simpleGraphDirectory(graphStore: GraphStore): SimpleGraphDirectory = {
    val path = current.configuration.getString("graph.simple.directory").get
    getArchivedSimpleGraphDirectory(path, graphStore: GraphStore)
  }
}

case class SimpleGraphDevModule() extends SimpleGraphModule {

  @Provides @Singleton
  def simpleGraphDirectory(graphStore: GraphStore): GraphDirectory = {
    current.configuration.getString("graph.simple.directory") match {
      case Some(path) => getArchivedSimpleGraphDirectory(path, graphStore: GraphStore)
      case None => new RatherUselessSimpleGraphDirectory()
    }
  }
}
