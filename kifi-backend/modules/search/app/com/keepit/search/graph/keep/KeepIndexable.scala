package com.keepit.search.graph.keep

import com.keepit.search.index.{ DefaultAnalyzer, Indexable }
import com.keepit.model.Keep
import com.keepit.search.LangDetector

object KeepFields {
  val libraryField = "lib"
  val uriField = "uri"
  val userField = "user"
  val titleField = "t"
  val titleStemmedField = "ts"
  val siteField = "site"
  val homePageField = "home_page"
  val recordField = "rec"
}

class KeepIndexable(keep: Keep) extends Indexable[Keep, Keep] {
  val id = keep.id.get
  val sequenceNumber = keep.seq
  val isDeleted = !keep.isActive

  override def buildDocument = {
    import KeepFields._
    val doc = super.buildDocument

    doc.add(buildKeywordField(libraryField, keep.libraryId.get.toString))
    doc.add(buildKeywordField(uriField, keep.uriId.toString))
    doc.add(buildKeywordField(userField, keep.userId.toString))

    keep.title.foreach { title =>
      val titleLang = LangDetector.detect(title)
      doc.add(buildTextField(titleField, title, DefaultAnalyzer.getAnalyzer(titleLang)))
      doc.add(buildTextField(titleStemmedField, title, DefaultAnalyzer.getAnalyzerWithStemmer(titleLang)))
    }

    buildDomainFields(keep.url, siteField, homePageField).foreach(doc.add)

    buildBinaryDocValuesField(recordField, KeepRecord(keep))
    doc
  }
}
