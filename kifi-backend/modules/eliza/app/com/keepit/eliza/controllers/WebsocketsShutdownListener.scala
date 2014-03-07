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
    val count = websocketRouter.connectedSockets
    val rate = ShutdownWindowInMilli / count
    println(s"closing $count sockets at rate of one every ${rate}ms")//on shutdown the logger may be terminated, double logging
    log.info(s"closing $count sockets at rate of one every ${rate}ms")
    new Timer(getClass.getCanonicalName, true).scheduleAtFixedRate(task, 0, rate)
  }

  private def task = new TimerTask {
    def run(): Unit = WebsocketsShutdownListener.this.synchronized {
      websocketRouter.getArbitrarySocketInfo match {
        case Some(socketInfo) =>
          websocketRouter.unregisterUserSocket(socketInfo)
          val count = websocketRouter.connectedSockets
          log.info(s"Closing socket $socketInfo because of server shutdown, $count to go")
          println(s"Closing socket $socketInfo because of server shutdown, $count to go")
          val timer = accessLog.timer(WS_IN)
          socketInfo.channel.push(Json.arr("bye", "shutdown"))
          socketInfo.channel.eofAndEnd()
          accessLog.add(timer.done(trackingId = socketInfo.trackingId, method = "DISCONNECT", body = "disconnect on server shutdown"))
        case None =>
          log.info("no more sockets to shutdown")
          println("no more sockets to shutdown")
      }
    }
  }
}
