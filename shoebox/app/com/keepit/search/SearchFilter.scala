package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.model.Collection
import com.keepit.common.akka.MonitoredAwait
import scala.concurrent.duration._
import scala.concurrent.Future

abstract class SearchFilter(
    context: Option[String],
    val collections: Option[Seq[Id[Collection]]],
    val timeRange: Option[SearchFilter.TimeRange]) {

  lazy val idFilter = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))

  def includeMine: Boolean
  def includeShared: Boolean
  def includeFriends: Boolean
  def includeOthers: Boolean
  def isDefault = false
  def isCustom = false
  def filterFriends(f: Set[Id[User]]) = f
}

object SearchFilter {

  case class TimeRange(start: Long, end: Long)

  private def timeRange(startTime: Option[String], endTime: Option[String], tz: Option[String]): Option[TimeRange] = {
    if (startTime.isDefined || endTime.isDefined) {
      val startMillis = startTime.map{ t => parseStandardTime(t + " 00:00:00.000 " + tz.getOrElse("+0000")).getMillis }.getOrElse(0L)
      val endMillis = endTime.map{ t => parseStandardTime(t + " 23:59:59.999 " + tz.getOrElse("+0000")).getMillis }.getOrElse(Long.MaxValue)
      Some(TimeRange(startMillis, endMillis))
    } else {
      None
    }
  }

  def default(context: Option[String] = None) = new SearchFilter(context, None, None) {
    def includeMine    = true
    def includeShared  = true
    def includeFriends = true
    def includeOthers  = true
    override def isDefault = true
  }

  def all(context: Option[String] = None,
          startTime: Option[String] = None,
          endTime: Option[String] = None,
          tz: Option[String] = None) = {
    val excludeOthers = (startTime.isDefined || endTime.isDefined)
    new SearchFilter(context, None, timeRange(startTime, endTime, tz)) {
      def includeMine    = true
      def includeShared  = true
      def includeFriends = true
      def includeOthers  = !excludeOthers
      override def isDefault = false
    }
  }

  def mine(context: Option[String] = None,
           collections: Option[Seq[Id[Collection]]] = None,
           startTime: Option[String] = None,
           endTime: Option[String] = None,
           tz: Option[String] = None) = {
    new SearchFilter(context, collections, timeRange(startTime, endTime, tz)) {
      def includeMine    = true
      def includeShared  = true
      def includeFriends = false
      def includeOthers  = false
    }
  }
  def friends(context: Option[String] = None,
              startTime: Option[String] = None,
              endTime: Option[String] = None,
              tz: Option[String] = None) = {
    new SearchFilter(context, None, timeRange(startTime, endTime, tz)) {
      def includeMine    = false
      def includeShared  = false
      def includeFriends = true
      def includeOthers  = false
    }
  }
  def custom(context: Option[String] = None,
             usersFuture: Future[Seq[Id[User]]],
             startTime: Option[String] = None,
             endTime: Option[String] = None,
             tz: Option[String]= None,
             monitoredAwait: MonitoredAwait) = {
    new SearchFilter(context, None, timeRange(startTime, endTime, tz)) {

      private[this] lazy val users = monitoredAwait.result(usersFuture, 5 seconds).toSet

      def includeMine    = false
      def includeShared  = true
      def includeFriends = true
      def includeOthers  = false
      override def isCustom = true
      override def filterFriends(f: Set[Id[User]]) = (users intersect f)
    }
  }
}
