package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.Library
import com.keepit.search.util.{ LongArraySet, IdFilterCompressor }

abstract class SearchFilter(context: Option[String]) {

  lazy val idFilter: LongArraySet = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))

  val libraryId: Option[Id[Library]] = None

  def includeMine: Boolean
  def includeFriends: Boolean
  def includeOthers: Boolean
  def isDefault = false
}

object SearchFilter {

  def default(context: Option[String] = None) = {
    new SearchFilter(context) {
      def includeMine = true
      def includeFriends = true
      def includeOthers = true
      override def isDefault = true
    }
  }

  def all(context: Option[String] = None) = {
    new SearchFilter(context) {
      def includeMine = true
      def includeFriends = true
      def includeOthers = false
    }
  }

  def mine(context: Option[String] = None) = {
    new SearchFilter(context) {
      def includeMine = true
      def includeFriends = false
      def includeOthers = false
    }
  }

  def friends(context: Option[String] = None) = {
    new SearchFilter(context) {
      def includeMine = false
      def includeFriends = true
      def includeOthers = false
    }
  }

  def library(libId: Id[Library], context: Option[String] = None) = {
    new SearchFilter(context) {

      override val libraryId: Option[Id[Library]] = Some(libId)

      def includeMine = true
      def includeFriends = true
      def includeOthers = true
    }
  }
}
