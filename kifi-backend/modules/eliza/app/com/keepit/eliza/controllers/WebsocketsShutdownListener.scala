package com.keepit.eliza.controllers

import com.keepit.common.shutdown.ShutdownListener
import java.util.{TimerTask, Timer}
import com.keepit.common.logging.Access.WS_IN
import play.api.libs.json.Json
import com.keepit.common.logging.AccessLog
import com.keepit.common.logging.Logging

/**
 * At this point, akka may start shutting down so we can't trust it or any other plugins we have :-(
 */
class WebsocketsShutdownListener(websocketRouter: WebSocketRouter, accessLog: AccessLog) extends ShutdownListener with Logging {

  val ShutdownWindowInMilli = 18000

  def shutdown(): Unit = {
    new Timer(getClass.getCanonicalName, true).scheduleAtFixedRate(task, 0, ShutdownWindowInMilli / websocketRouter.connectedSockets)
  }

  private def task = new TimerTask {
    def run(): Unit = {
      websocketRouter.getArbitrarySocketInfo map {socketInfo =>
        log.info(s"Closing socket $socketInfo because of server shutdown")
        val timer = accessLog.timer(WS_IN)
        socketInfo.channel.push(Json.arr("goodbye", "server shutdown"))
        socketInfo.channel.eofAndEnd()
        websocketRouter.unregisterUserSocket(socketInfo)
        accessLog.add(timer.done(trackingId = socketInfo.trackingId, method = "DISCONNECT", body = "disconnect on server shutdown"))
      }
    }
  }
}
