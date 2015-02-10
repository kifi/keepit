package com.keepit.search.index.graph.library

import com.keepit.common.db.Id
import com.keepit.model.view.LibraryMembershipView
import com.keepit.model._
import com.keepit.search.index._
import com.keepit.search.LangDetector
import com.keepit.search.util.LongArraySet
import org.apache.lucene.index.Term

object LibraryFields {
  val nameField = "t"
  val nameStemmedField = "ts"
  val namePrefixField = "tp"
  val nameValueField = "tv"
  val descriptionField = "c"
  val descriptionStemmedField = "cs"
  val visibilityField = "v"
  val kindField = "k"
  val ownerField = "o"
  val ownerIdField = "oid"
  val usersField = "u"
  val allUsersField = "a"
  val allUsersCountField = "ac"
  val recordField = "rec"

  val strictTextSearchFields = Set(nameField, nameStemmedField, descriptionField, descriptionStemmedField)
  val textSearchFields = strictTextSearchFields + namePrefixField
  val nameSearchFields = Set(nameField, nameStemmedField)

  val maxPrefixLength = 8

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

  object Kind {
    val SYSTEM_MAIN = 0
    val SYSTEM_SECRET = 1
    val USER_CREATED = 2

    @inline def toNumericCode(kind: LibraryKind) = kind match {
      case LibraryKind.SYSTEM_MAIN => SYSTEM_MAIN
      case LibraryKind.SYSTEM_SECRET => SYSTEM_SECRET
      case LibraryKind.USER_CREATED => USER_CREATED
    }

    @inline def fromNumericCode(kind: Long) = {
      if (kind == SYSTEM_MAIN) LibraryKind.SYSTEM_MAIN
      else if (kind == SYSTEM_SECRET) LibraryKind.SYSTEM_SECRET
      else LibraryKind.USER_CREATED
    }
  }

  val decoders: Map[String, FieldDecoder] = Map(
    nameValueField -> DocUtil.stringDocValFieldDecoder,
    recordField -> DocUtil.binaryDocValFieldDecoder(LibraryRecord.fromByteArray(_, _, _).toString)
  )
}

object LibraryIndexable {
  def isSecret(librarySearcher: Searcher, libraryId: Id[Library]): Boolean = {
    getVisibility(librarySearcher, libraryId.id).exists(_ == LibraryVisibility.SECRET)
  }

  def isPublished(librarySearcher: Searcher, libId: Long): Boolean = {
    getVisibility(librarySearcher, libId).exists(_ == LibraryVisibility.PUBLISHED)
  }

  def getVisibility(librarySearcher: Searcher, libId: Long): Option[LibraryVisibility] = {
    librarySearcher.getLongDocValue(LibraryFields.visibilityField, libId).map(LibraryFields.Visibility.fromNumericCode)
  }

  def getKind(librarySearcher: Searcher, libId: Long): Option[LibraryKind] = {
    librarySearcher.getLongDocValue(LibraryFields.kindField, libId).map(LibraryFields.Kind.fromNumericCode)
  }

  def getName(librarySearcher: Searcher, libId: Long): Option[String] = {
    librarySearcher.getStringDocValue(LibraryFields.nameValueField, libId)
  }

  def getMemberCount(librarySearcher: Searcher, libId: Long): Option[Long] = {
    librarySearcher.getLongDocValue(LibraryFields.allUsersCountField, libId)
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
        doc.add(buildPrefixField(namePrefixField, library.name, maxPrefixLength))
        doc.add(buildStringDocValuesField(nameValueField, library.name))
    }

    library.description.foreach { description =>
      val descriptionLang = LangDetector.detect(description)
      doc.add(buildTextField(descriptionField, description, DefaultAnalyzer.getAnalyzer(descriptionLang)))
      doc.add(buildTextField(descriptionStemmedField, description, DefaultAnalyzer.getAnalyzerWithStemmer(descriptionLang)))
    }

    doc.add(buildKeywordField(ownerField, library.ownerId.id.toString))
    doc.add(buildIteratorField(usersField, users.iterator) { id => id.id.toString })
    doc.add(buildIteratorField(allUsersField, allUsers.iterator) { id => id.id.toString })

    doc.add(buildIdValueField(ownerIdField, library.ownerId))
    doc.add(buildLongValueField(allUsersCountField, allUsers.size))
    doc.add(buildLongValueField(visibilityField, Visibility.toNumericCode(library.visibility)))
    doc.add(buildLongValueField(kindField, Kind.toNumericCode(library.kind)))

    doc.add(buildBinaryDocValuesField(recordField, LibraryRecord(library)))

    doc
  }
}
