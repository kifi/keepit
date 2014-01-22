package com.keepit.graph

import com.keepit.inject.EmptyInjector
import com.google.inject.util.Modules
import play.api.Mode
import com.keepit.common.time.FakeClockModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.graph.database.TestNeoGraphModule
import com.google.inject.{Module, Injector}
import org.neo4j.graphdb.GraphDatabaseService

trait GraphTestInjector extends EmptyInjector {

  val mode = Mode.Test
  val module = Modules.combine(FakeClockModule(), FakeHealthcheckModule(), TestNeoGraphModule())

  def withGraphDb[T](overridingModules: Module*)(f: Injector => T) = {
    withInjector(overridingModules:_*) { implicit injector =>
      val graphDb = inject[GraphDatabaseService]
      try {
        f(injector)
      } finally {
        graphDb.shutdown()
      }
    }
  }
}
