package com.keepit.search.graph.library

import com.keepit.model.{ User, Library }
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.search.index._
import com.keepit.search.LangDetector
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.concurrent.Future
import com.keepit.shoebox.ShoeboxServiceClient

object LibraryFields {
  val nameField = "n"
  val nameStemmedField = "ns"
  val descriptionField = "d"
  val descriptionStemmedField = "ds"
  val visibilityField = "v"
  val usersField = "u"
  val recordField = "rec"

  val decoders: Map[String, FieldDecoder] = Map.empty
}

class LibraryIndexable(library: Library, users: Seq[Id[User]]) extends Indexable[Library, Library] {

  val id = library.id.get
  val sequenceNumber = library.seq
  val isDeleted: Boolean = users.isEmpty

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

    doc.add(buildKeywordField(visibilityField, library.visibility.value))
    doc.add(buildIteratorField(usersField, users.iterator) { id => id.id.toString })

    val record = LibraryRecord(library.name, library.description, library.id.get)
    doc.add(buildBinaryDocValuesField(recordField, record))

    doc
  }
}

class LibraryIndexer(indexDirectory: IndexDirectory, shoebox: ShoeboxServiceClient, val airbrake: AirbrakeNotifier) extends Indexer[Library, Library, LibraryIndexer](indexDirectory, LibraryFields.decoders) {

  def update(): Int = throw new UnsupportedOperationException()

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  def fetchUpdates(seq: SequenceNumber[Library], fetchSize: Int): Future[Seq[Indexable[Library, Library]]] = {
    shoebox.getLibrariesWithMembersChanged(seq, fetchSize).map {
      case libraries =>
        libraries.map {
          case (library, memberships) =>
            val users = memberships.flatMap { membership =>
              if (membership.libraryId != library.id.get) { throw new IllegalArgumentException(s"Inconsistent membership for library ${library.id.get}: $membership") }
              Some(membership.userId) //todo(Léo): filter this once LibraryMembership tracks searchability
            }
            new LibraryIndexable(library, users)
        }
    }
  }

  def processUpdates(updates: Seq[Indexable[Library, Library]]): Int = updateLock.synchronized {
    doUpdate("LibraryIndex")(updates.iterator)
  }
}
