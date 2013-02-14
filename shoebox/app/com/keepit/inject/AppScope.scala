package com.keepit.inject

import com.google.inject.Key
import com.google.inject.OutOfScopeException
import com.google.inject.Provider
import com.google.inject.Scope
import com.keepit.common.logging.Logging
import play.api.Application
import play.api.Plugin
import play.utils.Threads

class AppScope extends Scope with Logging {

  private var started = false
  private var stopped = false

  private var app: Application = _
  private var plugins: List[Plugin] = Nil
  private[inject] var pluginsToStart: List[Plugin] = Nil
  private var instances: Map[Key[_], Any] = Map.empty

  def onStart(app: Application): Unit = synchronized {
    println("scope starting...")
    require(!started, "AppScope has already been started")
    this.app = app
    pluginsToStart foreach startPlugin
    pluginsToStart = Nil
    started = true
  }

  private[this] def startPlugin(plugin: Plugin): Unit = {
    log.info("starting plugin: " + plugin)
    // start plugin, explicitly using the app classloader
    Threads.withContextClassLoader(app.classloader) {
      plugin.onStart()
    }
    plugins = plugin :: plugins
  }

  def onStop(app: Application): Unit = synchronized {
    println("scope stopping...")
    if(!started) {
      log.error("AppScore has not been started")
    }
    else {
      require(!stopped, "AppScope has already been stopped")
      // stop plugins, explicitly using the app classloader
      Threads.withContextClassLoader(app.classloader) {
        for (plugin <- plugins) {
          log.info("stopping plugin: " + plugin)
          plugin.onStop()
        }
      }
      log.info("scope stopped")
      plugins = Nil
      stopped = true
    }
  }

  def scope[T](key: Key[T], unscoped: Provider[T]): Provider[T] = {
    val appScope = this
    // return a provider that always gives the same instance
    new Provider[T] {
      def get = appScope synchronized {
        instances.get(key) match {
          case Some(inst) => inst.asInstanceOf[T]
          case None =>
            val inst = unscoped.get()
            // if this instance is a plugin, start it and add to the list of plugins
            inst match {
              case plugin: Plugin =>
                started match {
                  case true => startPlugin(plugin)
                  case false => pluginsToStart = plugin :: pluginsToStart 
                }
              case _ =>
            }
            instances += key -> inst
            inst
        }
      }
    }
  }
}
