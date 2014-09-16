package com.keepit.search

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.Id
import com.keepit.model.Library
import com.keepit.search.util.{ LongArraySet, IdFilterCompressor }

abstract class SearchFilter(val libraryId: Option[Id[Library]], libraryAccessAuthorized: Boolean, context: Option[String]) {

  lazy val idFilter: LongArraySet = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))

  def includeMine: Boolean
  def includeFriends: Boolean
  def includeOthers: Boolean
  def isDefault = false
  def isLibraryAccessAuthorized = libraryAccessAuthorized
}

object SearchFilter {

  def apply(filter: Option[String], library: Option[String], libraryAccessAuthorized: Boolean, context: Option[String])(implicit publicIdConfig: PublicIdConfiguration): SearchFilter = {
    filter match {
      case Some("m") =>
        SearchFilter.mine(library, libraryAccessAuthorized, context)
      case Some("f") =>
        SearchFilter.friends(library, libraryAccessAuthorized, context)
      case Some("a") =>
        SearchFilter.all(library, libraryAccessAuthorized, context)
      case _ =>
        SearchFilter.default(library, libraryAccessAuthorized, context)
    }
  }

  def default(libraryPublicId: Option[String] = None, libraryAccessAuthorized: Boolean = false, context: Option[String] = None)(implicit publicIdConfig: PublicIdConfiguration) = {
    val libId: Option[Id[Library]] = libraryPublicId.map { str => Library.decodePublicId(PublicId[Library](str)).get }

    new SearchFilter(libId, libraryAccessAuthorized, context) {
      def includeMine = true
      def includeFriends = true
      def includeOthers = true
      override def isDefault = true
    }
  }

  def all(libraryPublicId: Option[String] = None, libraryAccessAuthorized: Boolean = false, context: Option[String] = None)(implicit publicIdConfig: PublicIdConfiguration) = {
    val libId: Option[Id[Library]] = libraryPublicId.map { str => Library.decodePublicId(PublicId[Library](str)).get }

    new SearchFilter(libId, libraryAccessAuthorized, context) {
      def includeMine = true
      def includeFriends = true
      def includeOthers = false
    }
  }

  def mine(libraryPublicId: Option[String] = None, libraryAccessAuthorized: Boolean = false, context: Option[String] = None)(implicit publicIdConfig: PublicIdConfiguration) = {
    val libId: Option[Id[Library]] = libraryPublicId.map { str => Library.decodePublicId(PublicId[Library](str)).get }

    new SearchFilter(libId, libraryAccessAuthorized, context) {
      def includeMine = true
      def includeFriends = false
      def includeOthers = false
    }
  }

  def friends(libraryPublicId: Option[String] = None, libraryAccessAuthorized: Boolean = false, context: Option[String] = None)(implicit publicIdConfig: PublicIdConfiguration) = {
    val libId: Option[Id[Library]] = libraryPublicId.map { str => Library.decodePublicId(PublicId[Library](str)).get }

    new SearchFilter(libId, libraryAccessAuthorized, context) {
      def includeMine = false
      def includeFriends = true
      def includeOthers = false
    }
  }
}
