package com.keepit.search

import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.util.BytesRef
import com.keepit.search.index._
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._
import org.apache.lucene.search.Weight

object Searcher {
  def apply(indexReader: DirectoryReader) = new Searcher(WrappedIndexReader(indexReader))
  def reopen(oldSearcher: Searcher) = {
    new Searcher(WrappedIndexReader.reopen(oldSearcher.indexReader))
  }
}

class Searcher(val indexReader: WrappedIndexReader) extends IndexSearcher(indexReader) {

  // search: hits are ordered by score
  def searchAll(query: Query): Seq[SearcherHit] = {
    val hitBuf = new ArrayBuffer[SearcherHit]()
    search(query) { (scorer, reader) =>
      val idMapper = reader.getIdMapper
      var doc = scorer.nextDoc()
      while (doc != NO_MORE_DOCS) {
        var score = scorer.score()
        hitBuf += SearcherHit(idMapper.getId(doc), score)
        doc = scorer.nextDoc()
      }
    }
    hitBuf.sortWith((a, b) => a.score >= b.score).toSeq
  }

  def findAllIds(term: Term, buf: ArrayBuffer[Long] = new ArrayBuffer[Long]): Seq[Long] = {
    foreachReader { reader =>
      val idMapper = reader.getIdMapper
      val td = reader.termDocsEnum(term)
      var doc = td.nextDoc()
      while (doc != NO_MORE_DOCS) {
        buf += idMapper.getId(doc)
        doc = td.nextDoc()
      }
    }
    buf
  }

  def freq(term: Term): Int = {
    var freq = 0
    foreachReader { reader => freq += reader.docFreq(term) }
    freq
  }

  def has(term: Term): Boolean = {
    foreachReader { reader =>
      val td = reader.termDocsEnum(term)
      var doc = td.nextDoc()
      if (doc != NO_MORE_DOCS) return true
    }
    false
  }

  def createWeight(query: Query): Weight = {
    val rewrittenQuery = rewrite(query)
    if (rewrittenQuery != null) createNormalizedWeight(rewrittenQuery) else null
  }

  def search(query: Query)(f: (Scorer, WrappedSubReader) => Unit) {
    search(createWeight(query: Query))(f)
  }

  def search(weight: Weight)(f: (Scorer, WrappedSubReader) => Unit) {
    if (weight != null) {
      indexReader.getContext.leaves.foreach { subReaderContext =>
        val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
        if (!subReader.skip) {
          val scorer = weight.scorer(subReaderContext, true, false, subReader.getLiveDocs)
          if (scorer != null) {
            f(scorer, subReader)
          }
        }
      }
    }
  }

  def foreachReader(f: WrappedSubReader => Unit) {
    indexReader.getContext.leaves.foreach { subReaderContext =>
      val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
      f(subReader)
    }
  }

  def findDocIdAndAtomicReaderContext(id: Long): Option[(Int, AtomicReaderContext)] = {
    indexReader.getContext.leaves.foreach { subReaderContext =>
      val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
      val liveDocs = subReader.getLiveDocs
      val docid = subReader.getIdMapper.getDocId(id)
      if (docid >= 0 && (liveDocs == null || liveDocs.get(docid))) return Some((docid, subReaderContext))
    }
    None
  }

  def getDecodedDocValue[T](field: String, id: Long)(implicit decode: (Array[Byte], Int, Int) => T): Option[T] = {
    findDocIdAndAtomicReaderContext(id).flatMap {
      case (docid, context) =>
        val reader = context.reader
        var docValues = reader.getBinaryDocValues(field)
        if (docValues != null) {
          var ref = new BytesRef()
          docValues.get(docid, ref)
          Some(decode(ref.bytes, ref.offset, ref.length))
        } else {
          None
        }
    }
  }

  def getLongDocValue(field: String, id: Long): Option[Long] = {
    findDocIdAndAtomicReaderContext(id).flatMap {
      case (docid, context) =>
        val reader = context.reader
        var docValues = reader.getNumericDocValues(field)
        if (docValues != null) {
          Some(docValues.get(docid))
        } else {
          None
        }
    }
  }

  def explain(query: Query, id: Long): Explanation = {
    findDocIdAndAtomicReaderContext(id) match {
      case Some((docid, context)) =>
        val rewrittenQuery = rewrite(query)
        if (rewrittenQuery != null) {
          val weight = createNormalizedWeight(rewrittenQuery)
          weight.explain(context, docid)
        } else {
          new Explanation(0.0f, "rewrittten query is null")
        }
      case None =>
        new Explanation(0.0f, "failed to find docid")
    }
  }
}

case class SearcherHit(id: Long, score: Float)

