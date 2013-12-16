package com.keepit.search.query

import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.index.IndexReader
import org.apache.lucene.search.Query
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Weight
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.Explanation
import org.apache.lucene.util.Bits
import org.apache.lucene.search.Scorer
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import collection.JavaConversions._
import org.apache.lucene.index.Term
import scala.collection.mutable.ArrayBuffer
import org.apache.lucene.search.similarities.Similarity
import com.keepit.search.index.Searcher
import java.util.{Set => JSet}
import java.util.HashSet
import org.apache.lucene.search.ComplexExplanation


class BooleanQueryWithSemanticLoss(val disableCoord: Boolean = false) extends BooleanQuery(disableCoord) {

  override def rewrite(reader: IndexReader): Query = {
    if (clauses.size() == 1) { // optimize 1-clause queries
      val c = clauses.get(0)
      if (!c.isProhibited()) {
        var query = c.getQuery().rewrite(reader)
        if (getBoost() != 1.0f) {
          // if rewrite was no-op then clone before boost
          if (query eq c.getQuery()) query = query.clone().asInstanceOf[Query]
          query.setBoost(getBoost() * query.getBoost())
        }
        return query
      }
    }

    var returnQuery = this
    val rewrittenQuery = new BooleanQueryWithSemanticLoss(disableCoord) // recursively rewrite
    getClauses.foreach { c =>
      val query = c.getQuery.rewrite(reader)

      if (query eq c.getQuery) rewrittenQuery.add(c)
      else {
        rewrittenQuery.add(query, c.getOccur)
        returnQuery = rewrittenQuery
      }
    }
    returnQuery
  }

  override def clone(): BooleanQuery = {
    val clone = new BooleanQueryWithSemanticLoss(disableCoord)
    clauses.foreach{c => clone.add(c)}
    clone.setBoost(getBoost)
    clone.setMinimumNumberShouldMatch(getMinimumNumberShouldMatch)
    clone
  }

  override def createWeight(searcher: IndexSearcher): Weight = new BooleanWeight(searcher, disableCoord) {

    private[this] val (requiredWeights, optionalWeights, prohibitedWeights) = initWeights

    val optionalSemanticLoss = computeOptionalClausesSemanticLoss

    private def computeOptionalClausesSemanticLoss: Array[Float] = {

      val optionalTerms: Array[Set[Term]] = {
        optionalWeights.map { w =>
          var terms = new HashSet[Term]
          val q = w.getQuery()
          q.asInstanceOf[TextQuery].getSemanticVectorQuery.extractTerms(terms)
          terms.toSet
        }.toArray
      }
      val semanticLoss: Map[String, Float] = {
        val analyzer = new SemanticContextAnalyzer(searcher.asInstanceOf[Searcher], null, null)
        analyzer.semanticLoss(optionalTerms.flatten.toSet)
      }

      val optionalSemanticLoss: Array[Float] = {
        optionalWeights.map { w =>
          var terms = new HashSet[Term]
          w.getQuery().asInstanceOf[TextQuery].getSemanticVectorQuery.extractTerms(terms)
          terms.foldLeft(1f) { case (loss, term) => semanticLoss(term.text) * loss }
        }.toArray
      }
      optionalSemanticLoss
    }

    private def initWeights = {

      val requiredWeights = new ArrayBuffer[Weight]
      val prohibitedWeights = new ArrayBuffer[Weight]
      val optionalWeights = new ArrayBuffer[Weight]
      clauses.zip(weights).foreach {
        case (c, w) =>
          if (c.isProhibited()) {
            prohibitedWeights += w
          } else {
            if (c.isRequired()) {
              requiredWeights += w
            } else {
              optionalWeights += w
            }
          }
      }
      (requiredWeights, optionalWeights, prohibitedWeights)
    }

    private[this] val coordFactorForRequired = if (disableCoord) 1.0f else similarity.coord(requiredWeights.length, requiredWeights.length)
    private[this] val coordFactorForOptional = {
      val maxCoord = requiredWeights.length + optionalWeights.length
      (0 to optionalWeights.length).map { i => if (disableCoord) 1.0f else similarity.coord(i + requiredWeights.length, maxCoord) }.toArray
    }

    override def getValueForNormalization(): Float = 1f

    override def explain(context: AtomicReaderContext, doc: Int): Explanation = {
      val sumExpl = new ComplexExplanation()
      var coord = 0
      var sum = 0f
      var fail = false
      requiredWeights.foreach{ w =>
        val e = w.explain(context, doc)
        if (e.isMatch()){
          sumExpl.addDetail(e)
          coord += 1
          sum += e.getValue()
        } else {
          val r = new Explanation(0.0f, s"no match on required clause (${w.getQuery().toString()})")
            sumExpl.addDetail(r)
            fail = true
        }
      }

       prohibitedWeights.foreach{ w =>
          val e = w.explain(context, doc)
          if (e.isMatch()) {
            val r = new Explanation(0.0f, s"match on prohibited clause (${w.getQuery().toString()})")
            r.addDetail(e)
            sumExpl.addDetail(r)
            fail = true
          }
        }
        optionalWeights.foreach{ case w =>
          val e = w.explain(context, doc)
          if (e.isMatch()) {
            sumExpl.addDetail(e)
            coord += 1
            sum += e.getValue()
          }
        }

        if (fail) {
          sumExpl.setMatch(false)
          sumExpl.setValue(0.0f)
          sumExpl.setDescription("Failure to meet condition(s) of required/prohibited clause(s)")
          return sumExpl
        }

        if (disableCoord) {
          sumExpl
        }
        else {
          val coordFactor = similarity.coord(coord, maxCoord)
          val result = new ComplexExplanation(sumExpl.isMatch(), sum*coordFactor, "product of:")
          result.addDetail(sumExpl)
          result.addDetail(new Explanation(coordFactor, s"coord(${coord}/${maxCoord})"))
          result
        }

    }


    override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
      val required = new ArrayBuffer[Scorer]
      val prohibited = new ArrayBuffer[Scorer]
      val optional = new ArrayBuffer[Scorer]

      requiredWeights.foreach { w =>
        val subScorer = w.scorer(context, true, false, acceptDocs)
        // if a required clasuse does not have a scorer, no hit
        if (subScorer == null) return null
        required += subScorer
      }
      prohibitedWeights.foreach { w =>
        val subScorer = w.scorer(context, true, false, acceptDocs)
        if (subScorer != null) prohibited += subScorer
      }
      optionalWeights.foreach { w =>
        val subScorer = w.scorer(context, true, false, acceptDocs)
        if (subScorer != null) optional += subScorer
      }

      if (required.isEmpty && optional.isEmpty) {
        // no required and optional clauses.
        null
      } else {
        val optionalWithSemanticLoss = (optional zip optionalSemanticLoss) map { case (scorer, loss) => ScorerWithSemanicLossFactor(scorer, loss) }
        ScorerFactory(this, disableCoord, similarity, coordFactorForRequired, required.toArray, optionalWithSemanticLoss.toArray, prohibited.toArray)
      }
    }
  }
}

object ScorerFactory {
  def apply(weight: Weight, disableCoord: Boolean, similarity: Similarity, coordFactorForRequired: Float,
    required: Array[Scorer],
    optional: Array[ScorerWithSemanicLossFactor],
    prohibited: Array[Scorer]) = {
    def conjunction() = {
      new BooleanAndScorer(weight, coordFactorForRequired, required, value = 0) // value is not used
    }
    def disjunction() = {
      new BooleanOrScorerWithSemanticLoss(weight: Weight, optional)
    }
    def prohibit(source: Scorer) = {
      new BooleanNotScorer(weight, source, prohibited)
    }

    val mainScorer =
      if (required.length > 0 && optional.length > 0) {
        println("==========construct boolean scorer")
        new BooleanScorer(weight, conjunction(), disjunction(), 1f, 0, 0)
      } else if (required.length > 0) {
        println("==========construct conjunction scorer")
        conjunction()
      } else if (optional.length > 0) {
        println("==========construct disjunction scorer")
        disjunction()
      } else QueryUtil.emptyScorer(weight)

    if (prohibited.length > 0) prohibit(mainScorer) else mainScorer
  }
}

case class ScorerWithSemanicLossFactor(scorer: Scorer, semanticLossFactor: Float) {
  def nextDoc(): Int = scorer.nextDoc()
  def advance(target: Int): Int = scorer.advance(target)
  def docID(): Int = scorer.docID()
  def score(): Float = scorer.score()
  def getSemanticLossFactor: Float = semanticLossFactor
}

class BooleanOrScorerWithSemanticLoss(weight: Weight, subScorers: Array[ScorerWithSemanicLossFactor]) extends Scorer(weight) with BooleanOrScorer {

  private var doc = -1
  private var docScore = Float.NaN
  private var numMatches = 0

  println("Smeantic booleanOr: num of subscorers: " + subScorers.size)

  val totalLossFactor = subScorers.foldLeft(1.0) { case (loss, s) => loss * s.getSemanticLossFactor } // use log if we have underflow issue

  private[this] val pq = new PriorityQueue[ScorerWithSemanicLossFactor](subScorers.size) {
    override def lessThan(a: ScorerWithSemanicLossFactor, b: ScorerWithSemanicLossFactor) = a.docID < b.docID
  }

  initScorerDocQueue()

  private def initScorerDocQueue() = subScorers.foreach { scr => if (scr.nextDoc() < NO_MORE_DOCS) pq.insertWithOverflow(scr) }


  override def advance(target: Int): Int = {

    println(s"semantic boolenOr: advance to target ${target}")

    docScore = 0f
    numMatches = 0

    if (pq.size == 0) { doc = NO_MORE_DOCS; return NO_MORE_DOCS }

    while (true) {

      if (pq.top.advance(target) != NO_MORE_DOCS) {
        println("updating pq")
        pq.updateTop()
      } else {
        pq.pop()
        if (pq.size == 0) { doc = NO_MORE_DOCS; return doc }
      }

      println("pq top id: " + pq.top.docID())
      if (pq.top.docID >= target) {
        val scoreCandidate = pq.top.docID
        return goThrough(scoreCandidate)
      }
    }
    NO_MORE_DOCS
  }

  private def goThrough(scoreCandidate: Int): Int = {

    println("\n======\n semantic boolean or: scoring for doc " + scoreCandidate)
    doc = scoreCandidate
    var lossFactor = 1.0
    while (pq.size > 0 && scoreCandidate == pq.top.docID) {
      docScore += pq.top.score
      numMatches += 1
      lossFactor *= pq.top.getSemanticLossFactor
      if (pq.top.nextDoc() == NO_MORE_DOCS) pq.pop()
      pq.updateTop()
    }
    val semanticLoss = totalLossFactor / lossFactor

    docScore *= semanticLoss.toFloat
    println(s"semantic boolean scorer: setting docScore to ${docScore}")
    scoreCandidate
  }

  override def nextDoc(): Int = {
    docScore = 0f
    numMatches = 0
    if (pq.size == 0) { doc = NO_MORE_DOCS; return NO_MORE_DOCS }
    val scoreCandidate = pq.top.docID()
    val next = goThrough(scoreCandidate)
    next
  }

  override def docID(): Int = doc

  override def score(): Float = { docScore }

  override def freq(): Int = numMatches

}
