package com.backhoff.clustream

/**
 * Created by omar on 9/25/15.
 */

import breeze.linalg._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.{TaskContext, Partition, SparkContext, Logging}
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.rdd.RDD
import org.apache.spark.annotation.{DeveloperApi, Experimental, Since}
import org.apache.spark.util.Utils
import org.apache.spark.util.random.XORShiftRandom

@Experimental
class CluStreamModel (
                       val q: Int,
                       val alpha: Int,
                       val alphaModifier: Int,
                       val numDimensions: Int,
                       val minInitPoints: Int,
                       val sc: SparkContext)
  extends Logging with Serializable{

  private var time: Long = 0L
  private var microClusters: Array[MicroCluster] = null
  private val centroids: Array[breeze.linalg.Vector[Double]] = null
  private var N: Long = 0L
  private var broadcastCentroids: Broadcast[Array[breeze.linalg.Vector[Double]]] = null
  private var broadcastQ: Broadcast[Int] = null
  private var initialized = false
  private var initArr: Array[Object] = Array()

  private def initRand(): Unit ={
    broadcastCentroids = sc.broadcast(Array.fill(q)(Vector.fill[Double](numDimensions)(rand())))
    broadcastQ = sc.broadcast(q)
    microClusters = Array.fill(q)(new MicroCluster(Vector.fill[Double](numDimensions)(0),Vector.fill[Double](numDimensions)(0),0L,0L,0L))
    initialized = true
  }

  private def initKmeans(rdd: RDD[breeze.linalg.Vector[Double]]): Unit ={
    if (initArr == null) initArr = initArr :+ rdd.collect else initArr = initArr :+ rdd.collect
    if(initArr.length >= minInitPoints) {
      import org.apache.spark.mllib.clustering.KMeans
      val parArr: Array[breeze.linalg.Vector[Double]] = Array.fill(q)(Vector.fill[Double](numDimensions)(0))
      for(i <- 0 until parArr.length) parArr(i) = DenseVector(initArr(i).)
      val clusters = KMeans.train(rdd.context.parallelize(initArr), q, 20)
      microClusters = Array.fill(q)(new MicroCluster(Vector.fill[Double](numDimensions)(0),Vector.fill[Double](numDimensions)(0),0L,0L,0L))
      for(i <- 0 until clusters.clusterCenters.length) centroids(i) = DenseVector(clusters.clusterCenters(i).toArray)
      broadcastCentroids = rdd.context.broadcast(centroids)
      broadcastQ = sc.broadcast(q)
      rdd.unpersist()
      initialized = true
    }
  }

  def run(data: DStream[breeze.linalg.Vector[Double]]): Unit ={
    data.foreachRDD { (rdd, time) =>
      this.time += 1
      this.N += rdd.count()

      if(!rdd.isEmpty() && !initialized){
       minInitPoints match {
           case 0 => initRand()
           case _ => initKmeans(rdd)
        }
      }

      if(!rdd.isEmpty() && initialized) {
        val assignations = assignToMicroCluster(rdd,broadcastQ.value, broadcastCentroids.value)
        updateMicroClusters(assignations)
        var i = 0
        for(mc <- this.microClusters){
          if(mc.getN > 0) centroids(i) = mc.getCf1x :/ mc.getN.toDouble
          i += 1
        }
        broadcastCentroids = rdd.context.broadcast(centroids)



        println("CF1X: ")
        microClusters.foreach(a => println(a.getCf1x))
        println("CF2X: ")
        microClusters.foreach(a => println(a.getCf2x))
        println("number of points per MC: ")
        microClusters.foreach(a => println(a.getN))
        println("Centers: ")
        broadcastCentroids.value.foreach(println)
        println("Total time units elapsed: " + this.time)
        println("Total number of points: " + N)
        println()

      }
    }
  }

  private def saveSnapshot(): Unit ={}
  private def mergeMicroClusters(): Unit ={}
  private def assignToMicroCluster(rdd: RDD[Vector[Double]],q: Int, centroids: Array[Vector[Double]]): RDD[(Int,Vector[Double])] ={
    rdd.map{ a =>
      val arr = Array.fill[(Int,Double)](q)(0,0)
      var i = 0
      for(c <- centroids){
        arr(i) = (i, squaredDistance(a, c))
        i += 1
      }
      (arr.min(new OrderingDoubleTuple)._1, a)
    }
  }

  private def updateMicroClusters(assignations: RDD[(Int,Vector[Double])]): Unit ={
    val pointCount = assignations.groupByKey.mapValues(a => a.size).collect
    val sums = assignations.reduceByKey(_ :+ _).collect
    val sumsSquares = assignations.mapValues(a => a :* a).reduceByKey(_ :+ _).collect

    for(mc <- this.microClusters){
      for(s <- sums) if(mc.getIds(0) == s._1) mc.setCf1x(mc.cf1x :+ s._2)
      for(ss <- sumsSquares) if(mc.getIds(0) == ss._1) mc.setCf2x(mc.cf2x :+ ss._2)
      for(pc <- pointCount) if(mc.getIds(0) == pc._1) mc.setN(mc.n + pc._2)
    }
  }

}

private object MicroCluster extends Serializable{
  private var current = -1
  private def inc = {current += 1; current}
}

private class MicroCluster(
                            var cf2x: breeze.linalg.Vector[Double],
                            var cf1x: breeze.linalg.Vector[Double],
                            var cf2t: Long,
                            var cf1t: Long,
                            var n: Long,
                            var ids: Array[Int]) extends Serializable{

  def this(cf2x: breeze.linalg.Vector[Double],cf1x: breeze.linalg.Vector[Double],cf2t: Long,cf1t: Long, n: Long) = this(cf2x,cf1x,cf2t,cf2t,n, Array(MicroCluster.inc))

  def setCf2x(cf2x: breeze.linalg.Vector[Double]): Unit = {
    this.cf2x = cf2x
  }
  def getCf2x: breeze.linalg.Vector[Double] = {
    this.cf2x
  }
  def setCf1x(cf1x: breeze.linalg.Vector[Double]): Unit = {
    this.cf1x = cf1x
  }
  def getCf1x: breeze.linalg.Vector[Double] = {
    this.cf1x
  }
  def setCf2t(cf2t: Long): Unit = {
    this.cf2t = cf2t
  }
  def getCf2t: Long = {
    this.cf2t
  }
  def setCf1t(cf1t: Long): Unit = {
    this.cf1t = cf1t
  }
  def getCf1t: Long = {
    this.cf1t
  }
  def setN(n: Long): Unit = {
    this.n = n
  }
  def getN: Long = {
    this.n
  }
  def setIds(ids: Array[Int]): Unit = {
    this.ids = ids
  }
  def getIds: Array[Int] = {
    this.ids
  }
}

private class OrderingDoubleTuple extends Ordering[Tuple2[Int, Double]] with Serializable{
  override def compare(x: (Int, Double), y: (Int, Double)): Int =
    Ordering[Double].compare(x._2, y._2)
}

private object CluStreamModel{
  private val RANDOM = "random"
  private val KMEANS = "kmeans"
}