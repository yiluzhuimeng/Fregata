package fregata.model.classification

import java.util.Random

import fregata._
import fregata.hash.{FastHash, RawHash, Hash}

import scala.collection.mutable.{HashMap => MHashMap, HashSet => MHashSet, ArrayBuffer}

/**
  * Created by hjliu on 16/10/31.
  */

class RDTModel(depth: Int, numClasses: Int, seeds: Array[Int], trees: Array[MHashMap[Long, Int]],
               models: MHashMap[(Int, (Long, Byte)), Array[Int]]) extends ClassificationModel {

  /**
    * predict to get every class probability
    * @param x  input vector
    * @return
    */
  def rdtPredict(x: Vector): (Array[Double], Int) = {
    val count = Array.ofDim[Int](numClasses)
    var j = 0
    while (j < trees.length) {
      val rawPath = getPredictPath(x, trees(j))
      val count_ = getCount(seeds(j), rawPath)
      (0 until numClasses).foreach(i => count(i) += count_(i))
      j += 1
    }

    j = 0
    var pLabel = 0
    var max = 0d
    val total = count.sum
    val probs = Array.ofDim[Num](numClasses)
    while(j < numClasses){
      if(count(j)>max){
        max = count(j)
        pLabel = j
      }
      probs(j) = asNum(count(j) + 1) / (total + numClasses)
      j += 1
    }

    probs -> pLabel
  }

  def rdtPredict(data: S[(Vector, Num)]): S[((Vector, Num), (Array[fregata.Num], Int))] = {
    data.map {
      case a@(x, label) =>
        a -> rdtPredict(x)
    }
  }

  /**
    * predict the max probability class
    * @param x input vector
    * @return (predict probability , predict class)
    */
  def classPredict(x: Vector): (Num, Num) = {
    val (probs, pl) = rdtPredict(x)
    if (numClasses == 2 && pl == 0)
      (probs(1), asNum(pl))
    else
      (asNum(probs(pl)), asNum(pl))
  }

  /**
    * get the instance's path of the given tree(generated by training)
    * @param inst input vector
    * @param tree the RandomDecisionTree generated by training datas
    * @return (path of the instance, depth of the path)
    */
  def getPredictPath(inst: fregata.Vector, tree: MHashMap[Long, Int]) = {
    var path = 0l
    var node = 1l
    var bCovered = true
    var i = 0
    while (i < depth - 1 && bCovered) {
      tree.get(node) match {
        case Some(feature) =>
          val xi = if (0d != inst(feature)) 1l else 0l
          path |= xi << (depth - 2 - i).toLong
          node = node * 2 + xi
        case _ =>
          path = 0
          bCovered = false
      }
      i += 1
    }
    (path, (depth - 1).toByte)
  }

  /**
    * get the count of instances belonging to each label for a given path
    * @param seed using to generate the RDT
    * @param rawPath one instance's path before being pruned
    * @return the count of instances belonging to each label in this path(leaf)
    */
  def getCount(seed: Int, rawPath: (Long, Byte)) = {
    var bFound = false
    var count = Array.ofDim[Int](numClasses)
    var i = 0
    while (i < rawPath._2 && !bFound) {
      models.get(seed ->(rawPath._1 << i, (rawPath._2 - i).toByte)) match {
        case Some(c) =>
          bFound = true
          count = c
        case _ =>
      }
      i += 1
    }
    count
  }
}

/**
  * the RandomDecisionTree algorithm
  * @param numTrees the number of trees
  * @param depth the depth of each tree
  * @param numFeatures the number of features of each instance
  * @param numClasses the number of classes of the input instance
  * @param seed used to generate seeds
  */
class RDT(numTrees: Int, depth: Int, numFeatures: Int, numClasses: Int = 2,
          seed: Long = 20170315l) extends Serializable {

  private var models = MHashMap[(Int, (Long, Byte)), Array[Int]]()
  var hasher: Hash = new RawHash

  private var trees = Array.ofDim[MHashMap[Long, Int]](numTrees)
  private var seeds = ArrayBuffer[Int]()

  def setTrees(trees: Array[MHashMap[Long, Int]]) = {
    this.trees = trees
  }

  def setModels(models: MHashMap[(Int, (Long, Byte)), Array[Int]]) = {
    this.models = models
  }

  def setHash(h: Hash) = {
    this.hasher = h
  }

  def getTrees = trees
  def getModels = models
  def getSeeds = seeds

  def log2(input: Int) = {
    (math.log(input) / math.log(2)).toInt
  }

  /**
    * get the instance's path of the given tree in training
    * @param inst input vector
    * @param treeId the id of trees
    * @return (path of the instance, depth of the path)
    */
  def getTrainPath(inst: fregata.Vector, treeId: Int) = {
    var path = 0l
    var node = 1l

    var i = 0
    while (i < depth - 1) {
      var selectedFeature = 0
      trees(treeId) match {
        case null =>
          selectedFeature = hasher.getHash(node + seeds(treeId)) % numFeatures
          trees(treeId) = MHashMap(node -> selectedFeature)
        case tree =>
          tree.get(node) match {
            case Some(feature) =>
              selectedFeature = feature
            case _ =>
              selectedFeature = hasher.getHash(node + seeds(treeId)) % numFeatures
              trees(treeId).update(node, selectedFeature)
          }
      }

      val xi = if (0d != inst(selectedFeature)) 1l else 0l
      path |= xi << (depth - 2 - i).toLong
      node = node * 2 + xi
      i += 1
    }

    (path, (depth - 1).toByte)
  }


  /**
    * get all the RDT trees by the training datas
    * @param insts all input datas
    * @return RDTModel used to predict
    */
  def train(insts: Array[(fregata.Vector, fregata.Num)]) = {
    val s = MHashSet[Int]()
    val r = new Random(seed)
    while (s.size < numTrees) {
      val seed_ = r.nextInt(Integer.MAX_VALUE)
      if (s.add(seed_))
        seeds += seed_
    }

    val instLength = insts.length
    var i = 0
    while (i < numTrees) {
      var j = 0
      while (j < instLength) {
        val pathDepth = getTrainPath(insts(j)._1, i)
        models.getOrElse((seeds(i), pathDepth), Array.ofDim[Int](numClasses)) match {
          case count =>
            count(insts(j)._2.toInt) += 1
            models.update((seeds(i), pathDepth), count)
        }
        j += 1
      }
      i += 1
    }

    new RDTModel(depth, numClasses, seeds.toArray, trees, models)
  }

  /**
    * prune the tree
    * @param minLeafCapacity the minimum number of instances in leaf
    * @param maxPruneNum  the maximum prune layer of one path
    * @return RDTModel used to predict
    */
  def prune(minLeafCapacity: Int, maxPruneNum: Int = 1) = {
    var bPruneNeeded = true
    var i = 0
    while (i < maxPruneNum && bPruneNeeded) {
      bPruneNeeded = false
      val abRemove = ArrayBuffer[(Int, (Long, Byte)) ]()
      val newModels = MHashMap[(Int, (Long, Byte)), Array[Int]]()
      models.foreach {
        case ((seed_, pathDepth@(path, depth_)), count) =>
          if (count.sum < minLeafCapacity) {
            val shortPath = (path >> 1, (depth_ - 1).toByte)
            newModels.getOrElse((seed_, shortPath), Array.ofDim[Int](numClasses)) match {
              case count_ =>
                (0 until numClasses).foreach{i=>count(i) += count_(i)}
                newModels.update((seed_, shortPath), count)
                abRemove.append( (seed_, pathDepth) )
            }
            bPruneNeeded = true
          }
      }
      abRemove.foreach(models.remove)
      models ++= newModels
      i += 1
    }

    new RDTModel(depth, numClasses, seeds.toArray, trees, models)
  }
}
