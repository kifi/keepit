package com.keepit.search.graph.keep

import com.keepit.search.index.{ FieldDecoder, DefaultAnalyzer, Indexable }
import com.keepit.model.Keep
import com.keepit.search.LangDetector

object KeepFields {
  val libraryField = "lib"
  val libraryIdField = "libId"
  val uriField = "uri"
  val uriIdField = "uriId"
  val discoverableUriField = "dUri"
  val userField = "user"
  val discoverabilityField = "d"
  val titleField = "t"
  val titleStemmedField = "ts"
  val siteField = "site"
  val homePageField = "home_page"
  val createdAtField = "createdAt"
  val recordField = "rec"

  val decoders: Map[String, FieldDecoder] = Map.empty
}

case class KeepIndexable(keep: Keep) extends Indexable[Keep, Keep] {
  val id = keep.id.get
  val sequenceNumber = keep.seq
  val isDeleted = !keep.isActive

  override def buildDocument = {
    import KeepFields._
    val doc = super.buildDocument

    doc.add(buildKeywordField(libraryField, keep.libraryId.get.toString))
    doc.add(buildKeywordField(uriField, keep.uriId.toString))
    if (keep.isDiscoverable) doc.add(buildKeywordField(discoverableUriField, keep.uriId.toString))
    doc.add(buildKeywordField(userField, keep.userId.toString))
    doc.add(buildKeywordField(discoverabilityField, keep.isDiscoverable.toString))

    keep.title.foreach { title =>
      val titleLang = LangDetector.detect(title)
      doc.add(buildTextField(titleField, title, DefaultAnalyzer.getAnalyzer(titleLang)))
      doc.add(buildTextField(titleStemmedField, title, DefaultAnalyzer.getAnalyzerWithStemmer(titleLang)))
    }

    buildDomainFields(keep.url, siteField, homePageField).foreach(doc.add)

    keep.libraryId.foreach(libId => buildIdValueField(libraryIdField, libId))
    buildIdValueField(uriIdField, keep.uriId)

    buildLongValueField(createdAtField, keep.createdAt.getMillis)

    buildBinaryDocValuesField(recordField, KeepRecord(keep))
    doc
  }
}
