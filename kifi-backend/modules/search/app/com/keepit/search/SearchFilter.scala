package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.model.Collection
import com.keepit.common.akka.MonitoredAwait
import com.keepit.search.util.{ LongArraySet, IdFilterCompressor }
import scala.concurrent.duration._
import scala.concurrent.Future

abstract class SearchFilter(context: Option[String]) {

  lazy val idFilter: LongArraySet = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))

  def includeMine: Boolean
  def includeShared: Boolean
  def includeFriends: Boolean
  def includeOthers: Boolean
  def isDefault = false
}

object SearchFilter {

  def default(context: Option[String] = None) = {
    new SearchFilter(context) {
      def includeMine = true
      def includeShared = true
      def includeFriends = true
      def includeOthers = true
      override def isDefault = true
    }
  }

  def all(context: Option[String] = None) = {
    new SearchFilter(context) {
      def includeMine = true
      def includeShared = true
      def includeFriends = true
      def includeOthers = false
    }
  }

  def mine(context: Option[String] = None) = {
    new SearchFilter(context) {
      def includeMine = true
      def includeShared = true
      def includeFriends = false
      def includeOthers = false
    }
  }

  def friends(context: Option[String] = None) = {
    new SearchFilter(context) {
      def includeMine = false
      def includeShared = false
      def includeFriends = true
      def includeOthers = false
    }
  }
}
