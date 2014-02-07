package com.keepit.common.cache

import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.ImmediateMap

trait ObjectCache[K <: Key[T], T] {
  val outerCache: Option[ObjectCache[K, T]] = None
  val ttl: Duration
  val consolidationTtl: Option[Duration] = None
  private val consolidator = consolidationTtl.map(new RequestConsolidator[K, Option[T]](_))
  outerCache map {outer => require(ttl <= outer.ttl)}

  protected[cache] def getFromInnerCache(key: K): ObjectState[T]
  protected[cache] def setInnerCache(key: K, value: Option[T]): Unit

  def remove(key: K): Unit

  def set(key: K, value: T): Unit = {
    outerCache map {outer => outer.set(key, value)}
    setInnerCache(key, Some(value))
  }

  def set(key: K, valueOpt: Option[T]) : Unit = {
    outerCache map {outer => outer.set(key, valueOpt)}
    setInnerCache(key, valueOpt)
  }

  def get(key: K): Option[T] = {
    internalGet(key) match {
      case Found(valueOpt) => valueOpt
      case _ => None
    }
  }

  def getOrElse(key: K)(orElse: => T): T = {
    get(key) match {
      case Some(value) =>
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

  def getOrElseOpt(key: K)(orElse: => Option[T]): Option[T] = {
    internalGet(key) match {
      case Found(valueOpt) => valueOpt
      case _ =>
        val valueOpt = orElse
        set(key, valueOpt)
        valueOpt
    }
  }

  def getOrElseFuture(key: K, consolidate: Boolean = true)(orElse: => Future[T]): Future[T] = {
    get(key) match {
      case Some(value) => Promise.successful(value).future
      case None =>
        val valueFuture = {
          if (consolidator.isDefined && consolidate)
            consolidator.get(key)(_ => orElse.imap(Some)).flatMap {
              case Some(value) => Future.successful(value)
              case None => orElse
            }
          else orElse
        }
        valueFuture.onSuccess{ case value => set(key, value) }
        valueFuture
    }
  }

  def getOrElseFutureOpt(key: K, consolidate: Boolean = true)(orElse: => Future[Option[T]]): Future[Option[T]] = {
    internalGet(key) match {
      case Found(valueOpt) => Promise.successful(valueOpt).future
      case _ =>
        val valueOptFuture = if (consolidator.isDefined && consolidate) consolidator.get(key)(_ => orElse) else orElse
        valueOptFuture.onSuccess{ case valueOpt => set(key, valueOpt) }
        valueOptFuture
    }
  }

  //
  // bulk get API
  //
  protected[cache] def bulkGetFromInnerCache(keys: Set[K]): Map[K, Option[T]]

  def bulkGet(keys: Set[K]): Map[K, Option[T]] = {
    var result = bulkGetFromInnerCache(keys)
    if (keys.size > result.size) {
      outerCache match {
        case Some(cache) =>
          val missing = keys -- result.keySet
          cache.bulkGet(missing).foreach{ kv =>
            result += kv
            setInnerCache(kv._1, kv._2)
          }
        case None =>
      }
    }
    result
  }

  def bulkGetOrElseFuture(keys: Set[K])(orElse: Set[K] => Future[Map[K, T]]): Future[Map[K, T]] = {
    val found = bulkGet(keys).collect{ case (k, Some(v)) => (k, v) }
    if (keys.size > found.size) {
      val missing = keys -- found.keySet
      orElse(missing).map{ valueMap =>
        valueMap.foreach{ kv => setInnerCache(kv._1, Some(kv._2)) }
        found ++ valueMap
      }
    } else {
      Promise.successful(found).future
    }
  }
}

sealed trait ObjectState[T]
case class Found[T](value: Option[T]) extends ObjectState[T]
case class NotFound[T]() extends ObjectState[T]
case class Removed[T]() extends ObjectState[T] // used by TransactionLocalCache

