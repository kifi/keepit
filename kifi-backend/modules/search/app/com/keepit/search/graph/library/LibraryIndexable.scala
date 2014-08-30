package com.keepit.search.graph.library

import com.keepit.common.db.Id
import com.keepit.model.{ LibraryVisibility, User, LibraryMembership, Library }
import com.keepit.search.index.{ DefaultAnalyzer, Indexable, FieldDecoder }
import com.keepit.search.LangDetector

object LibraryFields {
  val nameField = "n"
  val nameStemmedField = "ns"
  val descriptionField = "d"
  val descriptionStemmedField = "ds"
  val visibilityField = "v"
  val ownerField = "o"
  val usersField = "u"
  val allUsersField = "a"
  val recordField = "rec"

  object Visibility {
    val SECRET = 0
    val DISCOVERABLE = 1
    val PUBLISHED = 2

    @inline def toNumericCode(visibility: LibraryVisibility) = visibility match {
      case LibraryVisibility.SECRET => SECRET
      case LibraryVisibility.DISCOVERABLE => DISCOVERABLE
      case LibraryVisibility.PUBLISHED => PUBLISHED
    }
  }

  val decoders: Map[String, FieldDecoder] = Map.empty
}

class LibraryIndexable(library: Library, memberships: Seq[LibraryMembership]) extends Indexable[Library, Library] {

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

    val nameLang = LangDetector.detect(library.name)
    doc.add(buildTextField(nameField, library.name, DefaultAnalyzer.getAnalyzer(nameLang)))
    doc.add(buildTextField(nameStemmedField, library.name, DefaultAnalyzer.getAnalyzerWithStemmer(nameLang)))

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
