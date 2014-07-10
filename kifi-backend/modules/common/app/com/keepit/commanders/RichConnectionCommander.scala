package com.keepit.commanders

import com.keepit.common.queue.RichConnectionUpdateMessage

import scala.concurrent.Future

trait RichConnectionCommander {

  def processUpdate(message: RichConnectionUpdateMessage): Future[Unit]
  def processUpdateImmediate(message: RichConnectionUpdateMessage): Future[Unit]

}
