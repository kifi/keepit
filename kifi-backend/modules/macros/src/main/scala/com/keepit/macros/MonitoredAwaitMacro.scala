package com.keepit.macros

import scala.language.experimental.macros
import scala.language.experimental.macros._
import scala.concurrent.Await
import scala.concurrent.Awaitable
import scala.concurrent.duration._
import scala.reflect.macros.Context

trait MonitoredAwait {

  def result[T](awaitable: Awaitable[T], atMost: Duration, errorMessage: String, valueFailureHandler: T): T  = macro MonitoredAwaitMacro.resultWithDefault[T]
  def result[T](awaitable: Awaitable[T], atMost: Duration, errorMessage: String): T  = macro MonitoredAwaitMacro.result[T]

  def result[T](awaitable: Awaitable[T], atMost: Duration, errorMessage: String, valueFailureHandler: T, position: String): T = {
    val startTime = System.nanoTime()
    try {
      Await.result(awaitable, atMost)
    } catch {
      case ex: Throwable =>
        onError(position, ex, errorMessage)
        valueFailureHandler
    } finally {
      logTime(position, (System.nanoTime() - startTime))
    }
  }

  def result[T](awaitable: Awaitable[T], atMost: Duration, errorMessage: String, position: String): T = {
    val startTime = System.nanoTime()
    try {
      Await.result(awaitable, atMost)
    } catch {
      case ex: Throwable =>
        onError(position, ex, errorMessage)
        throw ex
    } finally {
      logTime(position, (System.nanoTime() - startTime))
    }
  }

  def onError(position: String, ex: Throwable, msg: String): Unit

  def logTime(tag: String, elapsedTimeNano: Long): Unit
}

object MonitoredAwaitMacro {

  type MonitoredAwaitContext = Context { type PrefixType =  MonitoredAwait }

  private[this] def getPosition(x: MonitoredAwaitContext) = {
    val className = Option(x.enclosingClass)
      .flatMap(c => Option(c.symbol))
      .map(_.toString)
      .getOrElse("")
    val methodName = Option(x.enclosingMethod)
      .flatMap(m => Option(m.symbol))
      .map(_.toString)
      .getOrElse("")
    val line = x.enclosingPosition.line

    s"Await[${className}][${methodName}]:${line}"
  }

  def resultWithDefault[T](x: MonitoredAwaitContext)(
      awaitable: x.Expr[Awaitable[T]],
      atMost: x.Expr[Duration],
      errorMessage: x.Expr[String],
      valueFailureHandler: x.Expr[T]
  ):  x.Expr[T] = {
    import x.universe._
    val position = getPosition(x)
    reify(x.prefix.splice.result(awaitable.splice, atMost.splice, errorMessage.splice, valueFailureHandler.splice, x.literal(position).splice))
  }

  def result[T](x: MonitoredAwaitContext)(
      awaitable: x.Expr[Awaitable[T]],
      atMost: x.Expr[Duration],
      errorMessage: x.Expr[String]
  ):  x.Expr[T] = {
    import x.universe._
    val position = getPosition(x)
    reify(x.prefix.splice.result(awaitable.splice, atMost.splice, errorMessage.splice, x.literal(position).splice))
  }
}

