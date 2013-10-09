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

  protected[cache] def getFromInnerCache(key: K): Option[Option[T]]
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
    getFromInnerCache(key) match {
      case Some(valueOpt) => valueOpt
      case None => outerCache match {
        case Some(cache) =>
          val valueOpt = cache.get(key)
          if (valueOpt.isDefined) setInnerCache(key, valueOpt)
          valueOpt
        case None => None
      }
    }
  }

  def getOrElse(key: K)(orElse: => T): T = {
    def fallback: T = {
      val value = outerCache match {
        case Some(cache) => cache.getOrElse(key)(orElse)
        case None => orElse
      }
      setInnerCache(key, Some(value))
      value
    }

    getFromInnerCache(key) match {
      case Some(valueOpt) => valueOpt match {
        case Some(value) => value
        case None => fallback
      }
      case None => fallback

    }
  }

  def getOrElseOpt(key: K)(orElse: => Option[T]): Option[T] = {
    getFromInnerCache(key) match {
      case Some(valueOpt) => valueOpt
      case None =>
        val valueOption : Option[T] = outerCache match {
          case Some(cache) => cache.getOrElseOpt(key)(orElse)
          case None => orElse
        }
        setInnerCache(key, valueOption)
        valueOption
    }
  }

  def getOrElseFuture(key: K)(orElse: => Future[T]): Future[T] = {
    def fallback: Future[T] = {
      val valueFuture = outerCache match {
        case Some(cache) => cache.getOrElseFuture(key)(orElse)
        case None => orElse
      }
      valueFuture.onSuccess {case value => setInnerCache(key, Some(value))}
      valueFuture
    }

    getFromInnerCache(key) match {
      case Some(valueOpt) => valueOpt match {
        case Some(value) => Promise.successful(value).future
        case None => fallback
      }
      case None => fallback
    }
  }

  def getOrElseFutureOpt(key: K)(orElse: => Future[Option[T]]): Future[Option[T]] = {
    getFromInnerCache(key) match {
      case Some(valueOpt) => Promise.successful(valueOpt).future
      case None =>
        val valueFutureOption = outerCache match {
          case Some(cache) => cache.getOrElseFutureOpt(key)(orElse)
          case None => orElse
        }
        valueFutureOption.onSuccess {case valueOption => setInnerCache(key, valueOption)}
        valueFutureOption
    }
  }
}
