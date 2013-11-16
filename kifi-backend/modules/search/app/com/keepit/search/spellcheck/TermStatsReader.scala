package com.keepit.search.spellcheck

import org.apache.lucene.store.Directory
import org.apache.lucene.index.SlowCompositeReaderWrapper
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.util.BytesRef
import org.apache.lucene.search.DocIdSetIterator

case class SimpleTermStats(docFreq: Int, docIds: Set[Int])

trait TermStatsReader {
  def numDocs(): Int
  def getSimpleTermStats(term: String): SimpleTermStats
}

class TermStatsReaderImpl(indexDir: Directory, field: String) extends TermStatsReader {

  lazy val reader = new SlowCompositeReaderWrapper(DirectoryReader.open(indexDir))
  lazy val termsEnum = {
    val fields = reader.fields()
    val terms = fields.terms(field)
    terms.iterator(null)
  }

  override def numDocs = reader.numDocs()

  override def getSimpleTermStats(term: String): SimpleTermStats  = {
    val found = termsEnum.seekExact(new BytesRef(term), true)
    var ret = Set.empty[Int]
    if (!found) return SimpleTermStats(0, ret)

    val freq = termsEnum.docFreq()
    val docs = termsEnum.docs(null, null)
    var docid = docs.nextDoc()
    while (docid != DocIdSetIterator.NO_MORE_DOCS){
      ret += docid
      docid = docs.nextDoc()
    }
    SimpleTermStats(freq, ret)
  }
}
