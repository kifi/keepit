package com.keepit.search.query

import com.keepit.common.logging.Logging
import com.keepit.search.index.Searcher
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.TermPositions
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.Similarity
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.util.ToStringUtils
import java.util.{Set => JSet}


class ConditionalQuery(val source: Query, val condition: Query) extends Query2 with Coordinator {

  override def createWeight2(searcher: Searcher): Weight = new ConditionalWeight(this, searcher)

  override def rewrite(reader: IndexReader): Query = {
    val rewrittenSrc = source.rewrite(reader)
    val rewrittenCond = condition.rewrite(reader)

    if ((rewrittenSrc eq source) && (rewrittenCond eq condition)) this
    else new ConditionalQuery(source.rewrite(reader), condition.rewrite(reader))
  }

  override def extractTerms(out: JSet[Term]): Unit = {
    source.extractTerms(out)
    condition.extractTerms(out)
  }

  override def toString(s: String) = "Conditional(%s, %s)".format(source.toString(s), condition.toString(s))

  override def equals(obj: Any): Boolean = obj match {
    case query: ConditionalQuery => (source == query.source) && (condition == query.condition)
    case _ => false
  }

  override def hashCode(): Int = source.hashCode() + condition.hashCode()
}

class ConditionalWeight(query: ConditionalQuery, searcher: Searcher) extends Weight with Logging {

  val sourceWeight = query.source.createWeight(searcher)
  val conditionWeight = query.condition.createWeight(searcher)

  override def getQuery() = query
  override def getValue() = query.getBoost()
  override def scoresDocsOutOfOrder() = false

  override def sumOfSquaredWeights() = {
    val sum = sourceWeight.sumOfSquaredWeights
    val value = query.getBoost()
    (sum * value * value)
  }

  override def normalize(norm: Float) {
    sourceWeight.normalize(norm * getValue())
  }

  override def explain(reader: IndexReader, doc: Int) = {
    val sc = scorer(reader, true, false);
    val exists = (sc != null && sc.advance(doc) == doc);

    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription("conditional, product of:")
      val score = sc.score
      val boost = query.getBoost
      result.setValue(score * boost)
      result.setMatch(true)
      result.addDetail(new Explanation(score, "source score"))
      result.addDetail(new Explanation(boost, "boost"))
    } else {
      result.setDescription("condition, doesn't match id %d".format(doc))
      result.setValue(0)
      result.setMatch(false)
    }

    val eSrc = sourceWeight.explain(reader, doc)
    eSrc.isMatch() match {
      case false =>
        val r = new Explanation(0.0f, "no match in (" + sourceWeight.getQuery.toString() + ")")
        result.addDetail(r)
        result.setMatch(false)
        result.setValue(0.0f)
        result.setDescription("Failure to the match source query");
      case true =>
        result.addDetail(eSrc)
        val eCond = conditionWeight.explain(reader, doc)
        eCond.isMatch() match {
          case true =>
            result.addDetail(eCond)
          case false =>
            val r = new Explanation(0.0f, "no match in (" + conditionWeight.getQuery.toString() + ")")
            r.addDetail(eCond)
            result.addDetail(r)
        }
    }
    result
  }

  override def scorer(reader: IndexReader, scoreDocsInOrder: Boolean, topScorer: Boolean): Scorer = {
    val sourceScorer = sourceWeight.scorer(reader, true, false)
    if (sourceScorer == null) null
    else {
      // main scorer has to implement Coordinator trait
      val mainScorer = if (sourceScorer.isInstanceOf[Coordinator]) {
        sourceScorer.asInstanceOf[Scorer with Coordinator]
      } else {
        QueryUtil.toScorerWithCoordinator(sourceScorer)
      }

      if (mainScorer == null) null
      else {
        val conditionScorer = conditionWeight.scorer(reader, true, false)
        if (conditionScorer == null) null
        else {
          new ConditionalScorer(this, mainScorer, conditionScorer)
        }
      }
    }
  }
}

class ConditionalScorer(weight: ConditionalWeight, sourceScorer: Scorer with Coordinator, conditionScorer: Scorer) extends Scorer(weight) with Coordinator {
  private[this] var doc = -1

  override def docID(): Int = doc
  override def nextDoc(): Int = advance(0)
  override def advance(target: Int): Int = {
    if (doc < NO_MORE_DOCS) {
      doc = if (target <= doc) doc + 1 else target
      doc = sourceScorer.advance(doc)
      var cond = conditionScorer.advance(doc)
      while (doc != cond && doc < NO_MORE_DOCS) {
        if (doc < cond) doc = sourceScorer.advance(cond)
        else {
          cond = conditionScorer.advance(doc)
          if (cond == NO_MORE_DOCS) doc == NO_MORE_DOCS
        }
      }
    }
    doc
  }
  override def score() = sourceScorer.score()
  override def coord = sourceScorer.coord
}
