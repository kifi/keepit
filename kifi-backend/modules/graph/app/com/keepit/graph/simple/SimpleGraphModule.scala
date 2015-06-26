package com.keepit.graph.simple

import com.keepit.graph.manager._
import com.google.inject.{ Singleton, Provides }
import play.api.Play.current
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import com.keepit.common.zookeeper.ServiceDiscovery
import java.io.File
import org.apache.commons.io.FileUtils
import com.keepit.common.healthcheck.AirbrakeNotifier

trait SimpleGraphModule extends GraphManagerModule {

  @Provides @Singleton
  def simpleGraphManager(graphDirectory: SimpleGraphDirectory, graphUpdater: GraphUpdater, serviceDiscovery: ServiceDiscovery, airbrake: AirbrakeNotifier): GraphManager = {
    val (simpleGraph, state) = Try(graphDirectory.load()) match {
      case Success((graph, state)) =>
        log.info(s"Successfully loaded SimpleGraph from disk. State:\n$state")
        (graph, state)
      case Failure(ex) =>
        //airbrake.notify("Failed to load SimpleGraph from disk", ex)
        log.error(s"Failed to load SimpleGraph from disk - ${ex}")
        log.info(s"Rebuilding SimpleGraph from scratch")
        (SimpleGraph(), GraphUpdaterState.empty)
    }

    new SimpleGraphManager(simpleGraph, state, graphDirectory, graphUpdater, serviceDiscovery, airbrake)
  }

  protected def getArchivedSimpleGraphDirectory(path: String, graphStore: GraphStore, serviceDiscovery: ServiceDiscovery): ArchivedSimpleGraphDirectory = {
    cleanOldVersions(path)
    val (dir, tempDir) = getVersionedGraphDirectories(path, GraphVersion.getVersionByStatus(serviceDiscovery))
    FileUtils.deleteDirectory(tempDir)
    FileUtils.forceMkdir(tempDir)
    tempDir.deleteOnExit()
    val archivedDirectory = new ArchivedSimpleGraphDirectory(dir, tempDir, graphStore)
    archivedDirectory.init()
    archivedDirectory
  }

  // returns graph directory and temp graph directory for the given version.
  private def getVersionedGraphDirectories(basepath: String, graphVersion: GraphVersion): (File, File) = {
    val dir = new File(basepath + graphVersion.dirSuffix)
    val tempDir = new File(current.configuration.getString("graph.temporary.directory").get, dir.getName())
    (dir, tempDir)
  }

  private def cleanOldVersions(basepath: String): Unit = {
    val versionsToClean = GraphVersion.getVersionsForCleanup()
    versionsToClean.foreach { version =>
      val (dir, tmpdir) = getVersionedGraphDirectories(basepath, version)
      try {
        log.info(s"removing old graph directories if they exist: ${dir.getName}, ${tmpdir.getName}")
        FileUtils.deleteDirectory(dir)
        FileUtils.deleteDirectory(tmpdir)
      } catch {
        case ex: Exception => log.error(s"error in removing old graph directories: ${dir.getName}, ${tmpdir.getName}")
      }
    }
  }

}

case class SimpleGraphProdModule() extends SimpleGraphModule {

  @Provides @Singleton
  def simpleGraphDirectory(graphStore: GraphStore, serviceDiscovery: ServiceDiscovery): SimpleGraphDirectory = {
    val path = current.configuration.getString("graph.simple.directory").get
    getArchivedSimpleGraphDirectory(path, graphStore: GraphStore, serviceDiscovery)
  }
}

case class SimpleGraphDevModule() extends SimpleGraphModule {

  @Provides @Singleton
  def simpleGraphDirectory(graphStore: GraphStore, serviceDiscovery: ServiceDiscovery): SimpleGraphDirectory = {
    current.configuration.getString("graph.simple.directory") match {
      case Some(path) => getArchivedSimpleGraphDirectory(path, graphStore: GraphStore, serviceDiscovery)
      case None => new RatherUselessSimpleGraphDirectory()
    }
  }
}

case class SimpleGraphTestModule() extends SimpleGraphModule {

  @Provides @Singleton
  def simpleGraphDirectory(): SimpleGraphDirectory = new RatherUselessSimpleGraphDirectory()
}
