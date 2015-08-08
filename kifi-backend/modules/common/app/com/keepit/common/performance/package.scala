package com.keepit.common

import com.keepit.common.logging.{ Logging, NamedStatsdTimer }
import play.api.Logger

import scala.util.Random
import scala.concurrent.{ Future, ExecutionContext }

import scala.reflect.macros._
import scala.language.experimental.macros
import scala.annotation.StaticAnnotation

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

  object statsdMacroInstance extends StatsdMacro("test", false)
  object statsdAsyncMacroInstance extends StatsdMacro("test", true)

  class StatsdTiming(name: String) extends StaticAnnotation {
    def macroTransform(annottees: Any*): Any = macro statsdMacroInstance.impl
  }

  class StatsdTimingAsync(name: String) extends StaticAnnotation {
    def macroTransform(annottees: Any*): Any = macro statsdAsyncMacroInstance.impl
  }

  class StatsdMacro(name: String, async: Boolean) {
    def impl(c: whitebox.Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
      import c.universe._

      def extractAnnotationParameters(tree: Tree): c.universe.Tree = tree match {
        case q"new $name( $param )" => param
        case _ => throw new Exception("Annotation must have exactly one argument.")
      }

      val name = extractAnnotationParameters(c.prefix.tree)

      def modifiedDeclaration1(defDecl: DefDef) = {
        val q"def $defName(..$args): $retType = { ..$body }" = defDecl

        if (async) {
          c.Expr(q"""
            def $defName(..$args): $retType = com.keepit.common.performance.statsdTimingAsync($name) { ..$body }
          """)
        } else {
          c.Expr(q"""
            def $defName(..$args): $retType = com.keepit.common.performance.statsdTiming($name) { ..$body }
          """)
        }
      }

      //Couldn't come up with a way to do this generically. Ideas welcome. In any case, curried one should be sufficient for almost all of our code.
      def modifiedDeclaration2(defDecl: DefDef) = {
        val q"def $defName(..$args1)(..$args2): $retType = { ..$body }" = defDecl

        if (async) {
          c.Expr(q"""
            def $defName(..$args1)(..$args2): $retType = com.keepit.common.performance.statsdTimingAsync($name) { ..$body }
          """)
        } else {
          c.Expr(q"""
            def $defName(..$args1)(..$args2): $retType = com.keepit.common.performance.statsdTiming($name) { ..$body }
          """)
        }
      }

      def modifiedDeclaration(defDecl: DefDef) = defDecl match {
        case q"def $defName(..$args): $retType = { ..$body }" => modifiedDeclaration1(defDecl)
        case q"def $defName(..$args1)(..$args2): $retType = { ..$body }" => modifiedDeclaration2(defDecl)
        case _ => throw new Exception("Unsupported function form.")
      }

      annottees.map(_.tree) match {
        case (defDecl: DefDef) :: Nil => modifiedDeclaration(defDecl)
        case _ => c.abort(c.enclosingPosition, "Invalid annottee")
      }
    }
  }

}
