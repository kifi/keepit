package com.keepit.common

import com.keepit.common.logging.{ Logging, NamedStatsdTimer }
import play.api.Logger

import scala.util.Random
import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration.Duration

import scala.reflect.macros._
import scala.language.experimental.macros
import scala.annotation.StaticAnnotation

import java.util.UUID

package object performance {

  case class Stopwatch(tag: String) extends Logging {
    var startTime = System.nanoTime()
    var lastLap: Long = startTime
    var elapsedTime: Long = 0

    def stop(): Long = {
      elapsedTime = System.nanoTime - startTime
      elapsedTime
    }

    override def toString = s"[$tag] elapsed milliseconds: ${elapsedTime / 1000000d}"

    private def recordLap() = {
      val now = System.nanoTime
      val sinceStart = now - startTime
      val lapTime = now - lastLap
      lastLap = now
      (sinceStart, lapTime)
    }

    def logTime()(implicit logger: Logger = log): Long = {
      val (sinceStart, lapTime) = recordLap()
      logger.info(s"lap:${lapTime / 1000000d}ms; sinceStart:${sinceStart / 1000000d}ms; $tag")
      lapTime
    }

    def logTimeWith(res: String)(implicit logger: Logger = log) {
      val (sinceStart, lapTime) = recordLap()
      logger.info(s"lap:${lapTime / 1000000d}ms; sinceStart:${sinceStart / 1000000d}ms; $tag - $res")
    }
  }

  def timing[A](tag: String)(f: => A)(implicit logger: Logger): A = timing(tag, 0.01)(f)(logger)
  def timing[A](tag: String, sampleRate: Double)(f: => A)(implicit logger: Logger): A = {
    if (Random.nextDouble() < sampleRate) {
      val sw = new Stopwatch(tag)
      val res = f
      sw.stop()
      sw.logTime()
      res
    } else {
      f
    }
  }

  def timing[A](tag: String, threshold: Long, conditionalBlock: Option[Long => Unit] = None, sampleRate: Double)(f: => A)(implicit logger: Logger): A = {
    if (Random.nextDouble() < sampleRate) {
      val sw = new Stopwatch(tag)
      val res = f
      val elapsed = sw.stop()
      val elapsedMili = elapsed / 1000000
      if (elapsedMili > threshold) {
        conditionalBlock match {
          case Some(block) => block(elapsedMili)
          case None => sw.logTime()
        }
      }
      res
    } else {
      f
    }
  }

  def timingWithResult[A](tag: String, r: (A => String) = { a: A => a.toString })(f: => A)(implicit logger: Logger): A = {
    val sw = new Stopwatch(tag)
    val res = f
    val resString = r(res)
    sw.stop()
    sw.logTimeWith(resString)
    res
  }

  def statsdTiming[A](name: String)(f: => A): A = {
    val timer = new NamedStatsdTimer(name)
    val res = f
    timer.stopAndReport()
    res
  }

  def statsdTimingAsync[A](name: String)(f: => Future[A])(implicit ec: ExecutionContext): Future[A] = {
    val timer = new NamedStatsdTimer(name)
    val res: Future[A] = f
    res.onComplete { _ =>
      timer.stopAndReport()
    }
    res
  }

  object statsdMacroInstance extends StatsdMacro(false)
  object statsdAsyncMacroInstance extends StatsdMacro(true)

  class StatsdTiming(name: String) extends StaticAnnotation {
    def macroTransform(annottees: Any*): Any = macro statsdMacroInstance.impl
  }

  class StatsdTimingAsync(name: String) extends StaticAnnotation {
    def macroTransform(annottees: Any*): Any = macro statsdAsyncMacroInstance.impl
  }

  class StatsdMacro(async: Boolean) {
    def impl(c: whitebox.Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
      import c.universe._

      def extractAnnotationParameters(tree: Tree): c.universe.Tree = tree match {
        case q"new $name( $param )" => param
        case _ => throw new Exception("Annotation must have exactly one argument.")
      }

      val name = extractAnnotationParameters(c.prefix.tree)

      def modifiedDeclaration(defDecl: DefDef) = {
        val q"$mods def $defName(...$args): $retType = { ..$body }" = defDecl

        if (async) {
          c.Expr(q"""
            $mods def $defName(...$args): $retType = com.keepit.common.performance.statsdTimingAsync($name) { ..$body }
          """)
        } else {
          c.Expr(q"""
            $mods def $defName(...$args): $retType = com.keepit.common.performance.statsdTiming($name) { ..$body }
          """)
        }
      }

      annottees.map(_.tree) match {
        case (defDecl: DefDef) :: Nil => modifiedDeclaration(defDecl)
        case _ => c.abort(c.enclosingPosition, "Invalid annottee")
      }
    }
  }

  object alertingMacroInstance extends AlertingMacro(false)
  object alertingMacroAsyncInstance extends AlertingMacro(true)

  class AlertingTimer(limit: Duration) extends StaticAnnotation {
    def macroTransform(annottees: Any*): Any = macro alertingMacroInstance.impl
  }

  class AlertingTimerAsync(limit: Duration) extends StaticAnnotation {
    def macroTransform(annottees: Any*): Any = macro alertingMacroAsyncInstance.impl
  }

  class AlertingMacro(async: Boolean) {
    def impl(c: whitebox.Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
      import c.universe._

      def extractAnnotationParameters(tree: Tree): c.universe.Tree = tree match {
        case q"new $name( $param )" => param
        case _ => throw new Exception("Annotation must have exactly one argument.")
      }

      val limit = extractAnnotationParameters(c.prefix.tree)

      def modifiedDeclaration(defDecl: DefDef) = {
        val q"$mods def $defName(...$args): $retType = { ..$body }" = defDecl

        val className = Option(c.enclosingClass).flatMap(c => Option(c.symbol)).map(_.toString).getOrElse("unknown")

        val fullName = s"$className.${defName.toString}"
        val name = c.universe.TermName(s"_timer_${UUID.randomUUID.toString.replace("-", "_")}")
        val message = c.universe.Constant(s"$fullName is taking too long")

        val invokation = if (async) {
          q"$mods def $defName(...$args): $retType = $name.timeitAsync { ..$body }"
        } else {
          q"$mods def $defName(...$args): $retType = $name.timeit { ..$body }"
        }

        c.Expr(q"""
          private object $name {
            import scala.concurrent.duration._

            val limit: FiniteDuration = $limit
            @volatile var samples: Long = 0
            @volatile var lastAlertAt: Option[Long] = None
            @volatile var mean: Long = 0

            def updateAndGetMean(sample: Long): FiniteDuration = synchronized {
              samples = samples + 1
              lastAlertAt = Some(System.nanoTime)
              mean = mean + (sample/samples) - (mean/samples)
              Duration.fromNanos(mean)
            }

            def monitor(t: Long): Unit = {
              val dur = updateAndGetMean(t)
              if (samples > 10 && dur > limit && !lastAlertAt.exists(_ > System.nanoTime - 600000000000L)) { //alert if the mean is over the limit and the last alert is more than 10 minutes ago
                airbrake.notify($message)
              }
            }

            def timeit[A](f: => A): A = {
              val now = System.nanoTime
              val res: A = f
              val elapsed = System.nanoTime - now
              monitor(elapsed)
              res
            }

            def timeitAsync[A](f: => Future[A]): Future[A] = {
              val now = System.nanoTime
              val res: Future[A] = f
              res.onComplete { _ =>
                val elapsed = System.nanoTime - now
                monitor(elapsed)
              }
              res
            }
          }
          $invokation
        """)
      }

      annottees.map(_.tree) match {
        case (defDecl: DefDef) :: Nil => modifiedDeclaration(defDecl)
        case _ => c.abort(c.enclosingPosition, "Invalid annottee")
      }
    }
  }

}
