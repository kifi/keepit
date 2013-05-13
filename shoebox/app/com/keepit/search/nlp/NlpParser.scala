package com.keepit.search.nlp

import scala.collection.JavaConversions._
import edu.stanford.nlp.trees._;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser
import scala.collection.mutable.ListBuffer


object NlpParser {
  val enabled = true
  val parser = LexicalizedParser.loadModel()

  private def getLeastCommonAncestor(root: Tree, nodes: Seq[Tree]): Tree = {
     if (nodes.size == 1){
       return nodes(0).ancestor(1, root)
     }
     val depths = nodes.map{ nd => root.depth(nd)}
     val minDepth = depths.foldLeft(depths(0))((m, x) => m min x)
     val starts = (0 until nodes.size).map{ i =>
       nodes(i).ancestor(depths(i) - minDepth, root)
     }
     var ancestors = starts.toSet
     while (ancestors.size != 1){
       ancestors = ancestors.map{x => x.ancestor(1, root)}
     }
     val rv = ancestors.toArray
     rv(0)
  }

  // TODO: more intelligent segmentation
  private def getSegments(cons: Set[Constituent]): Seq[(Int, Int)] = {
    var segs = ListBuffer.empty[(Int, Int)]
    if (cons.size == 0) {
      return segs
    }
    val pairs = cons.map{x => (x.start(), x.end())}.toArray
    val sortedPairs = pairs.sortWith( (x,y) => (x._1 < y._1) || (x._1 == y._1) && x._2 <= y._2 )
    var i = 0
    while(i < sortedPairs.size){
      val pair = sortedPairs(i)
      segs.append(pair)
      val nextStart = pair._2 + 1
      while(i < sortedPairs.size && sortedPairs(i)._1 < nextStart) i += 1
    }
    segs.toList
  }

  def getTaggedSegments(text: String) = {
    val tree = parser.parse(text)
    val cons = tree.constituents()
    val leaves = tree.getLeaves[Tree]()
    val segs = getSegments(cons.toSet)
    val tagged = segs.map{ seg =>
      val group = leaves.slice(seg._1, seg._2 + 1)
      val phrase = group.map{x => x.label().value().toString}.mkString(" ")
      val nd = getLeastCommonAncestor(tree, group)
      (nd.label().value(), phrase)
    }
    tagged
  }
}
