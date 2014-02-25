package com.keepit.commanders


import com.keepit.abook.ABookServiceClient
import com.keepit.common.queue.RichConnectionUpdateMessage

import com.kifi.franz.FormattedSQSQueue

import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.{Inject, Singleton}


@Singleton
class RemoteRichConnectionCommander @Inject() (
    abook: ABookServiceClient,
    queue: FormattedSQSQueue[RichConnectionUpdateMessage]
  ) extends RichConnectionCommander {

  def processUpdate(message: RichConnectionUpdateMessage): Future[Unit] = {
    queue.send(message).map(_ => ())
  }

  def processUpdateImmediate(message: RichConnectionUpdateMessage): Future[Unit] = {
    abook.richConnectionUpdate(message)
  }
}
