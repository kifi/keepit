package com.keepit.common.controller

import com.keepit.common.logging.Logging
import play.api.mvc._
import com.keepit.common.akka.SafeFuture
import scala.concurrent.ExecutionContext
import com.keepit.common.service.ServiceType


trait ServiceController extends Controller with Logging {

  def serviceType: ServiceType
  
  def SafeAsyncAction(f: Request[AnyContent] => Result)(implicit ex: ExecutionContext) = Action{ request =>  
    Async{ 
      SafeFuture(f(request)) 
    }  
  }

  def SafeAsyncAction(f: => Result)(implicit ex: ExecutionContext) = Action{  
    Async{ 
      SafeFuture(f) 
    }  
  }

  def SafeAsyncAction[A](parser: BodyParser[A])(f: Request[A] => Result)(implicit ex: ExecutionContext) = Action[A](parser){ request =>  
    Async{ 
      SafeFuture(f(request)) 
    }  
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
trait ScraperServiceController extends ServiceController {
  val serviceType: ServiceType = ServiceType.SCRAPER
}
