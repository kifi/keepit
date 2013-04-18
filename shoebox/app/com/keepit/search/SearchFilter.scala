package com.keepit.search

import com.keepit.common.db.Id
import com.keepit.model.User

abstract class SearchFilter(val idFilter: Set[Long]) {
  def includeMine: Boolean
  def includeShared: Boolean
  def includeFriends: Boolean
  def includeOthers: Boolean
  def isDefault = false
  def filterFriends(f: Set[Id[User]]) = f
}

object SearchFilter {
  def default(idFilter: Set[Long] = Set()) = new SearchFilter(idFilter) {
    def includeMine    = true
    def includeShared  = true
    def includeFriends = true
    def includeOthers  = true
    override def isDefault = true
  }
  def mine(idFilter: Set[Long] = Set()) = new SearchFilter(idFilter) {
    def includeMine    = true
    def includeShared  = true
    def includeFriends = false
    def includeOthers  = false
  }
  def friends(idFilter: Set[Long] = Set()) = new SearchFilter(idFilter) {
    def includeMine    = false
    def includeShared  = false
    def includeFriends = true
    def includeOthers  = false
  }
  def custom(idFilter: Set[Long] = Set(), users: Set[Id[User]]) = new SearchFilter(idFilter) {
    def includeMine    = false
    def includeShared  = true
    def includeFriends = true
    def includeOthers  = false
    override def filterFriends(f: Set[Id[User]]) = (users intersect f)
  }
}
