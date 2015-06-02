package com.keepit.search.index.graph.library.membership

import com.keepit.common.db.Id
import com.keepit.model.view.LibraryMembershipView
import com.keepit.model._
import com.keepit.search.index._
import com.keepit.search.index.graph.library.LibraryIndexable
import com.keepit.search.util.{ LongArrayBuilder, LongArraySet }
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
    val READ_INSERT = 2
    val READ_ONLY = 3

    @inline def toNumericCode(libraryAccess: LibraryAccess) = libraryAccess match {
      case LibraryAccess.READ_ONLY => READ_ONLY
      case LibraryAccess.READ_WRITE => READ_WRITE
      case LibraryAccess.READ_INSERT => READ_INSERT
      case LibraryAccess.OWNER => OWNER
    }

    @inline def fromNumericCode(access: Long) = {
      if (access == OWNER) LibraryAccess.OWNER
      else if (access == READ_WRITE) LibraryAccess.READ_WRITE
      else if (access == READ_INSERT) LibraryAccess.READ_INSERT
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

  def getLibrariesByCollaborator(libraryMembershipSearcher: Searcher, collaboratorId: Id[User]): Set[Long] = {
    LongArraySet.from(libraryMembershipSearcher.findSecondaryIds(new Term(collaboratorField, collaboratorId.id.toString), libraryIdField).toArray)
  }

  def countPublishedLibrariesByCollaborator(librarySearcher: Searcher, libraryMembershipSearcher: Searcher, collaboratorId: Id[User]): Int = {
    val libraryIds = libraryMembershipSearcher.findSecondaryIds(new Term(collaboratorField, collaboratorId.id.toString), libraryIdField).toArray
    libraryIds.count(LibraryIndexable.isPublished(librarySearcher, _))
  }

  def getMemberCount(libraryMembershipSearcher: Searcher, libId: Long): Int = {
    libraryMembershipSearcher.freq(new Term(libraryField, libId.toString))
  }

  def getMembersByLibrary(libraryMembershipSearcher: Searcher, libraryId: Id[Library]): (Set[Long], Set[Long], Set[Long]) = {
    import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
    val owners = new LongArrayBuilder
    val collaborators = new LongArrayBuilder
    val followers = new LongArrayBuilder

    val libraryTerm = new Term(libraryField, libraryId.id.toString)
    libraryMembershipSearcher.foreachReader { reader =>
      val userIdValues = reader.getNumericDocValues(userIdField)
      val accessValues = reader.getNumericDocValues(accessField)
      val td = reader.termDocsEnum(libraryTerm)
      if (td != null) {
        var doc = td.nextDoc()
        while (doc != NO_MORE_DOCS) {
          val access = accessValues.get(doc)
          val userId = userIdValues.get(doc)
          val buffer = {
            if (access == Access.OWNER) owners
            else if (access == Access.READ_WRITE || access == Access.READ_INSERT) collaborators
            else followers
          }
          buffer += userId
          doc = td.nextDoc()
        }
      }
    }

    (LongArraySet.from(owners.toArray), LongArraySet.from(collaborators.toArray), LongArraySet.from(followers.toArray))
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
