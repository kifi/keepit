package com.keepit.common

import play.api.http.HeaderNames._
import play.api.mvc.Request
import play.api.mvc.RequestHeader
import com.keepit.inject._
import com.keepit.common.actor.ActorPlugin
import play.api.Play.current

package object async {

  def dispatch(block: => Unit, onFail: Exception => Unit): Unit =
    try {
      inject[ActorPlugin].system.dispatcher.execute(new Runnable {
        def run(): Unit =
          try {
            block
          } catch {
            case e: Exception => onFail(e)
          }
      })
    } catch {
      case e: Exception => onFail(e)
    }

}
