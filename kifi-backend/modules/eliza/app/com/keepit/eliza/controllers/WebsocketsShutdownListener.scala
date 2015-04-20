package com.keepit.eliza.controllers

import com.keepit.common.shutdown.{ ShutdownCommander, ShutdownListener }
import java.util.{ TimerTask, Timer }
import com.keepit.common.logging.Access.WS_IN
import play.api.libs.json.Json
import com.keepit.common.logging.AccessLog
import com.keepit.common.logging.Logging
import com.google.inject.{ Singleton, Inject }

/**
 * At this point, akka may start shutting down so we can't trust it or any other plugins we have :-(
 */
@Singleton
class WebsocketsShutdownListener @Inject() (
    websocketRouter: WebSocketRouter,
    accessLog: AccessLog,
    shutdownCommander: ShutdownCommander) extends ShutdownListener with Logging {

  val ShutdownWindowInMilli = 18000

  shutdownCommander.addListener(this)

  def shuttingDown = shutdownCommander.shuttingDown

  def shutdown(): Unit = {
    // There may be no connections at all (and we don't want a divide by 0) and some may slip in.
    // Lets make sure that the timer runs anyway every at least ShutdownWindowInMilli/10 ms to ensure nice cleanup.
    val count = websocketRouter.connectedSockets.max(10)
    val rate = (ShutdownWindowInMilli / count).max(1)
    log.info(s"closing $count sockets at rate of one every ${rate}ms")
    new Timer(getClass.getCanonicalName, true).scheduleAtFixedRate(task((count / ShutdownWindowInMilli).max(1)), 0, rate)
  }

  private def task(chunkSize: Int) = new TimerTask {
    def run(): Unit = WebsocketsShutdownListener.this.synchronized {
      for (i <- 0 until chunkSize) terminateOne()
    }

    private def terminateOne(): Unit = {
      websocketRouter.getArbitrarySocketInfo match {
        case Some(socketInfo) =>
          websocketRouter.unregisterUserSocket(socketInfo)
          val count = websocketRouter.connectedSockets
          log.info(s"Closing socket $socketInfo because of server shutdown, $count to go")
          val timer = accessLog.timer(WS_IN)
          socketInfo.channel.push(Json.arr("bye", "shutdown"))
          socketInfo.channel.eofAndEnd()
          accessLog.add(timer.done(trackingId = socketInfo.trackingId, method = "DISCONNECT", body = "disconnect on server shutdown"))
        case None =>
          log.info("no more sockets to shutdown")
          cancel()
      }
    }
  }
}
