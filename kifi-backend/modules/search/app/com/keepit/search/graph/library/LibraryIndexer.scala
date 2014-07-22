package com.keepit.search.graph.library

import com.keepit.model.{User, Library}
import com.keepit.common.db.Id
import com.keepit.search.index.{DefaultAnalyzer, Indexable}
import com.keepit.search.LangDetector

object LibraryFields {
  val nameField = "n"
  val nameStemmedField = "ns"
  val descriptionField = "d"
  val descriptionStemmedField = "ds"
  val usersField = "u"
  val recordField = "rec"
}

class LibraryIndexable(library: Library, members: Seq[Id[User]]) extends Indexable[Library, Library] {

  val id = library.id.get
  val sequenceNumber = library.seq
  val isDeleted: Boolean = members.isEmpty

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

    doc.add(buildIteratorField(usersField, members.iterator) { id => id.id.toString })

    val record = LibraryRecord(library.name, library.description, library.id.get)
    doc.add(buildBinaryDocValuesField(recordField, record))

    doc
  }
}
