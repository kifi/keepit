package com.keepit.graph.database

import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Singleton, Provides}
import com.keepit.graph.model.{Graph, VertexData, EdgeData}
import play.api.Play._
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.factory.GraphDatabaseFactory

trait   NeoGraphModule extends ScalaModule {

  @Provides @Singleton
  def fortyTwoGraph(graphDb: GraphDatabaseService): Graph[VertexData, EdgeData] = new NeoGraph(graphDb, VertexData, EdgeData)

}

case class ProdNeoGraphModule() extends NeoGraphModule {
  def configure() {}

  val conf = current.configuration.getConfig("db.graph.neo4j").get

  @Provides @Singleton
  def graphDb(): GraphDatabaseService = new GraphDatabaseFactory().newEmbeddedDatabase(conf.getString("db").get)

}
