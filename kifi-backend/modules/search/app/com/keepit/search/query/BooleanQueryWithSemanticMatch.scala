package com.keepit.search.query

import java.util.HashSet
import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.JavaConversions.asScalaSet
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.search.similarities.Similarity
import org.apache.lucene.util.Bits
import org.apache.lucene.util.Bits.MatchNoBits
import org.apache.lucene.util.PriorityQueue
import com.keepit.search.Searcher
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.logging.Logging
import com.keepit.search.semantic.SemanticContextAnalyzer

class BooleanQueryWithSemanticMatch(val disableCoord: Boolean = false) extends BooleanQuery(disableCoord) with PercentMatchQuery with Logging {

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
    val rewrittenQuery = new BooleanQueryWithSemanticMatch(disableCoord) // recursively rewrite
    rewrittenQuery.setPercentMatch(percentMatch)
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
    val clone = new BooleanQueryWithSemanticMatch(disableCoord)
    clone.setPercentMatch(percentMatch)
    clauses.foreach { c => clone.add(c) }
    clone.setBoost(getBoost)
    clone.setMinimumNumberShouldMatch(getMinimumNumberShouldMatch)
    clone
  }

  override def createWeight(searcher: IndexSearcher): Weight = new BooleanWeight(searcher, disableCoord) {

    private[this] val (requiredWeights, optionalWeights, prohibitedWeights) = initWeights

    val optionalSemanticMatchFuture = Future { computeOptionalClausesSemanticMatch }
    def optionalSemanticMatch = {
      val t = System.currentTimeMillis
      val res = Await.result(optionalSemanticMatchFuture, 100 milli)
      log.info(s"waiting for semantic loss: ${System.currentTimeMillis() - t} milliseconds")
      res
    }
    // each optional clause is associated with a probability. The probability indicates how well the semantic of the boolean query is preserved
    // if ONLY THAT clause is NOT satisfied. e.g. if we have three clauses c1, c2, c3, and three factors p1, p2, p3.
    // If c1 and c3 are satisfied, the semantic matching will be p2 (because only c2 is missing). note p2 = p1*p2*p3 /(p1 * p3), this formula
    // is used in BooleanOrScorerWithSemanticMatch.

    private def computeOptionalClausesSemanticMatch: Array[Float] = {

      val optionalTerms: Array[Set[Term]] = {
        optionalWeights.map { w =>
          var terms = new HashSet[Term]
          val q = w.getQuery()
          q.asInstanceOf[TextQuery].getSemanticVectorQuery.extractTerms(terms)
          terms.toSet
        }.toArray
      }

      val semanticMatch: Map[String, Float] = {
        val analyzer = new SemanticContextAnalyzer(searcher.asInstanceOf[Searcher], null, null)
        analyzer.semanticLoss(optionalTerms.flatten.toSet)
      }

      log.info("semantic match: " + semanticMatch)

      val optionalSemanticMatch: Array[Float] = {
        optionalWeights.map { w =>
          var terms = new HashSet[Term]
          w.getQuery().asInstanceOf[TextQuery].getSemanticVectorQuery.extractTerms(terms)
          terms.foldLeft(1f) { case (m, term) => semanticMatch(term.text) * m }
        }.toArray
      }
      optionalSemanticMatch
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

    override def explain(context: AtomicReaderContext, doc: Int): Explanation = {
      val hotDocSet = hotDocs.map { f => f.getDocIdSet(context, context.reader.getLiveDocs).bits }.getOrElse(new MatchNoBits(context.reader.maxDoc))
      val sumExpl = new ComplexExplanation()
      var coord = 0
      var sum = 0f
      var fail = false
      requiredWeights.foreach { w =>
        val e = w.explain(context, doc)
        if (e.isMatch()) {
          sumExpl.addDetail(e)
          coord += 1
          sum += e.getValue()
        } else {
          val r = new Explanation(0.0f, s"no match on required clause (${w.getQuery().toString()})")
          sumExpl.addDetail(r)
          fail = true
        }
      }

      prohibitedWeights.foreach { w =>
        val e = w.explain(context, doc)
        if (e.isMatch()) {
          val r = new Explanation(0.0f, s"match on prohibited clause (${w.getQuery().toString()})")
          r.addDetail(e)
          sumExpl.addDetail(r)
          fail = true
        }
      }

      val totalMatchFactor = optionalSemanticMatch.foldLeft(1f)(_ * _)
      var matchedFactor = 1f
      var optionalScore = 0f

      val optionalExpl = new Explanation(0f, "semantic clause match, product of")
      val optionalSumExpl = new Explanation(0f, "optional clause match, sum of")
      val semanticMatchExpl = new Explanation(0f, "semanticMatch")

      sumExpl.addDetail(optionalExpl)
      optionalExpl.addDetail(optionalSumExpl)
      optionalExpl.addDetail(semanticMatchExpl)

      (optionalWeights zip optionalSemanticMatch).foreach {
        case (w, m) =>
          val e = w.explain(context, doc)
          if (e.isMatch()) {
            optionalSumExpl.addDetail(e)
            coord += 1
            matchedFactor *= m
            optionalScore += e.getValue()
          }
      }

      var semanticMatch = totalMatchFactor / matchedFactor
      sum += optionalScore * semanticMatch

      optionalSumExpl.setValue(optionalScore)
      semanticMatchExpl.setValue(semanticMatch)
      optionalExpl.setValue(optionalScore * semanticMatch)

      if (fail) {
        sumExpl.setMatch(false)
        sumExpl.setValue(0.0f)
        sumExpl.setDescription("Failure to meet condition(s) of required/prohibited clause(s)")
        return sumExpl
      } else if (!(semanticMatch > percentMatch / 100f || (hotDocSet.get(doc) && semanticMatch > percentMatchForHotDocs / 100f))) {
        sumExpl.setDescription(s"below percentMatch threshold. semantic match = ${semanticMatch}")
        sumExpl.setMatch(false)
        sumExpl.setValue(0.0f)
        return sumExpl
      }

      sumExpl.setMatch(true)
      sumExpl.setValue(sum)

      val hot = hotDocSet match {
        case h: HotDocSet =>
          h.explain(doc)
        case _ =>
          if (hotDocSet.get(doc)) new Explanation(1.0f, "hot") else new Explanation(0.0f, "")
      }

      sumExpl.setDescription(s"SematnicPercentMatch: ${hot.getDescription}, sum of:")

      if (disableCoord) {
        sumExpl
      } else {
        val coordFactor = similarity.coord(coord, maxCoord)
        val result = new ComplexExplanation(sumExpl.isMatch(), sum * coordFactor, "product of:")
        result.addDetail(sumExpl)
        result.addDetail(new Explanation(coordFactor, s"coord(${coord}/${maxCoord})"))
        result
      }

    }

    override def equals(obj: Any): Boolean = {
      obj match {
        case other: BooleanQueryWithSemanticMatch =>
          (percentMatch == other.getPercentMatch &&
            getBoost == other.getBoost &&
            clauses.equals(other.clauses) &&
            getMinimumNumberShouldMatch == other.getMinimumNumberShouldMatch &&
            disableCoord == other.disableCoord)
        case _ => false
      }
    }

    override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {

      val required = if (requiredWeights.isEmpty) Seq.empty[Scorer] else {
        val buf = new ArrayBuffer[Scorer]
        requiredWeights.foreach { w =>
          val subScorer = w.scorer(context, true, false, acceptDocs)
          // if a required clasuse does not have a scorer, no hit
          if (subScorer == null) return null
          buf += subScorer
        }
        buf
      }

      val prohibited = if (prohibitedWeights.isEmpty) Seq.empty[Scorer] else {
        val buf = new ArrayBuffer[Scorer]
        prohibitedWeights.foreach { w =>
          val subScorer = w.scorer(context, true, false, acceptDocs)
          if (subScorer != null) buf += subScorer
        }
        buf
      }

      val optional = if (optionalWeights.isEmpty) Seq.empty[ScorerWithSemanicMatchFactor] else {
        val buf = new ArrayBuffer[ScorerWithSemanicMatchFactor]
        (optionalWeights zip optionalSemanticMatch).foreach {
          case (w, semanticMatch) =>
            val subScorer = w.scorer(context, true, false, acceptDocs)
            if (subScorer != null) buf += ScorerWithSemanicMatchFactor(subScorer, semanticMatch)
        }
        buf
      }

      if (required.isEmpty && optional.isEmpty) {
        // no required and optional clauses.
        null
      } else {
        val hotDocSet = hotDocs.map { f => f.getDocIdSet(context, acceptDocs).bits() }.getOrElse(new MatchNoBits(context.reader.maxDoc))
        ScorerFactory(this, disableCoord, similarity, coordFactorForRequired, percentMatch / 100f, percentMatchForHotDocs / 100f, hotDocSet, required, optional, prohibited)
      }
    }
  }
}

object ScorerFactory {
  def apply(weight: Weight, disableCoord: Boolean, similarity: Similarity, coordFactorForRequired: Float, percentMatch: Float, percentMatchForHot: Float, hotDocSet: Bits,
    required: Seq[Scorer],
    optional: Seq[ScorerWithSemanicMatchFactor],
    prohibited: Seq[Scorer]) = {
    def conjunction() = {
      new BooleanAndScorer(weight, coordFactorForRequired, required.toArray, value = 0) // value is not used
    }
    def disjunction() = {
      new BooleanOrScorerWithSemanticMatch(weight: Weight, optional, percentMatch, percentMatchForHot, hotDocSet)
    }
    def prohibit(source: Scorer) = {
      new BooleanNotScorer(weight, source, prohibited.toArray)
    }

    val mainScorer =
      if (required.nonEmpty && optional.nonEmpty) {
        new BooleanScorer(weight, conjunction(), disjunction(), 1f)
      } else if (required.nonEmpty) {
        conjunction()
      } else if (optional.nonEmpty) {
        disjunction()
      } else QueryUtil.emptyScorer(weight)

    if (prohibited.nonEmpty) prohibit(mainScorer) else mainScorer
  }
}

case class ScorerWithSemanicMatchFactor(scorer: Scorer, semanticMatchFactor: Float) {
  def nextDoc(): Int = scorer.nextDoc()
  def advance(target: Int): Int = scorer.advance(target)
  def docID(): Int = scorer.docID()
  def score(): Float = scorer.score()
  def getSemanticMatchFactor: Float = semanticMatchFactor
  def cost(): Long = scorer.cost()
}

class BooleanOrScorerWithSemanticMatch(weight: Weight, subScorers: Seq[ScorerWithSemanicMatchFactor], percentMatchThreshold: Float, percentMatchThresholdForHot: Float, hotDocSet: Bits) extends Scorer(weight) with BooleanOrScorer with Logging {

  private var doc = -1
  private var docScore = Float.NaN
  private var numMatches = 0

  val totalMatchFactor = subScorers.foldLeft(1.0) { case (m, s) => m * s.getSemanticMatchFactor } // use log if we have underflow issue

  private[this] val pq = new PriorityQueue[ScorerWithSemanicMatchFactor](subScorers.size) {
    override def lessThan(a: ScorerWithSemanicMatchFactor, b: ScorerWithSemanicMatchFactor) = a.docID < b.docID
  }

  initScorerDocQueue()

  private def initScorerDocQueue() = subScorers.foreach { scr => if (scr.nextDoc() < NO_MORE_DOCS) pq.insertWithOverflow(scr) }

  override def advance(target: Int): Int = {

    while (true) {
      docScore = 0f
      numMatches = 0
      if (pq.size == 0) { doc = NO_MORE_DOCS; return doc }

      var findCandidate = false

      while (!findCandidate) {
        if (pq.top.advance(target) != NO_MORE_DOCS) {
          pq.updateTop()
        } else {
          pq.pop()
          if (pq.size == 0) { doc = NO_MORE_DOCS; return doc }
        }
        if (pq.top.docID >= target) {
          val scoreCandidate = pq.top.docID
          findCandidate = true
          val (docid, semanticMatch) = goThrough(scoreCandidate)
          if (semanticMatch > percentMatchThreshold || (semanticMatch > percentMatchThresholdForHot && hotDocSet.get(docid))) return docid
        }
      }

    }
    NO_MORE_DOCS
  }

  private def goThrough(scoreCandidate: Int): (Int, Float) = {

    doc = scoreCandidate
    var matchFactor = 1.0
    while (pq.size > 0 && scoreCandidate == pq.top.docID) {
      docScore += pq.top.score
      numMatches += 1
      matchFactor *= pq.top.getSemanticMatchFactor
      if (pq.top.nextDoc() == NO_MORE_DOCS) pq.pop()
      pq.updateTop()
    }

    val semanticMatch = totalMatchFactor / matchFactor

    docScore *= semanticMatch.toFloat
    (scoreCandidate, semanticMatch.toFloat)
  }

  override def nextDoc(): Int = {
    while (true) {
      docScore = 0f
      numMatches = 0
      if (pq.size == 0) { doc = NO_MORE_DOCS; return NO_MORE_DOCS }

      val scoreCandidate = pq.top.docID()
      val (docid, semanticMatch) = goThrough(scoreCandidate)
      if (semanticMatch > percentMatchThreshold || (semanticMatch > percentMatchThresholdForHot && hotDocSet.get(docid))) return docid
    }

    NO_MORE_DOCS
  }

  override def docID(): Int = doc

  override def score(): Float = { docScore }

  override def freq(): Int = numMatches

  override def cost(): Long = subScorers.map(_.cost()).sum
}
