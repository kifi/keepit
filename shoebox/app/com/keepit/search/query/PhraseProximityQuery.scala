package com.keepit.search.query

import org.apache.lucene.index.Term
import scala.collection.mutable.ListBuffer
import scala.collection.JavaConversions._
import org.apache.lucene.search.Weight
import java.util.{ Set => JSet }
import java.lang.{ Float => JFloat }
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.index.IndexReader
import org.apache.lucene.util.ToStringUtils
import org.apache.lucene.search.Explanation
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.util.Bits
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import com.keepit.search.query.QueryUtil._
import org.apache.lucene.util.PriorityQueue
import scala.math._
import java.util.Arrays
import java.lang.{Float => JFloat}
import java.util.{Set => JSet}
import scala.collection.mutable.ArrayBuffer

case class TrieNode(nodeText: String, var children: Map[String, TrieNode], var endOfPhrase: Boolean) {
  def hasChild(text: String) = children.keySet.contains(text)
  def addChild(text: String) = {
    if (!hasChild(text)) children += (text -> TrieNode(text, Map.empty[String, TrieNode], false))
  }
}

class PhraseTrie(val root: TrieNode = TrieNode("", Map.empty[String, TrieNode], false)) {
  def addPhrase(subroot: TrieNode = root, phrase: List[String]): Unit = {
    if (phrase.size > 0) {
      val text = phrase.head
      if (!subroot.hasChild(text)) subroot.addChild(text)
      if (phrase.size == 1) subroot.children(text).endOfPhrase = true
      else addPhrase(subroot.children(text), phrase.tail)
    }
  }
}

// phrases: (pos, len) pairs. We assume this comes from PhraseDetector and assume
// these pairs are compatible with term array. (i.e., no out of index problem)
class PhraseHelper(terms: Seq[String], phrases: Set[(Int, Int)]) {
  val inPhrase = {
    val inPhrase = new Array[Int](terms.size)
    phrases.foreach { case (pos, len) => (pos until pos + len).foreach(i => inPhrase(i) = 1) }
    inPhrase
  }

  val phraseMap = {
    var phraseId = Map.empty[String, (Int, Int)]
    var cnt = 0
    val nonPhraseIdx = for( i <- 0 until inPhrase.size ; if ( inPhrase(i) == 0 )) yield i
    nonPhraseIdx.foreach{ i =>
      if (!phraseId.contains(terms(i))) {
        phraseId += terms(i) -> (cnt, 1)
        cnt += 1
      }
    }
    phrases.foreach{ case (pos, len) => {
       val phrase = terms.slice(pos, pos + len).mkString(" ")
       if (!phraseId.contains(phrase)) { phraseId += phrase -> (cnt, len); cnt += 1; }
     }
    }
    phraseId
  }

  val phraseLenMap = phraseMap.foldLeft(Map.empty[Int, Int]) { case (m, (phrase, (id, len))) => m + (id -> len) }

  val numPhrases = phraseMap.size

  val phraseTrie = phraseMap.keys.foldLeft(new PhraseTrie) { (trie, phrase) => { trie.addPhrase(trie.root, phrase.split(" ").toList); trie } }

  // see PhraseProximityTest for use case
  def getMatchedPhrases(termPos: Seq[Int], terms: Seq[String]) = {
    val N = termPos.size min terms.size
    var i = 0
    var matches = ListBuffer.empty[(Int, Int, Int)]                 // phraseId, phraseLen, lastPosition
    while (i < N) {
      var j = i
      var lastPhrasePos = -1
      var node = phraseTrie.root
      if (node.hasChild(terms(j))) {
        node = node.children(terms(j))
        if (node.endOfPhrase) lastPhrasePos = j
        while (j + 1 < N && termPos(j+1) == termPos(j) + 1 && node.hasChild(terms(j+1))) {              // terms must occur consecutively and form a valid phrase
          node = node.children(terms(j+1))
          j += 1
          if (node.endOfPhrase) lastPhrasePos = j                   // greedy match
        }
      }
      if (lastPhrasePos != -1) {
        val phrase = terms.slice(i, lastPhrasePos + 1).mkString(" ")
        matches.append((phraseMap(phrase)._1, lastPhrasePos + 1 - i, termPos(lastPhrasePos)))
        i = lastPhrasePos + 1
      } else {
        i += 1
      }

    }
    matches.toArray
  }

  private[this] val ls = new Array[Float](numPhrases)                       // local score for a phrase
  private[this] val lp = new Array[Int](numPhrases)                         // last occurrence of a phrase.

  private def localPhraseScore(n: Int) = n * (n + 1) / 2.0f - n * (n - 1) * PhraseProximityQuery.gapPenalty / 2.0f // n = phrase len, n >= 0

  // similar to the scoring function in ProximityQuery. Local scores are computed at
  // phrase level. Diffusion of a phrase is sum of the diffusion of each term
  def getMatchedScore(matches: Seq[(Int, Int, Int)]) = {
    var maxScore = 0.0f
    Arrays.fill(ls, 0.0f)
    Arrays.fill(lp, -1)
    var prevPos = -1
    var runLen = 0
    var localSum = 0.0f
    var lastId = -1
    matches.foreach {
      case (id, phraseLen, lastPos) =>
        if (lastPos == prevPos + phraseLen && id != lastId) runLen += phraseLen else runLen = phraseLen
        lastId = id
        lp(id) = lastPos
        prevPos = lastPos
        localSum = 0.0f
        for (i <- 0 until numPhrases) {
          if (i == id) {
            ls(i) = (runLen - phraseLen) * phraseLen + localPhraseScore(phraseLen)      // first summand comes from 'run length growth bonus' (if current phrase is consecutive to the previous phrase)
          } else {
            ls(i) = max(ls(i) - (lastPos - lp(i)) * PhraseProximityQuery.gapPenalty * phraseLenMap(i), 0.0f)    // each term in the phrase decays. so multiply by phraseLenMap(i)
            lp(i) = lastPos
          }
          localSum += ls(i)
        }
        maxScore = max(maxScore, localSum)
    }
    maxScore
  }
}

object PhraseProximityQuery {
  def apply(terms: Seq[Term], phrases: Set[(Int, Int)]) = new PhraseProximityQuery(terms, phrases)
  val gapPenalty = 0.05f
}

class PhraseProximityQuery(val terms: Seq[Term], val phrases: Set[(Int, Int)]) extends Query {
  override def createWeight(searcher: IndexSearcher): Weight = new PhraseProximityWeight(this)

  override def rewrite(reader: IndexReader): Query = this

  override def extractTerms(out: JSet[Term]): Unit = out.addAll(terms)

  override def toString(s: String) = "phraseProximity(%s)%s".format(terms.mkString(","), ToStringUtils.boost(getBoost()))

  override def equals(obj: Any): Boolean = obj match {
    case prox: PhraseProximityQuery => (terms == prox.terms && phrases == prox.phrases && getBoost() == prox.getBoost())
    case _ => false
  }
  override def hashCode(): Int = terms.hashCode + phrases.hashCode + JFloat.floatToRawIntBits(getBoost())
}

class PhraseProximityWeight(query: PhraseProximityQuery) extends Weight {
  private[this] var value = 0.0f
  val phraseHelper = new PhraseHelper(query.terms.map(t => t.text), query.phrases)
  private[this] val maxRawScore = {
    val n = phraseHelper.phraseLenMap.map { case (id, len) => len }.foldLeft(0) { (s, l) => s + l }
    ((n * (n + 1.0f) / 2.0f) - (PhraseProximityQuery.gapPenalty * n * (n - 1.0f) / 2.0f))
  }

  def getWeightValue = value / maxRawScore

  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization() = {
    val boost = query.getBoost()
    boost * boost
  }

  override def normalize(norm: Float, topLevelBoost: Float) {
    value = query.getBoost * norm * topLevelBoost
  }

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val sc = scorer(context, true, false, context.reader.getLiveDocs);
    val exists = (sc != null && sc.advance(doc) == doc);
    val phrases = phraseHelper.phraseMap.keySet.mkString("[", " ; ", "]")
    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription("phrase proximity(%s, %s), product of:".format(query.terms.mkString(","), phrases))
      val proxScore = sc.score
      result.setValue(proxScore)
      result.setMatch(true)
      result.addDetail(new Explanation(proxScore / value, "phrase proximity score"))
      result.addDetail(new Explanation(value, "weight value"))
    } else {
      result.setDescription("phrase proximity(%s, %s), doesn't match id %d".format(query.terms.mkString(","), phrases, doc))
      result.setValue(0)
      result.setMatch(false)
    }
    result
  }

  def getQuery() = query

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
    if (query.terms.size > 0 && query.phrases.size > 0) {
      var i = -1
      // uses first 64 terms (enough?)
      val tps = query.terms.take(64).foldLeft(Map.empty[String, DocAndPosition]) { (tps, term) =>
        i += 1
        val termText = term.text()
        tps + (termText -> (tps.getOrElse(termText, makeDocAndPosition(context, term, acceptDocs))))
      }
      new PhraseProximityScorer(this, tps.values.toArray)
    } else {
      null
    }
  }

  private def makeDocAndPosition(context: AtomicReaderContext, term: Term, acceptDocs: Bits) = {
    val enum = termPositionsEnum(context, term, acceptDocs)
    if (enum == null) {
      new DocAndPosition(EmptyDocsAndPositionsEnum, term.text())
    } else {
      new DocAndPosition(enum, term.text())
    }
  }
}

class DocAndPosition(val tp: DocsAndPositionsEnum, val termText: String) {
  var doc = -1
  var pos = -1

  private[this] var posLeft = 0

  def fetchDoc(target: Int) {
    pos = -1
    doc = tp.advance(target)
    if (doc < NO_MORE_DOCS) {
      posLeft = tp.freq()
    } else {
      posLeft = 0
    }
  }

  def nextPos() {
    if (posLeft > 0) {
      pos = tp.nextPosition()
      posLeft -= 1
    } else {
      pos = Int.MaxValue
    }
  }
}

class PhraseProximityScorer(weight: PhraseProximityWeight, tps: Array[DocAndPosition]) extends Scorer(weight) {
  private[this] var curDoc = -1
  private[this] var proximityScore = 0.0f
  private[this] var scoredDoc = -1
  private[this] val numTerms = tps.size
  private[this] val weightVal = weight.getWeightValue
  private[this] var termPos = new ArrayBuffer[Int]()
  private[this] var termTexts = new ArrayBuffer[String]()

  private[this] def gapPenalty(distance: Int) = PhraseProximityQuery.gapPenalty * distance.toFloat

  private[this] val pq = new PriorityQueue[DocAndPosition](numTerms) {
    override def lessThan(nodeA: DocAndPosition, nodeB: DocAndPosition) = {
      if (nodeA.doc == nodeB.doc) nodeA.pos < nodeB.pos
      else nodeA.doc < nodeB.doc
    }
  }
  tps.foreach { tp => pq.insertWithOverflow(tp) }

  override def score(): Float = {

    val doc = curDoc
    termPos.clear()
    termTexts.clear()
    if (scoredDoc != doc) {
      var top = pq.top
      // if term still have positions left in this doc
      // retrieve all term and term positions, in increasing order of term position.
      if (top.pos < Int.MaxValue) {
        while (top.doc == doc && top.pos < Int.MaxValue) {
          if (top.pos != -1) {
            termPos.append(top.pos)
            termTexts.append(top.termText)
          }
          top.nextPos()
          top = pq.updateTop()
        }
      }
      val matches = weight.phraseHelper.getMatchedPhrases(termPos, termTexts)
      proximityScore = weight.phraseHelper.getMatchedScore(matches) * weightVal
      scoredDoc = doc
    }
    proximityScore
  }

  override def docID(): Int = curDoc

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    var top = pq.top
    val doc = if (target <= curDoc && curDoc < NO_MORE_DOCS) curDoc + 1 else target
    while (top.doc < doc) {
      top.fetchDoc(doc)
      top = pq.updateTop()
    }
    curDoc = top.doc
    curDoc
  }

  override def freq(): Int = 1
}
