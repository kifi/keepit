package com.keepit.inject

import com.google.inject.Key
import com.google.inject.Provider
import com.google.inject.Scope
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.SchedulerPlugin
import play.api.Application
import play.api.Plugin
import play.utils.Threads
import scala.collection.concurrent
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.Promise
import scala.util.Try
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits._
import java.util.concurrent.atomic.AtomicInteger

class AppScope extends Scope with Logging {

  private val identifier = ExternalId[AppScope]()

  private var started = false
  private var stopping = false
  private var stopped = false

  private var plugins: List[Plugin] = Nil
  private[inject] var pluginsToStart: List[Plugin] = Nil
  private[this] val instances: concurrent.TrieMap[Key[_], Future[_]] = new concurrent.TrieMap[Key[_], Future[_]]

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
          {
            log.info("stopping plugin: " + plugin)
            plugin match {
              case p: SchedulerPlugin =>
                p.cancelTasks()
                p.onStop()
              case p =>
                p.onStop()
            }
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
    // return a provider that always gives the same instance
    new Provider[T] {
      def get: T = {
        log.debug(s"requesting for $key")
        val instance = {
          val promise = Promise[T]
          val instFuture = instances.putIfAbsent(key, promise.future) match {
            case Some(instFuture) => instFuture
            case None =>
              promise.complete(Try(createInstance(key, unscoped)))
              promise.future
          }
          Await.result(instFuture, Duration.Inf).asInstanceOf[T]
        }
        log.debug(s"instance of key $key is $instance")
        instance
      }
    }
  }
}
