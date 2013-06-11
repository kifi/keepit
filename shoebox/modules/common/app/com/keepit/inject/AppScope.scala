package com.keepit.inject

import com.google.inject.Key
import com.google.inject.Provider
import com.google.inject.Scope
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.SchedulingPlugin

import play.api.Application
import play.api.Plugin
import play.utils.Threads

class AppScope extends Scope with Logging {

  private val identifier = ExternalId[AppScope]()

  private var started = false
  private var stopping = false
  private var stopped = false

  private var plugins: List[Plugin] = Nil
  private[inject] var pluginsToStart: List[Plugin] = Nil
  private var instances: Map[Key[_], Any] = Map.empty

  private var app: Application = _

  def onStart(app: Application): Unit = {
    println(s"[$identifier] scope starting...")
    require(!started, "AppScope has already been started")
    this.app = app
    pluginsToStart foreach { p => startPlugin(p) }
    pluginsToStart = Nil
    started = true
  }

  private[this] def startPlugin(plugin: Plugin): Unit = {
    log.info(s"[$identifier] starting plugin: $plugin")
    // start plugin, explicitly using the app classloader
    Threads.withContextClassLoader(app.classloader) {
      plugin.onStart()
    }
    plugins = plugin :: plugins
  }

  def onStop(app: Application): Unit = {
    val appScope = this
    stopping = true
    println(s"[$identifier] scope stopping...")
    if(!started) {
      log.error("App not started!", new Exception(s"[$identifier] AppScore has not been started"))
    }
    else {
      require(!stopped, s"[$identifier] AppScope has already been stopped")
      // stop plugins, explicitly using the app classloader
      Threads.withContextClassLoader(app.classloader) {
        for (plugin <- plugins) {
          log.info("stopping plugin: " + plugin)
          plugin match {
            case p: SchedulingPlugin =>
              p.cancelTasks()
              p.onStop()
            case p =>
              p.onStop()
          }
        }
      }
      log.info(s"[$identifier] scope stopped!")
      plugins = Nil
      stopped = true
      stopping = false
    }
  }

  private def createInstance[T](key: Key[T], unscoped: Provider[T]) = {
    if (stopped || stopping)
      throw new IllegalStateException(s"requesting for $key (in lock) while the scope stopped=$stopped, stopping=$stopping")
    val inst = unscoped.get()
    log.debug(s"created new instance of ${inst.getClass().getName()}")

    // if this instance is a plugin, start it and add to the list of plugins
    startIfPlugin(inst)
    instances += key -> inst
    log.debug(s"returning initiated instance of ${inst.getClass().getName()}")
    inst
  }

  private def startIfPlugin(instance: Any) =
    instance match {
      case plugin: Plugin =>
        started match {
          case true => startPlugin(plugin)
          case false => pluginsToStart = plugin :: pluginsToStart
        }
      case _ =>
    }

  def scope[T](key: Key[T], unscoped: Provider[T]): Provider[T] = {
    val appScope = this
    // return a provider that always gives the same instance
    new Provider[T] {
      def get: T = {
        log.debug(s"requesting for $key")
        val instance = {
          instances.get(key) match {
            case Some(inst) =>
              inst.asInstanceOf[T]
            case None =>
              appScope synchronized {
                //double check, it may have been initialized already
                instances.get(key) match {
                  case Some(inst) =>
                    inst.asInstanceOf[T]
                  case None =>
                    createInstance(key, unscoped)
                }
              }
          }
        }
        log.debug(s"instance of key $key is $instance")
        instance
      }
    }
  }
}
