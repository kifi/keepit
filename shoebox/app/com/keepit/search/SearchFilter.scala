package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.User

abstract class SearchFilter(val idFilter: Set[Long], val timeRange: Option[SearchFilter.TimeRange]) {
  def includeMine: Boolean
  def includeShared: Boolean
  def includeFriends: Boolean
  def includeOthers: Boolean
  def isDefault = false
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

  def default(idFilter: Set[Long] = Set()) = new SearchFilter(idFilter, None) {
    def includeMine    = true
    def includeShared  = true
    def includeFriends = true
    def includeOthers  = true
    override def isDefault = true
  }

  def all(idFilter: Set[Long] = Set(),
          startTime: Option[String] = None,
          endTime: Option[String] = None,
          tz: Option[String] = None) = {
    val excludeOthers = (startTime.isDefined || endTime.isDefined)
    new SearchFilter(idFilter, timeRange(startTime, endTime, tz)) {
      def includeMine    = true
      def includeShared  = true
      def includeFriends = true
      def includeOthers  = !excludeOthers
      override def isDefault = false
    }
  }

  def mine(idFilter: Set[Long] = Set(),
           startTime: Option[String] = None,
           endTime: Option[String] = None,
           tz: Option[String] = None) = {
    new SearchFilter(idFilter, timeRange(startTime, endTime, tz)) {
      def includeMine    = true
      def includeShared  = true
      def includeFriends = false
      def includeOthers  = false
    }
  }
  def friends(idFilter: Set[Long] = Set(),
              startTime: Option[String] = None,
              endTime: Option[String] = None,
              tz: Option[String] = None) = {
    new SearchFilter(idFilter, timeRange(startTime, endTime, tz)) {
      def includeMine    = false
      def includeShared  = false
      def includeFriends = true
      def includeOthers  = false
    }
  }
  def custom(idFilter: Set[Long] = Set(),
             users: Set[Id[User]],
             startTime: Option[String] = None,
             endTime: Option[String] = None,
             tz: Option[String]= None) = {
    new SearchFilter(idFilter, timeRange(startTime, endTime, tz)) {
      def includeMine    = false
      def includeShared  = true
      def includeFriends = true
      def includeOthers  = false
      override def filterFriends(f: Set[Id[User]]) = (users intersect f)
    }
  }
}
