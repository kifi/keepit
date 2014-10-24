package com.keepit.search

import com.keepit.search.util.{ LongArraySet, IdFilterCompressor }

abstract class SearchFilter(val libraryContext: LibraryContext, context: Option[String]) {

  lazy val idFilter: LongArraySet = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))

  def includeMine: Boolean
  def includeFriends: Boolean
  def includeOthers: Boolean
  def isDefault = false
}

object SearchFilter {

  def apply(filter: Option[String], library: LibraryContext, context: Option[String]): SearchFilter = {
    filter match {
      case Some("m") =>
        SearchFilter.mine(library, context)
      case Some("f") =>
        SearchFilter.friends(library, context)
      case Some("a") =>
        SearchFilter.all(library, context)
      case _ =>
        SearchFilter.default(library, context)
    }
  }

  def default(library: LibraryContext = LibraryContext.None, context: Option[String] = None) = {
    new SearchFilter(library, context) {
      def includeMine = true
      def includeFriends = true
      def includeOthers = true
      override def isDefault = true
    }
  }

  def all(library: LibraryContext = LibraryContext.None, context: Option[String] = None) = {
    new SearchFilter(library, context) {
      def includeMine = true
      def includeFriends = true
      def includeOthers = true
    }
  }

  def mine(library: LibraryContext = LibraryContext.None, context: Option[String] = None) = {
    new SearchFilter(library, context) {
      def includeMine = true
      def includeFriends = false
      def includeOthers = false
    }
  }

  def friends(library: LibraryContext = LibraryContext.None, context: Option[String] = None) = {
    new SearchFilter(library: LibraryContext, context) {
      def includeMine = false
      def includeFriends = true
      def includeOthers = false
    }
  }
}
