package com.keepit.common.cache

import com.keepit.common.akka.SafeFuture
import org.joda.time.DateTime
import com.keepit.common.time._
import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits._

trait ObjectCache[K <: Key[T], T] {
  val outerCache: Option[ObjectCache[K, T]] = None
  val minTTL: Duration
  val maxTTL: Duration

  outerCache foreach { outer => require(maxTTL <= outer.maxTTL) }

  protected[cache] def getFromInnerCache(key: K): ObjectState[T]
  protected[cache] def setInnerCache(key: K, value: Option[T]): Unit

  def remove(key: K): Unit

  def set(key: K, value: T): Unit = {
    outerCache foreach { outer => outer.set(key, value) }
    setInnerCache(key, Some(value))
  }

  def set(key: K, valueOpt: Option[T]): Unit = {
    outerCache foreach { outer => outer.set(key, valueOpt) }
    setInnerCache(key, valueOpt)
  }

  def get(key: K): Option[T] = {
    internalGet(key) match {
      case Found(valueOpt) => valueOpt
      case _ => None
    }
  }

  def getOrElse(key: K, needRefresh: T => Boolean = Function.const(false))(orElse: => T): T = {
    get(key) match {
      case Some(value) =>
        if (needRefresh(value)) SafeFuture { set(key, orElse) }
        value
      case None =>
        val value = orElse
        set(key, value)
        value
    }
  }

  private def internalGet(key: K): ObjectState[T] = {
    getFromInnerCache(key) match {
      case state @ Found(_) => state
      case NotFound() => outerCache match {
        case Some(cache) =>
          val state = cache.internalGet(key)
          state match {
            case Found(valueOpt) => setInnerCache(key, valueOpt)
            case _ =>
          }
          state
        case None => NotFound()
      }
      case Removed() => NotFound() // if removed at a transaction local cache, do not call outer cache
    }
  }

  def getOrElseOpt(key: K, needRefresh: Option[T] => Boolean = Function.const(false))(orElse: => Option[T]): Option[T] = {
    internalGet(key) match {
      case Found(valueOpt) =>
        if (needRefresh(valueOpt)) SafeFuture { set(key, orElse) }
        valueOpt
      case _ =>
        val valueOpt = orElse
        set(key, valueOpt)
        valueOpt
    }
  }

  def getOrElseFuture(key: K, needRefresh: T => Boolean = Function.const(false))(orElse: => Future[T]): Future[T] = {
    get(key) match {
      case Some(value) =>
        if (needRefresh(value)) orElse.onSuccess { case value => set(key, value) }
        Promise.successful(value).future
      case None =>
        val valueFuture = orElse
        valueFuture.onSuccess { case value => set(key, value) }
        valueFuture
    }
  }

  def getOrElseFutureOpt(key: K, needRefresh: Option[T] => Boolean = Function.const(false))(orElse: => Future[Option[T]]): Future[Option[T]] = {
    internalGet(key) match {
      case Found(valueOpt) =>
        if (needRefresh(valueOpt)) orElse.onSuccess { case valOpt => set(key, valOpt) }
        Future.successful(valueOpt)
      case _ =>
        val valueOptFuture = orElse
        valueOptFuture.onSuccess { case valueOpt => set(key, valueOpt) }
        valueOptFuture
    }
  }

  //
  // bulk get API
  //
  protected[cache] def bulkGetFromInnerCache(keys: Set[K]): Map[K, ObjectState[T]]

  private def internalBulkGet(keys: Set[K]): Map[K, ObjectState[T]] = {
    var result = bulkGetFromInnerCache(keys)

    outerCache match {
      case Some(cache) =>
        val missing = keys -- result.iterator.collect {
          case (key, Found(_)) => key
          case (key, Removed()) => key // if removed at a transaction local cache, do not call outer cache
        }
        if (missing.nonEmpty) {
          cache.internalBulkGet(missing).foreach { kv =>
            result += kv
            kv match {
              case (key, Found(valueOpt)) => setInnerCache(key, valueOpt)
              case _ =>
            }
          }
        }
      case None =>
    }
    result
  }

  def bulkGet(keys: Set[K]): Map[K, Option[T]] = {
    internalBulkGet(keys).mapValues { state =>
      state match {
        case Found(valueOpt) => valueOpt
        case _ => None
      }
    }
  }

  def bulkGetOrElse(keys: Set[K])(orElse: Set[K] => Map[K, T]): Map[K, T] = {
    var missing = Set.empty[K]
    var result = Map.empty[K, T]
    internalBulkGet(keys).map {
      case (key, state) =>
        state match {
          case Found(Some(value)) => result += (key -> value)
          case _ => missing += key
        }
    }
    if (missing.nonEmpty) {
      val valueMap = orElse(missing)
      valueMap.foreach { case (key, value) => set(key, value) }
      result ++ valueMap
    } else {
      result
    }
  }

  def bulkGetOrElseOpt(keys: Set[K])(orElse: Set[K] => Map[K, Option[T]]): Map[K, Option[T]] = {
    var missing = Set.empty[K]
    var result = Map.empty[K, Option[T]]
    internalBulkGet(keys).map {
      case (key, state) =>
        state match {
          case Found(valueOpt) => result += (key -> valueOpt)
          case _ => missing += key
        }
    }
    if (missing.nonEmpty) {
      val valueMap = orElse(missing)
      valueMap.foreach { case (key, valueOpt) => set(key, valueOpt) }
      result ++ valueMap
    } else {
      result
    }
  }

  def bulkGetOrElseFuture(keys: Set[K])(orElse: Set[K] => Future[Map[K, T]]): Future[Map[K, T]] = {
    var missing = Set.empty[K]
    var result = Map.empty[K, T]
    internalBulkGet(keys).map {
      case (key, state) =>
        state match {
          case Found(Some(value)) => result += (key -> value)
          case _ => missing += key
        }
    }
    if (missing.nonEmpty) {
      orElse(missing).map { valueMap =>
        valueMap.foreach { case (key, value) => set(key, value) }
        result ++ valueMap
      }
    } else {
      Promise.successful(result).future
    }
  }

  def bulkGetOrElseFutureOpt(keys: Set[K])(orElse: Set[K] => Future[Map[K, Option[T]]]): Future[Map[K, Option[T]]] = {
    var missing = Set.empty[K]
    var result = Map.empty[K, Option[T]]
    internalBulkGet(keys).map {
      case (key, state) =>
        state match {
          case Found(valueOpt) => result += (key -> valueOpt)
          case _ => missing += key
        }
    }
    if (missing.nonEmpty) {
      orElse(missing).map { valueMap =>
        valueMap.foreach { case (key, valueOpt) => set(key, valueOpt) }
        result ++ valueMap
      }
    } else {
      Promise.successful(result).future
    }
  }
}

sealed trait ObjectState[T]
case class Found[T](value: Option[T]) extends ObjectState[T]
case class NotFound[T]() extends ObjectState[T]
case class Removed[T]() extends ObjectState[T] // used by TransactionLocalCache

