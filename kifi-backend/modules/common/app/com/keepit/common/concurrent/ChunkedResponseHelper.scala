package com.keepit.common.concurrent

import play.api.libs.iteratee.{ Concurrent, Enumerator }
import play.api.libs.json.{ JsString, JsValue }
import com.keepit.common.core.futureExtensionOps

import scala.concurrent.{ ExecutionContext => ScalaExecutionContext, Future }
import scala.util.{ Failure, Success }

object ChunkedResponseHelper {
  def chunkedFuture[I](items: Seq[I])(process: I => Future[JsValue])(implicit exc: ScalaExecutionContext): Enumerator[JsValue] = {
    Concurrent.unicast(onStart = { (channel: Concurrent.Channel[JsValue]) =>
      FutureHelpers.sequentialExec(items) { x =>
        process(x).imap(channel.push)
      } andThen {
        case res =>
          if (res.isFailure) channel.push(JsString("server error"))
          channel.eofAndEnd()
      }
    })
  }
  def chunked[I](items: Seq[I])(process: I => JsValue)(implicit exc: ScalaExecutionContext): Enumerator[JsValue] = {
    chunkedFuture(items)(x => Future.successful(process(x)))
  }
}
