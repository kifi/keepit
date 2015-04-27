package com.keepit.test

import com.keepit.common.concurrent.WatchableExecutionContext
import com.keepit.common.controller._
import play.api.mvc.Controller
import scala.collection.JavaConversions._
import com.google.inject.Injector
import com.keepit.common.logging.Logging
import java.io.File
import com.keepit.inject.{ ApplicationInjector, ConfigurationModule }
import org.specs2.mutable.Specification
import play.api.test.Helpers.running
import net.codingwell.scalaguice._
import net.codingwell.scalaguice.InjectorExtensions._

object ServiceModuleTestHelpers extends Logging {

  private val excludedBindings = Set(classOf[akka.actor.Actor])

  def instantiateAllBindings()(implicit injector: Injector): Unit = {
    injector.getAllBindings().keys.foreach { key =>
      val keyClazz = key.getTypeLiteral.getRawType
      if (excludedBindings.exists(_ isAssignableFrom keyClazz)) log.info(s"Avoiding instantiation of $key")
      else injector.getInstance(key)
    }
  }

  def getServiceControllers(app: play.api.Application): Seq[Class[_ <: ServiceController]] = {
    val ClassRoute = "@(.+)@.+".r
    app.routes match {
      case None => throw new IllegalStateException(s"Routes not found in $app.")
      case Some(routes) => {
        val distinctClasses = routes.documentation.collect { case (_, _, ClassRoute(className)) => Class.forName(className) }.distinct
        distinctClasses.flatMap(asServiceController)
      }
    }
  }

  private def asServiceController(clazz: Class[_]): Option[Class[_ <: ServiceController]] = {
    if (classOf[Controller] isAssignableFrom clazz) {
      if (classOf[ServiceController] isAssignableFrom clazz) Some(clazz.asSubclass(classOf[ServiceController]))
      else throw new IllegalStateException(s"class $clazz is a controller that does not extends a service controller")
    } else None
  }
}

// if this class is not marked as abstract, Specs2 will try and fail to run it as a test
abstract class ServiceModuleTest(expectedModule: ConfigurationModule) extends Specification with ApplicationInjector with Logging {
  expectedModule.toString() should {
    "provide the app with consistent dependencies" in {
      val app = new play.api.test.FakeApplication()
      running(app) {
        log.info(s"Application is running from ${app.path.getCanonicalPath}")
        module === expectedModule
        log.info(s"Application is running with expected module: $expectedModule")

        ServiceModuleTestHelpers.instantiateAllBindings()
        log.info(s"All bindings could be instantiated.")

        val controllers = ServiceModuleTestHelpers.getServiceControllers(app)
        if (controllers.isEmpty) { throw new IllegalStateException(s"No controller found.") }
        controllers.foreach { controller =>
          log.info(s"Instantiating $controller")
          injector.getInstance(controller)
        }
        log.info(s"All controllers could be instantiated.")
        injector.instance[WatchableExecutionContext].kill()
        val happy = s"$expectedModule is all good"
        happy === happy
      }
    }
  }
}
