package com.keepit.common.cache

import scala.collection.concurrent.{TrieMap => ConcurrentMap}
import scala.concurrent._
import scala.concurrent.duration._

import java.util.concurrent.atomic.AtomicInteger

import net.codingwell.scalaguice.ScalaModule
import net.sf.ehcache._
import net.sf.ehcache.config.CacheConfiguration

import com.google.inject.{Inject, Singleton}
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.logging._
import com.keepit.common.time._
import com.keepit.serializer.{Serializer, BinaryFormat}
import com.keepit.common.logging.{AccessLogTimer, AccessLog}
import com.keepit.common.logging.Access._

import play.api.Logger
import play.api.Plugin
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.modules.statsd.api.Statsd


trait ObjectCache[K <: Key[T], T] {
  val outerCache: Option[ObjectCache[K, T]] = None
  val ttl: Duration
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

  def getOrElseFuture(key: K)(orElse: => Future[T]): Future[T] = {
    get(key) match {
      case Some(value) => Promise.successful(value).future
      case None =>
        val valueFuture = orElse
        valueFuture.onSuccess{ case value => set(key, value) }
        valueFuture
    }
  }

  def getOrElseFutureOpt(key: K)(orElse: => Future[Option[T]]): Future[Option[T]] = {
    internalGet(key) match {
      case Found(valueOpt) => Promise.successful(valueOpt).future
      case _ =>
        val valueOptFuture = orElse
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

