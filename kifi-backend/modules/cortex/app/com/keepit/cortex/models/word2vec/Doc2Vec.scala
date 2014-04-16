package com.keepit.cortex.models.word2vec

import scala.util.Random
import scala.collection.mutable.ArrayBuffer
import com.keepit.cortex.utils.MatrixUtils
import com.keepit.cortex.nlp.POSTagger

class Doc2Vec(mapper: Map[String, Array[Float]], dim: Int){

  type ArrayOfIndexes = Array[Array[Int]]
  type Vectors = Array[Array[Float]]
  type ArrayOfWords = Array[Array[String]]
  type Words = Array[String]
  type SetOfWords = Set[String]

  val SIZE_TAIL_CUT = 0.15f
  val POS_THRESHOLD = 0.2f
  val DEFAULT_RECOVER_THRESHOLD = 0.9f

  def genVecs(words: Words): (Words, Vectors) = {
    val ws = new ArrayBuffer[String]()
    val vecs = new ArrayBuffer[Array[Float]]()
    words.foreach{ w =>
      mapper.get(w).foreach{ wv =>
        ws.append(w)
        val v = new Array[Float](dim)
        System.arraycopy(wv, 0, v, 0, dim)
        vecs.append(v)
      }
    }
    (ws.toArray, vecs.toArray)
  }

  def indexToWords(words: Words, clusterIndexes: ArrayOfIndexes): ArrayOfWords = {
    clusterIndexes.map{ indexes =>
      indexes.map{ i => words(i)}
    }
  }

  // can be used as a naive version of doc2vec
  def getSumVector(words: Words): Array[Float] = {
    val vec = new Array[Float](dim)
    words.foreach{ w =>
      mapper.get(w).foreach{ v =>
        var i = 0
        while ( i < dim) { vec(i) = v(i); i += 1}
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
    while (rscore < threshold && !stop){
      var tmpBest = 0f
      var tmpBestWord = ""
      wordSet.foreach{ w =>
        val sim = MatrixUtils.cosineDistance(MatrixUtils.add(mapper(w), r), target)
        if (sim > rscore && sim > tmpBest){
          tmpBestWord = w
          tmpBest = sim
        }
      }
      if (tmpBest > rscore){
        val vec = mapper(tmpBestWord)
        (0 until dim).foreach{ i => r(i) += vec(i)}
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

  def cluster(text: String): (Vectors, ArrayOfWords) = {
    val tokens = text.toLowerCase().split(" ").toList
    val shuffled = Random.shuffle(tokens).toArray
    val (ws, vecs) = genVecs(shuffled)
    val crp = new CRPImpl()
    val CRPClusterResult(clusterSums, clusterIds) = crp.cluster(vecs)
    val cw = indexToWords(ws, clusterIds)
    (clusterSums, cw)
  }

  def getPOS(token: String): String = POSTagger.tagOneWord(token).value()

  def partOfSpeechCounts(words: Words): (Int, Int) = {
    var i = 0
    words.foreach{ w =>
      val tag = getPOS(w)
      if (tag == "NN" || tag == "NNS") i +=1
    }
    (i, words.size)
  }

  def filterAndRankClusters(clusterSums: Vectors, clusterWords: ArrayOfWords): (Vectors, ArrayOfWords) = {
    val clusterUniqueWords = clusterWords.map{_.toSet}
    val maxSz = clusterUniqueWords.map{_.size}.foldLeft(0)(_ max _)
    val indexAndScore = new ArrayBuffer[(Int, Float)]()
    (0 until clusterUniqueWords.size).foreach{ i =>
      val cwords = clusterUniqueWords(i)
      val csum = clusterSums(i)
      if (cwords.size * 1f / maxSz > SIZE_TAIL_CUT){
        val (rwords, rvec, rscore) = recover(csum, cwords)
        val (n, m) = partOfSpeechCounts(rwords)
        val r = n * 1f / m
        if ( r >= POS_THRESHOLD){
          val score = rwords.size / (0.01f max r)
          indexAndScore.append((i, score))
        }
      }
    }

    if (indexAndScore.isEmpty) return (Array(), Array())
    val sorted = indexAndScore.toArray.sortBy(_._2)
    val c = new ArrayBuffer[Array[Float]]()
    val cw = new ArrayBuffer[Array[String]]()
    sorted.foreach{ case (idx, _) =>
      c.append(clusterSums(idx))
      cw.append(clusterWords(idx))
    }
    (c.toArray, cw.toArray)
  }

  def getRepresentatives(text: String): (Vectors, ArrayOfWords) = {
    val (c, cw) = cluster(text)
    val (c2, cw2) = filterAndRankClusters(c, cw)
    (c2, cw2)
  }

  def getDocVecAndKeyWords(text: String): Option[(Array[Float], Words)] = {
    val (c, cw) = getRepresentatives(text)
    if (c.isEmpty) None
    else {
      val docVec = new Array[Float](dim)
      for (vec <- c){
        var i = 0
        while (i < dim){
          docVec(i) += vec(i)
          i += 1
        }
      }

      val keywords = cw.flatten.toArray     // not using idf info for now
      Some((docVec, keywords))
    }
  }

  // console debug
  def printClusters(clusterSums: Vectors, clusterWords: ArrayOfWords){
    (0 until clusterSums.size).foreach{ i =>
      val vec = clusterSums(i)
      val words = clusterWords(i).toSet
      val (rword, rvec, rscore) = recover(vec, words)
      println(s"cluster ${i}, rwords: ${rword.mkString(" , ")}, clusterSize: ${words.size}")
    }
  }

}
