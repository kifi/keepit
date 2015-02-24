package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.util.{ LongArraySet, IdFilterCompressor }

abstract class SearchFilter(val userFilter: Option[Id[User]], val libraryContext: LibraryContext, context: Option[String]) {

  lazy val idFilter: LongArraySet = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))

  def includeMine: Boolean
  def includeFriends: Boolean
  def includeOthers: Boolean
  def isDefault = false
}

object SearchFilter {

  def apply(filter: Option[Either[Id[User], String]], library: LibraryContext, context: Option[String]): SearchFilter = {
    filter match {
      case Some(Right("m")) =>
        SearchFilter.mine(None, library, context)
      case Some(Right("f")) =>
        SearchFilter.friends(None, library, context)
      case Some(Right("a")) =>
        SearchFilter.all(None, library, context)
      case Some(Left(userId)) =>
        SearchFilter.all(Some(userId), library, context)
      case _ => SearchFilter.default(None, library, context)
    }
  }

  def default(user: Option[Id[User]] = None, library: LibraryContext = LibraryContext.None, context: Option[String] = None) = {
    new SearchFilter(user, library, context) {
      def includeMine = true
      def includeFriends = true
      def includeOthers = true
      override def isDefault = true
    }
  }

  def all(user: Option[Id[User]] = None, library: LibraryContext = LibraryContext.None, context: Option[String] = None) = {
    new SearchFilter(user, library, context) {
      def includeMine = true
      def includeFriends = true
      def includeOthers = true
    }
  }

  def mine(user: Option[Id[User]] = None, library: LibraryContext = LibraryContext.None, context: Option[String] = None) = {
    new SearchFilter(user, library, context) {
      def includeMine = true
      def includeFriends = false
      def includeOthers = false
    }
  }

  def friends(user: Option[Id[User]] = None, library: LibraryContext = LibraryContext.None, context: Option[String] = None) = {
    new SearchFilter(user, library, context) {
      def includeMine = false
      def includeFriends = true
      def includeOthers = false
    }
  }
}
