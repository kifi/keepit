package com.keepit.common.controller

import com.keepit.common.logging.Logging
import play.api.mvc._
import com.keepit.common.akka.SafeFuture
import scala.concurrent.{ Future, ExecutionContext }
import com.keepit.common.service.ServiceType

trait ServiceController extends Controller with Logging {

  def serviceType: ServiceType

  def resolve[T](a: T) = Future.successful(a)

  def SafeAsyncAction(f: Request[AnyContent] => Result)(implicit ex: ExecutionContext) = Action.async { request =>
    SafeFuture(f(request))
  }

  def SafeAsyncAction(f: => Result)(implicit ex: ExecutionContext) = Action.async {
    SafeFuture(f)
  }

  def SafeAsyncAction[A](parser: BodyParser[A])(f: Request[A] => Result)(implicit ex: ExecutionContext) = Action.async[A](parser) { request =>
    SafeFuture(f(request))
  }

}

trait SearchServiceController extends ServiceController {
  val serviceType: ServiceType = ServiceType.SEARCH
}
trait ShoeboxServiceController extends ServiceController {
  val serviceType: ServiceType = ServiceType.SHOEBOX
}
trait ElizaServiceController extends ServiceController {
  val serviceType: ServiceType = ServiceType.ELIZA
}
trait HeimdalServiceController extends ServiceController {
  val serviceType: ServiceType = ServiceType.HEIMDAL
}
trait ABookServiceController extends ServiceController {
  val serviceType: ServiceType = ServiceType.ABOOK
}

trait CortexServiceController extends ServiceController {
  val serviceType: ServiceType = ServiceType.CORTEX
}

trait CuratorServiceController extends ServiceController {
  val serviceType: ServiceType = ServiceType.CURATOR
}

trait GraphServiceController extends ServiceController {
  val serviceType: ServiceType = ServiceType.GRAPH
}

trait RoverServiceController extends ServiceController {
  val serviceType: ServiceType = ServiceType.ROVER
}
