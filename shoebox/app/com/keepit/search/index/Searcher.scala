package com.keepit.search.index

import com.keepit.search.SemanticVector
import com.keepit.search.SemanticVectorComposer
import org.apache.lucene.index.AtomicReader
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.SegmentReader
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.similarities.TFIDFSimilarity
import org.apache.lucene.util.PriorityQueue
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._


object Searcher {
  def apply(indexReader: DirectoryReader) = new Searcher(WrappedIndexReader(indexReader))
  def reopen(oldSearcher: Searcher) = new Searcher(WrappedIndexReader.reopen(oldSearcher.indexReader))
}

class Searcher(val indexReader: WrappedIndexReader) extends IndexSearcher(indexReader) {

  def idf(term: Term) = getSimilarity.asInstanceOf[TFIDFSimilarity]idf(indexReader.docFreq(term), indexReader.maxDoc)

  // search: hits are ordered by score
  def search(query: Query): Seq[Hit] = {
    val hitBuf = new ArrayBuffer[Hit]()
    doSearch(query){ (scorer, idMapper) =>
      var doc = scorer.nextDoc()
      while (doc != NO_MORE_DOCS) {
        var score = scorer.score()
        hitBuf += Hit(idMapper.getId(doc), score)
        doc = scorer.nextDoc()
      }
    }
    hitBuf.sortWith((a, b) => a.score >= b.score).toSeq
  }

  def doSearch(query: Query)(f: (Scorer, IdMapper) => Unit) {
    val rewrittenQuery = rewrite(query)
    if (rewrittenQuery != null) {
      val weight = createNormalizedWeight(rewrittenQuery)
      if(weight != null) {
        indexReader.getContext.leaves.foreach{ subReaderContext =>
          val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
          val scorer = weight.scorer(subReaderContext, true, false, subReader.getLiveDocs)
          if (scorer != null) {
            f(scorer, subReader.getIdMapper)
          }
        }
      }
    }
  }

  def findDocIdAndAtomicReaderContext(id: Long): Option[(Int, AtomicReaderContext)] = {
    indexReader.getContext.leaves.foreach{ subReaderContext =>
      val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
      val liveDocs = subReader.getLiveDocs
      val docid = subReader.getIdMapper.getDocId(id)
      if (docid >= 0 && (liveDocs == null || liveDocs.get(docid))) return Some((docid, subReaderContext))
    }
    None
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

  protected def getSemanticVectorComposer(term: Term) = {
    val composer = new SemanticVectorComposer
    indexReader.getContext.leaves.foreach{ subReaderContext =>
      val subReader = subReaderContext.reader.asInstanceOf[WrappedSubReader]
      val tp = subReader.termPositionsEnum(term)
      var vector = new SemanticVector(new Array[Byte](SemanticVector.arraySize))
      while (tp.nextDoc < NO_MORE_DOCS) {
        var freq = tp.freq()
        while (freq > 0) {
          freq -= 1
          tp.nextPosition()
          val payload = tp.getPayload()
          vector.set(payload.bytes, payload.offset, payload.length)
          composer.add(vector, 1)
        }
      }
    }
    composer
  }

  def getSemanticVector(term: Term) = {
    val composer = getSemanticVectorComposer(term)

    if (composer.numInputs > 0) composer.getSemanticVector
    else SemanticVector.vectorize(SemanticVector.getSeed(term.text))
  }

  def getSemanticVector(terms: Set[Term]) = {
    val sketch = terms.foldLeft(SemanticVector.emptySketch){ (sketch, term) =>
      val composer = getSemanticVectorComposer(term)
      val termSketch = if (composer.numInputs > 0) composer.getQuasiSketch else SemanticVector.getSeed(term.text)
      SemanticVector.updateSketch(sketch, termSketch)
      sketch
    }
    SemanticVector.vectorize(sketch)
  }

  def getHitQueue(size: Int) = new HitQueue(size)
}

class MutableHit(var id: Long, var score: Float) {
  def apply(newId: Long, newScore: Float) = {
    id = newId
    score = newScore
  }
}

class HitQueue(sz: Int) extends PriorityQueue[MutableHit](sz) {
  override def lessThan(a: MutableHit, b: MutableHit) = (a.score < b.score || (a.score == b.score && a.id < b.id))

  var overflow: MutableHit = null // sorry about the null, but this is necessary to work with lucene's priority queue efficiently

  def insert(id: Long, score: Float) {
    if (overflow == null) overflow = new MutableHit(id, score)
    else overflow(id, score)

    overflow = insertWithOverflow(overflow)
  }

  // the following method is destructive. after the call HitQueue is unusable
  def toList: List[MutableHit] = {
    var res: List[MutableHit] = Nil
    var i = size()
    while (i > 0) {
      i -= 1
      res = pop() :: res
    }
    res
  }
}

case class Hit(id: Long, score: Float)

