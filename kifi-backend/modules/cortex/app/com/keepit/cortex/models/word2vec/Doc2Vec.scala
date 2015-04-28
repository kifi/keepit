package com.keepit.cortex.models.word2vec

import scala.util.Random
import scala.collection.mutable.ArrayBuffer
import com.keepit.cortex.utils.MatrixUtils._
import com.keepit.cortex.nlp.POSTagger
import com.keepit.common.logging.Logging

case class Doc2VecResult(vec: Array[Float], keywords: Array[String], bagOfWords: Map[String, Int])

class Doc2Vec(mapper: Map[String, Array[Float]], dim: Int) extends Logging {

  type ArrayOfIndexes = Array[Array[Int]]
  type Vectors = Array[Array[Float]]
  type ArrayOfWords = Array[Array[String]]
  type Words = Array[String]
  type SetOfWords = Set[String]

  private val SIZE_TAIL_CUT = 0.2f
  private val POS_THRESHOLD = 0.2f
  private val DEFAULT_RECOVER_THRESHOLD = 0.9f
  private val MAX_SCORE_CUT = 15f
  private val SCORE_TAIL_CUT = 4f

  private def genVecs(words: Words): (Words, Vectors) = {
    val ws = new ArrayBuffer[String]()
    val vecs = new ArrayBuffer[Array[Float]]()
    words.foreach { w =>
      mapper.get(w).foreach { wv =>
        ws.append(w)
        val v = new Array[Float](dim)
        System.arraycopy(wv, 0, v, 0, dim)
        vecs.append(v)
      }
    }
    (ws.toArray, vecs.toArray)
  }

  private def indexToWords(words: Words, clusterIndexes: ArrayOfIndexes): ArrayOfWords = {
    clusterIndexes.map { indexes =>
      indexes.map { i => words(i) }
    }
  }

  // can be used as a naive version of doc2vec
  def getSumVector(words: Words): Array[Float] = {
    val vec = new Array[Float](dim)
    words.foreach { w =>
      mapper.get(w).foreach { v =>
        var i = 0
        while (i < dim) { vec(i) = v(i); i += 1 }
      }
    }
    vec
  }

  // make sure words are in mapper's keySet. for performance reason, we don't check this.
  def recover(target: Array[Float], words: SetOfWords, threshold: Float = DEFAULT_RECOVER_THRESHOLD): (Words, Array[Float], Float) = {
    var wordSet = words
    val rwords = new ArrayBuffer[String]()
    val r = new Array[Float](dim)
    var rscore = 0f
    var stop = false
    while (rscore < threshold && !stop) {
      var tmpBest = 0f
      var tmpBestWord = ""
      wordSet.foreach { w =>
        val sim = cosineDistance(add(mapper(w), r), target)
        if (sim > rscore && sim > tmpBest) {
          tmpBestWord = w
          tmpBest = sim.toFloat
        }
      }
      if (tmpBest > rscore) {
        val vec = mapper(tmpBestWord)
        (0 until dim).foreach { i => r(i) += vec(i) }
        rscore = tmpBest
        wordSet = wordSet - tmpBestWord
        rwords.append(tmpBestWord)
        if (wordSet.isEmpty) stop = true
      } else {
        stop = true
      }
    }
    (rwords.toArray, r, rscore)
  }

  def cluster(tokens: Seq[String]): (Vectors, ArrayOfWords) = {
    val shuffled = Random.shuffle(tokens).toArray
    val (ws, vecs) = genVecs(shuffled)
    val crp = new CRPImpl()
    val CRPClusterResult(clusterSums, clusterIds) = crp.cluster(vecs)
    val cw = indexToWords(ws, clusterIds)
    (clusterSums, cw)
  }

  private def getPOS(token: String): String = POSTagger.tagOneWord(token).value()

  private def partOfSpeechCounts(words: Words): (Int, Int) = {
    var i = 0
    words.foreach { w =>
      val tag = getPOS(w)
      if (tag == "NN" || tag == "NNS") i += 1
    }
    (i, words.size)
  }

  private def filterAndRankClusters(clusterSums: Vectors, clusterWords: ArrayOfWords): (Vectors, ArrayOfWords) = {
    val clusterUniqueWords = clusterWords.map { _.toSet }
    val maxSz = clusterUniqueWords.map { _.size }.foldLeft(0)(_ max _)
    val indexAndScore = new ArrayBuffer[(Int, Float)]()
    (0 until clusterUniqueWords.size).foreach { i =>
      val cwords = clusterUniqueWords(i)
      val csum = clusterSums(i)
      if (cwords.size * 1f / maxSz > SIZE_TAIL_CUT) {
        val (rwords, rvec, rscore) = recover(csum, cwords)
        val (n, m) = partOfSpeechCounts(rwords)
        val r = n * 1f / m
        if (r >= POS_THRESHOLD) {
          val score = rwords.size / (0.01f max r)
          if (score < MAX_SCORE_CUT) indexAndScore.append((i, score))
        }
      }
    }

    if (indexAndScore.isEmpty) return (Array(), Array())
    val sorted = indexAndScore.toArray.sortBy(_._2)
    val bestScore = sorted(0)._2
    val c = new ArrayBuffer[Array[Float]]()
    val cw = new ArrayBuffer[Array[String]]()
    sorted.foreach {
      case (idx, score) =>
        if (score < bestScore * SCORE_TAIL_CUT) {
          c.append(clusterSums(idx))
          cw.append(clusterWords(idx))
        }
    }
    (c.toArray, cw.toArray)
  }

  def getRepresentatives(tokens: Seq[String]): (Vectors, ArrayOfWords) = {
    val (c, cw) = cluster(tokens)
    val (c2, cw2) = filterAndRankClusters(c, cw)
    (c2, cw2)
  }

  def getDocVecAndKeyWords(tokens: Seq[String]): Option[Doc2VecResult] = {
    val (c, cw) = getRepresentatives(tokens)
    if (c.isEmpty) None
    else {
      val docVec = new Array[Float](dim)

      for (vec <- c) {
        var i = 0
        while (i < dim) {
          docVec(i) += vec(i)
          i += 1
        }
      }

      val keywords = (0 until c.size).map { i =>
        val target = c(i)
        val (rword, _, _) = recover(target, cw(i).toSet)
        rword
      }.flatten.toSet.toArray

      val bagOfWords = cw.flatten.toArray.zipWithIndex.groupBy(_._1).map { case (a, b) => (a, b.size) } // not using idf info for now
      Some(Doc2VecResult(docVec, keywords, bagOfWords))
    }
  }

  def sampleBest(tokens: Seq[String], numTry: Int = 4, normalize: Boolean = true, parallel: Boolean = true): Option[Doc2VecResult] = {
    val samples = if (parallel) {
      (0 until numTry).par.flatMap { i => getDocVecAndKeyWords(tokens) }.toArray
    } else {
      (0 until numTry).flatMap { i => getDocVecAndKeyWords(tokens) }.toArray
    }

    if (samples.isEmpty) return None

    val weights = samples.map { res => 1f / res.bagOfWords.size }.toArray
    val target = if (normalize) weightedAverage(samples.map { s => toDoubleArray(s.vec) }, weights) else average(samples.map { s => toDoubleArray(s.vec) })

    val keyStat = scala.collection.mutable.Map[String, Int]().withDefaultValue(0)

    var bestIdx = -1
    var bestScore = -1f * Float.MaxValue
    samples.zipWithIndex.foreach {
      case (res, i) =>
        val s = cosineDistance(res.vec, target).toFloat
        res.keywords.foreach { w => keyStat(w) = keyStat(w) + 1 }
        if (s > bestScore) {
          bestScore = s; bestIdx = i
        }
    }

    // more stable keywords
    val avgKeywords = keyStat.toArray.filter(_._2 > 1).sortBy(-1 * _._2).take(5).map { _._1 }

    if (bestIdx == -1) None
    else Some(samples(bestIdx).copy(keywords = avgKeywords))
  }

  // console debug
  def printClusters(clusterSums: Vectors, clusterWords: ArrayOfWords) {
    (0 until clusterSums.size).foreach { i =>
      val vec = clusterSums(i)
      val words = clusterWords(i).toSet
      val (rword, rvec, rscore) = recover(vec, words)
      println(s"cluster ${i}, rwords: ${rword.mkString(" , ")}, clusterSize: ${words.size}")
    }
  }

}
