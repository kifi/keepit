package com.keepit.shoebox

import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.logging.Logging
import com.keepit.inject.inject
import com.keepit.test.ShoeboxApplication
import org.specs2.mutable.Specification
import play.api.Play.current
import play.api.mvc.Controller
import play.api.test.Helpers.running
import scala.reflect.ManifestFactory.classType
import com.keepit.FortyTwoGlobal
import scala.collection.JavaConversions._
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.mail.MailToKeepServerSettings
import com.keepit.search.ResultClickTracker

class ShoeboxModuleTest extends Specification with Logging {

  private def isShoeboxController(clazz: Class[_]): Boolean = {
    classOf[ShoeboxServiceController] isAssignableFrom clazz
  }

  "Module" should {
    "instantiate controllers" in {
      running(new ShoeboxApplication().withFakeHealthcheck().withFakeMail()) {
        val ClassRoute = "@(.+)@.+".r
        val classes = current.routes.map(_.documentation).reduce(_ ++ _).collect {
          case (_, _, ClassRoute(className)) => Class.forName(className)
        }.distinct.filter(isShoeboxController)
        for (c <- classes) inject(classType[Controller](c), current)
        val injector = current.global.asInstanceOf[FortyTwoGlobal].injector
        val bindings = injector.getAllBindings()
        bindings.keySet() filter { key =>
          val klazz = key.getTypeLiteral().getRawType()
          val exclude: Set[Class[_]] = Set(classOf[FortyTwoActor], classOf[MailToKeepServerSettings], classOf[ResultClickTracker])
          !exclude.contains(klazz) && !exclude.contains(klazz.getSuperclass())
        } foreach { key =>
          injector.getInstance(key)
        }
        true
      }
    }
  }
}
