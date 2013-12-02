package com.keepit.search.spellcheck

import org.apache.lucene.store.Directory
import org.apache.lucene.index.SlowCompositeReaderWrapper
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.util.{BytesRef, Bits, DocIdBitSet}
import org.apache.lucene.search.DocIdSetIterator
import java.util.BitSet
import scala.math.log

case class SimpleTermStats(docFreq: Int, docIds: Set[Int], idf: Float)

trait TermStatsReader {
  def numDocs(): Int
  def getSimpleTermStats(term: String): SimpleTermStats
  def getDocsAndPositions(term: String, liveDocs: Bits = null): Map[Int, Array[Int]]
}

object TermStatsReader {
  def genBits(docIds: Set[Int]): Bits = {
    val s = new BitSet()
    docIds.foreach{s.set(_)}
    new DocIdBitSet(s)
  }
}

class TermStatsReaderImpl(indexDir: Directory, field: String) extends TermStatsReader {

  private def log2(x: Double) = log(x)/log(2)

  lazy val reader = new SlowCompositeReaderWrapper(DirectoryReader.open(indexDir))
  lazy val termsEnum = {
    val fields = reader.fields()
    val terms = fields.terms(field)
    terms.iterator(null)
  }

  private def idf(termFreq: Int): Float = 1f + log2(numDocs.toFloat/(1f + termFreq)).toFloat

  override def numDocs = reader.numDocs()

  override def getSimpleTermStats(term: String): SimpleTermStats  = {
    val found = termsEnum.seekExact(new BytesRef(term), true)
    var ret = Set.empty[Int]
    if (!found) return SimpleTermStats(0, ret, 0f)

    val freq = termsEnum.docFreq()
    val docs = termsEnum.docs(null, null)
    var docid = docs.nextDoc()
    while (docid != DocIdSetIterator.NO_MORE_DOCS){
      ret += docid
      docid = docs.nextDoc()
    }
    SimpleTermStats(freq, ret, idf(freq))
  }

  override def getDocsAndPositions(term: String, liveDocs: Bits = null): Map[Int, Array[Int]] = {
    val found = termsEnum.seekExact(new BytesRef(term), true)
    if (!found) return Map()
    val docsAndPos = termsEnum.docsAndPositions(liveDocs, null)
    var ret = Map.empty[Int, Array[Int]]
    var docid = docsAndPos.nextDoc
    while (docid != DocIdSetIterator.NO_MORE_DOCS){
      val freq = docsAndPos.freq
      val pos = (0 until freq).map{ i => docsAndPos.nextPosition }.toArray
      ret += docid -> pos
      docid = docsAndPos.nextDoc
    }
    ret
  }
}
