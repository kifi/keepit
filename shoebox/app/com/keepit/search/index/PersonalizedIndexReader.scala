package com.keepit.search.index

import com.keepit.common.logging.Logging
import com.keepit.search.query.QueryUtil._
import org.apache.lucene.index.AtomicReader
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.DocValues
import org.apache.lucene.index.DocsEnum
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.index.FieldInfo
import org.apache.lucene.index.FieldInfo.IndexOptions
import org.apache.lucene.index.FieldInfos
import org.apache.lucene.index.Fields
import org.apache.lucene.index.StoredFieldVisitor
import org.apache.lucene.index.Term
import org.apache.lucene.index.Terms
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.Query
import org.apache.lucene.util.Bits
import org.apache.lucene.util.BytesRef
import scala.collection.JavaConversions._
import java.util.Comparator
import java.util.{Iterator=>JIterator}

class PersonalizedIndexReader(mainReader: AtomicReader, personalReader: CachingIndexReader) extends AtomicReader with Logging {

  private[this] val mainFields: Fields = mainReader.fields
  private[this] val personalFields: Fields = personalReader.fields
  private[this] val fieldToFields: Map[String, Fields] = {
    val m = mainReader.fields.iterator.foldLeft(Map.empty[String, Fields]){ (m, f) => m + (f -> mainFields) }
    personalReader.fields.iterator.foldLeft(m){ (m, f) => m + (f -> personalFields) }
  }
  private[this] lazy val fieldToFieldInfo: Map[String, FieldInfo] = {
    val m = mainReader.getFieldInfos.iterator.foldLeft(Map.empty[String, FieldInfo]){ (m, fi) => m + (fi.name -> fi) }
    personalReader.getFieldInfos.iterator.foldLeft(m){ (m, fi) => m + (fi.name -> fi) }
  }

  override def numDocs() = mainReader.numDocs
  override def maxDoc() = mainReader.numDocs

  override def fields(): Fields = new Fields {
    override def iterator(): JIterator[String] = fieldToFields.keys.iterator
    override def terms(field: String): Terms = {
      fieldToFields.get(field) match {
        case Some(fields) => fields.terms(field)
        case _ => null
      }
    }
    def size() = fieldToFields.size
  }

  override def getFieldInfos(): FieldInfos = {
    val infos = fieldToFieldInfo.iterator.zipWithIndex.map{ case ((name, fi), number) =>
      new FieldInfo(name, true, number, false, true, false,
                    IndexOptions.DOCS_AND_FREQS_AND_POSITIONS, null, null, null)
    }.toArray
    new FieldInfos(infos)
  }
  override def getLiveDocs(): Bits = mainReader.getLiveDocs

  override def getTermVectors(doc: Int) = throw new UnsupportedOperationException()
  override def docValues(field: String): DocValues = null
  override def normValues(field: String): DocValues = null
  override def hasDeletions() = mainReader.hasDeletions()
  override def document(doc: Int, visitor: StoredFieldVisitor) = throw new UnsupportedOperationException()
  protected def doClose() {}
}

//object PersonalizedIndexReader {
//  def termDocsEnum(personalizedIndexReader: PersonalizedIndexReader) = {
//    termDocsEnum(personalizedIndexReader)new PersonalizedDocsEnum(personalizedIndexReader)
//  }
//  def termPositionsEnum(personalizedIndexReader: PersonalizedIndexReader) = new PersonalizedDocsAndPositionsEnum(personalizedIndexReader)
//
//  class PersonalizedDocsEnum(personalizedIndexReader: PersonalizedIndexReader) extends DocsEnum {
//    private[this] var inner: DocsEnum = emptyDocsEnum
//
//    override def docID(): Int = inner.docID()
//    override def freq(): Int = inner.freq()
//    override def nextDoc(): Int = inner.nextDoc()
//    override def advance(did: Int): Int = inner.advance(did)
//  }
//
//  class PersonalizedDocsAndPositionsEnum(personalizedIndexReader: PersonalizedIndexReader) extends DocsAndPositionsEnum {
//    private[this] var inner: DocsAndPositionsEnum = emptyDocsAndPositionsEnum
//
//    override def docID(): Int = inner.docID()
//    override def freq(): Int = inner.freq()
//    override def nextDoc(): Int = inner.nextDoc()
//    override def advance(did: Int): Int = inner.advance(did)
//    override def nextPosition(): Int = inner.nextPosition()
//    override def startOffset(): Int = -1
//    override def endOffset(): Int = -1
//    override def getPayload(): BytesRef = inner.getPayload()
//  }
//}
