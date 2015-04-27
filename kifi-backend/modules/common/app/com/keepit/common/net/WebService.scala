package com.keepit.common.net

import java.util.concurrent.atomic.AtomicInteger

import com.google.inject.ImplementedBy
import com.keepit.common.concurrent.RetryFuture
import com.keepit.common.logging.Logging
import play.api.libs.iteratee.Iteratee
import play.api.libs.ws.{ WSResponse, WSRequestHolder }
import com.keepit.common.core._

import scala.concurrent.Future

@ImplementedBy(classOf[PlayWS])
trait WebService {
  def url(url: String): WSRequestHolder
  def status(url: String, followRedirects: Boolean = true, requestTimeout: Int = 20000): Future[Int] = {
    this.url(url).withRequestTimeout(requestTimeout).withFollowRedirects(followRedirects).getStream().imap {
      case (headers, streamBody) =>
        streamBody.run(Iteratee.ignore)
        headers.status
    }
  }
}

class PlayWS extends WebService {
  import play.api.Play.current
  import play.api.libs.ws.WS

  def url(url: String): WSRequestHolder = WS.url(url)(current)
}

object WebServiceUtils extends Logging {
  def getWithRetry(request: WSRequestHolder, attempts: Int): Future[WSResponse] = {
    val count = new AtomicInteger()
    val resolver: PartialFunction[Throwable, Boolean] = {
      case t: Throwable =>
        count.getAndIncrement
        log.error(s"[getWithRetry(${request.url}})] attempt#(${count.get}) failed with $t")
        true
    }
    RetryFuture(attempts, resolver) {
      request.get()
    }
  }
}
