package com.keepit.common.concurrent

import play.api.libs.iteratee.{ Concurrent, Enumerator }
import play.api.libs.json.{ JsString, JsValue }
import com.keepit.common.core.futureExtensionOps

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext, Future }
import scala.util.{ Failure, Success }

object ChunkedResponseHelper {
  def chunkedFuture[I](items: Seq[I])(process: I => Future[String])(implicit exc: ScalaExecutionContext): Enumerator[String] = {
    Concurrent.unicast(onStart = { (channel: Concurrent.Channel[String]) =>
      FutureHelpers.sequentialExec(items) { x =>
        process(x).imap(str => channel.push(if (str.endsWith("\n")) str else str + "\n"))
      } andThen {
        case res =>
          if (res.isFailure) channel.push(s"${res.failed.get.getMessage}\n")
          channel.eofAndEnd()
      }
    })
  }
  def chunked[I](items: Seq[I])(process: I => String): Enumerator[String] = {
    chunkedFuture(items)(x => Future.successful(process(x)))(ExecutionContext.immediate)
  }
}
