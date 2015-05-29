package com.keepit.search.index.graph.library.membership

import com.keepit.common.db.Id
import com.keepit.model.view.LibraryMembershipView
import com.keepit.model._
import com.keepit.search.index._
import com.keepit.search.index.graph.library.LibraryIndexable
import com.keepit.search.util.LongArraySet
import org.apache.lucene.index.Term

object LibraryMembershipFields {
  val userField = "user"
  val userIdField = "userId"
  val libraryField = "lib"
  val libraryIdField = "libId"
  val searcherField = "searcher"
  val accessField = "access"
  val ownerField = "owner"
  val collaboratorField = "collaborator"

  object Access {
    val OWNER = 0
    val READ_WRITE = 1
    val READ_ONLY = 2

    @inline def toNumericCode(libraryAccess: LibraryAccess) = libraryAccess match {
      case LibraryAccess.READ_ONLY => READ_ONLY
      case LibraryAccess.READ_WRITE => READ_WRITE
      case LibraryAccess.OWNER => OWNER
      case LibraryAccess.READ_INSERT => READ_ONLY // deprecated
    }

    @inline def fromNumericCode(access: Long) = {
      if (access == OWNER) LibraryAccess.OWNER
      else if (access == READ_WRITE) LibraryAccess.READ_WRITE
      else LibraryAccess.READ_ONLY
    }

    val collaborator = Set[LibraryAccess](LibraryAccess.OWNER, LibraryAccess.READ_WRITE, LibraryAccess.READ_ONLY)
  }

  val decoders: Map[String, FieldDecoder] = Map.empty
}

object LibraryMembershipIndexable {

  import LibraryMembershipFields._

  def getLibrariesByMember(libraryMembershipSearcher: Searcher, memberId: Id[User], showInSearchOnly: Boolean = true): Set[Long] = {
    val memberField = if (showInSearchOnly) searcherField else userField
    LongArraySet.from(libraryMembershipSearcher.findSecondaryIds(new Term(memberField, memberId.id.toString), libraryIdField).toArray)
  }

  def countPublishedLibrariesByMember(librarySearcher: Searcher, libraryMembershipSearcher: Searcher, memberId: Id[User]): Int = {
    val libraryIds = libraryMembershipSearcher.findSecondaryIds(new Term(userField, memberId.id.toString), libraryIdField).toArray
    libraryIds.count(LibraryIndexable.isPublished(librarySearcher, _))
  }

  def getLibrariesByCollaborator(libraryMembershipSearcher: Searcher, ownerId: Id[User]): Set[Long] = {
    LongArraySet.from(libraryMembershipSearcher.findSecondaryIds(new Term(collaboratorField, ownerId.id.toString), libraryIdField).toArray)
  }

  def countPublishedLibrariesByCollaborator(librarySearcher: Searcher, libraryMembershipSearcher: Searcher, ownerId: Id[User]): Int = {
    val libraryIds = libraryMembershipSearcher.findSecondaryIds(new Term(collaboratorField, ownerId.id.toString), libraryIdField).toArray
    libraryIds.count(LibraryIndexable.isPublished(librarySearcher, _))
  }

  def getMemberCount(libraryMembershipSearcher: Searcher, libId: Long): Int = {
    libraryMembershipSearcher.freq(new Term(libraryField, libId.toString))
  }
}

class LibraryMembershipIndexable(membership: LibraryMembershipView) extends Indexable[LibraryMembership, LibraryMembership] {

  val id = membership.id
  val sequenceNumber = membership.seq
  val isDeleted: Boolean = (membership.state == LibraryMembershipStates.INACTIVE)

  override def buildDocument = {
    import LibraryMembershipFields._
    val doc = super.buildDocument

    doc.add(buildKeywordField(userField, membership.userId.id.toString))
    doc.add(buildIdValueField(userIdField, membership.userId))

    doc.add(buildLongValueField(accessField, Access.toNumericCode(membership.access)))

    if (membership.showInSearch) {
      doc.add(buildKeywordField(searcherField, membership.userId.id.toString))
    }
    if (membership.access == LibraryAccess.OWNER) {
      doc.add(buildKeywordField(ownerField, membership.userId.id.toString))
    }

    if (Access.collaborator.contains(membership.access)) {
      doc.add(buildKeywordField(collaboratorField, membership.userId.id.toString))
    }
      
    doc.add(buildKeywordField(libraryField, membership.libraryId.id.toString))
    doc.add(buildIdValueField(libraryIdField, membership.libraryId))

    doc
  }
}
