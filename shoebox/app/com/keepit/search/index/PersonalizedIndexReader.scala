package com.keepit.search.index

import com.keepit.common.logging.Logging
import com.keepit.search.query.QueryUtil._
import org.apache.lucene.index.AtomicReader
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.DocsEnum
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.index.FieldInfo
import org.apache.lucene.index.FieldInfo.IndexOptions
import org.apache.lucene.index.FieldInfos
import org.apache.lucene.index.Fields
import org.apache.lucene.index.NumericDocValues
import org.apache.lucene.index.BinaryDocValues
import org.apache.lucene.index.SortedDocValues
import org.apache.lucene.index.SortedSetDocValues
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
    val m = personalFields.iterator.foldLeft(Map.empty[String, Fields]){ (m, f) => m + (f -> personalFields) }
    mainFields.iterator.foldLeft(m){ (m, f) => m + (f -> mainFields) }
  }
  private[this] lazy val fieldToFieldInfo: Map[String, FieldInfo] = {
    val m = personalReader.getFieldInfos.iterator.foldLeft(Map.empty[String, FieldInfo]){ (m, fi) => m + (fi.name -> fi) }
    mainReader.getFieldInfos.iterator.foldLeft(m){ (m, fi) => m + (fi.name -> fi) }
  }

  override def numDocs() = mainReader.numDocs
  override def maxDoc() = mainReader.maxDoc

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
  override def getNormValues(field: String): NumericDocValues = mainReader.getNormValues(field)

  override def getTermVectors(doc: Int) = throw new UnsupportedOperationException()
  override def getNumericDocValues(field: String): NumericDocValues = null
  override def getBinaryDocValues(field: String): BinaryDocValues = null
  override def getSortedDocValues(field: String): SortedDocValues = null
  override def getSortedSetDocValues(field: String): SortedSetDocValues = null
  override def hasDeletions() = mainReader.hasDeletions()
  override def document(doc: Int, visitor: StoredFieldVisitor) = throw new UnsupportedOperationException()
  protected def doClose() {}
}

