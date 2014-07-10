package com.keepit.commanders

import com.keepit.abook.ABookServiceClient
import com.keepit.common.queue.RichConnectionUpdateMessage
import com.keepit.common.akka.SafeFuture

import com.kifi.franz.SQSQueue

import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.{ Inject, Singleton }

@Singleton
class RemoteRichConnectionCommander @Inject() (
    abook: ABookServiceClient,
    queue: SQSQueue[RichConnectionUpdateMessage]) extends RichConnectionCommander {

  def processUpdate(message: RichConnectionUpdateMessage): Future[Unit] = {
    SafeFuture { queue.send(message) }.map(_ => ())
  }

  def processUpdateImmediate(message: RichConnectionUpdateMessage): Future[Unit] = {
    abook.richConnectionUpdate(message)
  }
}
