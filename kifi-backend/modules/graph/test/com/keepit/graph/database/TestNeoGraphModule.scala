package com.keepit.graph.database

import com.google.inject.{Singleton, Provides}
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.test.TestGraphDatabaseFactory

case class TestNeoGraphModule() extends NeoGraphModule {
  def configure() {}

  @Provides @Singleton
  def graphDb(): GraphDatabaseService = new TestGraphDatabaseFactory().newImpermanentDatabase()

}


