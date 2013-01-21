package com.keepit.search.index

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.{NormalizedURI, User}
import org.apache.lucene.index.IndexReader
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.Query
import org.apache.lucene.index.Term
import scala.collection.immutable.LongMap
import scala.collection.mutable.{Map => MutableMap}
import java.util.{Map => JMap}
import java.util.Arrays
import org.apache.lucene.document.FieldSelector
import org.apache.lucene.index.TermVectorMapper
import org.apache.lucene.index.TermEnum
import org.apache.lucene.index.TermDocs
import org.apache.lucene.index.TermPositions
import scala.collection.mutable.ArrayBuffer

class PersonalizedIndexReader(mainReader: IndexReader, personalReader: CachingIndexReader) extends IndexReader with Logging {

  private[index] def readerFor(term: Term): IndexReader = {
    if (mainReader.docFreq(term) > 0) mainReader else personalReader
  }

  override def docFreq(term: Term) = {
    val df = mainReader.docFreq(term)
    if (df > 0) df else personalReader.docFreq(term)
  }
  override def doClose() {}
  override def hasDeletions() = false
  override def isDeleted(docid: Int) = false
  override def hasNorms(field: String) = (mainReader.hasNorms(field) || personalReader.hasNorms(field))
  override def termDocs() = PersonalizedIndexReader.termDocs(this)
  override def termPositions() = PersonalizedIndexReader.termPositions(this)
  override def maxDoc() = mainReader.maxDoc()
  override def numDocs() = mainReader.numDocs()
  override def norms(field: String) = (if (mainReader.hasNorms(field)) mainReader else personalReader).norms(field)
  override def norms(field: String, bytes: Array[Byte], offset: Int) = (if (mainReader.hasNorms(field)) mainReader else personalReader).norms(field, bytes, offset)
  override def doCommit(commitUserData: JMap[String, String]) = throw new UnsupportedOperationException()
  override def document(doc: Int, fieldSelector: FieldSelector) = throw new UnsupportedOperationException()
  override def doDelete(doc: Int) = throw new UnsupportedOperationException()
  override def doSetNorm(doc: Int, field: String, value: Byte) = throw new UnsupportedOperationException()
  override def doUndeleteAll() = throw new UnsupportedOperationException()
  override def getFieldInfos() = throw new UnsupportedOperationException()
  override def getTermFreqVector(doc: Int, field: String) = throw new UnsupportedOperationException()
  override def getTermFreqVector(doc: Int, mapper: TermVectorMapper) = throw new UnsupportedOperationException()
  override def getTermFreqVector(doc: Int, field: String, mapper: TermVectorMapper) = throw new UnsupportedOperationException()
  override def getTermFreqVectors(doc: Int) = throw new UnsupportedOperationException()
  override def terms() = throw new UnsupportedOperationException()
  override def terms(term: Term) = throw new UnsupportedOperationException()
}

object PersonalizedIndexReader {
  def termDocs(personalizedIndexReader: PersonalizedIndexReader) = new PersonalizedTermDocs(personalizedIndexReader)
  def termPositions(personalizedIndexReader: PersonalizedIndexReader) = new PersonalizedTermPositions(personalizedIndexReader)

  class PersonalizedTermDocs(personalizedIndexReader: PersonalizedIndexReader) extends TermDocs {
    private[this] var inner: TermDocs = EmptyTermPositions.asInstanceOf[TermDocs]

    def close() { inner.close() }
    def doc() = inner.doc()
    def freq() = inner.freq()
    def next() = inner.next()
    def read(docs: Array[Int], freqs: Array[Int]): Int = inner.read(docs, freqs)
    def seek(term: Term) {
      inner = personalizedIndexReader.readerFor(term).termDocs()
      inner.seek(term)
    }
    def seek(termEnum: TermEnum) {
      seek(termEnum.term())
    }
    def skipTo(did: Int): Boolean = inner.skipTo(did)
  }

  class PersonalizedTermPositions(personalizedIndexReader: PersonalizedIndexReader) extends TermPositions {
    private[this] var inner: TermPositions = EmptyTermPositions

    def close() { inner.close() }
    def doc() = inner.doc()
    def freq() = inner.freq()
    def next() = inner.next()
    def read(docs: Array[Int], freqs: Array[Int]): Int = inner.read(docs, freqs)
    def seek(term: Term) {
      inner = personalizedIndexReader.readerFor(term).termPositions()
      inner.seek(term)
    }
    def seek(termEnum: TermEnum) {
      seek(termEnum.term())
    }
    def skipTo(did: Int): Boolean = inner.skipTo(did)
    def nextPosition() = inner.nextPosition()
    def getPayloadLength() = inner.getPayloadLength()
    def getPayload(data: Array[Byte], offset: Int) = inner.getPayload(data, offset)
    def isPayloadAvailable() = inner.isPayloadAvailable()
  }

  object EmptyTermPositions extends TermPositions {
    def close() { }
    def doc() = Int.MaxValue
    def freq() = 0
    def next() = false
    def read(docs: Array[Int], freqs: Array[Int]): Int = 0
    def seek(term: Term) {}
    def seek(termEnum: TermEnum) {}
    def skipTo(did: Int): Boolean = false
    def nextPosition() = 0
    def getPayloadLength() = 0
    def getPayload(data: Array[Byte], offset: Int) = data
    def isPayloadAvailable() = false

  }
}
