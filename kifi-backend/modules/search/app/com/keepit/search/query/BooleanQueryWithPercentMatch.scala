package com.keepit.search.query

import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.IndexReader
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.Filter
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.search.similarities.Similarity
import org.apache.lucene.util.Bits
import org.apache.lucene.util.Bits.MatchNoBits
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._
import scala.math._
import java.util.{ List => JList }
import com.keepit.common.logging.Logging

trait PercentMatchQuery extends Query {
  protected var percentMatch: Float = 0f
  protected var percentMatchForHotDocs: Float = 1f
  protected var hotDocs: Option[Filter] = None
  def getPercentMatch(): Float = percentMatch
  def getPercentMatchForHotDocs(): Float = percentMatchForHotDocs
  def setPercentMatch(pctMatch: Float) = { percentMatch = pctMatch }
  def setPercentMatchForHotDocs(pctMatch: Float, hotDocFilter: Filter) = {
    percentMatchForHotDocs = pctMatch
    hotDocs = Some(hotDocFilter)
  }
}

object BooleanQueryWithPercentMatch {
  def apply(clauses: JList[BooleanClause], percentMatch: Float, disableCoord: Boolean) = {
    val query = new BooleanQueryWithPercentMatch(disableCoord)
    query.setPercentMatch(percentMatch)
    clauses.foreach { clause => query.add(clause) }
    query
  }
}

class BooleanQueryWithPercentMatch(val disableCoord: Boolean = false) extends BooleanQuery(disableCoord) with PercentMatchQuery {

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
    val rewrittenQuery = new BooleanQueryWithPercentMatch(disableCoord) // recursively rewrite
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
    val clone = new BooleanQueryWithPercentMatch(disableCoord)
    clone.setPercentMatch(percentMatch)
    clauses.foreach { c => clone.add(c) }
    clone.setBoost(getBoost)
    clone.setMinimumNumberShouldMatch(getMinimumNumberShouldMatch)
    clone
  }

  override def createWeight(searcher: IndexSearcher) = {

    new BooleanWeight(searcher, disableCoord) {
      private[this] val requiredWeights = new ArrayBuffer[Weight]
      private[this] val prohibitedWeights = new ArrayBuffer[Weight]
      private[this] val optionalWeights = new ArrayBuffer[(Weight, Float)]
      private[this] val (normalizationValue: Float, totalValueOnRequired: Float, totalValueOnOptional: Float) = {
        var sum = 0.0d
        var sumOnReq = 0.0f
        var sumOnOpt = 0.0f
        clauses.zip(weights).foreach {
          case (c, w) =>
            if (c.isProhibited()) {
              prohibitedWeights += w
            } else {
              val value = w.getValueForNormalization().toDouble
              val sqrtValue = sqrt(value).toFloat

              if (c.isRequired()) {
                sumOnReq += sqrtValue
                requiredWeights += w
              } else {
                sumOnOpt += sqrtValue
                optionalWeights += ((w, sqrtValue))
              }
              sum += value
            }
        }
        (sum.toFloat * getBoost() * getBoost(), sumOnReq, sumOnOpt)
      }
      private[this] val totalValue = totalValueOnRequired + totalValueOnOptional
      private[this] val threshold = totalValue * percentMatch / 100.0f - totalValueOnRequired
      private[this] val thresholdForHotDocs = totalValue * percentMatchForHotDocs / 100.0f - totalValueOnRequired

      private[this] val coordFactorForRequired = if (disableCoord) 1.0f else similarity.coord(requiredWeights.length, requiredWeights.length + optionalWeights.length)
      private[this] val coordFactorForOptional = {
        val maxCoord = requiredWeights.length + optionalWeights.length
        (0 to optionalWeights.length).map { i => if (disableCoord) 1.0f else similarity.coord(i + requiredWeights.length, maxCoord) }.toArray
      }

      override def getValueForNormalization(): Float = normalizationValue

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

        val optional = if (optionalWeights.isEmpty) Seq.empty[BooleanScoreDoc] else {
          val buf = new ArrayBuffer[BooleanScoreDoc]
          optionalWeights.foreach {
            case (w, value) =>
              val subScorer = w.scorer(context, true, false, acceptDocs)
              if (subScorer != null) buf += new BooleanScoreDoc(subScorer, value)
          }
          buf
        }

        if (required.isEmpty && optional.isEmpty) {
          // no required and optional clauses.
          null
        } else {
          val hotDocSet = hotDocs.map { f => f.getDocIdSet(context, acceptDocs).bits() }.getOrElse(new MatchNoBits(context.reader.maxDoc))

          BooleanScorer(this, disableCoord, similarity, threshold, thresholdForHotDocs, coordFactorForRequired, coordFactorForOptional,
            required, totalValueOnRequired, optional, totalValueOnOptional, prohibited, hotDocSet)
        }
      }

      override def explain(context: AtomicReaderContext, doc: Int): Explanation = {
        val hotDocSet = hotDocs.map { f => f.getDocIdSet(context, context.reader.getLiveDocs).bits }.getOrElse(new MatchNoBits(context.reader.maxDoc))
        val maxCoord = clauses.filterNot { _.isProhibited }.size

        val sumExpl = new ComplexExplanation()

        var coord = 0
        var sum = 0.0f
        var fail = false
        var overlapValue = totalValueOnRequired
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
        optionalWeights.foreach {
          case (w, v) =>
            val e = w.explain(context, doc)
            if (e.isMatch()) {
              sumExpl.addDetail(e)
              coord += 1
              sum += e.getValue()
              overlapValue += v
            }
        }

        if (fail) {
          sumExpl.setMatch(false)
          sumExpl.setValue(0.0f)
          sumExpl.setDescription("Failure to meet condition(s) of required/prohibited clause(s)")
          return sumExpl
        } else if (overlapValue < threshold && overlapValue < thresholdForHotDocs) {
          sumExpl.setDescription(s"below percentMatch threshold (${overlapValue}/${totalValue})")
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

        sumExpl.setDescription(s"percentMatch(${overlapValue / totalValue * 100}% = ${overlapValue}/${totalValue}, ${hot.getDescription}), sum of:")

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
    }
  }

  override def equals(obj: Any): Boolean = {
    obj match {
      case other: BooleanQueryWithPercentMatch =>
        (percentMatch == other.getPercentMatch &&
          getBoost == other.getBoost &&
          clauses.equals(other.clauses) &&
          getMinimumNumberShouldMatch == other.getMinimumNumberShouldMatch &&
          disableCoord == other.disableCoord)
      case _ => false
    }
  }

  override def toString(s: String) = "%s>%f".format(super.toString(s), percentMatch)
}

object BooleanScorer {
  def apply(weight: Weight, disableCoord: Boolean, similarity: Similarity, threshold: Float, thresholdForHotDocs: Float,
    coordFactorForRequired: Float,
    coordFactorForOptional: Array[Float],
    required: Seq[Scorer], requiredValue: Float,
    optional: Seq[BooleanScoreDoc], optionalValue: Float,
    prohibited: Seq[Scorer],
    hotDocSet: Bits) = {
    def conjunction() = {
      val sorted = required.sortBy(_.cost())
      new BooleanAndScorer(weight, coordFactorForRequired, sorted.toArray, requiredValue)
    }
    def disjunction() = {
      new BooleanOrScorerImpl(weight, optional, coordFactorForOptional, threshold, thresholdForHotDocs, optionalValue, hotDocSet)
    }
    def prohibit(source: Scorer) = {
      new BooleanNotScorer(weight, source, prohibited.toArray)
    }

    val mainScorer =
      if (required.nonEmpty && optional.nonEmpty) {
        new BooleanScorer(weight, conjunction(), disjunction(), threshold)
      } else if (required.nonEmpty) {
        conjunction()
      } else if (optional.nonEmpty) {
        disjunction()
      } else QueryUtil.emptyScorer(weight)

    if (prohibited.nonEmpty) prohibit(mainScorer) else mainScorer
  }
}

class BooleanScorer(weight: Weight, required: BooleanAndScorer, optional: BooleanOrScorer, threshold: Float) extends Scorer(weight) {

  private[this] var doc = -1
  private[this] var scoredDoc = -1
  private[this] var scoreValue = 0.0f

  override def docID() = doc

  override def score(): Float = {
    if (scoredDoc != doc) {
      scoreValue = required.score()
      if (optional.docID == doc) scoreValue += optional.score()
      scoredDoc = doc
    }
    scoreValue
  }

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    doc = required.advance(target)
    if (threshold > 0.0f) { // some of the optional clauses must match to reach the threshold.
      while (doc < NO_MORE_DOCS) {
        if (doc == optional.docID) return doc
        if (doc == optional.advance(doc)) return doc
        doc = required.advance(optional.docID)
      }
    } else { // the required clauses have enough weights. the optional clause is truly optional.
      if (doc < NO_MORE_DOCS && optional.docID < doc) {
        optional.advance(doc)
      }
    }
    doc
  }

  override def freq(): Int = 1

  override def cost(): Long = required.cost()
}

class BooleanAndScorer(weight: Weight, val coordFactor: Float, scorers: Array[Scorer], val value: Float) extends Scorer(weight) {

  private[this] var doc = -1
  private[this] var scoredDoc = -1
  private[this] var scoreValue = 0.0f

  override def docID() = doc

  override def score(): Float = {
    if (doc != scoredDoc) {
      scoredDoc = doc
      var sum = 0.0f
      var i = 0
      while (i < scorers.length) {
        sum += scorers(i).score()
        i += 1
      }
      scoreValue = sum * coordFactor
    }
    scoreValue
  }

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    if (doc < NO_MORE_DOCS) {
      doc = if (target <= doc) doc + 1 else target
    }

    var i = 0
    while (doc < NO_MORE_DOCS && i < scorers.length) {
      val sc = scorers(i)
      var scdoc = sc.docID()
      if (scdoc < doc) scdoc = sc.advance(doc)
      if (scdoc == doc) {
        i += 1
      } else {
        doc = scdoc
        i = 0
      }
    }
    doc
  }

  override def freq(): Int = 1

  override def cost(): Long = scorers(0).cost()
}

trait BooleanOrScorer extends Scorer

class BooleanOrScorerImpl(weight: Weight, scorers: Seq[BooleanScoreDoc], coordFactors: Array[Float],
  threshold: Float, thresholdForHotDocs: Float, maxOverlapValue: Float, hotDocSet: Bits)
    extends Scorer(weight) with BooleanOrScorer with Logging {

  private[this] var doc = -1
  private[this] var matchValue = 0.0f
  private[this] var scoredDoc = -1
  private[this] var scoreValue = 0.0f
  private[this] val pq = BooleanSubScorerQueue(scorers)

  override def docID() = doc

  override def score(): Float = {
    if (scoredDoc < doc) {
      scoredDoc = doc
      scoreValue = doScore()
    }
    scoreValue
  }

  @inline private[this] def doScore(): Float = {
    var sum = 0.0f
    var cnt = 0
    var top = pq.top
    while (top.doc == doc) {
      sum += top.scoreAndNext()
      cnt += 1
      top = pq.updateTop()
    }
    sum * coordFactors(cnt)
  }

  @inline private[this] def qualified(): Boolean = {
    matchValue = 0.0f

    var top = pq.top
    while (top.doc == doc && !(top.prepared)) {
      matchValue += top.prepare()
      top = pq.updateTop()
    }

    (matchValue >= threshold || (matchValue >= thresholdForHotDocs && hotDocSet.get(doc)))
  }

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    if (doc < NO_MORE_DOCS) {
      doc = if (target <= doc) doc + 1 else target
      var top = pq.top
      while (top.doc < doc) {
        top.advance(doc)
        top = pq.updateTop()
      }
      doc = top.doc

      while (doc < NO_MORE_DOCS) {
        if (qualified()) return doc

        while (top.doc == doc) {
          top.next()
          top = pq.updateTop()
        }
        doc = top.doc
      }
    }
    scoreValue = 0.0f
    NO_MORE_DOCS
  }

  override def freq(): Int = 1

  override def cost(): Long = scorers.map(_.cost()).sum
}

class BooleanNotScorer(weight: Weight, scorer: Scorer, prohibited: Array[Scorer]) extends Scorer(weight) {

  private[this] var doc = -1
  private[this] var scoredDoc = -1
  private[this] var scoreValue = 0.0f

  override def docID() = doc

  override def score(): Float = {
    if (doc != scoredDoc) {
      scoredDoc = doc
      scoreValue = scorer.score()
    }
    scoreValue
  }

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    doc = scorer.advance(target)
    while (doc < NO_MORE_DOCS && isProhibited) {
      doc = scorer.advance(0)
    }
    doc
  }

  override def freq(): Int = 1

  override def cost(): Long = scorer.cost()

  private def isProhibited = {
    prohibited.exists { n =>
      if (n.docID < doc) n.advance(doc)
      (n.docID == doc)
    }
  }
}
