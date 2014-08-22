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
  val userIdField = "userId"
  val visibilityField = "v"
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

    keep.title.foreach { title =>
      val titleLang = LangDetector.detect(title)
      doc.add(buildTextField(titleField, title, DefaultAnalyzer.getAnalyzer(titleLang)))
      doc.add(buildTextField(titleStemmedField, title, DefaultAnalyzer.getAnalyzerWithStemmer(titleLang)))
    }

    buildDomainFields(keep.url, siteField, homePageField).foreach(doc.add)

    doc.add(buildIdValueField(uriIdField, keep.uriId))
    doc.add(buildIdValueField(userIdField, keep.userId))
    keep.libraryId.foreach(libId => doc.add(buildIdValueField(libraryIdField, libId)))

    doc.add(buildLongValueField(visibilityField, ???)) // todo(Andrew, Léo): denormalize library visibility onto keeps

    doc.add(buildBinaryDocValuesField(recordField, KeepRecord(keep)))
    buildLongValueField(createdAtField, keep.createdAt.getMillis)
    doc
  }
}
