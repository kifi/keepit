package com.keepit.search.index.graph.library

import com.keepit.common.db.Id
import com.keepit.model.view.LibraryMembershipView
import com.keepit.model._
import com.keepit.search.index.{ Searcher, DefaultAnalyzer, Indexable, FieldDecoder }
import com.keepit.search.LangDetector
import com.keepit.search.util.LongArraySet
import org.apache.lucene.index.Term

object LibraryFields {
  val nameField = "t"
  val nameStemmedField = "ts"
  val descriptionField = "c"
  val descriptionStemmedField = "cs"
  val visibilityField = "v"
  val ownerField = "o"
  val usersField = "u"
  val allUsersField = "a"
  val recordField = "rec"

  val textSearchFields = Set(nameField, nameStemmedField, descriptionField, descriptionStemmedField)
  val nameSearchFields = Set(nameField, nameStemmedField)

  object Visibility {
    val SECRET = 0
    val DISCOVERABLE = 1
    val PUBLISHED = 2

    @inline def toNumericCode(visibility: LibraryVisibility) = visibility match {
      case LibraryVisibility.SECRET => SECRET
      case LibraryVisibility.DISCOVERABLE => DISCOVERABLE
      case LibraryVisibility.PUBLISHED => PUBLISHED
    }

    @inline def fromNumericCode(visibility: Long) = {
      if (visibility == SECRET) LibraryVisibility.SECRET
      else if (visibility == DISCOVERABLE) LibraryVisibility.DISCOVERABLE
      else LibraryVisibility.PUBLISHED
    }
  }

  val decoders: Map[String, FieldDecoder] = Map.empty
}

object LibraryIndexable {
  def isSecret(librarySearcher: Searcher, libraryId: Id[Library]): Boolean = {
    getVisibility(librarySearcher, libraryId.id).exists(_ == LibraryVisibility.SECRET)
  }

  def isPublished(librarySearcher: Searcher, libId: Long): Boolean = {
    getVisibility(librarySearcher, libId).exists(_ == LibraryVisibility.PUBLISHED)
  }

  def getVisibility(librarySearcher: Searcher, libId: Long): Option[LibraryVisibility] = {
    librarySearcher.getLongDocValue(LibraryFields.visibilityField, libId).map(LibraryFields.Visibility.fromNumericCode(_))
  }

  def getRecord(librarySearcher: Searcher, libraryId: Id[Library]): Option[LibraryRecord] = {
    librarySearcher.getDecodedDocValue(LibraryFields.recordField, libraryId.id)
  }

  def getLibrariesByOwner(librarySearcher: Searcher, ownerId: Id[User]): Set[Long] = {
    LongArraySet.from(librarySearcher.findPrimaryIds(new Term(LibraryFields.ownerField, ownerId.id.toString)).toArray)
  }

  def getLibrariesByMember(librarySearcher: Searcher, memberId: Id[User]): Set[Long] = {
    LongArraySet.from(librarySearcher.findPrimaryIds(new Term(LibraryFields.usersField, memberId.id.toString)).toArray)
  }

  def countPublishedLibrariesByMember(librarySearcher: Searcher, memberId: Id[User]): Int = {
    librarySearcher.findPrimaryIds(new Term(LibraryFields.usersField, memberId.id.toString)).toArray().count(isPublished(librarySearcher, _))
  }
}

class LibraryIndexable(library: Library, memberships: Seq[LibraryMembershipView]) extends Indexable[Library, Library] {

  val id = library.id.get
  val sequenceNumber = library.seq
  val isDeleted: Boolean = memberships.isEmpty

  private val (users, allUsers) = {
    var users = Set.empty[Id[User]]
    val allUsers = memberships.map { membership =>
      require(membership.libraryId == id, s"This membership is unrelated to library $id: $membership")
      if (membership.showInSearch) { users += membership.userId }
      membership.userId
    }.toSet
    (users, allUsers)
  }

  override def buildDocument = {
    import LibraryFields._
    val doc = super.buildDocument

    library.kind match {
      case LibraryKind.SYSTEM_MAIN | LibraryKind.SYSTEM_SECRET => // do not index the name of main/private libraries
      case LibraryKind.USER_CREATED =>
        val nameLang = LangDetector.detect(library.name)
        doc.add(buildTextField(nameField, library.name, DefaultAnalyzer.getAnalyzer(nameLang)))
        doc.add(buildTextField(nameStemmedField, library.name, DefaultAnalyzer.getAnalyzerWithStemmer(nameLang)))
    }

    library.description.foreach { description =>
      val descriptionLang = LangDetector.detect(description)
      doc.add(buildTextField(descriptionField, description, DefaultAnalyzer.getAnalyzer(descriptionLang)))
      doc.add(buildTextField(descriptionStemmedField, description, DefaultAnalyzer.getAnalyzerWithStemmer(descriptionLang)))
    }

    doc.add(buildKeywordField(ownerField, library.ownerId.id.toString))
    doc.add(buildIteratorField(usersField, users.iterator) { id => id.id.toString })
    doc.add(buildIteratorField(allUsersField, allUsers.iterator) { id => id.id.toString })

    doc.add(buildLongValueField(visibilityField, Visibility.toNumericCode(library.visibility)))

    doc.add(buildBinaryDocValuesField(recordField, LibraryRecord(library)))

    doc
  }
}
