package com.keepit.inject

import com.google.inject.Key
import com.google.inject.Provider
import com.google.inject.Scope
import com.keepit.common.db.ExternalId
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.SchedulerPlugin
import play.api.{ Mode, Application, Plugin }
import play.utils.Threads
import scala.collection.concurrent
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.Promise
import scala.util.{ Failure, Success, Try }
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
    if (app.mode != Mode.Test) {
      val startedPlugins = pluginsToStart.map { p => startPlugin(p) }
      log.info(s"[$identifier] Plugins started!\nSummary: " + startedPlugins.map(t => s"${t._1.getClass.getSimpleName} (${t._2}ms)").mkString(", "))
    }

    pluginsToStart = Nil
    started = true
  }

  case class PluginMeasurement(plugin: Plugin, time: Long)

  private[this] def startPlugin(plugin: Plugin): (Plugin, Long) = {
    log.debug(s"[$identifier] starting plugin: ${plugin.getClass.getSimpleName}")
    // start plugin, explicitly using the app classloader
    val t = System.currentTimeMillis
    Threads.withContextClassLoader(app.classloader) {
      plugin.onStart()
    }
    plugins = plugin :: plugins
    (plugin, System.currentTimeMillis - t)
  }

  def onStop(app: Application): Unit = {
    stopping = true
    println(s"[$identifier] scope stopping...")
    if (!started) {
      log.error("App not started!", new Exception(s"[$identifier] AppScore has not been started"))
    } else {
      require(!stopped, s"[$identifier] AppScope has already been stopped")
      // stop plugins, explicitly using the app classloader
      val stoppedPlugins = Threads.withContextClassLoader(app.classloader) {
        plugins.map { plugin =>
          log.debug("stopping plugin: " + plugin.getClass.getSimpleName)
          val t = System.currentTimeMillis
          try {
            plugin match {
              case p: SchedulerPlugin =>
                p.cancelTasks()
                p.onStop()
              case p =>
                p.onStop()
            }
            (plugin, System.currentTimeMillis - t, None)
          } catch {
            case ex: Throwable => // We can't email out, but log it
              (plugin, System.currentTimeMillis - t, Some(ex))
          }
        }
      }
      val (successes, failures) = stoppedPlugins.partition(_._3.isEmpty)
      val successStr = "Successes: " + successes.map(t => s"${t._1.getClass.getSimpleName} (${t._2}ms)").mkString(", ")
      val failuresStr = "Failures: " + failures.map(t => s"${t._1.getClass.getSimpleName} (${t._2}ms)\n${t._3.get.toString}").mkString("\n\t")

      log.info(s"[$identifier] Plugins stopped!\n$successStr\n$failuresStr")
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
          Try(Await.result(instFuture, Duration(45, scala.concurrent.duration.SECONDS)).asInstanceOf[T]) match {
            case Success(res) => res
            case Failure(ex) =>
              throw new Exception(s"Guice problem getting: $key", ex)
          }
        }
        log.debug(s"instance of key $key is $instance")
        instance
      }
    }
  }
}
