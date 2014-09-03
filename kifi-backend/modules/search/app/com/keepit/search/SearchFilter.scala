package com.keepit.search

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.Id
import com.keepit.model.Library
import com.keepit.search.util.{ LongArraySet, IdFilterCompressor }

abstract class SearchFilter(val libraryId: Option[Id[Library]], context: Option[String]) {

  lazy val idFilter: LongArraySet = IdFilterCompressor.fromBase64ToSet(context.getOrElse(""))

  def includeMine: Boolean
  def includeFriends: Boolean
  def includeOthers: Boolean
  def isDefault = false
}

object SearchFilter {

  def default(libraryPublicId: Option[String] = None, context: Option[String] = None)(implicit publicIdConfig: PublicIdConfiguration) = {
    val libId: Option[Id[Library]] = libraryPublicId.map { str => Library.decodePublicId(PublicId[Library](str)).get }

    new SearchFilter(libId, context) {
      def includeMine = true
      def includeFriends = true
      def includeOthers = true
      override def isDefault = true
    }
  }

  def all(libraryPublicId: Option[String] = None, context: Option[String] = None)(implicit publicIdConfig: PublicIdConfiguration) = {
    val libId: Option[Id[Library]] = libraryPublicId.map { str => Library.decodePublicId(PublicId[Library](str)).get }

    new SearchFilter(libId, context) {
      def includeMine = true
      def includeFriends = true
      def includeOthers = false
    }
  }

  def mine(libraryPublicId: Option[String] = None, context: Option[String] = None)(implicit publicIdConfig: PublicIdConfiguration) = {
    val libId: Option[Id[Library]] = libraryPublicId.map { str => Library.decodePublicId(PublicId[Library](str)).get }

    new SearchFilter(libId, context) {
      def includeMine = true
      def includeFriends = false
      def includeOthers = false
    }
  }

  def friends(libraryPublicId: Option[String] = None, context: Option[String] = None)(implicit publicIdConfig: PublicIdConfiguration) = {
    val libId: Option[Id[Library]] = libraryPublicId.map { str => Library.decodePublicId(PublicId[Library](str)).get }

    new SearchFilter(libId, context) {
      def includeMine = false
      def includeFriends = true
      def includeOthers = false
    }
  }
}
