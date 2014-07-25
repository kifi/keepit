package com.keepit.search.graph.library

import com.keepit.model._
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
  val keepsDiscoveryField = "kd"
  val usersField = "u"
  val hiddenUsersField = "h"
  val recordField = "rec"

  val decoders: Map[String, FieldDecoder] = Map.empty
}

class LibraryIndexable(library: Library, memberships: Seq[LibraryMembership]) extends Indexable[Library, Library] {

  val id = library.id.get
  val sequenceNumber = library.seq
  val isDeleted: Boolean = memberships.isEmpty

  private val (users, hiddenUsers) = {
    var hiddenUsers = Set.empty[Id[User]]
    val users = memberships.map { membership =>
      require(membership.libraryId == id, s"This membership is unrelated to library $id: $membership")
      if (!membership.showInSearch) { hiddenUsers += membership.userId }
      membership.userId
    }
    (users, hiddenUsers)
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

    library.visibility match {
      case LibraryVisibility.ANYONE => doc.add(buildKeywordField(visibilityField, "anyone"))
      case _ =>
    }

    if (library.keepDiscoveryEnabled) { doc.add(buildKeywordField(keepsDiscoveryField, "anyone")) }

    doc.add(buildIteratorField(usersField, users.iterator) { id => id.id.toString })
    doc.add(buildIteratorField(hiddenUsersField, hiddenUsers.iterator) { id => id.id.toString })

    doc.add(buildBinaryDocValuesField(recordField, LibraryRecord(library)))

    doc
  }
}

class LibraryIndexer(indexDirectory: IndexDirectory, shoebox: ShoeboxServiceClient, val airbrake: AirbrakeNotifier) extends Indexer[Library, Library, LibraryIndexer](indexDirectory, LibraryFields.decoders) {

  def update(): Int = throw new UnsupportedOperationException()

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  private[library] def asyncUpdate(): Future[Boolean] = updateLock.synchronized {
    resetSequenceNumberIfReindex()
    val fetchSize = commitBatchSize
    fetchIndexables(sequenceNumber, fetchSize).map {
      case (indexables, exhausted) =>
        processIndexables(indexables)
        exhausted
    }
  }

  private def fetchIndexables(seq: SequenceNumber[Library], fetchSize: Int): Future[(Seq[LibraryIndexable], Boolean)] = {
    shoebox.getLibrariesAndMembershipsChanged(seq, fetchSize).map { updates =>
      val indexables = updates.map { case LibraryAndMemberships(library, memberships) => new LibraryIndexable(library, memberships) }
      val exhausted = updates.length < fetchSize
      (indexables, exhausted)
    }
  }

  private def processIndexables(updates: Seq[Indexable[Library, Library]]): Int = updateLock.synchronized {
    doUpdate("LibraryIndex")(updates.iterator)
  }
}
