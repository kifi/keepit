package com.keepit.common.controller

import com.keepit.common.logging.Logging
import play.api.mvc._
import com.keepit.common.akka.SafeFuture
import scala.concurrent.ExecutionContext


trait ServiceController extends Controller with Logging {
  
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

trait SearchServiceController extends ServiceController
trait ShoeboxServiceController extends ServiceController
trait ElizaServiceController extends ServiceController
trait HeimdalServiceController extends ServiceController
