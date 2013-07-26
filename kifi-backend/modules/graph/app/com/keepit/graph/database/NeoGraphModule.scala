package com.keepit.graph.database

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Singleton, Provides}
import com.keepit.graph.model.{Graph, VertexData, EdgeData}
import org.neo4j.graphdb.GraphDatabaseService
import com.keepit.graph.database.NeoGraph
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import play.api.Play._

trait NeoGraphModule extends ScalaModule

case class ProdNeoGraphModule() extends NeoGraphModule {
  def configure() {}

  val conf = current.configuration.getConfig("db.neo4j").get

  @Provides @Singleton
  def graphDb = new GraphDatabaseFactory().newEmbeddedDatabase(conf.getString("file").get)

  @Provides @Singleton
  def fortyTwoGraph(graphDb: GraphDatabaseService): Graph[VertexData, EdgeData] = new NeoGraph(graphDb)

}
