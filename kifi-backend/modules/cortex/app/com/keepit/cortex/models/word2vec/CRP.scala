package com.keepit.cortex.models.word2vec

import scala.collection.mutable.ArrayBuffer
import scala.util.Random
import com.keepit.cortex.utils.MatrixUtils

case class CRPClusterResult(clusterSums: Array[Array[Float]], clusterIds: Array[Array[Int]])

//Chinese Restaurant Process (with Similarity driven)
trait CRP {
  def cluster(points: Array[Array[Float]]): CRPClusterResult
}

class CRPImpl() extends CRP {
  def cluster(points: Array[Array[Float]]): CRPClusterResult = {

    if (points.isEmpty) return CRPClusterResult(Array(), Array())

    val clusterSums = new ArrayBuffer[Array[Float]]()
    val clusterIds = new ArrayBuffer[ArrayBuffer[Int]]()
    val dim = points(0).size
    val N = points.size
    var ncluster = 0
    var pNew = 1f / (1 + ncluster)

    var i = 0
    while (i < N) {
      var maxSim = -1 * Float.MaxValue
      var maxIdx = 0
      val v = points(i)

      for (j <- (0 until ncluster)) {
        val sim = MatrixUtils.cosineDistance(clusterSums(j), v)
        if (sim > maxSim) {
          maxIdx = j
          maxSim = sim
        }
      }

      // if v is not strongly associated with any existing clusters, create a new one with probability pNew
      if (maxSim < pNew && Random.nextFloat() < pNew) {
        val newClusterSum = new Array[Float](dim)
        System.arraycopy(v, 0, newClusterSum, 0, dim)

        clusterSums.append(newClusterSum)
        val ids = new ArrayBuffer[Int]()
        ids.append(i)
        clusterIds.append(ids)

        ncluster += 1
        pNew = 1f / (1 + ncluster)

      } else {
        (0 until dim).foreach { k => clusterSums(maxIdx)(k) += v(k) }
        clusterIds(maxIdx).append(i)
      }

      i += 1
    }

    CRPClusterResult(clusterSums.toArray, clusterIds.map { _.toArray }.toArray)
  }
}
